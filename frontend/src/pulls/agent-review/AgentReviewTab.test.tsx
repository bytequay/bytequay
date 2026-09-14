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
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import AgentReviewTab from './AgentReviewTab';
import ReviewFindingCard from './ReviewFindingCard';
import ReviewSummaryCard from './ReviewSummaryCard';
import { reviewFixture, runningReviewFixture } from './fixtures/review';
import { shownRun } from './model';
import type { AgentReviewDto, ReviewDraftDto, ReviewFindingDto } from './contracts';
import type { PullRequestDetailDto, UserProfileDto } from '../../types';

const detail = {
  id: 1, repo: 'trinodb/trino', number: 30304, title: 'Support sorted by nested fields in Iceberg',
  htmlUrl: null, state: 'open', draft: false, mergeable: true, mergeableState: 'clean',
  author: 'ezra-b', authorAvatarUrl: null, createdAt: null, updatedAt: null, mergedAt: null,
  baseRef: 'master', headRef: 'iceberg-nested-sort-fields', body: null, labels: [], assignees: [],
  milestone: null, reviewers: [], additions: 4366, deletions: 82, changedFiles: 30, approvalCount: 0,
  ciStatus: 'FAILING', recentActivity: [], checkRuns: [], unavailableSections: [], viewerCanWrite: true,
} as unknown as PullRequestDetailDto;

const viewer = { login: 'jackchen', name: 'Jack', avatarUrl: null } as unknown as UserProfileDto;

const NO_WORKSPACE = Symbol('no workspace');

function tab(review: AgentReviewDto | null, workspaceId: string | typeof NO_WORKSPACE = 'workspace-1'): string {
  return renderToStaticMarkup(
    <AgentReviewTab
      detail={detail}
      workspaceId={workspaceId === NO_WORKSPACE ? undefined : workspaceId}
      viewer={viewer}
      review={review}
      onReviewChange={() => {}}
      onOpenWorkspace={() => {}}
      onNavigateToChanges={() => {}}
      focusFindingId={null} />);
}

function card(review: AgentReviewDto, finding: ReviewFindingDto, projection: 'review' | 'changes' = 'review'): string {
  return renderToStaticMarkup(
    <ReviewFindingCard
      review={review}
      finding={finding}
      viewerLogin="jackchen"
      viewerAvatarUrl={null}
      projection={projection}
      defaultOpen
      actions={{
        onAsk: async () => {}, onResolve: async () => {}, onReopen: async () => {},
        onAddToReview: async () => {}, onPostNow: async () => {}, onEditDraft: async () => {},
        onNavigate: () => {},
      }} />);
}

function summary(review: AgentReviewDto): string {
  return renderToStaticMarkup(
    <ReviewSummaryCard review={review} run={shownRun(review)} findings={review.findings}
      viewerLogin="jackchen" viewerAvatarUrl={null} busy={false}
      onAsk={async () => {}} onSelectFinding={() => {}} />);
}

function draftWith(items: ReviewDraftDto['items']): ReviewDraftDto {
  return { repo: 'trinodb/trino', number: 30304, body: '', event: 'COMMENT', items, version: 1, updatedAt: '2026-09-06T16:30:00Z' };
}

describe('starting a review', () => {
  it('offers Start review and separates private availability from publication rights', () => {
    const html = tab(null);
    expect(html).toContain('No agent review yet');
    expect(html).toContain('Start review');
    expect(html).toContain('Private review is available on your own and closed pull requests too');
    expect(html).toContain('normal review permissions');
  });

  it('explains that a review needs a watched repository rather than offering a dead button', () => {
    const html = tab(null, NO_WORKSPACE);
    expect(html).toContain('Agent review needs a watched repository');
    expect(html).not.toContain('Start review');
  });
});

describe('the status strip', () => {
  it('offers Stop while running, and never Update or End at the same time', () => {
    const html = tab(runningReviewFixture());
    expect(html).toContain('>Stop</button>');
    expect(html).not.toContain('>Update review</button>');
    expect(html).not.toContain('>End review</button>');
    expect(html).not.toContain('>Recover runtime</button>');
  });

  it('offers Update review and End review once the run has settled', () => {
    const html = tab(reviewFixture());
    expect(html).toContain('>Update review</button>');
    expect(html).toContain('>End review</button>');
    expect(html).not.toContain('>Stop</button>');
    expect(html).not.toContain('>Recover runtime</button>');
  });

  it('offers Resume only for a paused review', () => {
    const stopped = reviewFixture({
      followUpPaused: true,
      runs: [{ ...reviewFixture().runs[0], state: 'STOPPED', coverage: null }],
    });
    expect(tab(stopped)).toContain('>Resume</button>');
    expect(tab(reviewFixture())).not.toContain('>Resume</button>');
  });

  it('keeps a failed run’s saved summary visible and lets the user retry when no runtime is held', () => {
    const failed = reviewFixture({
      followUpPaused: true,
      runs: [{ ...reviewFixture().runs[0], state: 'FAILED', coverage: null, outcome: 'Cannot start the review turn' }],
    });
    const html = tab(failed);
    expect(html).toContain('Cannot start the review turn');
    expect(html).toContain('PR intention');
    expect(html).toContain('>Resume</button>');
    expect(html).not.toMatch(/disabled=""[^>]*>Resume<\/button>/);
    expect(html).not.toMatch(/disabled=""[^>]*>Run review again<\/button>/);
  });

  it('shows recovery and disables further execution while an interrupted turn retains its claim', () => {
    const html = tab({ ...runningReviewFixture(), activeTurn: null, followUpPaused: true });
    expect(html).toContain('Recovery required');
    expect(html).toContain('Provider shutdown could not be verified');
    expect(html).toMatch(/<button[^>]*>Recover runtime<\/button>/);
    expect(html).not.toMatch(/disabled=""[^>]*>Recover runtime<\/button>/);
    expect(html).toMatch(/disabled=""[^>]*>Resume<\/button>/);
    expect(html).toMatch(/disabled=""[^>]*>Update review<\/button>/);
    expect(html).toMatch(/disabled=""[^>]*>End review<\/button>/);
    expect(html).toMatch(/disabled=""[^>]*>Ask agent<\/button>/);
    expect(html).not.toContain('>Stop</button>');
    expect(html).not.toContain('preparing the review');
  });

  it('keeps cards in the PR pane and opens the conversation in the workspace', () => {
    const html = tab(reviewFixture());
    expect(html).not.toContain('Summary &amp; comments');
    expect(html).not.toContain('>Activity');
    expect(html).not.toContain('aria-label="Agent review views"');
    expect(html).toContain('aria-label="View in workspace"');
    expect(html).toContain('data-review-card="run-1:purpose"');
  });
});

describe('banners', () => {
  it('mirrors a pending approval in the summary so the wait is not hidden behind Activity', () => {
    const html = tab({ ...runningReviewFixture(), pendingApprovals: 1 });
    expect(html).toContain('Approval needed');
    expect(html).toContain('waiting for your answer');
  });

  it('says an incomplete run is not a clean result', () => {
    const html = tab(reviewFixture({
      runs: [{ ...reviewFixture().runs[0], state: 'INCOMPLETE', coverage: null, summary: null, outcome: 'coverage was not recorded' }],
    }));
    expect(html).toContain('Incomplete');
    expect(html).toContain('it is not a clean result');
    expect(html).toContain('No summary was saved for this run');
  });

  it('offers Update review when new commits arrived', () => {
    const html = tab(reviewFixture({ observedHeadSha: '5e17b02a1b2c3d4e' }));
    expect(html).toContain('New commits');
    expect(html).toContain('d9a0c41 → 5e17b02');
  });

  it('says a stopped review keeps its discussion open', () => {
    const html = tab(reviewFixture({
      followUpPaused: true,
      runs: [{ ...reviewFixture().runs[0], state: 'STOPPED', coverage: null }],
    }));
    expect(html).toContain('Follow-up on new commits is paused');
    expect(html).toContain('The discussion stays open');
  });
});

describe('the summary card', () => {
  const review = reviewFixture();

  it('separates PR intention and commit implementation without redundant report cards', () => {
    const html = summary(review);
    expect(html).toContain('PR intention');
    expect(html).toContain('Commit implementation');
    expect(html).toContain('agr-learning-card--purpose');
    expect(html.match(/agr-learning-card--commit/g)).toHaveLength(5);
    expect(html.match(/agr-commit-list__title/g)).toHaveLength(5);
    expect(html).toContain('Review &amp; discussion');
    expect(html).toContain('5 in scope');
    expect(html).not.toContain('Combined result');
    expect(html).not.toContain('Critical issues');
    expect(html).not.toContain('Supporting evidence &amp; tests');
    expect(html).not.toContain('Review coverage');
    expect(html).not.toContain('Earlier discussions');
    expect(html).not.toContain('Limitations');
    expect(html).toContain('Splits “address.zip” into a path');
    // Rendered as Markdown, not shown as literal backticks.
    expect(html).toContain('<code>sorted_by</code>');
    expect(html).not.toContain('`sorted_by`');
  });

  it('marks an unexplained commit as not yet read rather than silently omitting it', () => {
    const html = summary(runningReviewFixture());
    expect(html).toContain('not yet read');
    expect(html).toContain('Provisional · rev 1');
  });

  it('keeps the summary discussion private and never publishable', () => {
    const html = summary(review);
    expect(html).toContain('Private — never published');
    expect(html).toContain('pr-inline-thread is-local-review');
    expect(html).toContain('Why does the second commit change IcebergUtil');
    expect(html.match(/data-review-card=/g)).toHaveLength(6);
    expect(html).toContain('Ask agent');
    expect(html).not.toContain('Post comment now');
  });

  it('keeps a removed card’s original target and discussion in collapsed history', () => {
    const run = review.runs[0];
    const legacy = { ...review.messages[0], findingId: null as string | null, runId: run.id, cardId: `${run.id}:limitations`, body: 'Why was the build skipped?' };
    const html = summary({ ...review, messages: [...review.messages, legacy] });
    expect(html).toContain('Why was the build skipped?');
    expect(html).toContain('data-review-card="run-1:limitations"');
    expect(html).toContain('<details class="agr-learning-history"><summary>Earlier discussions');
  });
});

describe('the finding card', () => {
  const review = reviewFixture();
  const finding = review.findings[0];

  it('shows the location, severity, identity and evidence', () => {
    const html = card(review, finding);
    expect(html).toContain('R118–R131');
    expect(html).toContain('Critical');
    expect(html).toContain('F-1 · rev 1');
    expect(html).toContain('Evidence · 2 references');
    expect(html).toContain('IcebergSortField.java');
  });

  it('separates private actions from the one that reaches GitHub', () => {
    const html = card(review, finding);
    expect(html).toContain('Ask agent');
    expect(html).toContain('Resolve locally');
    expect(html).toContain('View in Changes');
    expect(html).toContain('Add to my review');
    expect(html).toContain('Post comment now');
    // Only the GitHub-bound action carries the primary treatment.
    expect(html.match(/agr-action is-primary/g) ?? []).toHaveLength(1);
  });

  it('labels the way back differently in each projection but keeps one identity', () => {
    expect(card(review, finding, 'review')).toContain('View in Changes');
    expect(card(review, finding, 'changes')).toContain('Open in Review &amp; Learning');
    expect(card(review, finding, 'changes')).toContain('data-finding-id="finding-1"');
    expect(card(review, finding, 'review')).toContain('data-finding-id="finding-1"');
  });

  it('offers Reopen and the reason for a locally resolved finding, and nothing publishable', () => {
    const resolved = { ...finding, state: 'RESOLVED' as const, stateReason: 'Known limitation, tracked in #29981' };
    const html = card(reviewFixture({ findings: [resolved] }), resolved);
    expect(html).toContain('Resolved locally by you');
    expect(html).toContain('Known limitation, tracked in #29981');
    expect(html).toContain('excluded from publication, draft text kept');
    expect(html).toContain('>Reopen</button>');
    expect(html).not.toContain('Post comment now');
    expect(html).not.toContain('Add to my review');
    expect(html).toContain('Excluded from publication');
  });

  it('shows an outdated finding its recorded snapshot and refuses publication', () => {
    const outdated = { ...finding, outdated: true };
    const html = card(reviewFixture({ findings: [outdated] }), outdated);
    expect(html).toContain('no unambiguous mapping was found');
    expect(html).toContain('agr-snapshot');
    expect(html).toContain('resolveNestedFieldId');
    expect(html).toContain('Not publishable until re-anchored');
    expect(html).not.toContain('Post comment now');
  });

  it('shows a withdrawn finding without any action', () => {
    const withdrawn = { ...finding, state: 'WITHDRAWN' as const, stateReason: 'The fallback only runs for single-segment paths' };
    const html = card(reviewFixture({ findings: [withdrawn] }), withdrawn);
    expect(html).toContain('The agent withdrew this finding');
    expect(html).toContain('can no longer be published');
    expect(html).not.toContain('Ask agent');
    expect(html).not.toContain('Add to my review');
  });

  it('shows the outgoing draft inside the card, never as a second pending clone', () => {
    const withDraft = reviewFixture({
      draft: draftWith([{
        id: 'item-1', source: 'AGENT_FINDING', findingId: 'finding-1', findingRevision: 1,
        path: finding.path, side: 'RIGHT', line: 131, startLine: 118,
        body: 'Could this match the full path instead?', included: true, published: false, publication: null,
      }]),
    });
    const html = card(withDraft, finding);
    expect(html).toContain('Outgoing comment · in your pending review');
    expect(html).toContain('Editing this never changes the finding');
    expect(html).toContain('Could this match the full path instead?');
    expect(html).toContain('Pending · in your review');
    // Promoted once: the actions no longer offer to promote it again.
    expect(html).not.toContain('Add to my review');
  });

  it('keeps a published finding’s thread private and links the remote comment', () => {
    const published = reviewFixture({
      draft: draftWith([{
        id: 'item-1', source: 'AGENT_FINDING', findingId: 'finding-1', findingRevision: 1,
        path: finding.path, side: 'RIGHT', line: 131, startLine: 118, body: 'sent', included: true, published: true,
        publication: {
          state: 'PUBLISHED', remoteReviewId: 2201456, remoteCommentId: 9911,
          url: 'https://github.com/trinodb/trino/pull/30304#discussion_r2201456', at: '2026-09-06T16:32:00Z',
        },
      }]),
    });
    const html = card(published, finding);
    expect(html).toContain('Posted to GitHub as a review comment');
    expect(html).toContain('The thread below stays private');
    expect(html).toContain('discussion_r2201456');
    expect(html).not.toContain('Post comment now');
  });

  it('blocks publication of a provisional finding and says why', () => {
    const running = runningReviewFixture();
    const html = card(running, running.findings[0]);
    expect(html).toContain('Provisional');
    expect(html).toContain('Publishing unlocks when the run records coverage');
    expect(html).not.toContain('Post comment now');
  });
});

describe('styles', () => {
  const css = readFileSync(resolve(process.cwd(), 'src/css/agent-review.css'), 'utf8');

  function declarations(selector: string): string[] {
    const start = css.indexOf(`\n${selector} {`);
    expect(start, `${selector} is missing from agent-review.css`).toBeGreaterThan(-1);
    return css.slice(css.indexOf('{', start) + 1, css.indexOf('}', start))
      .split(';')
      .map(declaration => declaration.replace(/\/\*[^]*?\*\//g, '').trim())
      .filter(declaration => declaration !== '');
  }

  it('uses the development private review card for summaries and findings', () => {
    expect(summary(reviewFixture())).toContain('pr-inline-thread is-local-review');
    expect(card(reviewFixture(), reviewFixture().findings[0])).toContain('pr-inline-thread is-local-review');
    expect(declarations(':is(.agr-learning-card, .agr-finding, .agr-local-comments) :is(.pr-inline-thread, .pr-thread-row)'))
      .toContain('margin-left: 0');
  });

  it('gives only the GitHub-bound action the green primary treatment', () => {
    expect(declarations('.agr-action.is-primary')).toContain('background: #1f883d');
    expect(declarations('.agr-action')).toContain('background: #fff');
  });

  it('bounds an evidence excerpt instead of letting it run the card', () => {
    const excerpt = declarations('.agr-evidence__excerpt');
    expect(excerpt).toContain('max-height: 180px');
    expect(excerpt).toContain('overflow: auto');
    expect(excerpt).toContain('word-break: break-word');
  });

  it('wraps the strip, banners and action rows so a narrow pane never clips them', () => {
    expect(declarations('.agr-strip')).toContain('flex-wrap: wrap');
    expect(declarations('.agr-actions')).toContain('flex-wrap: wrap');
    expect(declarations('.agr-banner')).toContain('flex-wrap: wrap');
  });

  it('rings every control it owns, since the app has no global focus style', () => {
    const ring = declarations(
      ':is(.agr-pane, .agr-empty, .agr-finding, .agr-composer) :is(a, button, summary, textarea):focus-visible');
    expect(ring).toContain('outline: 2px solid var(--control-strong)');
    expect(ring).toContain('outline-offset: 2px');
  });

  it('stops the spinner for a reader who asked for less motion', () => {
    expect(css).toContain('@media (prefers-reduced-motion: reduce) { .agr-spinner { animation: none; } }');
  });
});
