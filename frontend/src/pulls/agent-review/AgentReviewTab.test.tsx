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
import { reviewDraftFixture, candidateFixture, explanationRunFixture, reviewFixture, runningReviewFixture } from './fixtures/review';
import { explanationRun, latestRun } from './model';
import type { AgentReviewDto, ReviewCandidateValidationDto, ReviewDraftDto, ReviewFindingDto, ReviewInvestigationDto, ReviewStepDto } from './contracts';
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
        onAddToReview: async () => {}, onPostNow: async () => {}, onEditDraft: async () => {}, onRemoveFromReview: async () => {},
        onNavigate: () => {},
      }} />);
}

function summary(review: AgentReviewDto): string {
  return renderToStaticMarkup(
    <ReviewSummaryCard review={review} run={explanationRun(review) ?? latestRun(review)} findings={review.findings}
      viewerLogin="jackchen" viewerAvatarUrl={null} busy={false}
      onAsk={async () => {}} onSelectFinding={() => {}} />);
}

function draftWith(items: ReviewDraftDto['items']): ReviewDraftDto {
  return reviewDraftFixture({ items });
}

describe('starting a review', () => {
  it('offers review of an already prepared working copy without claiming a review is running', () => {
    const html = tab(reviewFixture({ runs: [], followUpPaused: true, activeTurn: null }));
    expect(html).toContain('Ready to explore');
    expect(html).toContain('Start review');
    expect(html).toContain('Remove worktree');
    expect(html).not.toContain('>Review again</button>');
    expect(html).not.toContain('>Reviewing</strong>');
  });

  it('offers Start review and separates private availability from publication rights', () => {
    const html = tab(null);
    expect(html).toContain('No agent review yet');
    expect(html).toContain('Start review');
    expect(html).toContain('Private review is available on your own and closed pull requests too');
    expect(html).toContain('normal review permissions');
  });

  it('offers to watch the repository before starting a review', () => {
    const html = tab(null, NO_WORKSPACE);
    expect(html).toContain('Agent review needs a watched repository');
    expect(html).toContain('Watch repository</button>');
    expect(html).not.toContain('Start review');
  });
});

describe('the status strip', () => {
  it('shows the running pass beside the status, and its saved facts in the status tooltip', () => {
    const running = runningReviewFixture();
    const investigation: ReviewInvestigationDto = { changeMap: 'Trace the changed path.', reviewedFiles: ['src/A.java'],
      contextEvidenceRefs: [], perspectives: [], candidates: [candidateFixture('Check the boundary')], validations: [], answers: [] };
    const review: AgentReviewDto = { ...running, runs: [{ ...running.runs[0], investigation, steps: [
      { number: 1, kind: 'INVESTIGATE', repair: false, investigation, validation: null },
      { number: 2, kind: 'VALIDATE', repair: false, investigation: null, validation: null },
    ] }] };
    const html = tab(review);
    expect(html).toContain('Running · Validation</span>');
    expect(html).toContain('Current pass: Validation');
    expect(html).toContain('2 of 5 commit explanations saved');
    expect(html).toContain('1 changed file reported assessed in this run');
    expect(html).toContain('1 candidate claim recorded');
    expect(html).not.toContain('100%');
  });

  it('offers Stop while running, and never Update or End at the same time', () => {
    const html = tab(runningReviewFixture());
    expect(html).toContain('>Stop</button>');
    expect(html).not.toContain('>Review again</button>');
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
    expect(html).not.toContain('>Review again</button>');
    expect(html).not.toContain('>End review</button>');
  });

  it('offers Review again and End review once the run has settled', () => {
    const html = tab(reviewFixture());
    expect(html).toContain('>Review again</button>');
    expect(html).toContain('>End review</button>');
    expect(html).not.toContain('>Stop</button>');
    expect(html).not.toContain('>Recover runtime</button>');
  });

  it('offers Resume for a paused review and no resume action for a completed review', () => {
    const stopped = reviewFixture({
      followUpPaused: true,
      runs: [{ ...reviewFixture().runs[0], state: 'STOPPED', coverage: null }],
    });
    expect(tab(stopped)).toContain('>Resume</button>');
    expect(tab(reviewFixture())).not.toContain('>Resume</button>');
    expect(tab(reviewFixture())).not.toContain('>Resume review</button>');
  });

  it('keeps a failed run’s saved summary visible and offers to resume the review', () => {
    const failed = reviewFixture({
      followUpPaused: true,
      runs: [{ ...reviewFixture().runs[0], state: 'FAILED', coverage: null, outcome: 'Cannot start the review turn' }],
    });
    const html = tab(failed);
    expect(html).toContain('Cannot start the review turn');
    expect(html).toContain('Change intention');
    expect(html).not.toContain('Retry review');
    expect(html).toContain('>Resume review</button>');
    expect(html).not.toContain('>Run review again</button>');
    expect(html).toContain('>Review again</button>');
    expect(html).toContain('Saved work from this attempt is provisional.');
    expect(html).toContain('Chat messages only continue the discussion.');
  });

  it('shows recovery and disables further execution while an interrupted turn retains its claim', () => {
    const html = tab({ ...runningReviewFixture(), activeTurn: null, followUpPaused: true });
    expect(html).toContain('Recovery required');
    expect(html).toContain('Provider shutdown could not be verified');
    expect(html).toMatch(/<button[^>]*>Recover runtime<\/button>/);
    expect(html).not.toMatch(/disabled=""[^>]*>Recover runtime<\/button>/);
    expect(html).not.toContain('>Resume</button>');
    expect(html).not.toContain('>Resume review</button>');
    expect(html).toMatch(/disabled=""[^>]*>Review again<\/button>/);
    expect(html).toMatch(/disabled=""[^>]*>End review<\/button>/);
    expect(html).toContain('class="lrc__ask" disabled=""');
    expect(html).not.toContain('>Stop</button>');
    expect(html).not.toContain('preparing the review');
  });

  it('keeps cards in the PR pane and opens the conversation in the workspace', () => {
    const html = tab(reviewFixture());
    expect(html).not.toContain('Summary &amp; comments');
    expect(html).toContain('<button type="button" role="tab" aria-selected="true">Findings<span>3</span></button>');
    expect(html).toContain('<button type="button" role="tab" aria-selected="false">Activity</button>');
    expect(html).not.toContain('aria-label="Agent review views"');
    expect(html).toContain('aria-label="View in workspace"');
    expect(html).toContain('data-review-card="run-1:purpose"');
  });
});

describe('findings on a development task Summary', () => {
  it('opens on the last review round and keeps earlier rounds one tab away', () => {
    const base = reviewFixture();
    const runs = [1, 2].map(ordinal => ({ ...base.runs[0], id: `run-${ordinal}`, ordinal, headSha: String(ordinal).repeat(40) }));
    const review = { ...base, runs, findings: [{ ...base.findings[0], runId: 'run-1' }, { ...base.findings[1], runId: 'run-2' }] };
    const html = renderToStaticMarkup(<AgentReviewTab timeline workspaceId="workspace-1" viewer={null} review={review}
      onReviewChange={() => {}} onNavigateToChanges={() => {}} focusFindingId={null} />);
    expect(html).toContain('Round 2 · head 2222222');
    expect(html).toMatch(/aria-pressed="true">Last round<span>1<\/span>.*aria-pressed="false">All rounds<span>2<\/span>.*aria-pressed="false">Round 1<span>1<\/span>/);
    // Round dividers belong to All rounds only.
    expect(html).not.toContain('class="agr-round"');
    expect(html).toMatch(new RegExp(`<div hidden=""><[^>]*data-finding-id="${base.findings[0].id}"`));
    expect(html).toMatch(new RegExp(`<div><[^>]*data-finding-id="${base.findings[1].id}"`));
  });
});

describe('banners', () => {
  it('mirrors a pending approval in the summary so the wait is not hidden behind Activity', () => {
    const html = tab({ ...runningReviewFixture(), pendingApprovals: 1 });
    expect(html).toContain('Approval needed');
    expect(html).toContain('waiting for your answer');
  });

  it('shows an incomplete review with its reason and an explicit resume action', () => {
    const html = tab(reviewFixture({
      findings: [],
      runs: [{ ...reviewFixture().runs[0], state: 'INCOMPLETE', coverage: null, summary: null, outcome: 'coverage was not recorded' }],
    }));
    expect(html).toContain('Could not finish');
    expect(html).toContain('coverage was not recorded');
    expect(html).not.toContain('Retry review');
    expect(html).toContain('>Resume review</button>');
    expect(html).toContain('Round 1 · head d9a0c41 · 0 findings');
    expect(html).toContain('No summary was saved for this run');
    expect(html).not.toContain('Incomplete');
    expect(html).not.toContain('finished ');
    expect(html).not.toContain('No inline findings');
  });

  it('blocks resuming an unfinished review while a local run holds its working copy', () => {
    const review = reviewFixture();
    const html = tab({ ...review, task: { ...review.task, mutationClaim: 'LOCAL_RUN' },
      runs: [{ ...review.runs[0], state: 'FAILED', coverage: null }] });
    const controls = html.match(/<button[^>]*>Resume review<\/button>/g);
    expect(controls).toHaveLength(2);
    expect(controls?.every(button => button.includes('disabled=""') && button.includes('A run is using this working copy'))).toBe(true);
  });

  it('does not offer resume for running or closed reviews', () => {
    const review = reviewFixture();
    for (const value of [runningReviewFixture(), { ...review, state: 'CLOSED' as const,
      runs: [{ ...review.runs[0], state: 'FAILED' as const, coverage: null }] }]) {
      expect(tab(value)).not.toContain('>Resume review</button>');
    }
  });

  it('keeps an earlier completed report visible without hiding the latest failure reason', () => {
    const review = reviewFixture();
    const html = tab({ ...review, runs: [...review.runs, { ...review.runs[0], id: 'failed-run', state: 'FAILED',
      summary: null, coverage: null, outcome: 'The review tools could not save the report' }] });
    expect(html).toContain('The review tools could not save the report');
    expect(html).toContain('Saved work from this attempt is provisional.');
    expect(html).toContain('Original change explanation · head d9a0c41');
    expect(html).toContain('Change intention');
    expect(html).not.toContain('finished ');
  });

  it('offers Review latest when new commits arrived', () => {
    const html = tab(reviewFixture({ observedHeadSha: '5e17b02a1b2c3d4e' }));
    expect(html).toContain('New commits since this review — reviewed <strong>d9a0c41</strong>,');
    expect(html).toContain('head is <strong>5e17b02</strong>.');
    expect(html).toContain('>Review latest</button>');
    expect(html).toContain('Finished');
    expect(card(reviewFixture({ observedHeadSha: '5e17b02a1b2c3d4e' }), reviewFixture().findings[0])).toContain('Post now');
  });

  it.each([
    ['CURRENT', false, 'Working copy up to date', 'Review results still cover d9a0c41'],
    ['PENDING', false, 'Working copy waiting to sync', 'will sync when the review is idle'],
    ['BLOCKED', true, 'Working copy is stale', 'Saved local changes are preserved'],
    ['UNAVAILABLE', false, 'Working copy sync unavailable', 'Fetch failed'],
  ] as const)('explains %s worktree sync separately from the reviewed snapshot', (state, dirty, label, message) => {
    const review = reviewFixture({ observedHeadSha: '5e17b02a1b2c3d4e', worktreeSync: {
      state, dirty, headSha: state === 'CURRENT' ? '5e17b02a1b2c3d4e' : 'd9a0c41a1b2c3d4e',
      targetHeadSha: '5e17b02a1b2c3d4e', reason: state === 'UNAVAILABLE' ? 'Fetch failed' : null,
    } });
    const parsed = parseAgentReview(JSON.parse(JSON.stringify(review)));
    expect(parsed.worktreeSync).toEqual(review.worktreeSync);
    const html = tab(parsed);
    expect(html).toContain(label);
    expect(html).toContain(message);
    expect(html).toContain('>Review again</button>');
    expect(parsed.runs[0].headSha).toBe(reviewFixture().runs[0].headSha);
  });

  it('rejects an unknown worktree sync state', () => {
    expect(() => parseAgentReview({ ...reviewFixture(), worktreeSync: { state: 'RESETTING' } })).toThrow('worktree sync state');
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

describe('a finding about the whole pull request', () => {
  it('parses a finding that has no lines, as the backend sends it', () => {
    const original = reviewFixture();
    const wire: ReviewFindingDto = { ...original.findings[0], path: null, side: null, startLine: null, endLine: null, range: null, excerpt: [] };
    const parsed = parseAgentReview(JSON.parse(JSON.stringify({ ...original, findings: [wire, original.findings[0]] }))).findings;
    expect(parsed[0]).toMatchObject({ path: null, side: null, startLine: null, endLine: null, range: null });
    expect(parsed[1].path).toBe(original.findings[0].path);
  });
});

describe('review details', () => {
  it('keeps validation evidence in the Evidence tab and the working report out of review details', () => {
    const original = reviewFixture({ findings: [] });
    const run = original.runs[0];
    const review = parseAgentReview({ ...original, runs: [{ ...run, scope: 'INCREMENTAL', changedFiles: 0, steps: [],
      investigation: { changeMap: 'Provider ownership spans the **factory and scan**.',
        reviewedFiles: ['src/Factory.java'], contextEvidenceRefs: ['context-1'],
        perspectives: [], candidates: [candidateFixture('Draft leak hypothesis'), candidateFixture('Draft delegation hypothesis', 2)],
        validations: [{ candidate: 1, outcome: 'REJECTED', conclusion: 'The caller releases its reference in `finally`; E25 verifies cleanup.', findingId: null },
          { candidate: 2, outcome: 'SUPPORTED', conclusion: 'The delegated context drops the reservation.', findingId: 'finding-1' }] },
      evidence: [{ ref: 'context-1', source: 'src/UnchangedCaller.java:14–28', provenance: 'Frozen source',
        excerpt: 'finally { provider.release(); }', explanation: 'The caller owns cleanup.' },
      { ref: 'E25', source: 'command output: ./mvnw test -Dtest=TestProviderCleanup', provenance: 'Command executed during validation',
        excerpt: 'Frozen head: PASS; release removed: FAIL (reference remains)', explanation: '' }] }] });
    const html = tab(review);
    expect(html).not.toContain('agr-review-details');
    expect(html).not.toContain('changed file assessed');
    expect(html).not.toContain('Provider ownership spans');
    expect(html).toContain('src/UnchangedCaller.java:14–28');
    expect(html).toContain('finally { provider.release(); }');
    expect(html).toContain('2 references · frozen at head d9a0c41');
    expect(html).toContain('<span class="agr-card__refs">E25</span>');
    expect(html).toContain('command output: ./mvnw test -Dtest=TestProviderCleanup');
    expect(html).toContain('Frozen head: PASS; release removed: FAIL (reference remains)');
    expect(html).not.toContain('Draft leak hypothesis');
    expect(html).not.toContain('The caller releases its reference');
    expect(html).not.toContain('Behavior and contracts');
    expect(html).not.toContain('data-finding-id=');
    const partial = tab({ ...review, runs: [{ ...review.runs[0], investigation: null }] });
    expect(partial).toContain('Frozen head: PASS; release removed: FAIL (reference remains)');
  });

  it('retains supported unselected findings in details without counting them as selected results', () => {
    const original = reviewFixture();
    const source = original.runs[0];
    const review: AgentReviewDto = { ...original, runs: [{ ...source, steps: source.steps.map((step): ReviewStepDto => ({ ...step,
      validation: step.validation === null ? null : { ...step.validation, selectedFindingIds: [], selectionExplanation: 'Useful context, below the reporting threshold.' },
    })) }] };
    const html = tab(review);
    expect(html).toContain('Other recorded findings · 3');
    expect(html).toContain('No selected findings');
    expect(html).toContain('No findings were selected for the main result.');
    expect(html).toContain('Add to my review');
    expect(html).toContain('Review details <span class="agr-learning-cards__scope"> · other recorded findings</span>');
    expect(html).toContain('Round 1 · head d9a0c41 · 0 findings');
  });

  it('parses discovery provenance and perspectives without showing them', () => {
    const running = runningReviewFixture();
    const investigation: ReviewInvestigationDto = { changeMap: 'Changed teardown responsibility.', reviewedFiles: ['src/Factory.java'],
      contextEvidenceRefs: [], candidates: [{ ...candidateFixture('The callback may outlive its owner'), attributedSource: 'PR discussion' }],
      perspectives: [
        { perspective: 'BEHAVIOR', question: 'Who owns teardown?', responsibilities: ['R1'], paths: ['src/Factory.java'], evidenceRefs: [], status: 'CHECKED', explanation: 'Inspected teardown.' },
        { perspective: 'INTERACTIONS', question: 'Can cancellation overlap?', responsibilities: ['R1'], paths: [], evidenceRefs: [], status: 'BLOCKED', explanation: 'Caller source unavailable.' },
        { perspective: 'DESIGN', question: 'Is the change relevant to tests?', responsibilities: ['R1'], paths: [], evidenceRefs: [], status: 'NOT_APPLICABLE', explanation: 'No test harness changed.' },
      ], validations: [], answers: [] };
    const review = parseAgentReview({ ...running, runs: [{ ...running.runs[0], investigation,
      steps: [{ number: 1, kind: 'INVESTIGATE', repair: false, investigation: null, validation: null }] }] });
    expect(review.runs[0].investigation?.candidates[0]).toMatchObject({ originStep: 1, originTurnId: 'turn-1', attributedSource: 'PR discussion' });
    const html = tab(review);
    expect(html).toContain('Other recorded findings · 1');
    expect(html).not.toContain('Unsealed checkpoint');
    expect(html).not.toContain('Behavior and contracts');
    expect(html).not.toContain('The callback may outlive its owner');
    expect(() => parseAgentReview({ ...review, runs: [{ ...review.runs[0], investigation: { ...investigation, candidates: ['unstructured'] } }] })).toThrow('review candidate');
  });

  it('lists unreviewed scope in review details without the review passes', () => {
    const original = reviewFixture({ findings: [] });
    const investigation: ReviewInvestigationDto = { changeMap: 'Trace provider ownership.', reviewedFiles: ['src/Factory.java'], contextEvidenceRefs: ['E1'],
      perspectives: [], candidates: [candidateFixture('Provider teardown may retain memory')], validations: [], answers: [] };
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
          validation: { investigationStep: 1, candidates: [claim], coverage, newCandidates: [candidateFixture('The cancellation path may also retain the provider.', 2)], selectedFindingIds: [], selectionExplanation: '' } },
        { number: 3, kind: 'COLLECT_EVIDENCE', repair: false, investigation: { ...investigation, answers: [answer] }, validation: null },
        { number: 4, kind: 'VALIDATE', repair: false, investigation: null,
          validation: { investigationStep: 3, candidates: [{ ...claim, outcome: 'UNRESOLVED',
            conclusion: 'The available source does not settle the cleanup behavior.', request: null }], coverage, newCandidates: [], selectedFindingIds: [], selectionExplanation: '' } },
      ] }] });
    const html = tab(review);
    expect(html).toContain('Review details <span class="agr-learning-cards__scope"> · 1 item not reviewed</span>');
    expect(html).toContain('<h3>Not reviewed</h3><ul><li>Cleanup could not be verified</li></ul>');
    expect(html).not.toContain('Review passes');
    expect(html).not.toContain('Who releases the last reference?');
    expect(html).not.toContain('The integration fixture requires an unavailable service.');
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
    expect(html).toContain('Coverage <strong class="is-open">Not recorded</strong>');
    expect(html).not.toContain('No inline findings');
  });
});

describe('the summary card', () => {
  const review = reviewFixture();

  it('offers an accessible explanation refresh and disables it while the task is occupied or closed', () => {
    const label = 'aria-label="Refresh change intention and commit summaries"';
    expect(tab(review)).toContain(label);
    for (const value of [runningReviewFixture(), { ...review, state: 'CLOSED' as const },
      { ...review, task: { ...review.task, mutationClaim: 'LOCAL_RUN' as const } },
      { ...review, task: { ...review.task, mutationClaim: 'RECOVERY' as const } }]) {
      const withOwner = { ...value, runs: review.runs };
      expect(tab(withOwner)).toMatch(/aria-label="Refresh change intention and commit summaries"[^>]*disabled=""/);
    }
  });

  it.each(['RUNNING', 'FAILED', 'STOPPED', 'INCOMPLETE'] as const)('preserves the saved explanation during a %s refresh', state => {
    const refresh = explanationRunFixture({ state, outcome: state === 'RUNNING' ? null : 'The refresh did not finish.' });
    const html = tab({ ...review, runs: [...review.runs, refresh] });
    expect(html).toContain('data-review-card="run-1:purpose"');
    expect(html).not.toContain('Refreshed change intention.');
    expect(html).not.toContain('data-review-card="explanation-1:purpose"');
    expect(html).toContain('3 findings');
    if (state === 'RUNNING') expect(html).toContain('role="status">Refreshing change intention and commit summaries…');
    else {
      expect(html).toContain('role="alert">The refresh did not finish.');
      expect(html).toContain('Finished');
      expect(html).not.toContain('Could not finish');
    }
  });

  it('shows an accepted refreshed explanation without inventing review coverage or replacing the findings details', () => {
    const refreshed = { ...review, runs: [...review.runs, explanationRunFixture()] };
    const html = tab(refreshed);
    expect(html).toContain('data-review-card="explanation-1:purpose"');
    expect(html).toContain('data-review-card="run-1:details"');
    expect(html).toContain('Refreshed change intention.');
    expect(html).toContain('3 findings');
    expect(summary(refreshed)).not.toContain('Provisional');
    expect(summary(refreshed)).not.toContain('Not recorded');
  });

  it.each(['RUNNING', 'ACCEPTED', 'FAILED'] as const)('retains the original explanation beside the %s current run without a new summary', state => {
    const original = review.runs[0];
    const current: AgentReviewDto['runs'][number] = { ...original, id: 'run-2', ordinal: 2, headSha: 'b'.repeat(40), summary: null, state,
      investigation: { changeMap: 'Fresh map for the updated head.', reviewedFiles: [], contextEvidenceRefs: [],
        perspectives: [], candidates: [], validations: [], answers: [] },
      coverage: { complete: state === 'ACCEPTED', omissions: [], warnings: ['Current check could not run.'], recordedAt: original.createdAt },
      outcome: state === 'FAILED' ? 'Current review failed.' : null };
    const updated = { ...review, observedHeadSha: current.headSha, runs: [original, current] };
    const html = tab(updated);
    expect(html).toContain('Original change explanation · head d9a0c41');
    expect(html).toContain('data-review-card="run-1:purpose"');
    expect(html).toContain('data-review-card="run-2:details"');
    expect(html).not.toContain('Fresh map for the updated head.');
    expect(html).toContain('Current check could not run.');
    expect(html).not.toContain('No summary was saved');
    expect(html).not.toContain('0 of 5 commit explanations saved');
    expect(summary(updated)).not.toContain('Provisional');
    if (state === 'FAILED') expect(html).toContain('Current review failed.');
    if (state === 'ACCEPTED') expect(html).toContain('Finished');
  });

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
    expect(card(accepted, accepted.findings[0])).toContain('Post now');
  });

  it('keeps explanation warnings attached to their original report', () => {
    const completed: AgentReviewDto['runs'][number] = { ...review.runs[0], coverage: {
      complete: true, omissions: [], warnings: ['Original environment limitation'], recordedAt: '2026-09-06T16:15:00Z',
    } };
    const failed: AgentReviewDto['runs'][number] = { ...completed, id: 'run-2', state: 'FAILED', summary: null,
      coverage: { ...completed.coverage, warnings: ['Later environment limitation'] } };
    const html = summary({ ...review, runs: [completed, failed] });
    expect(html).toContain('Original environment limitation');
    expect(html).not.toContain('Later environment limitation');
    expect(summary({ ...review, runs: [completed, { ...review.runs[0], id: 'run-3', ordinal: 3 }] })).toContain('Original environment limitation');
  });

  it('omits the warning when coverage has no warnings, including older reports', () => {
    const parsed = parseAgentReview({ ...review, runs: [{ ...review.runs[0], coverage: {
      ...review.runs[0].coverage, warnings: undefined,
    } }] });
    expect(parsed.runs[0].coverage?.warnings).toEqual([]);
    expect(summary(parsed)).not.toContain('Verification warning');
    expect(summary(review)).not.toContain('Verification warning');
  });

  it('leads with the intention and picks one commit at a time from the row above it', () => {
    const html = summary(review);
    expect(html).toContain('aria-label="What changed"');
    expect(html).toContain('aria-label="Summary outline"');
    expect(html).not.toContain('described the change intention');
    expect(html).toContain('<button type="button" aria-current="true" title="Intention">Intention</button>');
    expect(html).toContain('<strong>Change intention</strong>');
    expect(html.match(/class="agr-changed__item"/g)).toHaveLength(6);
    expect(html.match(/aria-current="false" title="[^"]+"><span>\d<\/span>/g)).toHaveLength(5);
    expect(html.match(/<div hidden=""><div class="agr-summary-card"/g)).toHaveLength(5);
    expect(html).not.toContain('Design &amp; implementation');
    expect(html).toContain('updated 1mo ago · 5 commits through <code>d9a0c41</code>');
    expect(html).not.toContain('Commit implementation');
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
    expect(html.match(/Agent review · [^<]+ · Private<\/span>/g)).toHaveLength(6);
    expect(html).toContain('Why does the second commit change IcebergUtil');
    expect(html.match(/data-review-card=/g)).toHaveLength(6);
    expect(html).toContain('Ask reviewer');
    expect(html).not.toContain('Post now');
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
    expect(html).toContain('Ask reviewer');
    expect(html).toContain('>Resolve</button>');
    expect(html).toContain('View in Changes');
    expect(html).toContain('Add to my review');
    expect(html).toContain('Post now');
    // Only the GitHub-bound action carries the primary treatment.
    expect(html.match(/lrc__action is-primary/g) ?? []).toHaveLength(1);
    expect(html).toContain('<button type="button" class="lrc__action is-primary">Post now</button>');
    // The Changes copy of the card keeps its own wording.
    expect(card(review, finding, 'changes')).toContain('>Post comment now</button>');
  });

  it('labels the way back differently in each projection but keeps one identity', () => {
    expect(card(review, finding, 'review')).toContain('View in Changes');
    expect(card(review, finding, 'changes')).toContain('Open in Summary');
    expect(card(review, finding, 'changes')).toContain('data-finding-id="finding-1"');
    expect(card(review, finding, 'review')).toContain('data-finding-id="finding-1"');
  });

  it('offers Reopen and the reason for a locally resolved finding, and nothing publishable', () => {
    const resolved = { ...finding, state: 'RESOLVED' as const, stateReason: 'Known limitation, tracked in #29981' };
    const html = card(reviewFixture({ findings: [resolved] }), resolved);
    expect(html).toContain('Resolved locally by you');
    expect(html).toContain('Known limitation, tracked in #29981');
    expect(html).not.toContain('draft text kept');
    expect(html).toContain('>Reopen</button>');
    expect(html).not.toContain('Post now');
    expect(html).not.toContain('Add to my review');
    expect(html).toContain('Excluded from publication');
  });

  it('keeps an earlier accepted finding publishable at its recorded commit', () => {
    const outdated = { ...finding, outdated: true };
    const html = card(reviewFixture({ findings: [outdated] }), outdated);
    expect(html).toContain('Its comment stays attached to that commit');
    expect(html).toContain('pr-commented-code-line');
    expect(html).toContain('resolveNestedFieldId');
    expect(html).toContain('Post now');
  });

  it('keeps private questions available on a withdrawn finding without offering publication or resolution', () => {
    const withdrawn = { ...finding, state: 'WITHDRAWN' as const, stateReason: 'The fallback only runs for single-segment paths' };
    const html = card(reviewFixture({ findings: [withdrawn] }), withdrawn);
    expect(html).toContain('The agent withdrew this finding');
    expect(html).toContain('can no longer be added to your review');
    expect(html).toContain('<button type="button" class="lrc__ask">Ask reviewer</button>');
    expect(html).not.toContain('Add to my review');
    expect(html).not.toContain('Post now');
    expect(html).not.toContain('>Resolve</button>');
    expect(html).not.toContain('>Reopen</button>');
    expect(card(reviewFixture({ state: 'CLOSED', findings: [withdrawn] }), withdrawn))
      .toContain('<button type="button" class="lrc__ask" disabled="">Ask reviewer</button>');
  });

  it('shows the outgoing draft inside the card, never as a second pending clone', () => {
    const withDraft = reviewFixture({
      draft: draftWith([{
        id: 'item-1', source: 'AGENT_FINDING', findingId: 'finding-1', findingRevision: 1, commitId: finding.validatedHeadSha,
        path: finding.path, side: 'RIGHT', line: 131, startLine: 118,
        body: 'Could this match the full path instead?', included: true, published: false, publication: null,
      }]),
    });
    const html = card(withDraft, finding);
    // On the Summary the comment takes the finding's place; the Changes copy keeps the finding with the comment below.
    expect(html).toContain('Your comment');
    expect(html).not.toContain('Editing this never changes the finding');
    expect(html).not.toContain('<h3 class="agr-finding__title">');
    expect(html).toContain('Could this match the full path instead?');
    expect(html).toContain('· edited');
    expect(html).toContain('Show original</button>');
    expect(html).toContain('Pending · in your review');
    // Promoted once: the actions no longer offer to promote it again, only to take it back out.
    expect(html).not.toContain('Add to my review');
    expect(html).toContain('>✓ In my review</button>');
    const inChanges = card(withDraft, finding, 'changes');
    expect(inChanges).toContain('Outgoing comment · in your pending review');
    expect(inChanges).toContain('Editing this never changes the finding');
    expect(inChanges).toContain('<h3 class="agr-finding__title">');
  });

  it.each(['OPEN', 'RESOLVED', 'WITHDRAWN'] as const)('keeps a published %s finding’s thread open and private', state => {
    const published = reviewFixture({
      draft: draftWith([{
        id: 'item-1', source: 'AGENT_FINDING', findingId: 'finding-1', findingRevision: 1, commitId: finding.validatedHeadSha,
        path: finding.path, side: 'RIGHT', line: 131, startLine: 118, body: 'sent', included: true, published: true,
        publication: {
          state: 'PUBLISHED', remoteReviewId: 2201456, remoteCommentId: 9911,
          url: 'https://github.com/trinodb/trino/pull/30304#discussion_r2201456', at: '2026-09-06T16:32:00Z',
        },
      }]),
    });
    const html = card(published, { ...finding, state });
    expect(html).toContain('Posted to GitHub as a review comment');
    expect(html).toContain('The thread below stays private');
    expect(html).toContain('discussion_r2201456');
    expect(html).toContain('<button type="button" class="lrc__ask">Ask reviewer</button>');
    expect(html).not.toContain('Post now');
    expect(html).not.toContain('Add to my review');
  });

  it('blocks publication of a provisional finding and says why', () => {
    const running = runningReviewFixture();
    const html = card(running, running.findings[0]);
    expect(html).toContain('Provisional');
    expect(html).toContain('Publishing unlocks when validation and complete coverage are accepted');
    expect(html).not.toContain('Post now');
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

  it('draws findings with the one private card and summaries without a card, never the GitHub thread card', () => {
    for (const html of [summary(reviewFixture()), card(reviewFixture(), reviewFixture().findings[0])]) {
      expect(html).not.toContain('pr-inline-thread');
      expect(html).not.toContain('pr-thread-comment');
    }
    expect(summary(reviewFixture())).not.toContain('class="lrc is-');
    expect(card(reviewFixture(), reviewFixture().findings[0])).toContain('<details class="lrc is-local" open="">');
    expect(declarations(':is(.agr-summary-card, .agr-finding, .agr-local-comments) .lrc')).toContain('margin-left: 0');
  });

  it('gives only the GitHub-bound action the green primary treatment', () => {
    expect(declarations('.agr-action.is-primary')).toContain('background: light-dark(#1f883d, #287a43)');
    expect(declarations('.agr-action')).toContain('background: var(--surface-canvas)');
  });

  it('bounds an evidence excerpt instead of letting it run the card', () => {
    const excerpt = declarations('.agr-evidence__excerpt');
    expect(excerpt).toContain('max-height: 180px');
    expect(excerpt).toContain('overflow: auto');
    expect(excerpt).toContain('word-break: break-word');
  });

  it('wraps the review header, banners and action rows so a narrow pane never clips them', () => {
    expect(declarations('.agr-review__head')).toContain('flex-wrap: wrap');
    expect(declarations('.agr-review__behind')).toContain('flex-wrap: wrap');
    expect(declarations('.agr-changed__foot')).toContain('flex-wrap: wrap');
    expect(declarations('.agr-banner')).toContain('flex-wrap: wrap');
  });

  it('rings every control it owns, since the app has no global focus style', () => {
    const ring = declarations(
      ':is(.agr-pane, .agr-empty, .agr-finding, .agr-composer, .agr-summary-page) :is(a, button, summary, textarea):focus-visible');
    expect(ring).toContain('outline: 2px solid var(--control-strong)');
    expect(ring).toContain('outline-offset: 2px');
    expect(declarations(':is(.pull-diff-review textarea, .agr-pane .agt-compose__input):focus-visible')).toEqual(['outline: 0']);
  });

  it('stops the spinner for a reader who asked for less motion', () => {
    expect(css).toContain('@media (prefers-reduced-motion: reduce) { .agr-spinner { animation: none; } }');
  });
});
