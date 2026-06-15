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
import type { TaskPhaseDto } from '../types';
import { phaseGroupLabel, phaseGroupOf, phaseLabel } from './taskPhase';

describe('phaseGroupOf', () => {
  const cases: Array<[TaskPhaseDto, string]> = [
    ['IMPLEMENTING', 'IN_PROGRESS'],
    ['VALIDATING', 'IN_PROGRESS'],
    ['INTERNAL_REVIEW', 'IN_PROGRESS'],
    ['PUSHED_AWAITING_CI', 'IN_PROGRESS'],
    ['CI_FIXING', 'IN_PROGRESS'],
    ['ADDRESSING_COMMENTS', 'IN_PROGRESS'],
    ['AGENT_RE_REVIEW', 'IN_PROGRESS'],
    ['AWAITING_PUSH', 'AWAITING_YOU'],
    ['AWAITING_READY', 'AWAITING_YOU'],
    ['AWAITING_UPDATE_PUSH', 'AWAITING_YOU'],
    ['NEEDS_ATTENTION', 'AWAITING_YOU'],
    ['AWAITING_REMOTE_REVIEW', 'IDLE'],
    ['COMPLETED', 'DONE'],
  ];

  it.each(cases)('maps %s to %s', (phase, group) => {
    expect(phaseGroupOf(phase)).toBe(group);
  });
});

describe('phaseGroupLabel', () => {
  it('renders the four coarse labels', () => {
    expect(phaseGroupLabel('IN_PROGRESS')).toBe('In progress');
    expect(phaseGroupLabel('AWAITING_YOU')).toBe('Awaiting you');
    expect(phaseGroupLabel('IDLE')).toBe('Idle');
    expect(phaseGroupLabel('DONE')).toBe('Done');
  });
});

describe('phaseLabel', () => {
  it('humanises the enum value', () => {
    expect(phaseLabel('AWAITING_PUSH')).toBe('Awaiting push');
    expect(phaseLabel('AGENT_RE_REVIEW')).toBe('Agent re review');
    expect(phaseLabel('IMPLEMENTING')).toBe('Implementing');
  });
});
