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
import { describe, expect, it } from 'vitest';
import {
  askLabel,
  askQueues,
  draftItemFor,
  findingPill,
  findingPublication,
  latestRun,
  messagesFor,
  newCommits,
  openFindings,
  pendingDraftCount,
  provisional,
  queued,
  reviewElapsed,
  reviewRecoveryRequired,
  reviewRequests,
  reviewRunning,
  reviewStatus,
  shortPath,
  shortSha,
  shownRun,
} from './model';
import { reviewFixture, runningReviewFixture } from './fixtures/review';
import type { AgentReviewDto, ReviewDraftDto, ReviewFindingDto, ReviewMessageDto } from './contracts';

function withFindings(...findings: ReviewFindingDto[]): AgentReviewDto {
  return reviewFixture({ findings });
}

function draft(items: ReviewDraftDto['items']): ReviewDraftDto {
  return { repo: 'trinodb/trino', number: 30304, body: '', event: 'COMMENT', items, version: 1, updatedAt: '2026-09-06T16:30:00Z' };
}

function draftItem(overrides: Partial<ReviewDraftDto['items'][number]> = {}): ReviewDraftDto['items'][number] {
  return {
    id: 'item-1',
    source: 'AGENT_FINDING',
    findingId: 'finding-1',
    findingRevision: 1,
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
    expect(reviewStatus({ ...collecting, queuedInputIds: ['q1'] }).chip).toBe('Reviewing · 1 queued');
    expect(reviewStatus({ ...validating, activeTurn: { id: 'discussion-turn', state: 'RUNNING', startedAt: null, cancellationRequestedAt: null } }).label).toBe('Running');
    expect(reviewRequests(validating).find(request => request.turnId === 'turn-1')?.action).toBe('Follow-up review');
  });

  it('keeps incomplete accepted coverage provisional and never reports a clean result', () => {
    const original = reviewFixture();
    for (const coverage of [null, { complete: false, omissions: [], recordedAt: '2026-09-06T16:15:00Z' },
      { complete: true, omissions: ['Skipped the cleanup paths'], recordedAt: '2026-09-06T16:15:00Z' }]) {
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
    expect(shownRun(failed)?.id).toBe('run-1');
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

describe('run selection', () => {
  it('shows the newest run that has something to show', () => {
    const review = reviewFixture();
    expect(shownRun(review)?.id).toBe('run-1');
    expect(latestRun(review)?.id).toBe('run-1');
    expect(shownRun(reviewFixture({ runs: [] }))).toBeNull();
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

  it('blocks a provisional result from publication', () => {
    const running = runningReviewFixture();
    const publication = findingPublication(running, running.findings[0]);
    expect(publication.kind).toBe('blocked');
    expect(publication).toMatchObject({ reason: 'Publishing unlocks when validation and complete coverage are accepted' });
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

  it('blocks an outdated finding until it is re-anchored', () => {
    const outdated = { ...finding, outdated: true };
    expect(findingPublication(withFindings(outdated), outdated))
      .toEqual({ kind: 'blocked', reason: 'Not publishable until re-anchored' });
    expect(findingPill(withFindings(outdated), outdated)).toEqual({ text: 'Outdated', tone: 'warn' });
  });

  it('shows a promoted finding as pending, and a sent one as published', () => {
    const pending = reviewFixture({ draft: draft([draftItem()]) });
    expect(findingPublication(pending, finding).kind).toBe('draft');
    expect(findingPill(pending, finding)).toEqual({ text: 'Pending · in your review', tone: 'warn' });

    const sent = reviewFixture({
      draft: draft([draftItem({ published: true, publication: { state: 'PUBLISHED', remoteReviewId: 1, remoteCommentId: 2, url: null, at: '2026-09-06T16:32:00Z' } })]),
    });
    expect(findingPublication(sent, finding).kind).toBe('published');
    expect(findingPill(sent, finding)).toEqual({ text: 'Published', tone: 'ready' });
  });

  it('keeps a published finding published even after the user resolves it locally', () => {
    const sent = reviewFixture({
      findings: [{ ...finding, state: 'RESOLVED' }],
      draft: draft([draftItem({ published: true, publication: { state: 'PUBLISHED', remoteReviewId: 1, remoteCommentId: 2, url: null, at: '2026-09-06T16:32:00Z' } })]),
    });
    expect(findingPublication(sent, sent.findings[0]).kind).toBe('published');
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
    expect(askLabel(reviewFixture())).toBe('Ask agent');
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
    const path = 'plugin/trino-iceberg/src/main/java/io/trino/plugin/iceberg/IcebergSortField.java';
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
  it('renders initial, incremental and completion-repair requests from durable turn identities', () => {
    const review = reviewFixture();
    const original = review.runs[0];
    const updated = { ...original, id: 'run-2', ordinal: 2, scope: 'INCREMENTAL' as const,
      headSha: 'e'.repeat(40), commits: [original.commits[0]], changedFiles: 1, turnIds: ['update-turn', 'repair-turn'] };
    const requests = reviewRequests({ ...review, messages: [], runs: [original, updated],
      byteQuayTurnIds: [...original.turnIds, 'update-turn', 'repair-turn', 'missing-run-turn'] });
    expect(requests).toHaveLength(4);
    expect(requests[0]).toMatchObject({ action: 'Review pull request', topic: `${review.repo}#${review.number}`,
      body: `Review ${original.commits.length} commits across ${original.changedFiles} files.`, turnId: original.turnIds[0],
      target: { kind: 'card', runId: original.id, cardId: `${original.id}:purpose` } });
    expect(requests[1]).toMatchObject({ action: 'Review new commits', body: 'Review 1 new commit across 1 file.',
      turnId: 'update-turn', detail: 'Private · Review 2 · Head eeeeeee',
      target: { kind: 'card', runId: updated.id, cardId: `${updated.id}:purpose` } });
    expect(requests[2]).toMatchObject({ action: 'Follow-up review', body: 'Continue the investigation, validate suspected issues, and complete the review report.',
      turnId: 'repair-turn', target: { kind: 'card', runId: updated.id, cardId: `${updated.id}:purpose` } });
    expect(requests[3]).toMatchObject({ action: 'Review pull request', body: 'Review the pull request.', turnId: 'missing-run-turn' });
    expect(requests[3].target).toBeUndefined();
    expect(reviewRequests({ ...review, messages: [], byteQuayTurnIds: [] })).toHaveLength(1);
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
