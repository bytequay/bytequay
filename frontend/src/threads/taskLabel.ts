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

/** Humanise a `dev/clean-up-backend` branch into "clean up backend" — the
 *  fallback label for a task with no explicit name. Drops the leading
 *  namespace segment and turns separators into spaces. */
export function humaniseBranch(branch: string | null): string {
  if (branch === null || branch.trim() === '') {
    return '';
  }
  const tail = branch.replace(/^[^/]+\//, ''); // drop the leading "dev/" segment
  return tail.replace(/[-_]+/g, ' ').trim();
}

/**
 * A task's display name: its agent/user-supplied name, else the humanised
 * branch, else a neutral placeholder. Never the bare "Task N" — the sequence
 * number is an identifier, not a name (the `name` field's documented fallback
 * is the humanised branch).
 */
export function taskLabel(task: { name: string | null; branchName: string | null }): string {
  const name = task.name?.trim();
  if (name !== undefined && name !== '') {
    return name;
  }
  const branch = humaniseBranch(task.branchName);
  return branch !== '' ? branch : 'Untitled task';
}
