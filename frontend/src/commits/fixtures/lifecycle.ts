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
import type { CherryPickLifecycle } from '../../types';

/** A lifecycle that has not moved past applying, with any fields a test needs changed. */
export function lifecycle(overrides: Partial<CherryPickLifecycle> = {}): CherryPickLifecycle {
  return {
    stage: 'APPLY', delivers: false, baseSha: 'a'.repeat(40), hold: null, commits: [], rebases: [], ciRunIds: [], squash: null,
    readyForReview: false, notes: [], ...overrides,
  };
}
