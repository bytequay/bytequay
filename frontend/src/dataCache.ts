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
 * fetch but acceptable to refresh on a fixed cadence.
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

/**
 * Shares one in-flight request per key, and forgets a failed one so the next caller retries.
 *
 * The caller owns the map, so two callers with the same key never collide.
 */
export function once<T>(pending: Map<string, Promise<T | null>>, key: string, load: () => Promise<T | null>): Promise<T | null> {
  const existing = pending.get(key);
  if (existing) return existing;
  const request = load().catch((): null => null);
  pending.set(key, request);
  void request.then(value => {
    if (value === null) pending.delete(key);
  });
  return request;
}
