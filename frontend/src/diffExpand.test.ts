import { describe, it, expect } from 'vitest';
import {
  computeGap,
  computeGaps,
  computeFetchRange,
  isGapFullyLoaded,
  canExpandUp,
  canExpandDown,
  EXPAND_INCREMENT,
} from './diffExpand';
import type { DiffHunk } from './diffParse';

function hunk(oldStart: number, oldCount: number, newStart: number, newCount: number): DiffHunk {
  return { header: '', oldStart, oldCount, newStart, newCount, rows: [] };
}

describe('computeGap / computeGaps', () => {
  it('returns null for the top gap when the first hunk starts at line 1', () => {
    const hunks = [hunk(1, 5, 1, 5)];
    expect(computeGap(hunks, 0)).toBeNull();
  });

  it('returns the top gap when the first hunk starts past line 1', () => {
    const hunks = [hunk(10, 5, 10, 5)];
    const g = computeGap(hunks, 0);
    expect(g).not.toBeNull();
    expect(g!.newStart).toBe(1);
    expect(g!.newEnd).toBe(9);
    expect(g!.isTop).toBe(true);
    expect(g!.oldOffset).toBe(0);
  });

  it('returns the between-hunks gap with correct old-offset', () => {
    // Hunk 1: old 5..9 (5 lines), new 5..9 (5 lines) — additions cancel deletions.
    // Hunk 2: old 30..34, new 32..36 — net +2 from hunk 1's edits inside the gap? No,
    // simpler: pretend hunk 1 deleted 1 line. Then post-hunk1 old=10, new=10. Wait
    // for clarity make a hunk that adds 2 lines: oldCount 3, newCount 5. After it,
    // unchanged region's old-side trails new-side by -2.
    const hunks = [hunk(5, 3, 5, 5), hunk(20, 4, 22, 4)];
    const g = computeGap(hunks, 1);
    expect(g).not.toBeNull();
    expect(g!.newStart).toBe(10);  // 5 + 5
    expect(g!.newEnd).toBe(21);    // 22 - 1
    expect(g!.oldOffset).toBe(-2); // (5+3) - (5+5) = -2
  });

  it('returns the after-last gap with no upper bound', () => {
    const hunks = [hunk(1, 5, 1, 5)];
    const g = computeGap(hunks, 1);
    expect(g).not.toBeNull();
    expect(g!.newStart).toBe(6);
    expect(g!.newEnd).toBeNull();
    expect(g!.isBottom).toBe(true);
  });

  it('skips empty gaps when listing all', () => {
    // Two consecutive hunks with no gap between them.
    const hunks = [hunk(1, 5, 1, 5), hunk(6, 3, 6, 3)];
    const gaps = computeGaps(hunks);
    // Top gap empty (hunk starts at 1), middle gap empty (touch), bottom gap kept.
    expect(gaps.length).toBe(1);
    expect(gaps[0].isBottom).toBe(true);
  });
});

describe('computeFetchRange', () => {
  it('first "up" click fills the bottom of the gap (anchored to newEnd)', () => {
    const g = computeGap([hunk(50, 5, 50, 5)], 0)!; // top gap: 1..49
    const r = computeFetchRange(g, new Map(), 'up');
    expect(r).toEqual({ from: 30, to: 49 }); // 49 - 20 + 1 = 30
  });

  it('subsequent "up" click extends further up', () => {
    const g = computeGap([hunk(50, 5, 50, 5)], 0)!;
    // Already loaded 30..49.
    const loaded = new Map<number, string>();
    for (let i = 30; i <= 49; i++) loaded.set(i, '');
    const r = computeFetchRange(g, loaded, 'up');
    expect(r).toEqual({ from: 10, to: 29 });
  });

  it('"up" click clamps at gap.newStart', () => {
    const g = computeGap([hunk(50, 5, 50, 5)], 0)!; // top gap: 1..49
    const loaded = new Map<number, string>();
    for (let i = 10; i <= 49; i++) loaded.set(i, '');
    const r = computeFetchRange(g, loaded, 'up');
    expect(r).toEqual({ from: 1, to: 9 }); // partial last chunk
  });

  it('"up" returns null when gap is fully loaded', () => {
    const g = computeGap([hunk(50, 5, 50, 5)], 0)!;
    const loaded = new Map<number, string>();
    for (let i = 1; i <= 49; i++) loaded.set(i, '');
    expect(computeFetchRange(g, loaded, 'up')).toBeNull();
  });

  it('"up" returns null in bottom gap with nothing loaded (no anchor)', () => {
    const g = computeGap([hunk(1, 5, 1, 5)], 1)!; // bottom gap: 6..∞
    expect(computeFetchRange(g, new Map(), 'up')).toBeNull();
  });

  it('first "down" click fills the top of the gap (anchored to newStart)', () => {
    const hunks = [hunk(1, 5, 1, 5), hunk(100, 5, 100, 5)];
    const g = computeGap(hunks, 1)!; // middle gap: 6..99
    const r = computeFetchRange(g, new Map(), 'down');
    expect(r).toEqual({ from: 6, to: 25 });
  });

  it('subsequent "down" click extends further down', () => {
    const hunks = [hunk(1, 5, 1, 5), hunk(100, 5, 100, 5)];
    const g = computeGap(hunks, 1)!;
    const loaded = new Map<number, string>();
    for (let i = 6; i <= 25; i++) loaded.set(i, '');
    const r = computeFetchRange(g, loaded, 'down');
    expect(r).toEqual({ from: 26, to: 45 });
  });

  it('"down" click clamps at gap.newEnd', () => {
    const hunks = [hunk(1, 5, 1, 5), hunk(20, 5, 20, 5)];
    const g = computeGap(hunks, 1)!; // middle gap: 6..19
    const r = computeFetchRange(g, new Map(), 'down');
    expect(r).toEqual({ from: 6, to: 19 });
  });

  it('"down" in bottom gap has no upper bound', () => {
    const g = computeGap([hunk(1, 5, 1, 5)], 1)!;
    const r = computeFetchRange(g, new Map(), 'down');
    expect(r).toEqual({ from: 6, to: 6 + EXPAND_INCREMENT - 1 });
  });
});

describe('isGapFullyLoaded', () => {
  it('returns true when every line in a bounded gap is loaded', () => {
    const g = computeGap([hunk(10, 5, 10, 5)], 0)!;
    const loaded = new Map<number, string>();
    for (let i = 1; i <= 9; i++) loaded.set(i, '');
    expect(isGapFullyLoaded(g, loaded)).toBe(true);
  });

  it('returns false when one line is missing', () => {
    const g = computeGap([hunk(10, 5, 10, 5)], 0)!;
    const loaded = new Map<number, string>();
    for (let i = 1; i <= 9; i++) {
      if (i !== 5) loaded.set(i, '');
    }
    expect(isGapFullyLoaded(g, loaded)).toBe(false);
  });

  it('always returns false for bottom gap (no upper bound)', () => {
    const g = computeGap([hunk(1, 5, 1, 5)], 1)!;
    const loaded = new Map<number, string>();
    for (let i = 6; i <= 100; i++) loaded.set(i, '');
    expect(isGapFullyLoaded(g, loaded)).toBe(false);
  });
});

describe('canExpandUp / canExpandDown', () => {
  it('top gap allows up but not down with nothing loaded', () => {
    const g = computeGap([hunk(20, 5, 20, 5)], 0)!;
    expect(canExpandUp(g, new Map())).toBe(true);
    // canExpandDown wants gap.newStart-anchored fetch — that's available too
    // since gap is bounded; only the "no anchor" case is blocked.
    expect(canExpandDown(g, new Map())).toBe(true);
  });

  it('bottom gap allows down but not up with nothing loaded', () => {
    const g = computeGap([hunk(1, 5, 1, 5)], 1)!;
    expect(canExpandUp(g, new Map())).toBe(false);
    expect(canExpandDown(g, new Map())).toBe(true);
  });

  it('both directions become false in a fully-loaded bounded gap', () => {
    const g = computeGap([hunk(10, 5, 10, 5)], 0)!;
    const loaded = new Map<number, string>();
    for (let i = 1; i <= 9; i++) loaded.set(i, '');
    expect(canExpandUp(g, loaded)).toBe(false);
    expect(canExpandDown(g, loaded)).toBe(false);
  });
});
