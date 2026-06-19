import { describe, it, expect } from 'vitest';
import { taskRuntimeSec } from './taskRuntime';

const turn = (durationMs: number | null) => ({ durationMs });

describe('taskRuntimeSec', () => {
  it('sums completed turn durations into whole seconds', () => {
    expect(taskRuntimeSec([turn(1500), turn(2500), turn(null)])).toBe(4); // 4000ms
  });

  it('is 0 for a task that never ran', () => {
    // The bug: wall-clock since createdAt showed 10h+ for an idle task that
    // produced no turns. Real runtime is 0.
    expect(taskRuntimeSec([])).toBe(0);
    expect(taskRuntimeSec([turn(null), turn(null)])).toBe(0);
    expect(taskRuntimeSec(null)).toBe(0);
    expect(taskRuntimeSec(undefined)).toBe(0);
  });

  it('ignores zero / negative durations', () => {
    expect(taskRuntimeSec([turn(0), turn(-5), turn(3000)])).toBe(3);
  });
});
