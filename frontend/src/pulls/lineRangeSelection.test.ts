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
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  draggedLineSelection,
  inlineReviewComment,
  nextLineSelection,
  NO_RANGE_SELECTION,
  rangeHeading,
  rangeSelectionReducer,
  type RangeSelection,
  type RangeSelectionAction,
} from './lineRangeSelection';

const run = (actions: RangeSelectionAction[], state: RangeSelection = NO_RANGE_SELECTION) =>
  actions.reduce(rangeSelectionReducer, state);
const right = (line: number): RangeSelectionAction => ({ type: 'drag', oldLine: null, newLine: line, row: line });

describe('line range selection', () => {
  it('selects a consecutive range on one side of the diff', () => {
    const first = nextLineSelection(null, 'LEFT', 48, false, 2);

    expect(nextLineSelection(first, 'LEFT', 59, true, 4)).toEqual({ side: 'LEFT', start: 48, end: 59, rowStart: 2, rowEnd: 4 });
    expect(nextLineSelection(first, 'RIGHT', 60, true, 5)).toEqual({ side: 'RIGHT', start: 60, end: 60, rowStart: 5, rowEnd: 5 });
  });

  it('keeps extending a drag from anywhere on each diff row', () => {
    const anchor = { side: 'LEFT' as const, line: 48, oldLine: 48, newLine: 48, row: 2 };

    expect(draggedLineSelection(anchor, 59, null, 4)).toEqual({ side: 'LEFT', start: 48, end: 59, rowStart: 2, rowEnd: 4 });
    expect(draggedLineSelection({ ...anchor, newLine: null }, null, 60, 5)).toBeNull();
  });

  it('continues a downward drag from context into added lines', () => {
    const anchor = { side: 'LEFT' as const, line: 303, oldLine: 303, newLine: 303, row: 6 };

    expect(draggedLineSelection(anchor, null, 316, 9)).toEqual({ side: 'RIGHT', start: 303, end: 316, rowStart: 6, rowEnd: 9 });
    expect(draggedLineSelection({ ...anchor, oldLine: 303, newLine: null }, null, 316, 9)).toBeNull();
  });

  it('keeps every visible row between same-side drag endpoints selected', () => {
    const anchor = { side: 'RIGHT' as const, line: 144, oldLine: null as number | null, newLine: 144, row: 0 };

    expect(draggedLineSelection(anchor, 144, null, 3)).toBeNull();
    expect(draggedLineSelection(anchor, null, 147, 4)).toEqual({
      side: 'RIGHT', start: 144, end: 147, rowStart: 0, rowEnd: 4,
    });
  });

  it('keeps a dragged selection and ends the drag on finish', () => {
    const dragging = run([
      { type: 'start', side: 'RIGHT', line: 3, extend: false, oldLine: 3, newLine: 3, row: 1 },
      { type: 'drag', oldLine: 4, newLine: 5, row: 4 },
    ]);
    expect(dragging).toEqual({
      selection: { side: 'RIGHT', start: 3, end: 5, rowStart: 1, rowEnd: 4 },
      anchor: { side: 'RIGHT', line: 3, oldLine: 3, newLine: 3, row: 1 },
    });

    expect(rangeSelectionReducer(dragging, { type: 'finish' })).toEqual({ selection: dragging.selection, anchor: null });
  });

  it('returns the same state when a drag changes nothing', () => {
    const idle = run([{ type: 'select', side: 'RIGHT', line: 3, extend: false, row: 3 }]);
    expect(rangeSelectionReducer(idle, right(5))).toBe(idle);
    expect(rangeSelectionReducer(idle, { type: 'finish' })).toBe(idle);

    const dragging = run([{ type: 'start', side: 'RIGHT', line: 3, extend: false, oldLine: null, newLine: 3, row: 3 }, right(5)]);
    expect(rangeSelectionReducer(dragging, right(5))).toBe(dragging);
    expect(rangeSelectionReducer(dragging, { type: 'drag', oldLine: 4, newLine: null, row: 4 })).toBe(dragging);
  });

  it('moves the anchor to the new side when a drag leaves context for added lines', () => {
    const migrated = run([
      { type: 'start', side: 'LEFT', line: 303, extend: false, oldLine: 303, newLine: 303, row: 6 },
      { type: 'drag', oldLine: null, newLine: 316, row: 9 },
    ]);
    expect(migrated.selection).toEqual({ side: 'RIGHT', start: 303, end: 316, rowStart: 6, rowEnd: 9 });
    expect(migrated.anchor).toEqual({ side: 'RIGHT', line: 303, oldLine: 303, newLine: 303, row: 6 });

    expect(rangeSelectionReducer(migrated, { type: 'drag', oldLine: null, newLine: 318, row: 11 }).selection)
      .toEqual({ side: 'RIGHT', start: 303, end: 318, rowStart: 6, rowEnd: 11 });
  });

  it('extends a keyboard selection to the union without starting a drag', () => {
    const state = run([
      { type: 'select', side: 'LEFT', line: 10, extend: false, row: 1 },
      { type: 'select', side: 'LEFT', line: 14, extend: true, row: 5 },
      { type: 'select', side: 'LEFT', line: 12, extend: true, row: 3 },
    ]);

    expect(state).toEqual({ selection: { side: 'LEFT', start: 10, end: 14, rowStart: 1, rowEnd: 5 }, anchor: null });
  });

  it('clears the selection and any drag', () => {
    const dragging = run([{ type: 'start', side: 'RIGHT', line: 3, extend: false, oldLine: null, newLine: 3, row: 3 }]);

    expect(rangeSelectionReducer(dragging, { type: 'clear' })).toBe(NO_RANGE_SELECTION);
  });

  it('selects upward when a drag starts below its end', () => {
    const state = run([{ type: 'start', side: 'RIGHT', line: 20, extend: false, oldLine: null, newLine: 20, row: 20 }, right(12)]);

    expect(state.selection).toEqual({ side: 'RIGHT', start: 12, end: 20, rowStart: 12, rowEnd: 20 });
  });

  it('drags a Shift-press from the far end of the existing range', () => {
    const existing = run([{ type: 'select', side: 'RIGHT', line: 10, extend: false, row: 10 }, { type: 'select', side: 'RIGHT', line: 14, extend: true, row: 14 }]);
    const shift = (line: number): RangeSelectionAction => ({ type: 'start', side: 'RIGHT', line, extend: true, oldLine: null, newLine: line, row: line });

    const below = rangeSelectionReducer(existing, shift(20));
    expect(below.selection).toMatchObject({ start: 10, end: 20 });
    expect(rangeSelectionReducer(below, right(16)).selection).toMatchObject({ start: 10, end: 16 });
    expect(rangeSelectionReducer(below, { type: 'finish' }).selection).toMatchObject({ start: 10, end: 20 });

    const above = rangeSelectionReducer(existing, shift(5));
    expect(above.selection).toMatchObject({ start: 5, end: 14 });
    expect(rangeSelectionReducer(above, right(8)).selection).toMatchObject({ start: 8, end: 14, rowStart: 8, rowEnd: 14 });

    expect(run([shift(13), right(12)], existing).selection).toMatchObject({ start: 10, end: 12 });

    const otherSide = run([{ type: 'start', side: 'LEFT', line: 20, extend: true, oldLine: 20, newLine: null, row: 20 }, { type: 'drag', oldLine: 22, newLine: null, row: 22 }], existing);
    expect(otherSide.selection).toEqual({ side: 'LEFT', start: 20, end: 22, rowStart: 20, rowEnd: 22 });
  });

  it('names one line, a range and the old side', () => {
    expect(rangeHeading({ side: 'RIGHT', start: 10, end: 10 })).toBe('Add a comment on line R10');
    expect(rangeHeading({ side: 'RIGHT', start: 10, end: 14 })).toBe('Add a comment on lines R10 to R14');
    expect(rangeHeading({ side: 'LEFT', start: 2, end: 3 })).toBe('Add a comment on lines L2 to L3');
  });

  it('builds the inline review comment for a line or a range', () => {
    expect(inlineReviewComment('a.ts', '  \n', { side: 'RIGHT', start: 1, end: 1 }, 'abc')).toBeNull();
    expect(inlineReviewComment('a.ts', ' Why? ', { side: 'RIGHT', start: 4, end: 4 }, null)).toEqual({
      path: 'a.ts', body: 'Why?', line: 4, side: 'RIGHT', startLine: null, startSide: null, commitId: null,
    });
    expect(inlineReviewComment('a.ts', 'Why?', { side: 'LEFT', start: 2, end: 3 }, 'abc')).toEqual({
      path: 'a.ts', body: 'Why?', line: 3, side: 'LEFT', startLine: 2, startSide: 'LEFT', commitId: 'abc',
    });
  });

  it('paints selected diff rows and Code & Tests lines with the shared selection token', () => {
    const css = (name: string) => readFileSync(resolve(process.cwd(), `src/css/${name}`), 'utf8');

    expect(css('base.css')).toContain('--diff-selected: #fff8c5;');
    expect(css('pulls.css')).toMatch(/\.pull-diff-code tr\.is-selected \.is-source \{[^}]*background: var\(--diff-selected\)/);
    expect(css('code-tests.css')).toMatch(/\.ct-snapshot__line\.is-selected \{[^}]*background: var\(--diff-selected\)/);
    expect(css('code-tests.css')).toMatch(/\.ct-snapshot__line\.is-selected \.ct-snapshot__number \{[^}]*background: var\(--diff-selected\)/);
  });
});
