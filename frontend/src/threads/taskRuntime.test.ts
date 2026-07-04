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
