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

/**
 * Maps a file-change status to the visual badge shown in the file tree
 * — single letter + CSS modifier ("added"/"modified"/...). Two source
 * formats are supported because:
 *   - GitHub's REST diff returns long-form strings ("added", "renamed").
 *   - `git diff --name-status` (used by local-repo flows) returns
 *     letters ("A", "R100", "C75").
 * The CSS class is the long-form name in both cases so a single set of
 * styles in pr-detail.css handles both call sites.
 */
export type StatusBadge = { letter: string; cls: string };

export function statusBadge(status: string): StatusBadge {
  switch (status) {
    case 'added': return { letter: 'A', cls: 'added' };
    case 'removed': return { letter: 'D', cls: 'removed' };
    case 'renamed': return { letter: 'R', cls: 'renamed' };
    case 'copied': return { letter: 'C', cls: 'copied' };
    default: return { letter: 'M', cls: 'modified' };
  }
}

export function statusBadgeFromLetter(status: string): StatusBadge {
  // git --name-status emits R100/C75 etc. — match by leading char.
  const head = status.length > 0 ? status[0] : '';
  switch (head) {
    case 'A': return { letter: 'A', cls: 'added' };
    case 'D': return { letter: 'D', cls: 'removed' };
    case 'R': return { letter: 'R', cls: 'renamed' };
    case 'C': return { letter: 'C', cls: 'copied' };
    default: return { letter: 'M', cls: 'modified' };
  }
}
