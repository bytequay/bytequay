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
import { parseUnifiedDiff } from './diffParse';

describe('parseUnifiedDiff', () => {
  it('parses hunks with old and new line numbers', () => {
    const patch = [
      '@@ -10,3 +10,4 @@ function run()',
      ' unchanged',
      '-removed',
      '+added one',
      '+added two',
      ' trailing',
    ].join('\n');

    expect(parseUnifiedDiff(patch)[0]).toEqual({
      header: '@@ -10,3 +10,4 @@ function run()',
      oldStart: 10,
      oldCount: 3,
      newStart: 10,
      newCount: 4,
      rows: [
        { kind: 'context', oldLine: 10, newLine: 10, content: 'unchanged' },
        { kind: 'del', oldLine: 11, newLine: null, content: 'removed' },
        { kind: 'add', oldLine: null, newLine: 11, content: 'added one' },
        { kind: 'add', oldLine: null, newLine: 12, content: 'added two' },
        { kind: 'context', oldLine: 12, newLine: 13, content: 'trailing' },
      ],
    });
  });

  it('ignores missing patches and no-newline markers', () => {
    expect(parseUnifiedDiff(null)).toEqual([]);
    expect(parseUnifiedDiff('')).toEqual([]);
    expect(parseUnifiedDiff([
      '@@ -1 +1 @@',
      '-old',
      '\\ No newline at end of file',
      '+new',
    ].join('\n'))[0].rows).toHaveLength(2);
  });
});
