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
import { expect, it } from 'vitest';
import type { BlameSpan } from './contracts';
import { blameAt, blameLine, overviewLine } from './navigationModel';

it('aligns repeated blame spans, distinguishes local edits and escapes repository metadata', () => {
  const first: BlameSpan = { line: 1, count: 2, originalLine: 10, commit: 'a'.repeat(40), author: '<img src=x onerror=alert(1)>',
    authoredAt: '2024-01-01T00:00:00Z', summary: '"<script>alert(1)</script>', originalPath: 'src/測試.go' };
  const local = { ...first, line: 3, count: 1, commit: '0'.repeat(40) };
  const spans = [first, local, { ...first, line: 5 }];
  expect([1, 2, 3, 4, 5, 6, 7].map(line => blameAt(spans, line)?.commit)).toEqual([first.commit, first.commit, local.commit, undefined, first.commit, first.commit, undefined]);
  expect(blameAt(spans, 2)).toMatchObject({ line: 2, count: 1, originalLine: 11 });
  expect(blameLine(spans, 1)).toContain('&lt;img');
  expect(blameLine(spans, 1)).not.toContain('<img');
  expect(blameLine(spans, 1)).toContain('&quot;&lt;script&gt;');
  expect(blameLine(spans, 3)).toContain('Uncommitted');
});

it('maps the overview to folded document lines and review-card gaps without moving the cursor', () => {
  const tops = [0, 20, 40, 40, 40, 60, 320, 340];
  const line = (fraction: number) => overviewLine(fraction, 360, tops.length, line => tops[line - 1]);
  expect(line(-1)).toBe(1);
  expect(line(0.125)).toBe(5);
  expect(line(0.5)).toBe(6);
  expect(line(0.9)).toBe(7);
  expect(line(2)).toBe(8);
});
