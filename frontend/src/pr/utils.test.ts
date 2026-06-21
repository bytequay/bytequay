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
import { approvalCountsTowardMerge } from './utils';

describe('approvalCountsTowardMerge', () => {
  it('counts approvals from reviewers with write access', () => {
    for (const a of ['OWNER', 'MEMBER', 'COLLABORATOR']) {
      expect(approvalCountsTowardMerge(a)).toBe(true);
    }
  });

  it('does not count drive-by approvals from reviewers without write access', () => {
    for (const a of ['CONTRIBUTOR', 'FIRST_TIME_CONTRIBUTOR', 'FIRST_TIMER', 'MANNEQUIN', 'NONE']) {
      expect(approvalCountsTowardMerge(a)).toBe(false);
    }
  });

  it('does not count when the association is missing', () => {
    expect(approvalCountsTowardMerge(null)).toBe(false);
    expect(approvalCountsTowardMerge(undefined)).toBe(false);
  });
});
