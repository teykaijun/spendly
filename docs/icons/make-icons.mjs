// Generates the PNG app icons from the same shapes as icon.svg.
//
//   node icons/make-icons.mjs
//
// iOS will not accept an SVG for apple-touch-icon, and committing opaque binary
// blobs is worse than committing the 80 lines that produce them. Uses only
// node:zlib, so there is no image dependency to install or keep current.

import { deflateSync } from 'node:zlib';
import { writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));

const BRAND = [0x10, 0xa3, 0x7f];
const WHITE = [0xff, 0xff, 0xff];

/** A canvas of RGBA pixels with just enough drawing to render the mark. */
function canvas(size) {
  const px = new Uint8Array(size * size * 4);
  const put = (x, y, [r, g, b], a = 255) => {
    if (x < 0 || y < 0 || x >= size || y >= size) return;
    const i = (y * size + x) * 4;
    // Source-over, so anti-aliased edges blend instead of punching holes.
    const sa = a / 255;
    px[i] = px[i] * (1 - sa) + r * sa;
    px[i + 1] = px[i + 1] * (1 - sa) + g * sa;
    px[i + 2] = px[i + 2] * (1 - sa) + b * sa;
    px[i + 3] = Math.max(px[i + 3], a);
  };

  // Coverage-based anti-aliasing: sample each pixel on a 3x3 grid and use the
  // fraction inside the shape as alpha. Cheap, and enough for an icon.
  const fill = (test, colour) => {
    for (let y = 0; y < size; y++) {
      for (let x = 0; x < size; x++) {
        let hits = 0;
        for (let sy = 0; sy < 3; sy++) {
          for (let sx = 0; sx < 3; sx++) {
            if (test(x + (sx + 0.5) / 3, y + (sy + 0.5) / 3)) hits++;
          }
        }
        if (hits) put(x, y, colour, Math.round((hits / 9) * 255));
      }
    }
  };

  const roundRect = (x0, y0, w, h, r, colour) => fill((x, y) => {
    if (x < x0 || y < y0 || x > x0 + w || y > y0 + h) return false;
    const dx = Math.min(Math.max(x, x0 + r), x0 + w - r);
    const dy = Math.min(Math.max(y, y0 + r), y0 + h - r);
    return (x - dx) ** 2 + (y - dy) ** 2 <= r * r
      || (x >= x0 + r && x <= x0 + w - r) || (y >= y0 + r && y <= y0 + h - r);
  }, colour);

  const circle = (cx, cy, r, colour) =>
    fill((x, y) => (x - cx) ** 2 + (y - cy) ** 2 <= r * r, colour);

  return { px, roundRect, circle };
}

/**
 * @param size    pixel dimensions
 * @param inset   fraction of the canvas to keep clear round the mark. Maskable
 *                icons get cropped to a circle by some launchers, so the shape
 *                has to sit inside the safe zone.
 */
function drawIcon(size, inset = 0) {
  const c = canvas(size);
  const u = size / 108; // the SVG's coordinate space
  const pad = size * inset;
  const span = size - pad * 2;
  const s = span / 108;
  const X = (v) => pad + v * s;
  const Y = (v) => pad + v * s;

  c.roundRect(0, 0, size, size, 24 * u, BRAND);              // background
  c.roundRect(X(24), Y(38), 54 * s, 38 * s, 8 * s, WHITE);   // wallet body
  c.roundRect(X(62), Y(50), 18 * s, 14 * s, 7 * s, BRAND);   // card slot
  c.circle(X(69), Y(57), 3 * s, WHITE);                      // slot dot
  return c.px;
}

// ------------------------------------------------------------------- PNG ---

const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

function toPng(pixels, size) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8;   // bit depth
  ihdr[9] = 6;   // truecolour with alpha
  // Each scanline is prefixed with its filter type; 0 means none.
  const raw = Buffer.alloc(size * (size * 4 + 1));
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0;
    Buffer.from(pixels.buffer, y * size * 4, size * 4)
      .copy(raw, y * (size * 4 + 1) + 1);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

for (const [name, size, inset] of [
  ['apple-touch-icon.png', 180, 0],
  ['icon-512.png', 512, 0.14],
]) {
  const file = join(HERE, name);
  writeFileSync(file, toPng(drawIcon(size, inset), size));
  console.log(`wrote ${name} (${size}x${size})`);
}
