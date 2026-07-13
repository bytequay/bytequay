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
import type { AgentReviewData } from './agentReviewTypes';
import { confidenceCeiling, formatCents, roundPlanObjectives } from './agentReviewTypes';

describe('confidenceCeiling', () => {
  it('applies evidence, verifier, and criterion ceilings deterministically', () => {
    expect(confidenceCeiling('E1', 'partially', 'hard-invariant')).toBe(0.45);
    expect(confidenceCeiling('E5', 'verified', 'hard-invariant')).toBe(0.98);
    expect(confidenceCeiling('E5', 'unknown', 'hard-invariant')).toBe(0.5);
    expect(confidenceCeiling('E3', 'partially', 'engineering-principle')).toBe(0.65);
    expect(confidenceCeiling('E5', 'rejected', 'repo-convention')).toBe(0);
  });
});

describe('formatCents', () => {
  it('renders review costs with two dollar decimals', () => {
    expect(formatCents(0)).toBe('$0.00');
    expect(formatCents(19)).toBe('$0.19');
    expect(formatCents(150)).toBe('$1.50');
  });
});

describe('roundPlanObjectives', () => {
  it('keeps the failure-class audit ledger out of the locked plan-card layout', () => {
    const data = {
      rounds: [{ id: 'round' }],
      criteria: [{
        id: 'criterion-plan', kind: 'hard-invariant', statement: 'Preserve behavior',
        source_type: 'shipped-rule', source_ref: 'correctness',
      }],
      objectives: [{
        id: 'objective-plan', round_id: 'round', criterion_id: 'criterion-plan',
        statement: 'Preserve behavior', source: 'shipped-rule',
        applicability_status: 'applicable', resolution_status: 'pending',
      }],
    } as AgentReviewData;
    data.criteria.push({
      id: 'criterion-failure-class', kind: 'hard-invariant', statement: 'Check concurrency',
      source_type: 'failure-class', source_ref: 'concurrency',
    });
    data.objectives.push({
      id: 'objective-failure-class', round_id: 'round',
      criterion_id: 'criterion-failure-class', statement: 'Check concurrency',
      source: 'failure-class', applicability_status: 'applicable', resolution_status: 'pending',
    });

    expect(roundPlanObjectives(data, 'round').map(row => row.id))
      .toEqual(['objective-plan']);
  });
});
