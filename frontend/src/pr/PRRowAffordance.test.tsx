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
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { PrLinksDto, PullRequestDto } from '../types';
import { PRRowAffordance } from './PRRowAffordance';

afterEach(cleanup);

function pr(origin: 'AUTHORED' | 'REVIEW_REQUESTED'): PullRequestDto {
  return { repo: 'acme/widget', number: 42, origin } as PullRequestDto;
}

const noLinks: PrLinksDto = {
  linkedActiveTask: null, linkedCompletedTaskIds: [], linkedActiveReviewRef: null,
};

function render1(origin: 'AUTHORED' | 'REVIEW_REQUESTED', links: PrLinksDto) {
  render(
    <PRRowAffordance
      pr={pr(origin)} links={links}
      onCreateTask={() => {}} onAssignReview={() => {}}
      onOpenTask={() => {}} onOpenReview={() => {}}
    />);
}

describe('PRRowAffordance', () => {
  it('own + unlinked → Create dev task only', () => {
    render1('AUTHORED', noLinks);
    expect(screen.getByText('+ Create dev task')).toBeTruthy();
    expect(screen.queryByText('+ Assign review')).toBeNull();
    expect(screen.queryByText('Multi-agent review')).toBeNull();
  });

  it('own + linked → TaskChip only', () => {
    render1('AUTHORED', {
      ...noLinks,
      linkedActiveTask: { id: 't1', title: 'Fix parser', phaseGroup: 'IN_PROGRESS' },
    });
    expect(screen.getByText('Fix parser')).toBeTruthy();
    expect(screen.queryByText('+ Create dev task')).toBeNull();
    expect(screen.queryByText('+ Assign review')).toBeNull();
  });

  it('others + unlinked → Assign review only', () => {
    render1('REVIEW_REQUESTED', noLinks);
    expect(screen.getByText('+ Assign review')).toBeTruthy();
    expect(screen.queryByText('+ Create dev task')).toBeNull();
  });

  it('others + linked → ReviewChip only', () => {
    render1('REVIEW_REQUESTED', {
      ...noLinks,
      linkedActiveReviewRef: {
        passId: 'p1', phase: 'DEBATE', hostKind: 'THREAD',
        round: 2, roundCap: 3, costSpentMilli: 0, costCapMilli: 500,
      },
    });
    expect(screen.getByText('Multi-agent review')).toBeTruthy();
    expect(screen.queryByText('+ Assign review')).toBeNull();
    expect(screen.queryByText('+ Create dev task')).toBeNull();
  });
});
