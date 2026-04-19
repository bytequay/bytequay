/*
 * Tiny module-scope cache used to make tab switches feel instant.
 *
 * Pattern: each page reads from the cache at mount time and shows that
 * value immediately (no spinner), then kicks off a background fetch that
 * updates both the cache and the page state when it lands. The user sees
 * the last-known data right away and the fresh data replaces it silently.
 *
 * Memory-only on purpose — survives tab switches within a session but
 * not a full app restart. If we ever need reload-resilience we can swap
 * this for a persisted store (localStorage / backend) without changing
 * callers.
 */

type Entry = { value: unknown; storedAt: number };

const cache = new Map<string, Entry>();

export function getCached<T>(key: string): T | undefined {
  const entry = cache.get(key);
  return entry ? (entry.value as T) : undefined;
}

export function setCached<T>(key: string, value: T): void {
  cache.set(key, { value, storedAt: Date.now() });
}

export function invalidate(key: string): void {
  cache.delete(key);
}

export function invalidatePrefix(prefix: string): void {
  for (const key of cache.keys()) {
    if (key.startsWith(prefix)) cache.delete(key);
  }
}
