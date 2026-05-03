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
  it('returns an empty list for null or empty patches', () => {
    expect(parseUnifiedDiff(null)).toEqual([]);
    expect(parseUnifiedDiff(undefined)).toEqual([]);
    expect(parseUnifiedDiff('')).toEqual([]);
  });

  it('parses a single-hunk patch and assigns line numbers on both sides', () => {
    const patch = [
      '@@ -10,3 +10,4 @@ some context',
      ' line ten',
      '-removed',
      '+added one',
      '+added two',
      ' line thirteen',
    ].join('\n');

    const hunks = parseUnifiedDiff(patch);
    expect(hunks).toHaveLength(1);
    const h = hunks[0];
    expect(h.oldStart).toBe(10);
    expect(h.oldCount).toBe(3);
    expect(h.newStart).toBe(10);
    expect(h.newCount).toBe(4);

    // Drop the synthetic hunk-header row for ease of assertion.
    const body = h.rows.filter(r => r.kind !== 'hunk-header');
    expect(body).toEqual([
      { kind: 'context', oldLine: 10, newLine: 10, content: 'line ten' },
      { kind: 'del',     oldLine: 11, newLine: null, content: 'removed' },
      { kind: 'add',     oldLine: null, newLine: 11, content: 'added one' },
      { kind: 'add',     oldLine: null, newLine: 12, content: 'added two' },
      { kind: 'context', oldLine: 12, newLine: 13, content: 'line thirteen' },
    ]);
  });

  it('handles multiple hunks in one file', () => {
    const patch = [
      '@@ -1,1 +1,1 @@',
      '-a',
      '+A',
      '@@ -10,1 +10,1 @@',
      '-b',
      '+B',
    ].join('\n');
    const hunks = parseUnifiedDiff(patch);
    expect(hunks).toHaveLength(2);
    expect(hunks[0].oldStart).toBe(1);
    expect(hunks[1].oldStart).toBe(10);
  });

  it('ignores "\\ No newline at end of file" markers', () => {
    const patch = [
      '@@ -1,1 +1,1 @@',
      '-old',
      '\\ No newline at end of file',
      '+new',
      '\\ No newline at end of file',
    ].join('\n');
    const rows = parseUnifiedDiff(patch)[0].rows.filter(r => r.kind !== 'hunk-header');
    expect(rows.map(r => r.kind)).toEqual(['del', 'add']);
  });
});
