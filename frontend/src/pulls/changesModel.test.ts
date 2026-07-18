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
import { describe, expect, it } from 'vitest';
import type { DiffHunk } from '../diffParse';
import { assocLabel, diffRowsFor, snippetRowFor } from './changesModel';

function hunk(newStart: number): DiffHunk {
  return {
    header: `@@ -${newStart},1 +${newStart},1 @@`,
    oldStart: newStart,
    oldCount: 1,
    newStart,
    newCount: 1,
    rows: [],
  };
}

describe('diffRowsFor', () => {
  it('keeps each hidden run between the loaded lines surrounding it', () => {
    const loaded = new Map<number, string>([[2, 'two'], [4, 'four']]);

    expect(diffRowsFor([hunk(6)], new Map([[0, loaded]]))).toEqual([
      { kind: 'exp', gapIndex: 0, text: '1 unmodified lines' },
      { kind: 'code', cls: '', sign: '  ', text: 'two', oldLn: '2', newLn: '2', side: 'RIGHT', line: 2 },
      { kind: 'exp', gapIndex: 0, text: '1 unmodified lines' },
      { kind: 'code', cls: '', sign: '  ', text: 'four', oldLn: '4', newLn: '4', side: 'RIGHT', line: 4 },
      { kind: 'exp', gapIndex: 0, text: '1 unmodified lines' },
      { kind: 'hunk', text: '@@ -6,1 +6,1 @@' },
    ]);
  });
});

describe('snippetRowFor', () => {
  const patch = ['@@ -10,2 +11,2 @@', ' same', '-old', '+new'].join('\n');

  it('preserves the side and line of a LEFT-side context anchor', () => {
    expect(snippetRowFor(patch, 'LEFT', 10)).toMatchObject({
      side: 'LEFT', line: 10, oldLn: '10', newLn: '11', text: 'same',
    });
  });
});

describe('assocLabel', () => {
  it('uses the shared GitHub association mapping', () => {
    expect(assocLabel('OWNER')).toBe('Owner');
    expect(assocLabel('FIRST_TIME_CONTRIBUTOR')).toBe('Contributor');
    expect(assocLabel('NONE')).toBeNull();
    expect(assocLabel('UNKNOWN')).toBeNull();
  });
});
