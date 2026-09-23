// PDF text extraction.
//
// pdf.js is loaded on demand from a CDN rather than bundled: it is roughly 2 MB
// with its worker, and most people never import a statement. Once fetched the
// service worker keeps a copy, so the second import works offline. The first
// one needs a connection, and says so plainly if it does not have one.

const PDFJS_VERSION = '4.6.82';
const BASE = `https://cdnjs.cloudflare.com/ajax/libs/pdf.js/${PDFJS_VERSION}`;

export class PdfError extends Error {}

let pdfjsPromise = null;

function loadPdfjs() {
  if (pdfjsPromise) return pdfjsPromise;
  pdfjsPromise = import(/* @vite-ignore */ `${BASE}/pdf.min.mjs`)
    .then((mod) => {
      mod.GlobalWorkerOptions.workerSrc = `${BASE}/pdf.worker.min.mjs`;
      return mod;
    })
    .catch((e) => {
      pdfjsPromise = null;
      throw new PdfError(
        navigator.onLine
          ? 'The PDF reader could not be loaded. Try again in a moment.'
          : 'Reading a PDF needs a connection the first time. Once loaded it works offline.',
      );
    });
  return pdfjsPromise;
}

/**
 * Extracts the text of a PDF.
 *
 * @param file          a File or Blob
 * @param askPassword   called when the document is locked; returns the password
 *                      or null to give up. Bank statements are routinely locked.
 */
export async function extractPdfText(file, askPassword) {
  const pdfjs = await loadPdfjs();
  const data = new Uint8Array(await file.arrayBuffer());

  let password;
  for (let attempt = 0; attempt < 4; attempt++) {
    try {
      // A fresh copy each attempt: pdf.js transfers and neuters the buffer.
      const task = pdfjs.getDocument({ data: data.slice(), password });
      const doc = await task.promise;
      const text = await readAllPages(doc);
      if (!text.trim()) {
        throw new PdfError(
          'That PDF has no text in it — it is probably a scan or a photo. ' +
          'Spendly reads text, not images, so this one has to be entered by hand.',
        );
      }
      return text;
    } catch (e) {
      if (e instanceof PdfError) throw e;
      const name = e?.name;
      if (name === 'PasswordException') {
        const entered = await askPassword();
        if (entered === null || entered === undefined) {
          throw new PdfError('Cancelled.');
        }
        password = entered;
        continue;
      }
      if (name === 'InvalidPDFException') throw new PdfError('That file is not a readable PDF.');
      throw new PdfError(e?.message || 'That file could not be read.');
    }
  }
  throw new PdfError('That password did not open the file.');
}

async function readAllPages(doc) {
  const pages = [];
  for (let i = 1; i <= doc.numPages; i++) {
    const page = await doc.getPage(i);
    const content = await page.getTextContent();
    pages.push(rowsFromItems(content.items));
    page.cleanup();
  }
  return pages.join('\n');
}

/**
 * Rebuilds lines from positioned text fragments.
 *
 * A PDF has no notion of a line — it has glyphs at coordinates. Statement rows
 * are columns, so fragments are grouped by their y position and then sorted by
 * x, which is what turns a page back into "date  description  amount  balance".
 */
function rowsFromItems(items) {
  const rows = new Map();
  for (const item of items) {
    if (!item.str || !item.str.trim()) continue;
    const y = Math.round(item.transform[5]);
    // Tolerate sub-pixel drift within a row by snapping to a 2pt grid.
    const key = Math.round(y / 2);
    if (!rows.has(key)) rows.set(key, []);
    rows.get(key).push({ x: item.transform[4], s: item.str });
  }
  return [...rows.entries()]
    .sort((a, b) => b[0] - a[0]) // top of the page downwards
    .map(([, parts]) => parts.sort((a, b) => a.x - b.x).map((p) => p.s).join(' ')
      .replace(/\s{2,}/g, '  ').trim())
    .filter(Boolean)
    .join('\n');
}
