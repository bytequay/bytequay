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
import { ListChecks } from 'lucide-react';
import { describe, expect, it } from 'vitest';
import {
  askLabel,
  askQueues,
  draftItemFor,
  draftCommentCommit,
  evidenceGroups,
  explanationRun,
  findingInResults,
  findingPill,
  findingPublication,
  latestRun,
  mergeReviewFindings,
  messagesFor,
  newCommits,
  openFindings,
  pendingDraftCount,
  provenanceFacts,
  provisional,
  queued,
  reviewElapsed,
  reviewPasses,
  reviewProgress,
  reviewRecoveryRequired,
  reviewRequests,
  reviewRunning,
  reviewStatus,
  shortPath,
  shortSha,
} from './model';
import { reviewDraftFixture, candidateFixture, explanationRunFixture, reviewFixture, runningReviewFixture } from './fixtures/review';
import { parseAgentReview } from './contracts';
import type { AgentReviewDto, ReviewDraftDto, ReviewFindingDto, ReviewInvestigationDto, ReviewMessageDto, ReviewStepDto } from './contracts';

function withFindings(...findings: ReviewFindingDto[]): AgentReviewDto {
  return reviewFixture({ findings });
}

it('keeps newer finding state when review reads arrive out of order', () => {
  const open = reviewFixture();
  const resolved = { ...open, findings: [{ ...open.findings[0], state: 'RESOLVED' as const, stateReason: 'Fixed', version: 1 }, ...open.findings.slice(1)] };
  const refreshed = mergeReviewFindings(resolved, { ...open, pendingApprovals: 1 });
  expect(refreshed.findings).toEqual(resolved.findings);
  expect(refreshed.pendingApprovals).toBe(1);
  const reopened = { ...resolved, findings: [{ ...open.findings[0], version: 2 }, ...open.findings.slice(1)] };
  expect(mergeReviewFindings(resolved, reopened).findings).toEqual(reopened.findings);
  expect(mergeReviewFindings(reopened, resolved).findings).toEqual(reopened.findings);
  expect(mergeReviewFindings(null, open)).toBe(open);
  const other = { ...open, id: 'another-review' };
  expect(mergeReviewFindings(resolved, other)).toBe(other);
});

function draft(items: ReviewDraftDto['items']): ReviewDraftDto {
  return reviewDraftFixture({ items });
}

function draftItem(overrides: Partial<ReviewDraftDto['items'][number]> = {}): ReviewDraftDto['items'][number] {
  return {
    id: 'item-1',
    source: 'AGENT_FINDING',
    findingId: 'finding-1',
    findingRevision: 1,
    commitId: reviewFixture().findings[0].validatedHeadSha,
    path: 'a.java',
    side: 'RIGHT',
    line: 131,
    startLine: 118,
    body: 'outgoing text',
    included: true,
    published: false,
    publication: null,
    ...overrides,
  };
}

describe('review status', () => {
  it('reads Finished with its coverage and scope from an accepted run', () => {
    const status = reviewStatus(reviewFixture());
    expect(status.status).toBe('ready');
    expect(status.label).toBe('Finished');
    expect(status.tone).toBe('ready');
    expect(status.detail).toBe('complete coverage · 5 commits · 30 files');
    expect(status.chip).toBe('3 findings');
    expect(status.busy).toBe(false);
  });

  it('says No findings only when a run recorded coverage with none', () => {
    const accepted = reviewFixture({ findings: [] });
    expect(reviewStatus(accepted).status).toBe('nofindings');
    expect(reviewStatus(accepted).chip).toBe('No findings');

    // The same empty result while the run is still going is not a clean zero-finding review.
    const running = runningReviewFixture();
    const stillWorking: AgentReviewDto = { ...running, findings: [], runs: [{ ...running.runs[0], summary: null }] };
    expect(reviewStatus(stillWorking).status).toBe('running');
  });

  it('reports that the review could not finish when the run recorded no coverage', () => {
    const review = reviewFixture({
      runs: [{ ...reviewFixture().runs[0], state: 'INCOMPLETE', coverage: null, summary: null, outcome: 'coverage was not recorded' }],
    });
    const status = reviewStatus(review);
    expect(status.status).toBe('failed');
    expect(status.chip).toBe('Could not finish');
    expect(status.detail).toBe('coverage was not recorded');
  });

  it('prefers Waiting for approval over Running while an approval is pending', () => {
    const running = runningReviewFixture();
    expect(reviewStatus(running).chip).toBe('Reviewing');
    expect(reviewStatus({ ...running, pendingApprovals: 1 }).status).toBe('approval');
    expect(reviewStatus({ ...running, pendingApprovals: 1 }).chip).toBe('Needs approval');
  });

  it('shows recovery when a retained turn claim outlives the turn, even with a saved review result', () => {
    const running = runningReviewFixture();
    const interrupted: AgentReviewDto = { ...running, activeTurn: null };
    expect(reviewRunning(interrupted)).toBe(false);
    expect(reviewRecoveryRequired(interrupted)).toBe(true);
    expect(reviewStatus(interrupted)).toMatchObject({ status: 'recovery', label: 'Recovery required', chip: 'Recovery required', busy: false });
    expect(reviewElapsed(interrupted, Date.now())).toBeNull();
    expect(reviewRecoveryRequired(running)).toBe(false);
    expect(reviewRecoveryRequired({ ...interrupted, task: { ...interrupted.task, mutationClaim: null } })).toBe(false);
    expect(reviewRecoveryRequired({ ...interrupted, state: 'CLOSED' })).toBe(false);
  });

  it('counts queued questions in the running chip', () => {
    const running = { ...runningReviewFixture(), queuedInputIds: ['q1', 'q2'] };
    expect(reviewStatus(running).status).toBe('queued');
    expect(reviewStatus(running).chip).toBe('Reviewing · 2 queued');
    expect(reviewStatus(running).detail).toBe('2 questions queued');
  });

  it('keeps all review passes under Reviewing while distinguishing discussion turns', () => {
    const running = runningReviewFixture();
    expect(reviewStatus(running).label).toBe('Reviewing');
    const validating: AgentReviewDto = { ...running, runs: [{ ...running.runs[0], turnIds: ['turn-0', 'turn-1'], steps: [
      { number: 1, kind: 'VALIDATE', repair: false, investigation: null, validation: null },
    ] }] };
    expect(reviewStatus(validating)).toMatchObject({ label: 'Reviewing', chip: 'Reviewing' });
    const collecting: AgentReviewDto = { ...validating, runs: [{ ...validating.runs[0], steps: [
      { number: 1, kind: 'VALIDATE', repair: false, investigation: null, validation: null },
      { number: 2, kind: 'COLLECT_EVIDENCE', repair: false, investigation: null, validation: null },
    ] }] };
    expect(reviewStatus(collecting)).toMatchObject({ label: 'Reviewing', chip: 'Reviewing' });
    const collectingAgain = parseAgentReview({ ...collecting, runs: [{ ...collecting.runs[0], steps: [
      ...collecting.runs[0].steps,
      { number: 3, kind: 'COLLECT_EVIDENCE', repair: false, investigation: null, validation: null },
    ] }] });
    expect(reviewStatus(collectingAgain)).toMatchObject({ label: 'Reviewing', chip: 'Reviewing' });
    expect(reviewProgress(collectingAgain)?.phase).toBe('Current pass: Evidence collection');
    expect(reviewStatus({ ...collecting, queuedInputIds: ['q1'] }).chip).toBe('Reviewing · 1 queued');
    expect(reviewStatus({ ...validating, activeTurn: { id: 'discussion-turn', state: 'RUNNING', startedAt: null, cancellationRequestedAt: null } }).label).toBe('Running');
    expect(reviewRequests(validating).find(request => request.turnId === 'turn-1')?.action).toBe('Follow-up review');
  });

  it('keeps incomplete accepted coverage provisional and never reports a clean result', () => {
    const original = reviewFixture();
    const coverages: Array<AgentReviewDto['runs'][number]['coverage']> = [null,
      { complete: false, omissions: [], warnings: [], recordedAt: '2026-09-06T16:15:00Z' },
      { complete: true, omissions: ['Skipped the cleanup paths'], warnings: [], recordedAt: '2026-09-06T16:15:00Z' }];
    for (const coverage of coverages) {
      const review: AgentReviewDto = { ...original, findings: [], runs: [{ ...original.runs[0], coverage }] };
      expect(reviewStatus(review)).toMatchObject({ status: 'failed', chip: 'Could not finish' });
      expect(provisional(review)).toBe(true);
      expect(findingPublication(review, original.findings[0]).kind).toBe('blocked');
    }
  });

  it('reports the latest failed attempt while retaining an earlier readable report', () => {
    const review = reviewFixture();
    const failed = { ...review, runs: [...review.runs, { ...review.runs[0], id: 'failed-run', state: 'FAILED' as const,
      summary: null, coverage: null, outcome: 'The current scope could not be reviewed' }] };
    expect(latestRun(failed)?.id).toBe('failed-run');
    expect(explanationRun(failed)?.id).toBe('run-1');
    expect(reviewStatus(failed)).toMatchObject({ status: 'failed', detail: 'The current scope could not be reviewed' });
  });

  it('reports New commits when the observed head moved past the reviewed one', () => {
    const review = reviewFixture({ observedHeadSha: '5e17b02' });
    expect(newCommits(review)).toBe(true);
    expect(reviewStatus(review).status).toBe('newcommits');
    expect(reviewStatus(review).chip).toBe('New commits');
    expect(newCommits(reviewFixture())).toBe(false);
  });

  it('reports Stopped for a stopped run whose follow-up is paused', () => {
    const stopped = reviewFixture({
      followUpPaused: true,
      runs: [{ ...reviewFixture().runs[0], state: 'STOPPED', coverage: null }],
    });
    expect(reviewStatus(stopped).status).toBe('stopped');
    expect(reviewStatus(stopped).chip).toBe('Stopped');
  });

  it('shows a stopped review while continuation is paused between its two passes', () => {
    const running = runningReviewFixture();
    const stopped: AgentReviewDto = { ...running, activeTurn: null, followUpPaused: true, task: { ...running.task, mutationClaim: null } };
    expect(reviewStatus(stopped)).toMatchObject({ status: 'stopped', label: 'Stopped', chip: 'Stopped', busy: false });
    const continuing = { ...stopped, followUpPaused: false };
    expect(reviewRunning(continuing)).toBe(true);
    expect(reviewStatus(continuing)).toMatchObject({ status: 'running', label: 'Reviewing', chip: 'Reviewing', busy: true });
    expect(reviewStatus({ ...stopped, activeTurn: running.activeTurn }).status).toBe('running');
    expect(reviewStatus({ ...stopped, task: { ...stopped.task, mutationClaim: 'RECOVERY' } }).status).toBe('recovery');
  });

  it('counts only open findings in the chip once some are resolved', () => {
    const review = reviewFixture();
    const resolved = withFindings(
      review.findings[0],
      { ...review.findings[1], state: 'RESOLVED' },
      review.findings[2]);
    expect(reviewStatus(resolved).chip).toBe('2 open');
  });

  it('keeps a closed review readable', () => {
    const ended = reviewFixture({ state: 'CLOSED', closedAt: '2026-09-06T17:00:00Z' });
    expect(reviewStatus(ended).status).toBe('ended');
    expect(reviewRunning(ended)).toBe(false);
  });
});

describe('review progress', () => {
  it('shows the current persisted pass and reported scope without inventing a completion percentage', () => {
    const running = runningReviewFixture();
    const investigation: ReviewInvestigationDto = { changeMap: 'Trace the changed path.', reviewedFiles: ['src/A.java', 'src/B.java'],
      contextEvidenceRefs: [], perspectives: [], candidates: [candidateFixture('First hypothesis'), candidateFixture('Second hypothesis', 2)], validations: [], answers: [] };
    const review: AgentReviewDto = { ...running, runs: [{ ...running.runs[0], steps: [
      { number: 1, kind: 'INVESTIGATE', repair: false, investigation, validation: null },
      { number: 2, kind: 'VALIDATE', repair: false, investigation: null, validation: null },
    ], investigation }] };
    expect(reviewProgress(review)).toEqual({
      phase: 'Current pass: Validation',
      facts: ['2 of 5 commit explanations saved', '2 changed files reported assessed in this run', '2 candidate claims recorded'],
      gaps: [],
    });
    expect(reviewProgress(review, true)).toEqual({ phase: 'Validation pass', facts: ['2/5', '2 files', '2 claims'], gaps: [] });
    expect(reviewProgress({ ...review, runs: [{ ...review.runs[0], steps: [...review.runs[0].steps.slice(0, 1),
      { ...review.runs[0].steps[1], repair: true }] }] })?.phase).toBe('Current pass: Validation · correcting report');
  });

  it('keeps a new discovery checkpoint separate from the preceding validation result', () => {
    const running = runningReviewFixture();
    const investigation: ReviewInvestigationDto = { changeMap: 'Trace the changed path.', reviewedFiles: ['src/A.java'],
      contextEvidenceRefs: [], perspectives: [], candidates: [candidateFixture('First hypothesis')], validations: [], answers: [] };
    const validation: NonNullable<ReviewStepDto['validation']> = { investigationStep: 1, candidates: [{ candidate: 1, outcome: 'NEEDS_EVIDENCE',
      conclusion: 'A boundary check is missing.', evidenceRefs: ['E1'], counterevidence: 'Checked the guard.', findingId: null,
      findingRevision: null, request: { question: 'Does the boundary fail?', check: 'Run a focused test.', resolvesWhen: 'The output shows the result.' } }],
      coverage: { complete: false, omissions: ['Boundary behavior unresolved'], warnings: ['Remote check unavailable'], recordedAt: '2026-09-06T16:15:00Z' },
      newCandidates: [], selectedFindingIds: [], selectionExplanation: '' };
    const review: AgentReviewDto = { ...running, runs: [{ ...running.runs[0], investigation, steps: [
      { number: 1, kind: 'INVESTIGATE', repair: false, investigation, validation: null },
      { number: 2, kind: 'VALIDATE', repair: false, investigation: null, validation },
      { number: 3, kind: 'COLLECT_EVIDENCE', repair: false, investigation: null, validation: null },
    ] }] };
    expect(reviewProgress(review)).toEqual({
      phase: 'Current pass: Evidence collection',
      facts: ['2 of 5 commit explanations saved', '1 changed file reported assessed in this run', '1 candidate claim recorded'],
      gaps: [],
    });
  });

  it('does not carry an earlier validation into a new evidence investigation', () => {
    const running = runningReviewFixture();
    const investigation: ReviewInvestigationDto = { changeMap: 'Trace the changed path.', reviewedFiles: ['src/A.java'],
      contextEvidenceRefs: [], perspectives: [], candidates: [candidateFixture('First hypothesis')], validations: [], answers: [] };
    const answer: ReviewInvestigationDto['answers'][number] = {
      candidate: 1, attempted: 'Checked the boundary.', evidenceRefs: ['E2'], limitation: null,
    };
    const oldValidation: NonNullable<ReviewStepDto['validation']> = { investigationStep: 1,
      candidates: [{ candidate: 1, outcome: 'NEEDS_EVIDENCE', conclusion: 'More evidence needed.', evidenceRefs: ['E1'],
        counterevidence: '', findingId: null, findingRevision: null,
        request: { question: 'Does it fail?', check: 'Run the boundary test.', resolvesWhen: 'The output settles it.' } }],
      coverage: { complete: false, omissions: ['Boundary unresolved'], warnings: [], recordedAt: '2026-09-06T16:15:00Z' },
      newCandidates: [], selectedFindingIds: [], selectionExplanation: '' };
    const review: AgentReviewDto = { ...running, runs: [{ ...running.runs[0], investigation: { ...investigation, answers: [answer] }, steps: [
      { number: 1, kind: 'INVESTIGATE', repair: false, investigation, validation: null },
      { number: 2, kind: 'VALIDATE', repair: false, investigation: null, validation: oldValidation },
      { number: 3, kind: 'COLLECT_EVIDENCE', repair: false, investigation: { ...investigation, answers: [answer] }, validation: null },
      { number: 4, kind: 'VALIDATE', repair: false, investigation: null, validation: null },
    ] }] };
    expect(reviewProgress(review)).toEqual({
      phase: 'Current pass: Validation',
      facts: ['2 of 5 commit explanations saved', '1 changed file reported assessed in this run', '1 candidate claim recorded'],
      gaps: [],
    });
  });

  it('does not present a superseded validation report as the current result during repair', () => {
    const running = runningReviewFixture();
    const investigation: ReviewInvestigationDto = { changeMap: 'Trace the changed path.', reviewedFiles: ['src/A.java'],
      contextEvidenceRefs: [], perspectives: [], candidates: [candidateFixture('First hypothesis')], validations: [], answers: [] };
    const oldValidation: NonNullable<ReviewStepDto['validation']> = { investigationStep: 1,
      candidates: [{ candidate: 1, outcome: 'SUPPORTED', conclusion: 'Earlier conclusion.', evidenceRefs: ['E1'],
        counterevidence: '', findingId: 'finding-1', findingRevision: 1, request: null }],
      coverage: { complete: true, omissions: [], warnings: ['Earlier unavailable check'], recordedAt: '2026-09-06T16:15:00Z' },
      newCandidates: [], selectedFindingIds: [], selectionExplanation: '' };
    const review: AgentReviewDto = { ...running, runs: [{ ...running.runs[0], investigation, steps: [
      { number: 1, kind: 'INVESTIGATE', repair: false, investigation, validation: null },
      { number: 2, kind: 'VALIDATE', repair: false, investigation: null, validation: oldValidation },
      { number: 3, kind: 'VALIDATE', repair: true, investigation: null, validation: null },
    ] }] };
    expect(reviewProgress(review)).toEqual({
      phase: 'Current pass: Validation · correcting report',
      facts: ['2 of 5 commit explanations saved', '1 changed file reported assessed in this run', '1 candidate claim recorded'],
      gaps: [],
    });
  });

  it('distinguishes an accepted result from the latest failed attempt even when an earlier result remains readable', () => {
    const accepted = reviewFixture();
    expect(reviewProgress(accepted)?.facts).toContain('Complete review scope recorded');
    const failed: AgentReviewDto = { ...accepted, runs: [...accepted.runs, { ...accepted.runs[0], id: 'run-2',
      state: 'INCOMPLETE', summary: null, coverage: null, steps: [], investigation: null, outcome: 'No report' }] };
    expect(reviewProgress(failed)).toEqual({
      phase: 'Review pass not recorded', facts: ['5 commits in this review'], gaps: ['Complete review result not accepted'],
    });
  });
});

describe('run selection', () => {
  it('adopts only the latest accepted complete explicit refresh without replacing the findings baseline', () => {
    const original = reviewFixture();
    const refresh = explanationRunFixture({ headSha: 'a'.repeat(40) });
    const next = { ...original, observedHeadSha: refresh.headSha, runs: [...original.runs, refresh] };
    expect(explanationRun(parseAgentReview(next))?.id).toBe(refresh.id);
    expect(latestRun(next)?.id).toBe(original.runs[0].id);
    expect(newCommits(next)).toBe(true);
    expect(provisional(next)).toBe(false);
    expect(openFindings(next)).toEqual(openFindings(original));
    expect(reviewStatus(next).status).toBe('newcommits');
    const laterReview: AgentReviewDto['runs'][number] = { ...original.runs[0], id: 'later-review', ordinal: 3, summary: null };
    const laterRefresh = { ...refresh, id: 'later-refresh', ordinal: 4 };
    expect(explanationRun({ ...next, runs: [...next.runs, laterReview] })?.id).toBe(refresh.id);
    expect(explanationRun({ ...next, runs: [...next.runs, laterReview, laterRefresh] })?.id).toBe(laterRefresh.id);
  });

  it.each(['RUNNING', 'FAILED', 'STOPPED', 'INCOMPLETE'] as const)('retains the prior complete explanation through a %s refresh', state => {
    const original = reviewFixture();
    const accepted = explanationRunFixture();
    const attempted = explanationRunFixture({ id: 'next-explanation', ordinal: 3, state });
    expect(explanationRun({ ...original, runs: [...original.runs, attempted] })?.id).toBe(original.runs[0].id);
    expect(explanationRun({ ...original, runs: [...original.runs, accepted, attempted] })?.id).toBe(accepted.id);
  });

  it('rejects an accepted refresh with incomplete commit explanations', () => {
    const original = reviewFixture();
    const refresh = explanationRunFixture();
    expect(explanationRun({ ...original, runs: [...original.runs, { ...refresh, summary: refresh.summary === null ? null
      : { ...refresh.summary, commits: refresh.summary.commits.slice(1) } }] })?.id).toBe(original.runs[0].id);
  });

  it('keeps refresh execution busy without presenting it as finding review or losing the saved review progress', () => {
    const original = reviewFixture();
    const refresh = explanationRunFixture({ state: 'RUNNING', endedAt: null });
    const value: AgentReviewDto = { ...original, runs: [...original.runs, refresh], activeTurn: null, followUpPaused: false };
    expect(reviewRunning(value)).toBe(true);
    expect(reviewStatus(value)).toMatchObject({ label: 'Refreshing explanation', busy: true });
    expect(reviewProgress(value)).toEqual(reviewProgress(original));
    expect(reviewElapsed(value, Date.parse(refresh.createdAt) + 9000)).toBe('9s');
    expect(reviewRequests(value).find(request => request.turnId === refresh.turnIds[0])).toMatchObject({
      action: 'Refresh explanation', target: { kind: 'card', runId: refresh.id, cardId: `${refresh.id}:details` },
    });
    const failed = { ...value, runs: [...original.runs, { ...refresh, state: 'FAILED' as const }] };
    expect(reviewStatus(failed)).toEqual(reviewStatus(original));
  });

  it('keeps the current run separate from the original change explanation', () => {
    const review = reviewFixture();
    expect(explanationRun(review)?.id).toBe('run-1');
    expect(latestRun(review)?.id).toBe('run-1');
    expect(explanationRun(reviewFixture({ runs: [] }))).toBeNull();
    const current: AgentReviewDto['runs'][number] = { ...review.runs[0], id: 'run-2', ordinal: 2, summary: null, state: 'FAILED',
      headSha: 'a'.repeat(40), coverage: null };
    const updated = { ...review, observedHeadSha: current.headSha, runs: [...review.runs, current] };
    expect(latestRun(updated)).toBe(current);
    expect(explanationRun(updated)?.id).toBe('run-1');
    expect(provisional(updated)).toBe(true);
    expect(newCommits(updated)).toBe(false);
  });

  it('selects the earliest complete explanation using its own commit identities regardless of run outcome', () => {
    const review = reviewFixture();
    const first = review.runs[0];
    const summary = first.summary;
    if (summary === null) throw new Error('Fixture needs a complete explanation');
    const later = { ...first, id: 'run-2', ordinal: 2 };
    expect(explanationRun({ ...review, runs: [later, { ...first, state: 'FAILED' }] })?.id).toBe(first.id);
    for (const commits of [summary.commits.slice(1), [...summary.commits, summary.commits[0]],
      summary.commits.map(() => summary.commits[0]), summary.commits.map((commit, index) => index === 0 ? { ...commit, sha: 'unknown' } : commit)]) {
      expect(explanationRun({ ...review, runs: [{ ...first, summary: { ...summary, commits } }, later] })?.id).toBe(later.id);
    }
  });

  it('marks results provisional until their run is accepted', () => {
    expect(provisional(reviewFixture())).toBe(false);
    expect(provisional(runningReviewFixture())).toBe(true);
  });
});

describe('the elapsed clock', () => {
  it('counts up while the run works and fixes when it ends', () => {
    const running = runningReviewFixture();
    const started = Date.parse(running.runs[0].createdAt);
    expect(reviewElapsed(running, started + 125_000)).toBe('2m 05s');
    expect(reviewElapsed(running, started + 9_000)).toBe('9s');
    expect(reviewElapsed(reviewFixture(), started)).toMatch(/^finished /);
  });

  it('uses the latest attempt time without calling a failed or stopped review finished', () => {
    const review = reviewFixture();
    const endedAt = '2026-09-06T18:45:00Z';
    const ended = new Date(endedAt).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
    for (const state of ['INCOMPLETE', 'FAILED', 'STOPPED'] as const) {
      const latest: AgentReviewDto['runs'][number] = { ...review.runs[0], id: 'retry-run', state, summary: null, coverage: null, endedAt };
      expect(reviewElapsed({ ...review, runs: [...review.runs, latest] }, Date.now())).toBe(`ended ${ended}`);
    }
    expect(reviewElapsed({ ...review, runs: [{ ...review.runs[0], coverage: null, endedAt }] }, Date.now())).toBe(`ended ${ended}`);
  });
});

describe('publication eligibility', () => {
  const review = reviewFixture();
  const finding = review.findings[0];

  it('lets an accepted, open, current finding be published', () => {
    expect(findingPublication(review, finding)).toEqual({ kind: 'publishable' });
    expect(findingPill(review, finding)).toEqual({ text: 'Private', tone: 'neutral' });
  });

  it('never offers a whole-PR finding again once a submitted review carried its text', () => {
    const whole: typeof finding = { ...finding, path: null };
    const submitted = { ...review, draft: { ...draft([]), postedSummaryFindingIds: [whole.id] } };
    expect(findingPublication(submitted, whole)).toEqual({ kind: 'blocked', reason: 'Posted in your review summary' });
    expect(findingPill(submitted, whole)).toEqual({ text: 'Accepted', tone: 'ready' });
  });

  it('blocks a provisional result from publication', () => {
    const running = runningReviewFixture();
    const publication = findingPublication(running, running.findings[0]);
    expect(publication.kind).toBe('blocked');
    expect(publication).toMatchObject({ reason: 'Publishing unlocks when validation and complete coverage are accepted' });
  });

  it('does not promote a new hypothesis merely because an earlier accepted run shares its head', () => {
    const latest: AgentReviewDto['runs'][number] = { ...review.runs[0], id: 'run-2', state: 'RUNNING', summary: null, coverage: null };
    const hypothesis = { ...finding, id: 'new-hypothesis', runId: latest.id };
    expect(findingPublication({ ...review, runs: [...review.runs, latest], findings: [hypothesis] }, hypothesis))
      .toMatchObject({ kind: 'blocked', reason: 'Publishing unlocks when validation and complete coverage are accepted' });
  });

  it('recognizes exact accepted validation after a finding is reassessed at a newer commit', () => {
    if (review.runs[0].coverage === null) throw new Error('Fixture requires completed coverage');
    const reassessed = { ...finding, validatedHeadSha: 'e'.repeat(40) };
    const validated: AgentReviewDto['runs'][number] = { ...review.runs[0], id: 'run-2', headSha: reassessed.validatedHeadSha,
      steps: [{ number: 1, kind: 'VALIDATE', repair: false, investigation: null, validation: { investigationStep: 1,
        candidates: [{ candidate: 1, outcome: 'SUPPORTED', conclusion: 'Verified at the new commit', evidenceRefs: [], counterevidence: '',
          findingId: finding.id, findingRevision: finding.revision, request: null }], coverage: review.runs[0].coverage, newCandidates: [], selectedFindingIds: [], selectionExplanation: '' } }] };
    const accepted = { ...review, runs: [...review.runs, validated], findings: [reassessed] };
    expect(findingPublication(accepted, reassessed)).toEqual({ kind: 'publishable' });
    expect(findingPublication(accepted, { ...reassessed, revision: 2 }).kind).toBe('blocked');
  });

  it('keeps a pinned outgoing comment editable after its source finding is reassessed', () => {
    const changed = { ...finding, runId: 'run-2', revision: 2, validatedHeadSha: 'e'.repeat(40), outdated: true };
    const item = draftItem();
    const carrying = reviewFixture({ findings: [changed], draft: draft([item]), runs: [...review.runs,
      { ...review.runs[0], id: 'run-2', state: 'RUNNING', summary: null, coverage: null, headSha: changed.validatedHeadSha }] });
    expect(findingPublication(carrying, changed)).toEqual({ kind: 'draft', item });
  });

  it.each(['RESOLVED', 'WITHDRAWN'] as const)('keeps a saved comment pending after its source finding is %s', state => {
    const changed = { ...finding, state, revision: 2, body: 'The agent changed its conclusion' };
    const item = draftItem({ body: 'My saved comment' });
    const carrying = reviewFixture({ findings: [changed], draft: draft([item]) });
    expect(findingPublication(carrying, changed)).toEqual({ kind: 'draft', item });
    expect(pendingDraftCount(carrying.draft)).toBe(1);
  });

  it('keeps an accepted earlier finding publishable while a newer run is active or incomplete', () => {
    for (const state of ['RUNNING', 'INCOMPLETE'] as const) {
      const latest: AgentReviewDto['runs'][number] = { ...review.runs[0], id: 'run-2', state, summary: null, coverage: null };
      const attempted: AgentReviewDto = { ...review, runs: [...review.runs, latest] };
      expect(explanationRun(attempted)?.id).toBe('run-1');
      expect(latestRun(attempted)?.id).toBe('run-2');
      expect(findingPublication(attempted, finding)).toEqual({ kind: 'publishable' });
    }
  });

  it('preserves publication at the reviewed commit after the head or base moves', () => {
    const newHead = '5e17b02a1b2c3d4e5f60718293a4b5c6d7e8f905';
    const acceptedLater: AgentReviewDto = { ...review, observedHeadSha: newHead, runs: [...review.runs,
      { ...review.runs[0], id: 'run-2', state: 'ACCEPTED', headSha: newHead }] };
    expect(findingPublication(acceptedLater, finding)).toEqual({ kind: 'publishable' });
    expect(findingPublication({ ...review, observedHeadSha: newHead }, finding)).toEqual({ kind: 'publishable' });
    expect(findingPublication({ ...review, runs: [...review.runs, { ...review.runs[0], id: 'run-2', baseSha: 'e'.repeat(40) }] }, finding))
      .toEqual({ kind: 'publishable' });
  });

  it('excludes a locally resolved finding and offers it back through Reopen', () => {
    const resolved = { ...finding, state: 'RESOLVED' as const, stateReason: 'Known limitation' };
    const publication = findingPublication(withFindings(resolved), resolved);
    expect(publication).toEqual({ kind: 'blocked', reason: 'Excluded from publication' });
    expect(findingPill(withFindings(resolved), resolved)).toEqual({ text: 'Resolved locally', tone: 'neutral' });
  });

  it('excludes a withdrawn finding', () => {
    const withdrawn = { ...finding, state: 'WITHDRAWN' as const };
    expect(findingPublication(withFindings(withdrawn), withdrawn).kind).toBe('blocked');
    expect(findingPill(withFindings(withdrawn), withdrawn)).toEqual({ text: 'Withdrawn', tone: 'neutral' });
    expect(openFindings(withFindings(withdrawn))).toHaveLength(0);
  });

  it('keeps a historically accepted finding publishable while awaiting reassessment', () => {
    const outdated = { ...finding, outdated: true };
    expect(findingPublication(withFindings(outdated), outdated))
      .toEqual({ kind: 'publishable' });
    expect(findingPill(withFindings(outdated), outdated)).toEqual({ text: 'Needs reassessment', tone: 'warn' });
  });

  it('shows a promoted finding as pending, and a sent one as accepted', () => {
    const pending = reviewFixture({ draft: draft([draftItem()]) });
    expect(findingPublication(pending, finding).kind).toBe('draft');
    expect(findingPill(pending, finding)).toEqual({ text: 'Pending · in your review', tone: 'warn' });

    const sent = reviewFixture({
      draft: draft([draftItem({ published: true, publication: { state: 'PUBLISHED', remoteReviewId: 1, remoteCommentId: 2, url: null, at: '2026-09-06T16:32:00Z' } })]),
    });
    expect(findingPublication(sent, finding).kind).toBe('published');
    expect(findingPill(sent, finding)).toEqual({ text: 'Accepted', tone: 'ready' });
  });

  it('keeps a published finding published even after the user resolves it locally', () => {
    const sent = reviewFixture({
      findings: [{ ...finding, state: 'RESOLVED' }],
      draft: draft([draftItem({ published: true, publication: { state: 'PUBLISHED', remoteReviewId: 1, remoteCommentId: 2, url: null, at: '2026-09-06T16:32:00Z' } })]),
    });
    expect(findingPublication(sent, sent.findings[0]).kind).toBe('published');
    expect(findingPill(sent, sent.findings[0])).toEqual({ text: 'Accepted', tone: 'ready' });
  });

  it.each(['UNCERTAIN', 'RESERVED'] as const)('does not treat %s delivery as published', state => {
    const pending = reviewFixture({ draft: draft([draftItem({ publication: {
      state, remoteReviewId: null, remoteCommentId: null, url: null, at: '2026-09-06T16:32:00Z',
    } })]) });
    expect(findingPublication(pending, finding).kind).toBe(state === 'UNCERTAIN' ? 'uncertain' : 'sending');
    expect(findingPill(pending, finding).text).toBe(state === 'UNCERTAIN' ? 'Delivery unconfirmed' : 'Sending…');
    expect(findingInResults({ ...pending, runs: [] }, finding)).toBe(true);
  });

  it('counts only included, unsent comments toward Submit review', () => {
    expect(pendingDraftCount(null)).toBe(0);
    expect(pendingDraftCount(draft([
      draftItem(),
      draftItem({ id: 'item-2', included: false }),
      draftItem({ id: 'item-3', published: true }),
    ]))).toBe(1);
  });

  it('finds a finding’s outgoing item by the finding it came from', () => {
    const pending = draft([draftItem()]);
    expect(draftItemFor(pending, 'finding-1')?.id).toBe('item-1');
    expect(draftItemFor(pending, 'finding-9')).toBeNull();
    expect(draftItemFor(null, 'finding-1')).toBeNull();
  });

  it('recovers an unpinned agent comment commit only from its unchanged source revision', () => {
    expect(draftCommentCommit(draftItem({ commitId: null }), review.findings)).toBe(finding.validatedHeadSha);
    expect(draftCommentCommit(draftItem({ commitId: null, findingRevision: 2 }), review.findings)).toBeNull();
    expect(draftCommentCommit(draftItem({ commitId: 'original-commit' }), [{ ...finding, validatedHeadSha: 'new-head' }])).toBe('original-commit');
    expect(draftCommentCommit(draftItem({ source: 'MANUAL', findingId: null, findingRevision: null, commitId: null }), [])).toBeNull();
  });
});

describe('discussion', () => {
  it('separates the summary thread from each finding’s', () => {
    const review = reviewFixture();
    expect(messagesFor(review, null)).toHaveLength(2);
    expect(messagesFor(review, 'finding-1')).toHaveLength(0);
  });

  it('marks a question queued while its queue row is still waiting, including a retry', () => {
    const review = reviewFixture({ queuedInputIds: ['q1'] });
    const waiting: ReviewMessageDto = { ...review.messages[0], turnId: null, queuedInputId: 'q1' };
    expect(queued(review, waiting)).toBe(true);
    expect(queued(review, { ...waiting, turnId: 'turn-3' })).toBe(true);
    expect(queued(reviewFixture(), waiting)).toBe(false);
  });

  it('says the question will queue while the agent is busy', () => {
    expect(askQueues(reviewFixture())).toBe(false);
    expect(askLabel(reviewFixture())).toBe('Ask reviewer');
    expect(askQueues(runningReviewFixture())).toBe(true);
    expect(askLabel(runningReviewFixture())).toBe('Queue question');
  });
});

describe('text helpers', () => {
  it('shortens a sha and keeps a short one whole', () => {
    expect(shortSha('d9a0c41a1b2c3d4e')).toBe('d9a0c41');
    expect(shortSha('abc')).toBe('abc');
  });

  it('shortens a path from the left so the file name survives, on a separator', () => {
    const path = 'plugin/catalog/src/main/java/com/example/iceberg/IcebergSortField.java';
    const short = shortPath(path);
    expect(short.startsWith('…')).toBe(true);
    expect(short.endsWith('IcebergSortField.java')).toBe(true);
    expect(short.length).toBeLessThanOrEqual(45);
    expect(shortPath('a/b.java')).toBe('a/b.java');
  });
});


describe('review request topics', () => {
  it('uses saved card/run identities and retains the requested revision when the report changes', () => {
    const review = reviewFixture();
    const run = review.runs[0];
    const message = { ...review.messages[0], runId: run.id, cardId: `${run.id}:limitations` };
    const requests = reviewRequests({ ...review, runs: [run, { ...run, id: 'new-run', headSha: 'f'.repeat(40) }], messages: [message,
      { ...message, id: 'reply', body: 'Follow up' },
      { ...message, id: 'finding', findingId: review.findings[0].id, cardId: null },
      { ...message, id: 'commit', cardId: `${run.id}:commit:${run.commits[0].sha}` },
      { ...message, id: 'missing', cardId: 'unavailable-card', runId: 'unavailable-run' },
      { ...message, id: 'answer', author: 'AGENT' },
    ] });
    expect(requests).toHaveLength(6);
    expect(requests[0]).toMatchObject({ action: 'Ask about review', topic: 'Limitations', detail: 'Private · Revision 1 · Reviewed head d9a0c41' });
    expect(requests[1]).toMatchObject({ action: 'Reply to review', body: 'Follow up' });
    expect(requests[2].topic).toBe(`${review.findings[0].label} · ${review.findings[0].title}`);
    expect(requests[3].topic).toBe(`${shortSha(run.commits[0].sha)} · ${run.commits[0].message}`);
    expect(requests[4].topic).toBe('Review summary');
    const olderQuestion: ReviewMessageDto = { ...message, findingId: review.findings[0].id, cardId: null };
    expect(reviewRequests({ ...review, findings: [{ ...review.findings[0], revision: 2, title: 'Revised finding' }],
      messages: [olderQuestion] })[0].topic).toBe(`${review.findings[0].label} · Review finding`);
  });
});


describe('review request navigation and generated turns', () => {
  it('labels generated local and replay requests without a null PR number and keeps user questions separate', () => {
    const local = reviewFixture({ number: null, sourceTaskId: 'source-task' });
    const requests = reviewRequests(local);
    expect(requests.filter(request => request.generated)).toHaveLength(1);
    expect(requests.find(request => request.generated)).toMatchObject({ action: 'Review changes', topic: `${local.repo} · Local changes` });
    expect(requests.find(request => request.action === 'Ask about review')?.generated).toBeUndefined();
    const replay: AgentReviewDto = { ...local, sourceTaskId: null, replay: { id: 'replay', number: 7, memory: false } };
    expect(reviewRequests(replay).find(request => request.generated)?.topic).toBe(`${local.repo} · Past change`);
  });

  it('opens current review details for an update that reuses the original explanation', () => {
    const review = reviewFixture();
    const current: AgentReviewDto['runs'][number] = { ...review.runs[0], id: 'run-2', ordinal: 2, summary: null, turnIds: ['update-turn'] };
    expect(reviewRequests({ ...review, runs: [...review.runs, current] }).find(request => request.turnId === 'update-turn')?.target)
      .toEqual({ kind: 'card', runId: current.id, cardId: `${current.id}:details` });
  });

  it('renders initial, incremental and completion-repair requests from durable turn identities', () => {
    const review = reviewFixture();
    const original = review.runs[0];
    const retried: ReviewStepDto = { number: 1, kind: 'INVESTIGATE', repair: true, investigation: null, validation: null };
    const updated = { ...original, id: 'run-2', ordinal: 2, scope: 'INCREMENTAL' as const,
      headSha: 'e'.repeat(40), commits: [original.commits[0]], changedFiles: 1, turnIds: ['update-turn', 'repair-turn'], steps: [retried] };
    const requests = reviewRequests({ ...review, messages: [], runs: [original, updated],
      byteQuayTurnIds: [...original.turnIds, 'update-turn', 'repair-turn', 'missing-run-turn'] });
    expect(requests).toHaveLength(4);
    expect(requests[0]).toMatchObject({ action: 'Review pull request', topic: `${review.repo}#${review.number}`,
      body: `Review ${original.commits.length} commits across ${original.changedFiles} files.`, turnId: original.turnIds[0],
      target: { kind: 'card', runId: original.id, cardId: `${original.id}:purpose` } });
    expect(requests[1]).toMatchObject({ action: 'Review new commits', body: 'Review 1 new commit across 1 file.',
      turnId: 'update-turn', detail: 'Private · Review 2 · Head eeeeeee',
      target: { kind: 'card', runId: updated.id, cardId: `${updated.id}:purpose` } });
    expect(requests[2]).toMatchObject({ action: 'Retry investigation', body: 'Map the change and record candidates.',
      turnId: 'repair-turn', target: { kind: 'card', runId: updated.id, cardId: `${updated.id}:purpose` } });
    expect(requests[3]).toMatchObject({ action: 'Review pull request', body: 'Review the pull request.', turnId: 'missing-run-turn' });
    expect(requests[3].target).toBeUndefined();
    expect(reviewRequests({ ...review, messages: [], byteQuayTurnIds: [] })).toHaveLength(1);
  });

  it('names each follow-up turn after the step it ran, telling a rerun step from a replaced stale report', () => {
    const review = reviewFixture();
    const step = (number: number, kind: ReviewStepDto['kind'], repair = false): ReviewStepDto => ({ number, kind, repair, investigation: null, validation: null });
    const run = { ...review.runs[0], turnIds: ['t1', 't2', 't3', 't4', 't5', 't6'],
      steps: [step(1, 'INVESTIGATE'), step(2, 'VALIDATE', true), step(3, 'COLLECT_EVIDENCE'), step(4, 'VALIDATE'), step(5, 'VALIDATE', true)] };
    const requests = reviewRequests({ ...review, messages: [], byteQuayTurnIds: [], runs: [run] });
    expect(requests.map(request => request.action)).toEqual(
      ['Review pull request', 'Validate candidates', 'Retry validation', 'Collect evidence', 'Validate candidates', 'Retry validation']);
    expect(requests[3].body).toBe('Gather evidence for each open candidate.');
    expect(requests.map(request => request.icon)).toEqual([undefined, ListChecks, ListChecks, undefined, ListChecks, ListChecks]);
  });

  it('keeps original discussion targets after a later report and groups unscoped summary replies with purpose', () => {
    const review = reviewFixture();
    const run = review.runs[0];
    const user: ReviewMessageDto = { ...review.messages[0], runId: run.id, cardId: null };
    const requests = reviewRequests({ ...review, runs: [run, { ...run, id: 'later-run', turnIds: ['later-turn'] }], messages: [
      user,
      { ...user, id: 'purpose-reply', cardId: `${run.id}:purpose` },
      { ...user, id: 'commit-question', cardId: `${run.id}:commit:${run.commits[0].sha}` },
      { ...user, id: 'finding-question', findingId: review.findings[0].id },
      { ...user, id: 'agent-answer', author: 'AGENT' },
    ] });
    expect(requests[0].target).toEqual({ kind: 'card', runId: run.id, cardId: `${run.id}:purpose` });
    expect(requests[1]).toMatchObject({ action: 'Reply to review', target: requests[0].target });
    expect(requests[2].target).toEqual({ kind: 'card', runId: run.id, cardId: `${run.id}:commit:${run.commits[0].sha}` });
    expect(requests[3].target).toEqual({ kind: 'finding', runId: run.id, findingId: review.findings[0].id });
    expect(requests.filter(request => request.body === user.body)).toHaveLength(4);
  });

  it('does not classify ordinary user turns from text or agent reply associations', () => {
    const review = reviewFixture();
    const requests = reviewRequests({ ...review, byteQuayTurnIds: [], runs: [], messages: [
      { ...review.messages[0], author: 'AGENT', turnId: 'ordinary-turn', body: 'Review pull request' },
    ] });
    expect(requests).toEqual([]);
  });
});


describe('sealed result selection', () => {
  it('shows only selected automatic findings and accepted discussion findings, retaining every record', () => {
    const original = reviewFixture();
    const source = original.runs[0];
    const review: AgentReviewDto = { ...original, runs: [{ ...source, steps: source.steps.map(step => ({ ...step,
      validation: step.validation === null ? null : { ...step.validation, selectedFindingIds: ['finding-1'] },
    })) }] };
    expect(openFindings(review).map(finding => finding.id)).toEqual(['finding-1']);
    expect(review.findings).toHaveLength(3);
    expect(findingPublication(review, review.findings[1]).kind).toBe('publishable');
    expect(findingInResults(review, { ...review.findings[1], state: 'RESOLVED' })).toBe(false);
    expect(findingInResults(review, { ...review.findings[0], state: 'RESOLVED' })).toBe(true);
    expect(findingInResults(review, { ...review.findings[1], origin: 'DISCUSSION' })).toBe(true);
    expect(findingInResults({ ...review, runs: [{ ...review.runs[0], state: 'RUNNING' }] }, review.findings[0])).toBe(false);
    expect(findingInResults({ ...review, runs: [{ ...review.runs[0], state: 'INCOMPLETE' }] }, { ...review.findings[1], origin: 'DISCUSSION' })).toBe(false);
    expect(findingInResults({ ...review, draft: draft([draftItem({ findingId: 'finding-2', published: false })]) }, review.findings[1])).toBe(false);
    expect(findingInResults({ ...review, draft: draft([draftItem({ findingId: 'finding-2', published: true })]) }, review.findings[1])).toBe(true);
    const updated: AgentReviewDto = { ...review, runs: [...review.runs, { ...source, id: 'run-2', state: 'RUNNING', steps: [] }] };
    expect(openFindings(updated).map(finding => finding.id)).toEqual(['finding-1']);
  });

  it('preserves selected discussion edits but requires exact final support to publish unselected findings', () => {
    const original = reviewFixture();
    const source = original.runs[0];
    const review: AgentReviewDto = { ...original, runs: [{ ...source, steps: source.steps.map(step => ({ ...step,
      validation: step.validation === null ? null : { ...step.validation, selectedFindingIds: ['finding-1'] },
    })) }] };
    expect(findingPublication(review, { ...review.findings[0], revision: 2 }).kind).toBe('publishable');
    expect(findingPublication(review, { ...review.findings[1], revision: 2 }).kind).toBe('blocked');
    const sealed = source.steps[0].validation;
    if (sealed === null) throw new Error('Fixture needs a sealed validation');
    const rejected = { ...review, runs: [{ ...review.runs[0], steps: [...review.runs[0].steps, { ...source.steps[0], number: 4,
      validation: { ...sealed, selectedFindingIds: ['finding-1'], candidates: [] },
    }] }] };
    expect(findingPublication(rejected, review.findings[1]).kind).toBe('blocked');
  });
});

describe('a review on the Summary timeline', () => {
  it('marks the passes before the current one done, and all of them once coverage is accepted', () => {
    const run = runningReviewFixture().runs[0];
    const step = (number: number, kind: ReviewStepDto['kind']): ReviewStepDto => ({ number, kind, repair: false, investigation: null, validation: null });
    expect(reviewPasses({ ...run, steps: [] })).toEqual(['active', 'todo', 'todo']);
    expect(reviewPasses({ ...run, steps: [step(1, 'INVESTIGATE'), step(2, 'VALIDATE')] })).toEqual(['done', 'done', 'active']);
    // Validation asked for more evidence: it is no longer done.
    expect(reviewPasses({ ...run, steps: [step(1, 'INVESTIGATE'), step(2, 'VALIDATE'), step(3, 'COLLECT_EVIDENCE')] })).toEqual(['done', 'active', 'todo']);
    expect(reviewPasses(reviewFixture().runs[0])).toEqual(['done', 'done', 'done']);
  });

  it('groups evidence by the pull request, file or issue it was read from', () => {
    const evidence = (ref: string, source: string, excerpt = '') => ({ ref, source, provenance: '', excerpt, explanation: '' });
    const groups = evidenceGroups([
      evidence('E1', 'owner/repository#31198 metadata', 'Fix deduplication\nAuthor: someone'),
      evidence('E2', 'core/planner/ScopeAware.java L1–L198 (frozen head 6466c90)'),
      evidence('E3', 'owner/repository#31198 activity', 'octocat commented at 2026-10-08T17:07:30Z\nLooks right'),
      evidence('E10', 'owner/repository#31178: TABLESAMPLE projections get deduplicated'),
      evidence('E11', 'owner/repository#31178 activity'),
      evidence('E12', 'History owner/repository#31000'),
      evidence('E13', 'command output: ./mvnw test -Dtest=TestScope#testEquals', '$ ./mvnw test'),
    ], 'owner/repository#31198');
    expect(groups.map(group => [group.title, group.subject, group.items.map(item => item.evidence.ref).join(' ')])).toEqual([
      ['This pull request', 'owner/repository#31198', 'E1 E3'],
      ['Source', 'core/planner/ScopeAware.java', 'E2'],
      ['Linked issue', 'owner/repository#31178', 'E10 E11'],
      ['Linked discussion', 'owner/repository#31000', 'E12'],
      ['Other context', '', 'E13'],
    ]);
    expect(groups[0].items.map(item => [item.label, item.detail])).toEqual([['Metadata', 'Fix deduplication'], ['Activity', 'octocat commented at 2026-10-08T17:07:30Z']]);
    expect(groups[1].items[0]).toMatchObject({ label: 'L1–L198', detail: 'frozen head 6466c90' });
    expect(groups[2].items[0].label).toBe('TABLESAMPLE projections get deduplicated');
    expect(groups[3].items[0].label).toBe('History');
    expect(groups[4].items[0]).toMatchObject({ label: 'command output: ./mvnw test -Dtest=TestScope#testEquals', detail: '$ ./mvnw test' });
  });

  it('reads the recorded facts out of a provenance line and keeps its prose', () => {
    expect(provenanceFacts('Selected pull request owner/repository#31198 read by ByteQuay; sourceUpdatedAt=2026-10-08T17:07:30Z; stale=false; cache freshness is not remote currentness')).toEqual({
      notes: 'Selected pull request owner/repository#31198 read by ByteQuay; cache freshness is not remote currentness',
      facts: [['Source updated at', '2026-10-08T17:07:30Z'], ['Stale', 'false']],
    });
  });
});
