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
import type { AgentReviewDto, ReviewCandidateValidationDto, ReviewDraftDto, ReviewFindingDto, ReviewInvestigationDto } from './contracts';
import { parseAgentReview } from './contracts';
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

  it('keeps automatic continuation under Reviewing and Stop while no assignment turn is active', () => {
    const running = runningReviewFixture();
    const html = tab({ ...running, activeTurn: null, task: { ...running.task, mutationClaim: null } });
    expect(html).toContain('Reviewing');
    expect(html).toContain('>Stop</button>');
    expect(html).not.toContain('>Resume</button>');
    expect(html).not.toContain('Retry review');
    expect(html).not.toContain('>Update review</button>');
    expect(html).not.toContain('>End review</button>');
  });

  it('offers Update review and End review once the run has settled', () => {
    const html = tab(reviewFixture());
    expect(html).toContain('>Update review</button>');
    expect(html).toContain('Local experiments are restored when possible. If they conflict, all local experiments are discarded.');
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

  it('keeps a failed run’s saved summary visible without asking the user to recover it', () => {
    const failed = reviewFixture({
      followUpPaused: true,
      runs: [{ ...reviewFixture().runs[0], state: 'FAILED', coverage: null, outcome: 'Cannot start the review turn' }],
    });
    const html = tab(failed);
    expect(html).toContain('Cannot start the review turn');
    expect(html).toContain('PR intention');
    expect(html).not.toContain('Retry review');
    expect(html).not.toContain('>Resume</button>');
    expect(html).not.toContain('>Run review again</button>');
    expect(html).toContain('>Update review</button>');
    expect(html).toContain('Any saved work below is provisional.');
  });

  it('shows recovery and disables further execution while an interrupted turn retains its claim', () => {
    const html = tab({ ...runningReviewFixture(), activeTurn: null, followUpPaused: true });
    expect(html).toContain('Recovery required');
    expect(html).toContain('Provider shutdown could not be verified');
    expect(html).toMatch(/<button[^>]*>Recover runtime<\/button>/);
    expect(html).not.toMatch(/disabled=""[^>]*>Recover runtime<\/button>/);
    expect(html).not.toContain('>Resume</button>');
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

  it('shows a failed review with its reason, never a successful result or a retry instruction', () => {
    const html = tab(reviewFixture({
      findings: [],
      runs: [{ ...reviewFixture().runs[0], state: 'INCOMPLETE', coverage: null, summary: null, outcome: 'coverage was not recorded' }],
    }));
    expect(html).toContain('Could not finish');
    expect(html).toContain('coverage was not recorded');
    expect(html).not.toContain('Retry review');
    expect(html).not.toContain('>Resume</button>');
    expect(html).toContain('none saved');
    expect(html).toContain('No summary was saved for this run');
    expect(html).not.toContain('Incomplete');
    expect(html).not.toContain('finished ');
    expect(html).not.toContain('No inline findings');
  });

  it('keeps an earlier completed report visible without hiding the latest failure reason', () => {
    const review = reviewFixture();
    const html = tab({ ...review, runs: [...review.runs, { ...review.runs[0], id: 'failed-run', state: 'FAILED',
      summary: null, coverage: null, outcome: 'The review tools could not save the report' }] });
    expect(html).toContain('The review tools could not save the report');
    expect(html).toContain('The previous completed review is shown below.');
    expect(html).toContain('PR intention');
    expect(html).not.toContain('finished ');
  });

  it('offers Update review when new commits arrived', () => {
    const html = tab(reviewFixture({ observedHeadSha: '5e17b02a1b2c3d4e' }));
    expect(html).toContain('New commits');
    expect(html).toContain('d9a0c41 → 5e17b02');
    expect(html).toContain('Local experiments are restored when possible. If they conflict, all local experiments are discarded.');
  });

  it('says a stopped review keeps its discussion open', () => {
    const html = tab(reviewFixture({
      followUpPaused: true,
      runs: [{ ...reviewFixture().runs[0], state: 'STOPPED', coverage: null }],
    }));
    expect(html).toContain('Resume continues from the saved state');
    expect(html).toContain('The discussion stays open');
  });

  it('offers Resume instead of an ongoing startup when the review is stopped between passes', () => {
    const running = runningReviewFixture();
    const html = tab({ ...running, activeTurn: null, followUpPaused: true, task: { ...running.task, mutationClaim: null } });
    expect(html).toContain('Stopped');
    expect(html).toContain('>Resume</button>');
    expect(html).not.toMatch(/disabled=""[^>]*>Resume<\/button>/);
    expect(html).not.toContain('preparing the review');
    expect(html).not.toContain('>Stop</button>');
  });
});

describe('review details', () => {
  it('shows assessed scope and final validation evidence without promoting draft candidates', () => {
    const original = reviewFixture({ findings: [] });
    const run = original.runs[0];
    const review = parseAgentReview({ ...original, runs: [{ ...run, scope: 'INCREMENTAL', changedFiles: 0,
      investigation: { changeMap: 'Provider ownership spans the **factory and scan**.',
        reviewedFiles: ['src/Factory.java'], contextEvidenceRefs: ['context-1'],
        candidates: ['Draft leak hypothesis', 'Draft delegation hypothesis'],
        validations: [{ candidate: 1, outcome: 'REJECTED', conclusion: 'The caller releases its reference in `finally`; E25 verifies cleanup.', findingId: null },
          { candidate: 2, outcome: 'SUPPORTED', conclusion: 'The delegated context drops the reservation.', findingId: 'finding-1' }] },
      evidence: [{ ref: 'context-1', source: 'src/UnchangedCaller.java:14–28', provenance: 'Frozen source',
        excerpt: 'finally { provider.release(); }', explanation: 'The caller owns cleanup.' },
      { ref: 'E25', source: 'command output: ./mvnw test -Dtest=TestProviderCleanup', provenance: 'Command executed during validation',
        excerpt: 'Frozen head: PASS; release removed: FAIL (reference remains)', explanation: '' }] }] });
    const html = tab(review);
    expect(html).toContain('<details class="agr-review-details"><summary>Review details');
    expect(html).toContain('1 changed file assessed');
    expect(html).not.toContain('of 0 changed files');
    expect(html).toContain('src/Factory.java');
    expect(html).toContain('src/UnchangedCaller.java:14–28');
    expect(html).toContain('finally { provider.release(); }');
    expect(html).toContain('Context &amp; validation evidence · 2 references');
    expect(html).toContain('<span class="agr-card__refs">E25</span>');
    expect(html).toContain('command output: ./mvnw test -Dtest=TestProviderCleanup');
    expect(html).toContain('Frozen head: PASS; release removed: FAIL (reference remains)');
    expect(html).toContain('2 of 2 candidates checked');
    expect(html).toContain('1 supported · 1 rejected');
    expect(html).toContain('The caller releases its reference');
    expect(html).not.toContain('Draft leak hypothesis');
    expect(html).not.toContain('data-finding-id=');
    const partial = tab({ ...review, runs: [{ ...review.runs[0], investigation: null }] });
    expect(partial).toContain('Frozen head: PASS; release removed: FAIL (reference remains)');
    const retained = parseAgentReview({ ...review, runs: [{ ...review.runs[0], investigation: { ...review.runs[0].investigation,
      validations: [{ candidate: 1, conclusion: 'This assessment predates explicit validation.', findingId: 'finding-1' }] } }] });
    expect(tab(retained)).toContain('1 unresolved');
  });

  it('shows evidence requests and their answers without calling unresolved claims rejected', () => {
    const original = reviewFixture({ findings: [] });
    const investigation: ReviewInvestigationDto = { changeMap: 'Trace provider ownership.', reviewedFiles: ['src/Factory.java'], contextEvidenceRefs: ['E1'],
      candidates: ['Provider teardown may retain memory'], validations: [], answers: [] };
    const coverage = { complete: false, omissions: ['Cleanup could not be verified'], recordedAt: '2026-09-06T16:15:00Z' };
    const claim: ReviewCandidateValidationDto = { candidate: 1, outcome: 'NEEDS_EVIDENCE', conclusion: 'The final owner has not been established.', evidenceRefs: ['E1'],
      counterevidence: 'One caller releases its reference, but the factory also retains one.', findingId: null, findingRevision: null,
      request: { question: 'Who releases the last reference?', check: 'Trace factory teardown and run the cleanup test.',
        resolvesWhen: 'A final release is observed or a retained reference is reproduced.' } };
    const answer = { candidate: 1, attempted: 'Traced teardown and attempted the cleanup test.', evidenceRefs: ['E2'],
      limitation: 'The integration fixture requires an unavailable service.' };
    const review = parseAgentReview({ ...original, runs: [{ ...original.runs[0], state: 'INCOMPLETE', coverage,
      investigation: { ...investigation, answers: [answer] }, steps: [
        { number: 1, kind: 'INVESTIGATE', repair: false, investigation, validation: null },
        { number: 2, kind: 'VALIDATE', repair: false, investigation: null,
          validation: { investigationStep: 1, candidates: [claim], coverage, newCandidates: ['The cancellation path may also retain the provider.'] } },
        { number: 3, kind: 'COLLECT_EVIDENCE', repair: false, investigation: { ...investigation, answers: [answer] }, validation: null },
        { number: 4, kind: 'VALIDATE', repair: false, investigation: null,
          validation: { investigationStep: 3, candidates: [{ ...claim, outcome: 'UNRESOLVED',
            conclusion: 'The available source does not settle the cleanup behavior.', request: null }], coverage } },
      ] }] });
    const html = tab(review);
    expect(html).toContain('Review passes · 4');
    expect(html).toContain('Evidence collection');
    expect(html).toContain('1 needs evidence');
    expect(html).toContain('The cancellation path may also retain the provider.');
    expect(html).toContain('Who releases the last reference?');
    expect(html).toContain('Trace factory teardown and run the cleanup test.');
    expect(html).toContain('A final release is observed or a retained reference is reproduced.');
    expect(html).toContain('The integration fixture requires an unavailable service.');
    expect(html).toContain('Checked the evidence from pass 3.');
    expect(html).toContain('1 unresolved');
    expect(html).not.toContain('rejected');
    expect(html).not.toContain('data-finding-id=');
    const malformed: Partial<ReviewCandidateValidationDto> = { ...claim };
    delete malformed.outcome;
    expect(() => parseAgentReview({ ...review, runs: [{ ...review.runs[0], steps: [{ number: 1, kind: 'VALIDATE',
      validation: { investigationStep: 1, candidates: [malformed], coverage } }] }] })).toThrow('Malformed validation outcome');
  });

  it('keeps omitted scope visible without claiming no findings for an incomplete accepted run', () => {
    const original = reviewFixture({ findings: [] });
    const html = tab({ ...original, runs: [{ ...original.runs[0], coverage: {
      complete: false, omissions: ['Resource cleanup has not been reviewed'], warnings: [], recordedAt: '2026-09-06T16:15:00Z',
    } }] });
    expect(html).toContain('Could not finish');
    expect(html).toContain('Resource cleanup has not been reviewed');
    expect(html).toContain('Complete coverage has not been recorded.');
    expect(html).not.toContain('No inline findings');
  });
});

describe('the summary card', () => {
  const review = reviewFixture();

  it('shows verification warnings inside the intention card without blocking a finished review', () => {
    const warning = 'Live Snowflake authentication tests were not run because account credentials were unavailable.';
    const accepted = parseAgentReview({ ...review, runs: [{ ...review.runs[0], coverage: {
      ...review.runs[0].coverage, warnings: [warning],
    } }] });
    const html = tab(accepted);
    expect(html).toContain('Finished');
    expect(html).not.toContain('Could not finish');
    expect(html).not.toContain('Provisional');
    const purposeCard = summary(accepted).split('data-review-card="run-1:purpose"')[1].split('</article>')[0];
    expect(purposeCard).toContain('aria-label="Verification warning"');
    expect(purposeCard).toContain(warning);
    expect(card(accepted, accepted.findings[0])).toContain('Post comment now');
  });

  it('shows only the displayed report’s warnings when another attempt has no summary', () => {
    const completed: AgentReviewDto['runs'][number] = { ...review.runs[0], coverage: {
      complete: true, omissions: [], warnings: ['Original environment limitation'], recordedAt: '2026-09-06T16:15:00Z',
    } };
    const failed: AgentReviewDto['runs'][number] = { ...completed, id: 'run-2', state: 'FAILED', summary: null,
      coverage: { ...completed.coverage, warnings: ['Later environment limitation'] } };
    const html = summary({ ...review, runs: [completed, failed] });
    expect(html).toContain('Original environment limitation');
    expect(html).not.toContain('Later environment limitation');
    expect(summary({ ...review, runs: [completed, { ...review.runs[0], id: 'run-3' }] })).not.toContain('Verification warning');
  });

  it('omits the warning when coverage has no warnings, including older reports', () => {
    const parsed = parseAgentReview({ ...review, runs: [{ ...review.runs[0], coverage: {
      ...review.runs[0].coverage, warnings: undefined,
    } }] });
    expect(parsed.runs[0].coverage?.warnings).toEqual([]);
    expect(summary(parsed)).not.toContain('Verification warning');
    expect(summary(review)).not.toContain('Verification warning');
  });

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
    expect(html).toContain('pr-commented-code-line');
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
    expect(html).toContain('Publishing unlocks when validation and complete coverage are accepted');
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
    expect(declarations(':is(.agr-composer textarea, .agr-pane .agt-compose__input):focus-visible')).toEqual(['outline: 0']);
  });

  it('stops the spinner for a reader who asked for less motion', () => {
    expect(css).toContain('@media (prefers-reduced-motion: reduce) { .agr-spinner { animation: none; } }');
  });
});
