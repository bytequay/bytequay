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
import type { WorktreeChangesDto } from '../code-tests/contracts';
import type { AgentReviewDto } from './contracts';
import { reviewFixture, runningReviewFixture } from './fixtures/review';
import { TaskReviewControls, TaskReviewSection, type TaskReview } from './TaskReviewSummary';

const source: WorktreeChangesDto = { branch: 'feature', baseSha: 'a'.repeat(40), headSha: 'b'.repeat(40), baseLabel: 'main', scope: 'COMMITTED',
  selectedCommit: null, sourceFingerprint: null, truncated: false, staged: [], unstaged: [], untracked: [], commits: [], files: [] };

function state(review: AgentReviewDto | null, overrides: Partial<TaskReview> = {}): TaskReview {
  const noop = async () => {};
  return { review, loading: false, error: null, source, pending: null, unavailable: null, changeReview: () => {}, retry: () => {},
    start: noop, stop: noop, update: noop, resume: noop, end: noop, ...overrides };
}

const controls = (value: TaskReview) => renderToStaticMarkup(<TaskReviewControls state={value} onShowReview={() => {}} />);
const section = (value: TaskReview) => renderToStaticMarkup(<TaskReviewSection state={value}
  options={{ workspaceId: 'workspace-1', onNavigateToChanges: () => {} }} />);

describe('the Summary strip and review event', () => {
  it('offers Review changes beside the committed range before any review exists', () => {
    const html = controls(state(null));
    expect(html).toContain('title="Unpushed commits are included. Uncommitted changes are excluded.">main → bbbbbbb</span>');
    expect(html).toContain('Review changes</button>');
    expect(controls(state(null, { unavailable: 'The development task is closed' }))).toMatch(/disabled=""[^>]*>.*Review changes<\/button>/);
  });

  it('swaps the button for progress and Stop while the reviewer reads the commits', () => {
    const review: AgentReviewDto = { ...runningReviewFixture(), number: null };
    expect(controls(state(review))).toContain('Reviewing 5 commits…');
    expect(controls(state(review))).toContain('>Stop</button>');
    expect(section(state(review))).toContain('<strong>Agent review</strong> is reading 5 commits in its own working copy');
  });

  it('links a finished review from the strip and states its local comments on the timeline', () => {
    const review = reviewFixture();
    const strip = controls(state(review));
    expect(strip).toContain('3 findings');
    expect(strip).toContain('>Review again</button>');
    const timeline = section(state(review));
    expect(timeline).toContain('<strong>Agent review</strong> left 3 local review comments');
    expect(timeline).toContain('Local · never posted to GitHub');
    // The page's strip and this event replace the review's own strip and Findings heading, and the developer's
    // cards above already explain the change. Review details retain discovery and unselected records here.
    expect(timeline).not.toContain('agr-strip');
    expect(timeline).not.toContain('<h2>Findings</h2>');
    expect(timeline).not.toContain('data-review-card=');
    expect(timeline).toContain('Review details');
  });
});
