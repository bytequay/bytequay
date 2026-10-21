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
import type { AgentEventEnvelope } from '../../agents/events';
import type { AgentReviewDto, ReviewRunDto } from './contracts';
import { reviewFixture, runningReviewFixture } from './fixtures/review';
import { ReviewActivityPanel, ReviewEvidencePanel, reviewActivity, type ReviewRunActivity } from './ReviewRunPanels';
import { evidenceTarget } from './ReviewEvidenceLinks';

const NONE: ReviewRunActivity = { turns: [], actions: [], complete: true };
const panels = (review: AgentReviewDto, activity = NONE) => renderToStaticMarkup(<>
  <ReviewActivityPanel review={review} run={review.runs[0]} now={Date.parse('2026-09-06T16:05:00Z')} activity={activity} />
  <ReviewEvidencePanel review={review} run={review.runs[0]} />
</>);

function tool(sequence: number, turnId: string, state: string, itemId: string, description: string | null): AgentEventEnvelope {
  return {
    schemaVersion: 2, eventId: `event-${sequence}`, sequence, taskId: 'task-1', turnId,
    source: { providerThreadId: null, parentProviderThreadId: null, logicalAgentPath: null, providerTurnId: null, providerItemId: itemId, providerEventId: null },
    occurredAt: `2026-09-06T16:04:0${sequence}Z`, type: 'tool',
    payload: { name: 'mcp__bytequay__review_get_context', state, summary: '', durationMs: null, description },
  } as AgentEventEnvelope;
}

describe('the Activity and Evidence tabs', () => {
  it('shows the current pass while running, holds the scope facts back, and waits for the first action', () => {
    const running = runningReviewFixture();
    const run: ReviewRunDto = { ...running.runs[0], steps: [], evidence: reviewFixture().findings[0].evidence };
    const html = panels({ ...running, runs: [run] });
    expect(html).toMatch(/<li class="is-active" aria-current="step"><span class="agr-run__dot"><span class="agr-spinner"[^>]*><\/span><\/span><strong>Investigation<\/strong><span>5 commits · \d+ files?<\/span>/);
    expect(html).not.toContain('Scope <strong>');
    expect(html).toContain('No actions yet.');
    // Evidence links on finding cards open a reference by this id.
    expect(html).toContain(`id="${evidenceTarget(run.id, 'E1')}"`);
    expect(html).toContain('2 references · frozen at head d9a0c41');
  });

  it('marks every pass done for an accepted review and states its scope, coverage and work', () => {
    const review = reviewFixture();
    const actions = reviewActivity([tool(1, 'turn-1', 'COMPLETED', 'a', 'Read PR #31198')], new Set(['turn-1']));
    const html = panels(review, { ...actions, complete: true });
    expect(html.match(/<li class="is-done"/g)).toHaveLength(3);
    expect(html).toContain('<span>Scope <strong>Full review · master → d9a0c41</strong></span>');
    expect(html).toContain('<span>Commits explained <strong>5 of 5</strong></span>');
    expect(html).toContain('<strong class="is-complete">Complete</strong>');
    expect(html).toContain('1 action · 1 turn');
    expect(html).toContain('Read PR #31198');
    expect(html).not.toContain('No actions yet.');
    expect(html).toContain('No context or validation evidence was recorded.');
  });

  it('lists the actions of the given turns newest first, each once, with the time it started', () => {
    const { turns, actions } = reviewActivity([
      tool(1, 'turn-0', 'COMPLETED', 'earlier', 'Read PR #1'),
      tool(2, 'turn-1', 'STARTED', 'a', null),
      tool(3, 'turn-1', 'COMPLETED', 'a', 'Read PR #31198'),
      tool(4, 'turn-1', 'STARTED', 'b', 'Read src/A.java · lines 1–20'),
    ], new Set(['turn-1']));
    expect(turns).toHaveLength(1);
    expect(actions.map(action => [action.member.kind === 'tool' && action.member.description, action.at])).toEqual([
      ['Read src/A.java · lines 1–20', '2026-09-06T16:04:04Z'],
      ['Read PR #31198', '2026-09-06T16:04:02Z'],
    ]);
  });
});
