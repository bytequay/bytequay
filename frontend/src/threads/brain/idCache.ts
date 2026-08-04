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

/** A tiny module-level stale-while-revalidate cache keyed by id. */
export type IdCache<T> = {
  get: (id: string) => T | undefined;
  set: (id: string, value: T) => void;
};

/**
 * Creates a process-lifetime cache keyed by id (stage id, task id, thread
 * id). A data hook reads the last-known value the instant the caller
 * switches to that id — painting stale data immediately — while a fresh
 * fetch revalidates in the background. This is what makes switching between
 * stage / brain / diff surfaces feel instant on revisit instead of blanking
 * through an IPC round-trip. The cache is intentionally unbounded: the id
 * space here is small (the stages of a task, the tasks of a thread) and
 * entries are cheap snapshots.
 */
const stores: Map<string, unknown>[] = [];

export function makeIdCache<T>(): IdCache<T> {
  const store = new Map<string, T>();
  stores.push(store as Map<string, unknown>);
  return {
    get: (id: string) => store.get(id),
    set: (id: string, value: T) => { store.set(id, value); },
  };
}

/**
 * Empties every cache. Process lifetime is one app run in production, but
 * one whole spec file under the test runner: a snapshot cached under an id
 * in one case would otherwise paint synchronously in the next case that
 * reuses that id, before its own fetch resolves. Called from the shared
 * test setup, not from app code.
 */
export function clearIdCaches(): void {
  stores.forEach(store => store.clear());
}
