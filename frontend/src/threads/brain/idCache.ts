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
export function makeIdCache<T>(): IdCache<T> {
  const store = new Map<string, T>();
  return {
    get: (id: string) => store.get(id),
    set: (id: string, value: T) => { store.set(id, value); },
  };
}
