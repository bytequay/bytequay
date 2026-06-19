import { describe, it, expect } from 'vitest';
import { currentContextTokens, contextWindowPct } from './contextWindow';

const turn = (tokensIn: number | null) => ({ tokensIn });

describe('currentContextTokens', () => {
  it('uses the most recent turn input tokens, not the cumulative sum', () => {
    // Three turns of 50k / 60k / 70k — the cumulative would be 180k, but the
    // current window is just the latest turn's 70k.
    expect(currentContextTokens([turn(50_000), turn(60_000), turn(70_000)])).toBe(70_000);
  });

  it('ignores rows without a per-turn snapshot (non turn_done)', () => {
    expect(currentContextTokens([turn(40_000), turn(null), turn(null)])).toBe(40_000);
  });

  it('prefers the in-flight live usage when it is larger', () => {
    expect(currentContextTokens([turn(30_000)], 45_000)).toBe(45_000);
  });

  it('is 0 when there is no turn data', () => {
    expect(currentContextTokens(null)).toBe(0);
    expect(currentContextTokens([])).toBe(0);
    expect(currentContextTokens([turn(null)])).toBe(0);
  });
});

describe('contextWindowPct', () => {
  it('does NOT report a fresh task as critical (the 26M bug)', () => {
    // A brand-new task used to inherit the thread's 26M cumulative → 100%.
    // With no turns yet it must read 0.
    expect(contextWindowPct([], 0)).toBe(0);
  });

  it('computes occupancy from the last turn against the limit', () => {
    expect(contextWindowPct([turn(100_000)], 0, 200_000)).toBe(50);
  });

  it('caps at 100 when a single turn exceeds the window', () => {
    expect(contextWindowPct([turn(500_000)], 0, 200_000)).toBe(100);
  });

  it('reflects only the latest turn even after an earlier huge turn', () => {
    // An earlier 26M-token turn must not dominate — the current window is the
    // most recent turn (20k → 10%).
    expect(contextWindowPct([turn(26_000_000), turn(20_000)], 0, 200_000)).toBe(10);
  });
});
