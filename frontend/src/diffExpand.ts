/*
 * Pure helpers for the "expand collapsed code" affordance in the diff
 * viewer. Given a list of parsed hunks plus the set of already-loaded
 * extra context lines, computes:
 *
 *   - the bounds of each gap (region of the new file that's hidden
 *     between the file edges and the diff hunks),
 *   - which direction(s) the user can still expand into,
 *   - the fetch range a click should request,
 *   - the old-side line number for any new-side line inside a gap
 *     (so expanded rows can render their oldLine column correctly).
 *
 * Gap indexing: gap `g` is "the gap that comes BEFORE hunks[g]". So
 * gap 0 is the top-of-file gap, gap hunks.length is the after-last-hunk
 * gap. There are hunks.length + 1 gaps in total.
 */
import type { DiffHunk } from './diffParse';

export const EXPAND_INCREMENT = 20;

export type Gap = {
  /** Index of the hunk this gap appears BEFORE; hunks.length means
   *  "after the last hunk". */
  index: number;
  /** First new-side line number in this gap (inclusive). */
  newStart: number;
  /** Last new-side line number in this gap (inclusive), or null when
   *  unknown — the after-last-hunk gap has no upper bound until we've
   *  fetched past the end of the file. */
  newEnd: number | null;
  /** Offset to add to a new-side line number to get the corresponding
   *  old-side line number for unchanged context inside this gap. */
  oldOffset: number;
  /** True when this is the top-of-file gap (only "up" expand makes
   *  sense — there's no hunk above to grow from). */
  isTop: boolean;
  /** True when this is the after-last-hunk gap (only "down" expand
   *  makes sense). */
  isBottom: boolean;
};

export function computeGap(hunks: DiffHunk[], gapIndex: number): Gap | null {
  const hAbove = gapIndex === 0 ? null : hunks[gapIndex - 1];
  const hBelow = gapIndex >= hunks.length ? null : hunks[gapIndex];
  const newStart = hAbove ? hAbove.newStart + hAbove.newCount : 1;
  const newEnd = hBelow ? hBelow.newStart - 1 : null;
  // Offset = oldLine - newLine for an unchanged line inside the gap.
  // Derive from whichever boundary hunk we have. Both should agree when
  // both exist (the diff is consistent), but prefer hAbove since its
  // post-hunk anchor is exactly where the unchanged region resumes.
  const oldOffset = hAbove
    ? (hAbove.oldStart + hAbove.oldCount) - (hAbove.newStart + hAbove.newCount)
    : hBelow
    ? hBelow.oldStart - hBelow.newStart
    : 0;
  // A gap with no hidden lines (e.g. consecutive hunks that touch) is
  // not interesting — caller skips rendering for it.
  if (newEnd != null && newEnd < newStart) return null;
  return {
    index: gapIndex,
    newStart,
    newEnd,
    oldOffset,
    isTop: gapIndex === 0,
    isBottom: gapIndex >= hunks.length,
  };
}

/** All gaps with at least one hidden line, in order. */
export function computeGaps(hunks: DiffHunk[]): Gap[] {
  const out: Gap[] = [];
  for (let g = 0; g <= hunks.length; g++) {
    const gap = computeGap(hunks, g);
    if (gap) out.push(gap);
  }
  return out;
}

/** State of one gap: which new-side lines we've already loaded. */
export type LoadedGap = Map<number, string>;

/** The lines we'd request next on a click, given which direction the
 *  user clicked. Returns null when there's nothing more to load in
 *  that direction. */
export function computeFetchRange(
  gap: Gap,
  loaded: LoadedGap,
  direction: 'up' | 'down',
  increment: number = EXPAND_INCREMENT,
): { from: number; to: number } | null {
  const lowestLoaded = loaded.size > 0 ? Math.min(...loaded.keys()) : null;
  const highestLoaded = loaded.size > 0 ? Math.max(...loaded.keys()) : null;
  if (direction === 'up') {
    // Growing upward = filling toward gap.newEnd (the line just before
    // the hunk below). The first click loads the (increment) lines
    // ending at gap.newEnd; subsequent clicks load (increment) lines
    // ending just below the lowest already-loaded line.
    const upperEnd = lowestLoaded != null ? lowestLoaded - 1 : (gap.newEnd ?? null);
    if (upperEnd == null) return null; // no anchor for "up" in bottom gap with nothing loaded
    if (upperEnd < gap.newStart) return null;
    const lowerEnd = Math.max(gap.newStart, upperEnd - increment + 1);
    return { from: lowerEnd, to: upperEnd };
  }
  // direction === 'down' — fill toward gap.newStart-edge from above.
  const lowerStart = highestLoaded != null ? highestLoaded + 1 : gap.newStart;
  if (gap.newEnd != null && lowerStart > gap.newEnd) return null;
  const upperStart = gap.newEnd != null
    ? Math.min(gap.newEnd, lowerStart + increment - 1)
    : lowerStart + increment - 1;
  return { from: lowerStart, to: upperStart };
}

/** True when every line in the gap has been loaded; the renderer can
 *  hide the expand controls for this gap entirely. */
export function isGapFullyLoaded(gap: Gap, loaded: LoadedGap): boolean {
  if (gap.newEnd == null) return false; // bottom gap is "done" only when fetch returns short
  for (let n = gap.newStart; n <= gap.newEnd; n++) {
    if (!loaded.has(n)) return false;
  }
  return true;
}

/** True when the user can click "up" (load lines toward the bottom-edge
 *  of the gap). For the top gap this is the only direction. */
export function canExpandUp(gap: Gap, loaded: LoadedGap): boolean {
  if (gap.isBottom && loaded.size === 0) return false; // no lower edge yet
  return computeFetchRange(gap, loaded, 'up') != null;
}

/** True when the user can click "down" (load lines from the top-edge of
 *  the gap downward). For the bottom gap this is the only direction. */
export function canExpandDown(gap: Gap, loaded: LoadedGap): boolean {
  return computeFetchRange(gap, loaded, 'down') != null;
}
