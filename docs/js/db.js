// Storage. IndexedDB rather than localStorage: it is asynchronous, has a far
// larger quota, and survives Safari's cap on script-writable storage for sites
// added to the Home Screen.
//
// Dates are stored as 'YYYY-MM-DD' strings. They sort correctly as text, carry
// no time zone to go wrong, and are what the statement parser already produces.

const DB_NAME = 'spendly';
const DB_VERSION = 1;

export const SOURCE = { MANUAL: 'MANUAL', STATEMENT: 'STATEMENT' };

export const DEFAULT_CATEGORIES = [
  { name: 'Food & Drink', emoji: '🍜', color: '#EF5350' },
  { name: 'Groceries', emoji: '🛒', color: '#26A69A' },
  { name: 'Transport', emoji: '🚗', color: '#42A5F5' },
  { name: 'Shopping', emoji: '🛍️', color: '#AB47BC' },
  { name: 'Bills & Home', emoji: '🏠', color: '#FFA726' },
  { name: 'Fun', emoji: '🎬', color: '#EC407A' },
  { name: 'Health', emoji: '💊', color: '#66BB6A' },
  { name: 'Work', emoji: '💼', color: '#78909C' },
  { name: 'Gifts', emoji: '🎁', color: '#FFCA28' },
  { name: 'Other', emoji: '➕', color: '#90A4AE' },
];

let dbPromise = null;

function open() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = (event) => {
      const db = req.result;
      if (!db.objectStoreNames.contains('entries')) {
        const entries = db.createObjectStore('entries', { keyPath: 'id', autoIncrement: true });
        entries.createIndex('date', 'date');
        entries.createIndex('dedupeKey', 'dedupeKey');
      }
      if (!db.objectStoreNames.contains('categories')) {
        const cats = db.createObjectStore('categories', { keyPath: 'id', autoIncrement: true });
        // Seeded inside the upgrade transaction, so the app can never start
        // with an empty category list.
        DEFAULT_CATEGORIES.forEach((c, i) => cats.add({ ...c, sortOrder: i, usageCount: 0 }));
      }
      if (!db.objectStoreNames.contains('meta')) {
        db.createObjectStore('meta', { keyPath: 'key' });
      }
      void event;
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
    req.onblocked = () => reject(new Error('Another tab is holding the database open'));
  });
  return dbPromise;
}

function tx(store, mode, fn) {
  return open().then((db) => new Promise((resolve, reject) => {
    const t = db.transaction(store, mode);
    const s = t.objectStore(store);
    let result;
    try {
      result = fn(s);
    } catch (e) {
      reject(e);
      return;
    }
    t.oncomplete = () => resolve(result && result.__req ? result.__req.result : result);
    t.onerror = () => reject(t.error);
    t.onabort = () => reject(t.error);
  }));
}

const wrap = (req) => ({ __req: req });

// ------------------------------------------------------------- categories --

export const allCategories = () =>
  tx('categories', 'readonly', (s) => wrap(s.getAll()))
    .then((list) => list.sort((a, b) => b.usageCount - a.usageCount || a.sortOrder - b.sortOrder));

export const categoriesInOrder = () =>
  tx('categories', 'readonly', (s) => wrap(s.getAll()))
    .then((list) => list.sort((a, b) => a.sortOrder - b.sortOrder));

export async function bumpCategory(id) {
  const db = await open();
  return new Promise((resolve, reject) => {
    const t = db.transaction('categories', 'readwrite');
    const s = t.objectStore('categories');
    const get = s.get(id);
    get.onsuccess = () => {
      const c = get.result;
      if (c) s.put({ ...c, usageCount: (c.usageCount || 0) + 1 });
    };
    t.oncomplete = resolve;
    t.onerror = () => reject(t.error);
  });
}

// ---------------------------------------------------------------- entries --

export const addEntry = (entry) =>
  tx('entries', 'readwrite', (s) => wrap(s.add({ createdAt: Date.now(), ...entry })));

export const putEntry = (entry) =>
  tx('entries', 'readwrite', (s) => wrap(s.put(entry)));

export const getEntry = (id) =>
  tx('entries', 'readonly', (s) => wrap(s.get(id)));

export const deleteEntry = (id) =>
  tx('entries', 'readwrite', (s) => wrap(s.delete(id)));

export const allEntries = () =>
  tx('entries', 'readonly', (s) => wrap(s.getAll()));

/** Entries between two 'YYYY-MM-DD' dates, inclusive. */
export const entriesBetween = (from, to) =>
  tx('entries', 'readonly', (s) => wrap(s.index('date').getAll(IDBKeyRange.bound(from, to))));

export const entriesOn = (date) =>
  tx('entries', 'readonly', (s) => wrap(s.index('date').getAll(IDBKeyRange.only(date))));

/** Has a statement row with this key already been imported? */
export const hasDedupeKey = (key) =>
  tx('entries', 'readonly', (s) => wrap(s.index('dedupeKey').count(IDBKeyRange.only(key))))
    .then((n) => n > 0);

// --------------------------------------------------------------- settings --

export const getSetting = (key, fallback = null) =>
  tx('meta', 'readonly', (s) => wrap(s.get(key)))
    .then((row) => (row === undefined || row === null ? fallback : row.value));

export const setSetting = (key, value) =>
  tx('meta', 'readwrite', (s) => wrap(s.put({ key, value })));

/** Wipes everything. Used by "Delete all data" in settings. */
export async function clearAll() {
  const db = await open();
  return new Promise((resolve, reject) => {
    const t = db.transaction(['entries', 'meta'], 'readwrite');
    t.objectStore('entries').clear();
    t.objectStore('meta').clear();
    t.oncomplete = resolve;
    t.onerror = () => reject(t.error);
  });
}
