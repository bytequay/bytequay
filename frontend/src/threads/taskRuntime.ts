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
