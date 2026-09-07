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
import type {
  AgentReviewDto,
  ReviewDraftDto,
  ReviewDraftItemDto,
  ReviewFindingDto,
  ReviewMessageDto,
  ReviewRunDto,
} from './contracts';

/**
 * What the review is doing, derived from task, turn and result facts. There is no stored review
 * lifecycle beyond open and closed; everything here is read from what already happened.
 */
export type ReviewStatus =
  | 'running'
  | 'approval'
  | 'queued'
  | 'ready'
  | 'nofindings'
  | 'incomplete'
  | 'recovery'
  | 'newcommits'
  | 'stopped'
  | 'ended';

export type ReviewStatusView = {
  status: ReviewStatus;
  /** The word shown in the status strip, and the tone class the strip and chip share. */
  label: string;
  tone: 'running' | 'warn' | 'ready' | 'danger' | 'neutral';
  detail: string;
  /** The compact chip appended to the tab, or null when the tab shows no chip. */
  chip: string | null;
  busy: boolean;
};

export function latestRun(review: AgentReviewDto): ReviewRunDto | null {
  return review.runs.length === 0 ? null : review.runs[review.runs.length - 1];
}

export function acceptedRun(review: AgentReviewDto): ReviewRunDto | null {
  return review.runs.filter(run => run.state === 'ACCEPTED').at(-1) ?? null;
}

/** The run whose summary and findings the pane shows: the newest one that has anything to show. */
export function shownRun(review: AgentReviewDto): ReviewRunDto | null {
  const latest = latestRun(review);
  if (latest !== null && (latest.summary !== null || latest.state === 'RUNNING')) return latest;
  return acceptedRun(review) ?? latest;
}

export function reviewRunning(review: AgentReviewDto): boolean {
  return review.state === 'OPEN'
    && review.activeTurn !== null
    && (review.activeTurn.state === 'RUNNING' || review.activeTurn.state === 'ACCEPTED');
}

/** A turn can end before its provider is verified stopped; its retained claim still blocks execution. */
export function reviewRecoveryRequired(review: AgentReviewDto): boolean {
  return review.state === 'OPEN' && review.task.state === 'OPEN'
    && (review.task.mutationClaim === 'RECOVERY' || (review.task.mutationClaim === 'TURN' && !reviewRunning(review)));
}

/** Findings the user can still act on; a withdrawn one is history, not a card to publish. */
export function openFindings(review: AgentReviewDto): ReviewFindingDto[] {
  return review.findings.filter(finding => finding.state !== 'WITHDRAWN');
}

/**
 * A provisional result is one from a run that has not been accepted: it is shown as it arrives so
 * the wait is not blank, and it is never publishable.
 */
export function provisional(review: AgentReviewDto): boolean {
  const run = shownRun(review);
  return run !== null && run.state !== 'ACCEPTED';
}

export function newCommits(review: AgentReviewDto): boolean {
  const run = shownRun(review);
  return run !== null
    && review.observedHeadSha !== null
    && review.observedHeadSha !== run.headSha;
}

export function reviewStatus(review: AgentReviewDto): ReviewStatusView {
  const run = shownRun(review);
  const findings = openFindings(review);
  const open = findings.filter(finding => finding.state === 'OPEN').length;
  const findingChip = findings.length === 0
    ? null
    : open === findings.length
      ? `${findings.length} ${findings.length === 1 ? 'finding' : 'findings'}`
      : `${open} open`;
  if (review.state === 'CLOSED') {
    return { status: 'ended', label: 'Ended', tone: 'neutral', detail: 'the review is closed; its findings and discussion stay', chip: findingChip, busy: false };
  }
  if (reviewRecoveryRequired(review)) {
    return { status: 'recovery', label: 'Recovery required', tone: 'danger', detail: 'provider shutdown could not be verified', chip: 'Recovery required', busy: false };
  }
  if (reviewRunning(review)) {
    if (review.pendingApprovals > 0) {
      return { status: 'approval', label: 'Waiting for approval', tone: 'warn', detail: 'the run waits until you answer', chip: 'Needs approval', busy: false };
    }
    const queued = review.queuedInputIds.length;
    const detail = run === null
      ? 'starting the review'
      : `reviewing ${run.commits.length} ${run.commits.length === 1 ? 'commit' : 'commits'} at ${shortSha(run.headSha)}`;
    return queued > 0
      ? { status: 'queued', label: 'Running', tone: 'running', detail: `${queued} ${queued === 1 ? 'question' : 'questions'} queued`, chip: `Running · ${queued} queued`, busy: true }
      : { status: 'running', label: 'Running', tone: 'running', detail, chip: 'Running', busy: true };
  }
  if (review.followUpPaused && run !== null && run.state === 'STOPPED') {
    return { status: 'stopped', label: 'Stopped', tone: 'neutral', detail: 'follow-up paused · discussion open', chip: 'Stopped', busy: false };
  }
  if (run !== null && (run.state === 'INCOMPLETE' || run.state === 'FAILED')) {
    return { status: 'incomplete', label: 'Incomplete', tone: 'danger', detail: run.outcome ?? 'coverage was not recorded', chip: 'Incomplete', busy: false };
  }
  if (newCommits(review)) {
    return {
      status: 'newcommits',
      label: 'New commits',
      tone: 'warn',
      detail: run === null ? 'a newer head is waiting' : `reviewed ${shortSha(run.headSha)} · remote head ${shortSha(review.observedHeadSha ?? '')}`,
      chip: 'New commits',
      busy: false,
    };
  }
  if (run !== null && run.state === 'ACCEPTED') {
    const coverage = run.coverage;
    const scope = `${run.commits.length} ${run.commits.length === 1 ? 'commit' : 'commits'} · ${run.changedFiles} ${run.changedFiles === 1 ? 'file' : 'files'}`;
    const complete = coverage?.complete === true ? 'complete coverage' : 'partial coverage';
    return findings.length === 0
      ? { status: 'nofindings', label: 'Ready', tone: 'ready', detail: `${complete} · no inline findings`, chip: 'No findings', busy: false }
      : { status: 'ready', label: 'Ready', tone: 'ready', detail: `${complete} · ${scope}`, chip: findingChip, busy: false };
  }
  return { status: 'running', label: 'Starting', tone: 'running', detail: 'preparing the review', chip: 'Running', busy: true };
}

/**
 * The run's own clock: how long it has been working, or when it finished. Measured from the run's
 * recorded timestamps, never from a timer the renderer keeps.
 */
export function reviewElapsed(review: AgentReviewDto, now: number): string | null {
  if (reviewRecoveryRequired(review)) return null;
  const run = shownRun(review);
  if (run === null) return null;
  if (run.endedAt !== null) {
    const ended = Date.parse(run.endedAt);
    return Number.isNaN(ended) ? null : `finished ${new Date(ended).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}`;
  }
  const started = Date.parse(run.createdAt);
  if (Number.isNaN(started)) return null;
  const seconds = Math.max(0, Math.floor((now - started) / 1000));
  const minutes = Math.floor(seconds / 60);
  return minutes === 0 ? `${seconds}s` : `${minutes}m ${String(seconds % 60).padStart(2, '0')}s`;
}

export function shortSha(sha: string): string {
  return sha.length > 7 ? sha.slice(0, 7) : sha;
}

/** Whether the agent can take a question right now, or the question has to queue behind a turn. */
export function askQueues(review: AgentReviewDto): boolean {
  return reviewRunning(review);
}

/** The composer's primary label: the same control queues when the agent is busy, and says so. */
export function askLabel(review: AgentReviewDto): string {
  return askQueues(review) ? 'Queue question' : 'Ask agent';
}

export function messagesFor(review: AgentReviewDto, findingId: string | null): ReviewMessageDto[] {
  return review.messages.filter(message => (message.findingId ?? null) === findingId);
}

/** A question that reached the queue but not yet a turn is still waiting; the thread says so. */
export function queued(review: AgentReviewDto, message: ReviewMessageDto): boolean {
  return message.queuedInputId !== null
    && message.turnId === null
    && review.queuedInputIds.includes(message.queuedInputId);
}

export function draftItemFor(draft: ReviewDraftDto | null, findingId: string): ReviewDraftItemDto | null {
  return draft?.items.find(item => item.findingId === findingId) ?? null;
}

export type FindingPublication =
  | { kind: 'publishable' }
  | { kind: 'draft'; item: ReviewDraftItemDto }
  | { kind: 'published'; item: ReviewDraftItemDto }
  | { kind: 'blocked'; reason: string };

/**
 * What the finding's outgoing state is, and why it cannot be published when it cannot.
 *
 * <p>Publishing is refused for a provisional result, a locally resolved or withdrawn finding, and
 * one whose lines no longer map. The backend refuses the same cases again at the send boundary.
 */
export function findingPublication(review: AgentReviewDto, finding: ReviewFindingDto): FindingPublication {
  const item = draftItemFor(review.draft, finding.id);
  if (item !== null && item.published) return { kind: 'published', item };
  if (finding.state === 'WITHDRAWN') return { kind: 'blocked', reason: 'The agent withdrew this finding' };
  if (finding.state === 'RESOLVED') return { kind: 'blocked', reason: 'Excluded from publication' };
  if (finding.outdated) return { kind: 'blocked', reason: 'Not publishable until re-anchored' };
  if (provisional(review)) return { kind: 'blocked', reason: 'Publishing unlocks when the run records coverage' };
  if (item !== null) return { kind: 'draft', item };
  return { kind: 'publishable' };
}

/** The pill on a finding card header; at most one, in the order the design ranks them. */
export function findingPill(review: AgentReviewDto, finding: ReviewFindingDto): { text: string; tone: 'neutral' | 'warn' | 'ready' | 'danger' } {
  const publication = findingPublication(review, finding);
  if (finding.state === 'WITHDRAWN') return { text: 'Withdrawn', tone: 'neutral' };
  if (finding.state === 'RESOLVED') return { text: 'Resolved locally', tone: 'neutral' };
  if (publication.kind === 'published') return { text: 'Published', tone: 'ready' };
  if (finding.outdated) return { text: 'Outdated', tone: 'warn' };
  if (publication.kind === 'draft') return { text: 'Pending · in your review', tone: 'warn' };
  return { text: 'Private', tone: 'neutral' };
}

/** How many promoted comments the Submit review count includes, alongside manual ones. */
export function pendingDraftCount(draft: ReviewDraftDto | null): number {
  return draft?.items.filter(item => item.included && !item.published).length ?? 0;
}

/**
 * A path shortened from the left, so the file name always survives. The full path stays in the
 * element's title, which is where a truncated path is still readable.
 */
export function shortPath(path: string, maximum = 44): string {
  if (path.length <= maximum) return path;
  const tail = path.slice(-(maximum - 1));
  const cut = tail.indexOf('/');
  return `…${cut === -1 ? tail : tail.slice(cut)}`;
}
