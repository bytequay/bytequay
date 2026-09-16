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

import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { AgentTaskList } from './AgentTasksView';
import { groupReviewsByDate, ReviewTaskList } from './ReviewTasksView';
import { previewTask } from './fixtures/previewBridge';
import { reviewFixture, runningReviewFixture } from '../pulls/agent-review/fixtures/review';

const reviews = [
  runningReviewFixture(),
  reviewFixture({ id: 'ended-review', number: 27365, state: 'CLOSED', closedAt: '2026-09-06T16:20:00Z' }),
];
const props = {
  reviews,
  loading: false,
  error: null as string | null,
  onRetry: () => {},
  onOpenReview: () => {},
  now: Date.parse('2026-09-07T16:20:00Z'),
};

describe('workspace review tasks', () => {
  it('groups by local creation date with non-overlapping ranges and newest reviews first', () => {
    const now = new Date(2026, 8, 10, 0, 5).getTime();
    const reviewDaysAgo = (id: string, daysAgo: number, hour = 23, minute = 59) => reviewFixture({
      id,
      createdAt: new Date(2026, 8, 10 - daysAgo, hour, minute).toISOString(),
    });
    const input = [
      reviewDaysAgo('six-days', 6),
      reviewDaysAgo('today-b', 0, 0, 0),
      reviewDaysAgo('thirty-days', 30),
      reviewDaysAgo('yesterday', 1),
      reviewDaysAgo('seven-days', 7),
      reviewDaysAgo('today-a', 0, 0, 0),
      reviewDaysAgo('two-days', 2),
      reviewDaysAgo('twenty-nine-days', 29),
      reviewDaysAgo('future', -1),
    ];
    const original = structuredClone(input);

    expect(groupReviewsByDate(input, now).map(group => ({ label: group.label, ids: group.reviews.map(review => review.id) }))).toEqual([
      { label: 'Today', ids: ['future', 'today-a', 'today-b'] },
      { label: 'Yesterday', ids: ['yesterday'] },
      { label: 'Recent 7 days', ids: ['two-days', 'six-days'] },
      { label: 'Recent 30 days', ids: ['seven-days', 'twenty-nine-days'] },
      { label: 'Older', ids: ['thirty-days'] },
    ]);
    expect(input).toEqual(original);

    const markup = renderToStaticMarkup(<ReviewTaskList {...props} reviews={input} now={now} />);
    expect(Array.from(markup.matchAll(/class="agt-group__label">([^<]+)</g), match => match[1])).toEqual([
      'Today', 'Yesterday', 'Recent 7 days', 'Recent 30 days', 'Older',
    ]);
    expect(Array.from(markup.matchAll(/class="agt-group__count">([^<]+)</g), match => match[1])).toEqual([
      '3 reviews', '1 review', '2 reviews', '2 reviews', '1 review',
    ]);
  });

  it('shows reviews as the third section on Active and leaves them out of Archived', () => {
    const renderList = (initialTab: 'active' | 'archived') => renderToStaticMarkup(
      <AgentTaskList
        tasks={[{ ...previewTask('workspace-1', 'interactive-task'), title: 'Interactive task' }]}
        loading={false}
        error="Interactive tasks unavailable"
        laneId="codex"
        providers={[]}
        models={[]}
        selection={null}
        draft=""
        blocker={null}
        onDraftChange={() => {}}
        onSelectProvider={() => {}}
        onSelectModel={() => {}}
        onCreate={() => {}}
        onOpenTask={() => {}}
        initialTab={initialTab}
        reviewTasks={<ReviewTaskList {...props} />}
      >
        <section aria-label="Cherry-pick executions">Cherry-pick executions</section>
      </AgentTaskList>,
    );
    const markup = renderList('active');

    expect(markup.match(/role="tab"/g)).toHaveLength(2);
    expect(markup).toMatch(/role="tab" aria-selected="true"[^>]*>Active<span/);
    expect(Array.from(markup.matchAll(/<section\b[^>]*aria-label="([^"]+)"/g), match => match[1])).toEqual([
      'Cherry-pick executions', 'Agent tasks', 'Review tasks',
    ]);
    expect(markup).toContain('trinodb/trino #27365');
    expect(markup).toContain('Interactive task');
    expect(markup).toContain('Interactive tasks unavailable');
    expect(markup).toContain('aria-label="Describe a task"');

    const archived = renderList('archived');
    expect(archived).toContain('aria-label="Archived tasks"');
    expect(archived).not.toContain('Review tasks');
    expect(archived).not.toContain('trinodb/trino #27365');
    expect(archived).not.toContain('Cherry-pick executions');
    expect(archived).not.toContain('aria-label="Describe a task"');
  });

  it('keeps ended reviews visible and uses review status, provider, model, and recorded creation time', () => {
    const markup = renderToStaticMarkup(<ReviewTaskList {...props} />);

    expect(markup.match(/class="agt-trow agt-review-row"/g)).toHaveLength(2);
    expect(markup.match(/class="agt-group__label"/g)).toHaveLength(1);
    expect(markup).toContain('class="agt-group__label">Yesterday</span>');
    expect(markup).toContain('2 reviews');
    expect(markup).toContain('Reviewing');
    expect(markup).toContain('Ended');
    expect(markup).toContain('Codex');
    expect(markup).toContain('gpt-5-codex');
    expect(markup).toContain(`started <time dateTime="${reviews[0].createdAt}"`);
    expect(markup).not.toContain('aria-label="Describe a task"');
    expect(markup).not.toContain('Archive task:');
    expect(markup).not.toContain('Delete task:');
  });

  it('shows approval needs from the same status projection as the PR review', () => {
    const markup = renderToStaticMarkup(<ReviewTaskList {...props} reviews={[{ ...runningReviewFixture(), pendingApprovals: 1 }]} />);

    expect(markup).toContain('Waiting for approval');
    expect(markup).toContain('agt-state is-waiting');
    expect(markup).not.toContain('agt-spin');
  });

  it('names the stored pull request by number and title beside its state icon', () => {
    const merged = reviewFixture({ id: 'merged-review', number: 31020, pullRequest: { title: 'Avoid duplicate field index lookup', state: 'merged', draft: false } });
    const draft = reviewFixture({ id: 'draft-review', number: 30975, pullRequest: { title: 'Speed up the planner', state: 'open', draft: true } });
    const markup = renderToStaticMarkup(<ReviewTaskList {...props} reviews={[merged, draft, reviews[1]]} />);

    expect(markup).toContain('title="trinodb/trino #31020 Avoid duplicate field index lookup">#31020 Avoid duplicate field index lookup</span>');
    expect(markup).toMatch(/class="[^"]*lucide-git-merge[^"]*agt-pr-icon is-merged agt-review-row__icon"/);
    expect(markup).toMatch(/class="[^"]*lucide-git-pull-request-draft[^"]*agt-pr-icon is-draft agt-review-row__icon"/);
    // A pull request ByteQuay has not stored yet keeps its repository and number beside a grey mark.
    expect(markup).toContain('title="trinodb/trino #27365">trinodb/trino #27365</span>');
    expect(markup).toContain('aria-label="View review #31020 inside workspace"');
    expect(markup.match(/title="View inside workspace"/g)).toHaveLength(3);
    expect(markup).toMatch(/class="[^"]*lucide-git-pull-request[^"]*agt-pr-icon agt-review-row__icon"/);
  });

  it('distinguishes loading, failure with retry, and an empty successful response', () => {
    const loading = renderToStaticMarkup(<ReviewTaskList {...props} reviews={[]} loading />);
    expect(loading).toContain('role="status">Loading review tasks…');
    expect(loading).not.toContain('No review tasks yet');

    const error = renderToStaticMarkup(<ReviewTaskList {...props} reviews={[]} error="Review service unavailable" />);
    expect(error).toContain('role="alert"');
    expect(error).toContain('Review service unavailable');
    expect(error).toContain('>Retry</button>');
    expect(error).not.toContain('No review tasks yet');

    const empty = renderToStaticMarkup(<ReviewTaskList {...props} reviews={[]} />);
    expect(empty).toContain('No review tasks yet');
    expect(empty).toContain('Review &amp; Learning tab');
    expect(empty).not.toContain('role="alert"');
    expect(empty).not.toContain('class="agt-group"');
  });
});
