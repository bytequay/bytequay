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
import type { InlineReviewCommentDto } from '../types';
import type { ReviewSide } from './agent-review/contracts';

export type LineSelection = { side: ReviewSide; start: number; end: number; rowStart: number; rowEnd: number };
export type DragAnchor = { side: ReviewSide; line: number; oldLine: number | null; newLine: number | null; row: number };

export function nextLineSelection(
  previous: LineSelection | null,
  side: ReviewSide,
  line: number,
  extend: boolean,
  row: number,
): LineSelection {
  if (!extend || previous === null || previous.side !== side) return { side, start: line, end: line, rowStart: row, rowEnd: row };
  return {
    side,
    start: Math.min(previous.start, line),
    end: Math.max(previous.end, line),
    rowStart: Math.min(previous.rowStart, row),
    rowEnd: Math.max(previous.rowEnd, row),
  };
}

export function draggedLineSelection(
  anchor: DragAnchor | null,
  oldLine: number | null,
  newLine: number | null,
  row: number,
): LineSelection | null {
  if (anchor === null) return null;
  const line = anchor.side === 'LEFT' ? oldLine : newLine;
  if (line !== null) {
    return {
      side: anchor.side,
      start: Math.min(anchor.line, line),
      end: Math.max(anchor.line, line),
      rowStart: Math.min(anchor.row, row),
      rowEnd: Math.max(anchor.row, row),
    };
  }

  const otherSide = anchor.side === 'LEFT' ? 'RIGHT' : 'LEFT';
  const otherAnchorLine = otherSide === 'LEFT' ? anchor.oldLine : anchor.newLine;
  const otherLine = otherSide === 'LEFT' ? oldLine : newLine;
  if (otherAnchorLine === null || otherLine === null) return null;
  return {
    side: otherSide,
    start: Math.min(otherAnchorLine, otherLine),
    end: Math.max(otherAnchorLine, otherLine),
    rowStart: Math.min(anchor.row, row),
    rowEnd: Math.max(anchor.row, row),
  };
}

/** `anchor` is non-null exactly while a drag is in progress. */
export type RangeSelection = { selection: LineSelection | null; anchor: DragAnchor | null };
export const NO_RANGE_SELECTION: RangeSelection = { selection: null, anchor: null };
export type RangeSelectionAction =
  | { type: 'select'; side: ReviewSide; line: number; extend: boolean; row: number }
  | { type: 'start'; side: ReviewSide; line: number; extend: boolean; oldLine: number | null; newLine: number | null; row: number }
  | { type: 'drag'; oldLine: number | null; newLine: number | null; row: number }
  | { type: 'finish' }
  | { type: 'clear' };

/** Returns `state` itself when nothing changes, such as a drag with no anchor or a drag onto the same range. */
export function rangeSelectionReducer(state: RangeSelection, action: RangeSelectionAction): RangeSelection {
  switch (action.type) {
    case 'select':
      return { ...state, selection: nextLineSelection(state.selection, action.side, action.line, action.extend, action.row) };
    case 'start': {
      const { side, line, extend, oldLine, newLine, row } = action;
      const previous = state.selection;
      const selection = nextLineSelection(previous, side, line, extend, row);
      if (!extend || previous === null || previous.side !== side) return { selection, anchor: { side, line, oldLine, newLine, row } };
      // A Shift-drag grows or shrinks from the far end of the existing range, not from the pressed line.
      // ponytail: that end's other-side line is unknown, so this drag cannot migrate sides; store both lines per end if that matters.
      const fromStart = line - previous.start >= previous.end - line;
      const anchorLine = fromStart ? previous.start : previous.end;
      return {
        selection,
        anchor: {
          side,
          line: anchorLine,
          oldLine: side === 'LEFT' ? anchorLine : null,
          newLine: side === 'RIGHT' ? anchorLine : null,
          row: fromStart ? previous.rowStart : previous.rowEnd,
        },
      };
    }
    case 'drag': {
      const anchor = state.anchor;
      const next = draggedLineSelection(anchor, action.oldLine, action.newLine, action.row);
      if (next === null || anchor === null) return state;
      const migratedLine = next.side === 'LEFT' ? anchor.oldLine : anchor.newLine;
      const nextAnchor = next.side === anchor.side || migratedLine === null ? anchor : { ...anchor, side: next.side, line: migratedLine };
      const current = state.selection;
      if (nextAnchor === anchor && current !== null && current.side === next.side && current.start === next.start
        && current.end === next.end && current.rowStart === next.rowStart && current.rowEnd === next.rowEnd) return state;
      return { selection: next, anchor: nextAnchor };
    }
    case 'finish':
      return state.anchor === null ? state : { ...state, anchor: null };
    case 'clear':
      return NO_RANGE_SELECTION;
  }
}

export function rangeHeading(range: Pick<LineSelection, 'side' | 'start' | 'end'>): string {
  const prefix = range.side === 'RIGHT' ? 'R' : 'L';
  return range.start === range.end
    ? `Add a comment on line ${prefix}${range.start}`
    : `Add a comment on lines ${prefix}${range.start} to ${prefix}${range.end}`;
}

/**
 * GitHub anchors a whole review at one commit and resolves every comment's path and line against
 * it, so comments written while different commits were selected have to go out as one review each.
 * The group written on `headSha` sorts last, so the verdict is recorded against the current head.
 * Returns one empty group when there is nothing staged, so a body-only review still goes out.
 */
export function reviewCommentGroups(
  comments: InlineReviewCommentDto[],
  headSha: string | null,
): InlineReviewCommentDto[][] {
  const groups = new Map<string | null, InlineReviewCommentDto[]>();
  for (const comment of comments.filter(value => value.replyToCommentId == null)) {
    const group = groups.get(comment.commitId);
    if (group === undefined) groups.set(comment.commitId, [comment]);
    else group.push(comment);
  }
  if (groups.size === 0) return [comments];
  // sort is stable, so every other group keeps the order its comments were written in.
  const batches = [...groups.values()].sort((left, right) =>
    Number(left[0].commitId === headSha) - Number(right[0].commitId === headSha));
  batches[batches.length - 1].push(...comments.filter(comment => comment.replyToCommentId != null));
  return batches;
}

/** Null when the body is blank. */
export function inlineReviewComment(
  path: string,
  body: string,
  range: Pick<LineSelection, 'side' | 'start' | 'end'>,
  commitId: string | null,
): InlineReviewCommentDto | null {
  if (!body.trim()) return null;
  const single = range.start === range.end;
  return {
    path,
    body: body.trim(),
    line: range.end,
    side: range.side,
    startLine: single ? null : range.start,
    startSide: single ? null : range.side,
    commitId,
  };
}
