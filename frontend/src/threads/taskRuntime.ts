/**
 * A task's actual runtime, in seconds: the sum of its completed turn
 * durations (turn_done rows carry the per-turn durationMs). This is real
 * compute time — NOT wall-clock since the task was created, which the metrics
 * card used to show and which ticks up forever even while the task sits idle
 * and never ran (a never-run task read "10h 37m").
 */
type DurationRow = { durationMs: number | null };

export function taskRuntimeSec(messages: readonly DurationRow[] | null | undefined): number {
  let totalMs = 0;
  for (const m of messages ?? []) {
    if (typeof m.durationMs === 'number' && m.durationMs > 0) {
      totalMs += m.durationMs;
    }
  }
  return Math.floor(totalMs / 1000);
}
