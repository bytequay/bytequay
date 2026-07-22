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
import { describe, expect, it } from 'vitest';
import type { DevPhaseDto } from '../../types/brainView';
import { deriveLocalReviewGate } from './localReviewGate';

function phases(brainMeta: string | null, validation: DevPhaseDto['status'] = 'done'): DevPhaseDto[] {
  return [
    { key: 'implementing', status: 'done', meta: null, badgeRunId: null },
    { key: 'validation', status: validation, meta: null, badgeRunId: null },
    { key: 'brainReview', status: brainMeta === null ? 'running' : 'done', meta: brainMeta, badgeRunId: null },
  ];
}

describe('deriveLocalReviewGate', () => {
  it('opens the green path only from AWAITING_PUSH with validation and explicit Brain approval', () => {
    expect(deriveLocalReviewGate('AWAITING_PUSH', phases('brain approved'))).toEqual({
      eligible: true,
      reason: 'Validation and Brain review passed.',
      brainReview: { state: 'approved' },
    });
  });

  it('keeps Brain budget exhaustion as an explicit amber human decision path', () => {
    expect(deriveLocalReviewGate('AWAITING_PUSH', phases('brain unresolved · 3'))).toEqual({
      eligible: true,
      reason: 'Brain review exhausted its budget with unresolved findings; human approval is required.',
      brainReview: { state: 'unresolved', unresolved: 3 },
    });
  });

  it.each([
    ['ADDRESSING_LOCAL_COMMENTS', phases('brain approved')],
    ['VALIDATING', phases('brain approved', 'running')],
    ['AWAITING_PUSH', phases(null)],
    ['AWAITING_PUSH', []],
  ])('fails closed while phase/validation/Brain authority is incomplete (%s)', (phase, devPhases) => {
    expect(deriveLocalReviewGate(phase, devPhases).eligible).toBe(false);
  });
});
