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

export type DiffRow = {
  kind: 'context' | 'add' | 'del';
  oldLine: number | null;
  newLine: number | null;
  content: string;
};

export type DiffHunk = {
  header: string;
  oldStart: number;
  oldCount: number;
  newStart: number;
  newCount: number;
  rows: DiffRow[];
};

const HUNK_HEADER = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/;

export function parseUnifiedDiff(patch: string | null | undefined): DiffHunk[] {
  if (!patch) return [];
  const hunks: DiffHunk[] = [];
  let current: DiffHunk | null = null;
  let oldLine = 0;
  let newLine = 0;

  for (const line of patch.split('\n')) {
    const match = HUNK_HEADER.exec(line);
    if (match) {
      current = {
        header: line,
        oldStart: Number(match[1]),
        oldCount: match[2] ? Number(match[2]) : 1,
        newStart: Number(match[3]),
        newCount: match[4] ? Number(match[4]) : 1,
        rows: [],
      };
      oldLine = current.oldStart;
      newLine = current.newStart;
      hunks.push(current);
      continue;
    }
    if (!current) continue;

    const content = line.slice(1);
    if (line.startsWith('+')) {
      current.rows.push({ kind: 'add', oldLine: null, newLine: newLine++, content });
    }
    else if (line.startsWith('-')) {
      current.rows.push({ kind: 'del', oldLine: oldLine++, newLine: null, content });
    }
    else if (line.startsWith(' ') || line === '') {
      current.rows.push({ kind: 'context', oldLine: oldLine++, newLine: newLine++, content });
    }
  }
  return hunks;
}
