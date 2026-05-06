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
import { describe, it, expect } from 'vitest';
import { sliceHunkToRange } from './CommentBody';

describe('sliceHunkToRange', () => {
  it('slices a multi-line + range to just the commented lines', () => {
    // Hunk: 5 added lines starting at new-side line 238.
    const hunk = [
      '@@ -237,1 +237,5 @@ class Foo',
      ' previous line',
      '+    finally {',
      '+        metastore.dropTable(databaseName, tableName, false);',
      '+    }',
      '+}',
    ].join('\n');
    const sliced = sliceHunkToRange(hunk, { startLine: 238, endLine: 240, side: 'RIGHT' });
    expect(sliced.map(r => `${r.lineNo}${r.kind === 'add' ? '+' : ''}${r.text}`)).toEqual([
      '238+    finally {',
      '239+        metastore.dropTable(databaseName, tableName, false);',
      '240+    }',
    ]);
  });

  it('keeps just the single commented line for a single-line range', () => {
    const hunk = [
      '@@ -10,3 +10,4 @@',
      ' a',
      ' b',
      '+inserted line',
      ' c',
    ].join('\n');
    const sliced = sliceHunkToRange(hunk, { startLine: 12, endLine: 12, side: 'RIGHT' });
    expect(sliced.length).toBe(1);
    expect(sliced[0]).toEqual({ kind: 'add', lineNo: 12, text: 'inserted line' });
  });

  it('honours LEFT side comments — keeps - lines, drops + lines', () => {
    const hunk = [
      '@@ -50,3 +50,2 @@',
      ' ctx',
      '-removed line',
      '+added line',
    ].join('\n');
    const sliced = sliceHunkToRange(hunk, { startLine: 51, endLine: 51, side: 'LEFT' });
    expect(sliced).toEqual([{ kind: 'del', lineNo: 51, text: 'removed line' }]);
  });

  it('drops other-side change lines so the range stays focused', () => {
    // Comment on RIGHT lines 100-101. The hunk has - lines too — they
    // shouldn't pollute the slice.
    const hunk = [
      '@@ -98,4 +98,4 @@',
      ' ctx 98',
      '-old line 99',
      '+new line 99',
      '-old line 100',
      '+new line 100',
      '+new line 101',
    ].join('\n');
    const sliced = sliceHunkToRange(hunk, { startLine: 100, endLine: 101, side: 'RIGHT' });
    expect(sliced.map(r => `${r.lineNo}:${r.kind}:${r.text}`)).toEqual([
      '100:add:new line 100',
      '101:add:new line 101',
    ]);
  });

  it('returns an empty array when the range falls outside the hunk', () => {
    const hunk = [
      '@@ -10,2 +10,2 @@',
      '+a',
      '+b',
    ].join('\n');
    const sliced = sliceHunkToRange(hunk, { startLine: 100, endLine: 102, side: 'RIGHT' });
    expect(sliced).toEqual([]);
  });

  it('skips the "\\ No newline at end of file" metadata line', () => {
    const hunk = [
      '@@ -5,1 +5,1 @@',
      '-old',
      '\\ No newline at end of file',
      '+new',
      '\\ No newline at end of file',
    ].join('\n');
    const sliced = sliceHunkToRange(hunk, { startLine: 5, endLine: 5, side: 'RIGHT' });
    expect(sliced).toEqual([{ kind: 'add', lineNo: 5, text: 'new' }]);
  });
});
