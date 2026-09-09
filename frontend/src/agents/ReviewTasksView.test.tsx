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
import { ReviewTaskList } from './ReviewTasksView';
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
  it('opens a separate review list without interactive task controls or errors', () => {
    const markup = renderToStaticMarkup(
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
        initialTab="reviews"
        reviewTasks={<ReviewTaskList {...props} />}
      >
        <div>Cherry-pick executions</div>
      </AgentTaskList>,
    );

    expect(markup).toMatch(/role="tab" aria-selected="true"[^>]*>Review tasks<\/button>/);
    expect(markup).toContain('aria-label="Review tasks"');
    expect(markup).toContain('trinodb/trino #27365');
    expect(markup).not.toContain('Interactive task');
    expect(markup).not.toContain('Cherry-pick executions');
    expect(markup).not.toContain('aria-label="Describe a task"');
    expect(markup).not.toContain('Archive task:');
    expect(markup).not.toContain('Delete task:');
  });

  it('keeps ended reviews visible and uses review status, provider, model, and recorded creation time', () => {
    const markup = renderToStaticMarkup(<ReviewTaskList {...props} />);

    expect(markup.match(/class="agt-trow agt-review-row"/g)).toHaveLength(2);
    expect(markup).toContain('2 reviews');
    expect(markup).toContain('Running');
    expect(markup).toContain('Ended');
    expect(markup).toContain('Codex');
    expect(markup).toContain('gpt-5-codex');
    expect(markup).toContain(`started <time dateTime="${reviews[0].createdAt}"`);
  });

  it('shows approval needs from the same status projection as the PR review', () => {
    const markup = renderToStaticMarkup(<ReviewTaskList {...props} reviews={[{ ...runningReviewFixture(), pendingApprovals: 1 }]} />);

    expect(markup).toContain('Waiting for approval');
    expect(markup).toContain('agt-state is-waiting');
    expect(markup).not.toContain('agt-spin');
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
    expect(empty).toContain('Start an agent review from a pull request');
    expect(empty).not.toContain('role="alert"');
  });
});
