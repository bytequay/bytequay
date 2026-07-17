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
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { PullRequestDto } from '../types';
import PullRequestBoardList from './PullRequestBoardList';

afterEach(cleanup);

describe('PullRequestBoardList', () => {
  it('gives cleared-today precedence over attention and progress', () => {
    const cleared = pr({
      id: 1,
      number: 1,
      title: 'Cleared despite stale marker',
      reviewedAt: new Date().toISOString(),
      attentionReason: 'STALE',
    });
    const attention = pr({
      id: 2,
      number: 2,
      title: 'Needs a first review',
    });
    const progress = pr({
      id: 3,
      number: 3,
      title: 'Review already in progress',
      handledAction: 'COMMENTED',
    });

    const { container } = render(
      <PullRequestBoardList
        title="Pull requests"
        rows={[cleared, attention, progress]}
        loading={false}
        error={null}
        showRepository={false}
        onOpen={() => {}}
        onRefresh={() => {}}
      />,
    );

    const columns = container.querySelectorAll('.wu-pr-column');
    expect(columns).toHaveLength(3);
    expect(within(columns[0] as HTMLElement)
      .getByText('Needs a first review')).toBeTruthy();
    expect(within(columns[1] as HTMLElement)
      .getByText('Review already in progress')).toBeTruthy();
    expect(within(columns[2] as HTMLElement)
      .getByText('Cleared despite stale marker')).toBeTruthy();
  });

  it('filters list mode and includes closed rows only on request', () => {
    const onOpen = vi.fn();
    render(
      <PullRequestBoardList
        title="Reviews"
        rows={[
          pr({ id: 1, number: 1, title: 'Requested review' }),
          pr({
            id: 2,
            number: 2,
            title: 'My authored PR',
            origin: 'AUTHORED',
          }),
          pr({
            id: 3,
            number: 3,
            title: 'Closed review',
            state: 'closed',
            closedAt: new Date().toISOString(),
          }),
        ]}
        loading={false}
        error={null}
        showRepository
        remoteOnly
        onOpen={onOpen}
        onRefresh={() => {}}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: /List/ }));
    expect(screen.getByText('Requested review')).toBeTruthy();
    expect(screen.queryByText('My authored PR')).toBeNull();
    expect(screen.queryByText('Closed review')).toBeNull();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Include closed' }));
    expect(screen.getByText('Closed review')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Mine' }));
    expect(screen.getByText('My authored PR')).toBeTruthy();
    expect(screen.queryByText('Requested review')).toBeNull();
    expect(screen.getByText('REMOTE ONLY')).toBeTruthy();
    expect(screen.getByText(/Local source, branches, tests, memory/))
      .toBeTruthy();
  });
});

function pr(overrides: Partial<PullRequestDto> = {}): PullRequestDto {
  return {
    id: 1,
    repo: 'acme/widget',
    number: 1,
    title: 'Pull request',
    author: 'alice',
    htmlUrl: 'https://github.com/acme/widget/pull/1',
    createdAt: '2026-07-16T00:00:00Z',
    updatedAt: '2026-07-16T00:00:00Z',
    origin: 'REVIEW_REQUESTED',
    labels: [],
    labelColors: null,
    draft: false,
    viewedAt: null,
    reviewedAt: null,
    handledAction: null,
    requestedReviewers: ['jack'],
    ciStatus: 'PASSING',
    additions: 12,
    deletions: 3,
    commentCount: 0,
    attentionReason: null,
    state: 'open',
    closedAt: null,
    mergedAt: null,
    mergeable: true,
    mergeableState: 'clean',
    headPushedAt: null,
    reviewerVerdicts: {},
    snoozedUntil: null,
    snoozeWakeReason: null,
    headRef: 'feature/workspace',
    ...overrides,
  };
}
