/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

type Entry = { value: unknown; storedAt: number };

const cache = new Map<string, Entry>();

export function getCached<T>(key: string): T | undefined {
  const entry = cache.get(key);
  return entry ? (entry.value as T) : undefined;
}

/**
 * Like {@link getCached} but evicts the entry — and returns undefined —
 * once it's older than {@code maxAgeMs}. For data that's expensive to
 * fetch but acceptable to refresh on a fixed cadence (e.g. the team
 * home's "merged this week" GitHub-search count, with a 10-minute TTL).
 */
export function getCachedFresh<T>(key: string, maxAgeMs: number): T | undefined {
  const entry = cache.get(key);
  if (!entry) return undefined;
  if (Date.now() - entry.storedAt > maxAgeMs) {
    cache.delete(key);
    return undefined;
  }
  return entry.value as T;
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
