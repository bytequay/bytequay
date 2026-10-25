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
import { TaskReviewSection, type TaskReview } from './TaskReviewSummary';

const source: WorktreeChangesDto = { branch: 'feature', baseSha: 'a'.repeat(40), headSha: 'b'.repeat(40), baseLabel: 'main', scope: 'COMMITTED',
  selectedCommit: null, sourceFingerprint: null, truncated: false, staged: [], unstaged: [], untracked: [],
  commits: [{ sha: 'b'.repeat(40), parentSha: 'a'.repeat(40), message: 'Fix configuration', author: null, authoredAt: null }], files: [] };

function state(review: AgentReviewDto | null, overrides: Partial<TaskReview> = {}): TaskReview {
  return { review, loading: false, error: null, source, starting: false, unavailable: null, changeReview: () => {}, retry: () => {},
    start: async () => {}, ...overrides };
}

const section = (value: TaskReview, explain = false) => renderToStaticMarkup(<TaskReviewSection state={value} explain={explain}
  options={{ workspaceId: 'workspace-1', onNavigateToChanges: () => {} }} />);

describe('the agent review on a development task Summary', () => {
  it('offers Review changes beside the committed range before any review exists', () => {
    const html = section(state(null));
    expect(html).toContain('<h2>Agent review</h2>');
    expect(html).toContain('title="Includes committed, staged, unstaged, and new files in this working copy.">main → bbbbbbb</span>');
    expect(html).toContain('Review changes</button>');
    expect(section(state(null, { unavailable: 'The development task is closed' }))).toMatch(/disabled=""[^>]*>.*Review changes<\/button>/);
  });

  it('allows review when all changes are uncommitted', () => {
    const html = section(state(null, { source: { ...source, commits: [] } }));
    expect(html).toContain('Review changes</button>');
    expect(html).not.toMatch(/disabled=""[^>]*>.*Review changes<\/button>/);
  });

  it('shows the running pass and Stop, and opens on Activity while the reviewer works', () => {
    const review: AgentReviewDto = { ...runningReviewFixture(), number: null };
    const html = section(state(review));
    expect(html).toMatch(/<span class="pull-detail__review-chip is-running"[^>]*><span class="agr-spinner"[^>]*><\/span>Running · Validation<\/span>/);
    expect(html).toContain('>Stop</button>');
    expect(html).toContain('role="tab" aria-selected="true">Activity');
  });

  it('shows a finished review as the same panel the reviewer page shows, without the reviewer’s own explanation', () => {
    const review = reviewFixture();
    const html = section(state(review));
    expect(html).toContain('Local · never posted to GitHub');
    expect(html).toContain('>Review again</button>');
    expect(html).toContain('role="tab" aria-selected="true">Findings<span>3</span>');
    // The developer's What changed above already explains the change.
    expect(html).not.toContain('aria-label="What changed"');
    expect(html).toContain('aria-label="Review rounds"');
    expect(html).not.toContain('data-review-card=');
    // Every finding was selected, every file reviewed, and no thread of the user's was judged.
    expect(html).not.toContain('Review details');
  });

  it('explains the change with the reviewer’s summary for a task without its own', () => {
    const html = section(state(reviewFixture()), true);
    expect(html).toContain('aria-label="What changed"');
    expect(html).toContain('<strong>Change intention</strong>');
    expect(html).toContain('Lets <code>sorted_by</code> name a field inside a struct column');
    expect(html.indexOf('aria-label="What changed"')).toBeLessThan(html.indexOf('aria-label="Agent review"'));
  });

  it('offers to review the latest when the developer moved past the reviewed head', () => {
    const html = section(state(reviewFixture()));
    expect(html).toContain('New commits since this review — reviewed <strong>d9a0c41</strong>,');
    expect(html).toContain('head is <strong>bbbbbbb</strong>.');
    expect(html).toContain('Review latest</button>');
  });

  it('shows a reviewer that waits for the running developer, with no Start review', () => {
    const review: AgentReviewDto = { ...reviewFixture(), sourceTaskId: 'task-1', number: null, runs: [], findings: [], followUpPaused: false, activeTurn: null };
    const html = section(state(review));
    expect(html).toMatch(/<span class="pull-detail__review-chip is-neutral"[^>]*>Waiting for developer<\/span>/);
    expect(html).not.toContain('Start review</button>');
    expect(html).toContain('>End review</button>');
  });
});
