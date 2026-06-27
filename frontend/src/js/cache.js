/**
 * 轻量请求级 TTL 内存缓存
 */
export function createTtlCache(ttlMs = 60000) {
  const store = new Map();

  function get(key) {
    const entry = store.get(key);
    if (!entry) return undefined;
    if (Date.now() - entry.ts > ttlMs) {
      store.delete(key);
      return undefined;
    }
    return entry.value;
  }

  function set(key, value) {
    store.set(key, { value, ts: Date.now() });
  }

  function invalidate(pattern) {
    if (!pattern) {
      store.clear();
      return;
    }
    for (const key of store.keys()) {
      if (key.includes(pattern)) store.delete(key);
    }
  }

  function has(key) {
    return get(key) !== undefined;
  }

  return { get, set, invalidate, has };
}
