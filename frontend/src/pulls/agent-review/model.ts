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
import type { ReviewRequest, ReviewRequestTarget } from '../../agents/ReviewRequestCard';
import type { AgentEventEnvelope } from '../../agents/events';
import { formatDuration } from '../../agents/transcript';
import { shortSha } from '@bytequay/ui/displayText';
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
type ReviewStatus =
  | 'prepared'
  | 'running'
  | 'approval'
  | 'queued'
  | 'ready'
  | 'nofindings'
  | 'failed'
  | 'recovery'
  | 'newcommits'
  | 'stopped'
  | 'ended';

type ReviewStatusView = {
  status: ReviewStatus;
  /** The word shown in the status strip, and the tone class the strip and chip share. */
  label: string;
  tone: 'running' | 'warn' | 'ready' | 'danger' | 'neutral';
  detail: string;
  /** The compact chip appended to the tab, or null when the tab shows no chip. */
  chip: string | null;
  busy: boolean;
};

type ReviewProgressView = {
  phase: string;
  facts: string[];
  gaps: string[];
};

export function latestRun(review: AgentReviewDto): ReviewRunDto | null {
  return review.runs.findLast(run => !isExplanationRun(run)) ?? null;
}

/** Delayed reads must not undo Resolve/Reopen; finding versions advance independently of the review. */
export function mergeReviewFindings(current: AgentReviewDto | null, updated: AgentReviewDto): AgentReviewDto {
  if (current === null || current.id !== updated.id) return updated;
  const findings = new Map(current.findings.map(finding => [finding.id, finding]));
  return { ...updated, findings: updated.findings.map(finding => {
    const latest = findings.get(finding.id);
    return latest !== undefined && latest.version > finding.version ? latest : finding;
  }) };
}

export function isExplanationRun(run: ReviewRunDto): boolean {
  return run.steps[0]?.kind === 'EXPLAIN';
}

export function latestExplanationRun(review: AgentReviewDto): ReviewRunDto | null {
  return review.runs.findLast(isExplanationRun) ?? null;
}

export function completeCoverage(run: ReviewRunDto): boolean {
  return run.coverage?.complete === true && run.coverage.omissions.length === 0;
}

/** Only an accepted explicit refresh replaces the first complete explanation. */
export function explanationRun(review: AgentReviewDto): ReviewRunDto | null {
  const complete = review.runs.filter(run => run.summary !== null
    && run.summary.commits.length === run.commits.length
    && new Set(run.summary.commits.map(commit => commit.sha)).size === run.commits.length
    && run.summary.commits.every(explanation => run.commits.some(commit => commit.sha === explanation.sha)));
  return complete.filter(run => isExplanationRun(run) && run.state === 'ACCEPTED')
    .reduce<ReviewRunDto | null>((latest, run) => latest === null || run.ordinal > latest.ordinal ? run : latest, null)
    ?? complete.filter(run => !isExplanationRun(run))
    .reduce<ReviewRunDto | null>((first, run) => first === null || run.ordinal < first.ordinal ? run : first, null);
}

export function reviewRunning(review: AgentReviewDto): boolean {
  return review.state === 'OPEN'
    && ((review.activeTurn !== null && (review.activeTurn.state === 'RUNNING' || review.activeTurn.state === 'ACCEPTED'))
      || (!review.followUpPaused && review.runs.at(-1)?.state === 'RUNNING' && review.task.mutationClaim === null));
}

/** A turn can end before its provider is verified stopped; its retained claim still blocks execution. */
export function reviewRecoveryRequired(review: AgentReviewDto): boolean {
  return review.state === 'OPEN' && review.task.state === 'OPEN'
    && (review.task.mutationClaim === 'RECOVERY' || (review.task.mutationClaim === 'TURN' && !reviewRunning(review)));
}

/** Automatic cards use the sealed selection; explicit discussion and user history remain visible. */
export function findingInResults(review: AgentReviewDto, finding: ReviewFindingDto): boolean {
  if (review.draft?.items.some(item => item.findingId === finding.id && (item.published || item.publication !== null))) return true;
  const source = review.runs.find(run => run.id === finding.runId);
  if (source?.state !== 'ACCEPTED' || !completeCoverage(source)) return false;
  return finding.origin === 'DISCUSSION'
    || source.steps.findLast(step => step.validation !== null)?.validation?.selectedFindingIds.includes(finding.id) === true;
}

/** Selected and discussion findings the user can still act on. */
export function openFindings(review: AgentReviewDto): ReviewFindingDto[] {
  return review.findings.filter(finding => finding.state !== 'WITHDRAWN' && findingInResults(review, finding));
}

/**
 * A provisional result is one from a run that has not been accepted: it is shown as it arrives so
 * the wait is not blank, and it is never publishable.
 */
export function provisional(review: AgentReviewDto): boolean {
  const run = latestRun(review);
  return run !== null && (run.state !== 'ACCEPTED' || !completeCoverage(run));
}

function liveScope(review: AgentReviewDto): boolean {
  return review.state === 'OPEN' && (review.pullRequest === null || review.pullRequest.state.toLowerCase() === 'open');
}

export function newCommits(review: AgentReviewDto): boolean {
  const run = latestRun(review);
  const head = review.observedHeadSha;
  return liveScope(review) && run !== null && head !== null && head !== run.headSha;
}

export function reviewWorktreeNotice(review: AgentReviewDto): { label: string; message: string } | null {
  const sync = review.worktreeSync;
  if (sync == null || !liveScope(review)) return null;
  const run = latestRun(review);
  const assessment = run === null ? '' : ` Review results still cover ${shortSha(run.headSha)}.`;
  if (sync.state === 'CURRENT') {
    if (!newCommits(review)) return null;
    return { label: 'Working copy up to date', message: `Working copy is at the latest PR commit ${shortSha(sync.headSha ?? '')}.${assessment} Update review to assess the new changes.` };
  }
  if (sync.state === 'PENDING') {
    return { label: 'Working copy waiting to sync', message: `The PR has changed. The working copy will sync when the review is idle.${assessment}` };
  }
  if (sync.state === 'BLOCKED') {
    return { label: 'Working copy is stale', message: `${sync.dirty ? 'Saved local changes are preserved; this branch is out of date.' : sync.reason ?? 'Local work prevents automatic sync.'} Use Update review to catch up and review the PR.` };
  }
  return { label: 'Working copy sync unavailable', message: `${sync.reason ?? 'The working copy could not be synced.'} Use Update review to retry.` };
}

export function reviewStatus(review: AgentReviewDto): ReviewStatusView {
  const run = latestRun(review);
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
    const explanation = latestExplanationRun(review);
    const explaining = explanation?.state === 'RUNNING' && (review.activeTurn === null || explanation.turnIds.includes(review.activeTurn.id));
    const label = explaining ? 'Refreshing explanation' : run?.state === 'RUNNING' && (review.activeTurn === null || run.turnIds.includes(review.activeTurn.id))
      ? 'Reviewing' : 'Running';
    const detail = explaining ? `explaining ${explanation.commits.length} ${explanation.commits.length === 1 ? 'commit' : 'commits'} at ${shortSha(explanation.headSha)}` : run === null
      ? 'starting the review'
      : `reviewing ${run.commits.length} ${run.commits.length === 1 ? 'commit' : 'commits'} at ${shortSha(run.headSha)}`;
    return queued > 0
      ? { status: 'queued', label, tone: 'running', detail: `${queued} ${queued === 1 ? 'question' : 'questions'} queued`, chip: `${label} · ${queued} queued`, busy: true }
      : { status: 'running', label, tone: 'running', detail, chip: label, busy: true };
  }
  if (run === null) {
    return { status: 'prepared', label: 'Ready to explore', tone: 'neutral', detail: 'working copy ready · agent review not started', chip: null, busy: false };
  }
  if (review.followUpPaused && run !== null && (run.state === 'STOPPED' || run.state === 'RUNNING')) {
    return { status: 'stopped', label: 'Stopped', tone: 'neutral', detail: 'follow-up paused · discussion open', chip: 'Stopped', busy: false };
  }
  if (run !== null && (run.state === 'INCOMPLETE' || run.state === 'FAILED' || (run.state === 'ACCEPTED' && !completeCoverage(run)))) {
    return { status: 'failed', label: 'Could not finish', tone: 'danger', detail: run.outcome ?? 'The agent did not save a complete review result.', chip: 'Could not finish', busy: false };
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
    const scope = `${run.commits.length} ${run.commits.length === 1 ? 'commit' : 'commits'} · ${run.changedFiles} ${run.changedFiles === 1 ? 'file' : 'files'}`;
    return findings.length === 0
      ? { status: 'nofindings', label: 'Finished', tone: 'ready', detail: review.findings.length > 0 ? 'complete coverage · no findings selected' : 'complete coverage · no inline findings', chip: review.findings.length > 0 ? 'None selected' : 'No findings', busy: false }
      : { status: 'ready', label: 'Finished', tone: 'ready', detail: `complete coverage · ${scope}`, chip: findingChip, busy: false };
  }
  return { status: 'running', label: 'Reviewing', tone: 'running', detail: 'preparing the review', chip: 'Reviewing', busy: true };
}

/** Summarizes saved reports from the latest attempt without estimating work or treating file counts as proof. */
export function reviewProgress(review: AgentReviewDto, compact = false): ReviewProgressView | null {
  const run = latestRun(review);
  if (run === null) return null;
  const step = run.steps.at(-1);
  const phaseName = step === undefined ? 'Preparation' : {
    INVESTIGATE: 'Investigation', COLLECT_EVIDENCE: 'Evidence collection', VALIDATE: 'Validation', EXPLAIN: 'Explanation',
  }[step.kind];
  const phase = step === undefined && run.state !== 'RUNNING' ? 'Review pass not recorded'
    : `${compact ? `${phaseName} pass` : `${run.state === 'RUNNING' ? 'Current pass' : 'Last pass'}: ${phaseName}`}${step?.repair ? ' · correcting report' : ''}`;
  const commits = new Set(run.commits.map(commit => commit.sha));
  const explained = new Set(run.summary?.commits.filter(commit => commits.has(commit.sha)).map(commit => commit.sha) ?? []);
  const originalExplanation = explanationRun(review);
  const facts = [originalExplanation !== null && originalExplanation.id !== run.id
    ? `${run.commits.length} ${run.commits.length === 1 ? 'commit' : 'commits'}${compact ? '' : ' in this review'}`
    : compact ? `${explained.size}/${run.commits.length}` : `${explained.size} of ${run.commits.length} commit explanations saved`];
  const investigationStep = run.steps.findLast(value => value.investigation !== null);
  const collecting = step !== undefined && step.kind !== 'VALIDATE' && step.investigation === null;
  const investigation = collecting ? run.investigation : investigationStep?.investigation ?? run.investigation;
  if (investigation !== null) {
    facts.push(compact ? `${investigation.reviewedFiles.length} ${investigation.reviewedFiles.length === 1 ? 'file' : 'files'}`
      : `${investigation.reviewedFiles.length} changed ${investigation.reviewedFiles.length === 1 ? 'file' : 'files'} reported assessed in this run`);
  }
  // A new validation assignment supersedes a sealed report being corrected, even before it has a replacement.
  const validation = collecting || step?.kind === 'VALIDATE' && step.validation === null ? undefined : run.steps.findLast(value => value.validation !== null
    && (investigationStep === undefined || value.validation?.investigationStep === investigationStep.number))?.validation;
  const conclusions = validation?.candidates ?? (run.steps.length === 0 ? investigation?.validations : undefined);
  if (investigation !== null) {
    const candidates = conclusions?.length ?? investigation.candidates.length;
    facts.push(compact ? `${candidates} ${candidates === 1 ? 'claim' : 'claims'}` : `${candidates} candidate ${candidates === 1 ? 'claim' : 'claims'} recorded`);
  }
  if (conclusions !== undefined) {
    const supported = conclusions.filter(value => value.outcome === 'SUPPORTED').length;
    const rejected = conclusions.filter(value => value.outcome === 'REJECTED').length;
    if (!compact && supported + rejected > 0) facts.push(`${supported} supported · ${rejected} rejected`);
  }
  const coverage = run.coverage ?? validation?.coverage;
  const gaps: string[] = [];
  const requested = conclusions?.filter(value => value.outcome === 'NEEDS_EVIDENCE').length ?? 0;
  if (requested > 0) gaps.push(`${requested} specific ${requested === 1 ? 'check' : 'checks'} requested`);
  if (coverage !== undefined && coverage !== null) {
    if (coverage.warnings.length > 0) gaps.push(`${coverage.warnings.length} unavailable ${coverage.warnings.length === 1 ? 'check' : 'checks'}`);
    if (coverage.omissions.length > 0) gaps.push(`${coverage.omissions.length} unresolved or unreviewed ${coverage.omissions.length === 1 ? 'item' : 'items'}`);
  }
  if (run.state === 'ACCEPTED' && coverage?.complete === true && coverage.omissions.length === 0) {
    if (!compact) facts.push('Complete review scope recorded');
  }
  else if (run.state === 'INCOMPLETE' || run.state === 'FAILED') {
    gaps.push('Complete review result not accepted');
  }
  else if (run.state === 'STOPPED') {
    gaps.push('Review stopped before acceptance');
  }
  return { phase, facts, gaps };
}

/**
 * The run's own clock: how long it has been working, or when it finished. Measured from the run's
 * recorded timestamps, never from a timer the renderer keeps.
 */
export function reviewElapsed(review: AgentReviewDto, now: number): string | null {
  if (reviewRecoveryRequired(review)) return null;
  const explanation = latestExplanationRun(review);
  const run = explanation?.state === 'RUNNING' && reviewRunning(review) ? explanation : latestRun(review);
  if (run === null) return null;
  if (run.endedAt !== null) {
    const ended = Date.parse(run.endedAt);
    const label = run.state === 'ACCEPTED' && completeCoverage(run) ? 'finished' : 'ended';
    return Number.isNaN(ended) ? null : `${label} ${new Date(ended).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}`;
  }
  const started = Date.parse(run.createdAt);
  if (Number.isNaN(started)) return null;
  return formatDuration(Math.max(0, now - started));
}



/** What a run covers, as "Full review · master → 6466c90". */
export function reviewScope(run: ReviewRunDto): string {
  return `${run.scope === 'FULL' ? 'Full review' : 'Incremental review'} · ${run.priorHeadSha === null ? run.baseRef : shortSha(run.priorHeadSha)} → ${shortSha(run.headSha)}`;
}

const PASS_ORDER = ['INVESTIGATE', 'COLLECT_EVIDENCE', 'VALIDATE'] as const;

/**
 * Where a review run stands in its three passes. Validation can send a run back to collect more evidence,
 * so a pass after the current one is never shown as done.
 */
export function reviewPasses(run: ReviewRunDto): Array<'done' | 'active' | 'todo'> {
  if (run.state === 'ACCEPTED' && completeCoverage(run)) return PASS_ORDER.map(() => 'done');
  const kind = run.steps.at(-1)?.kind;
  const current = Math.max(0, PASS_ORDER.findIndex(pass => pass === kind));
  return PASS_ORDER.map((_, index) => index < current ? 'done' : index === current ? 'active' : 'todo');
}

type ReviewEvidence = ReviewRunDto['evidence'][number];

export type ReviewEvidenceGroup = {
  title: string;
  /** The pull request, issue or file the items were read from; empty for evidence with no single subject. */
  subject: string;
  items: Array<{ evidence: ReviewEvidence; label: string; detail: string }>;
};

/**
 * Evidence grouped by what it was read from, in the order the agent first read each subject. The source line is
 * the only place a subject is named, so the groups follow the forms the review tools write there.
 */
export function evidenceGroups(evidence: ReviewEvidence[], pullRequest: string | null): ReviewEvidenceGroup[] {
  const groups = new Map<string, ReviewEvidenceGroup>();
  for (const item of evidence) {
    const file = /^(.+) (L\d+–L\d+) \((.+)\)$/.exec(item.source);
    const thread = file === null ? /^(History )?(\S+#\d+)(?:(:?) (.+))?$/s.exec(item.source) : null;
    const subject = file?.[1] ?? thread?.[2] ?? '';
    let group = groups.get(subject);
    if (group === undefined) {
      group = { subject, items: [], title: file !== null ? 'Source' : thread === null ? 'Other context'
        : subject === pullRequest ? 'This pull request' : 'Linked discussion' };
      groups.set(subject, group);
    }
    // Only an issue read by its number is recorded with its title.
    if (thread?.[3] === ':' && group.title === 'Linked discussion') group.title = 'Linked issue';
    const part = thread?.[4] ?? (thread?.[1] === undefined ? '' : 'History');
    group.items.push({
      evidence: item,
      label: file?.[2] ?? (part === '' ? item.source : part.charAt(0).toUpperCase() + part.slice(1)),
      detail: file?.[3] ?? item.excerpt.trim().split('\n', 1)[0],
    });
  }
  return [...groups.values()];
}

/** A provenance line as its recorded `key=value` facts, with the prose around them kept as written. */
export function provenanceFacts(provenance: string): { notes: string; facts: Array<[string, string]> } {
  const notes: string[] = [];
  const facts: Array<[string, string]> = [];
  for (const part of provenance.split('; ')) {
    const fact = /^([A-Za-z]+)=(.*)$/s.exec(part);
    if (fact === null) notes.push(part);
    else facts.push([fact[1].replace(/[A-Z]/g, letter => ` ${letter.toLowerCase()}`).replace(/^./, first => first.toUpperCase()), fact[2]]);
  }
  return { notes: notes.join('; '), facts };
}

/** Whether the agent can take a question right now, or the question has to queue behind a turn. */
export function askQueues(review: AgentReviewDto): boolean {
  return reviewRunning(review);
}

/** The composer's primary label: the same control queues when the agent is busy, and says so. */
export function askLabel(review: AgentReviewDto): string {
  return askQueues(review) ? 'Queue question' : 'Ask reviewer';
}

export function messagesFor(review: AgentReviewDto, findingId: string | null): ReviewMessageDto[] {
  return review.messages.filter(message => (message.findingId ?? null) === findingId);
}

/** Live queue membership includes paused attempts that already received a turn identity. */
export function queued(review: AgentReviewDto, message: ReviewMessageDto): boolean {
  return message.queuedInputId !== null && review.queuedInputIds.includes(message.queuedInputId);
}

export function findingReviewText(finding: ReviewFindingDto): string {
  return `${finding.title.trim()}\n\n${finding.body.trim()}`;
}

export function draftItemFor(draft: ReviewDraftDto | null, findingId: string): ReviewDraftItemDto | null {
  return draft?.items.find(item => item.findingId === findingId) ?? null;
}

export function draftCommentCommit(item: ReviewDraftItemDto, findings: ReviewFindingDto[]): string | null {
  return item.commitId ?? (item.source === 'AGENT_FINDING'
    ? findings.find(finding => finding.id === item.findingId && finding.revision === item.findingRevision)?.validatedHeadSha ?? null : null);
}

type FindingPublication =
  | { kind: 'publishable' }
  | { kind: 'inReviewText' }
  | { kind: 'draft'; item: ReviewDraftItemDto }
  | { kind: 'published'; item: ReviewDraftItemDto }
  | { kind: 'sending'; item: ReviewDraftItemDto }
  | { kind: 'uncertain'; item: ReviewDraftItemDto }
  | { kind: 'blocked'; reason: string };

/**
 * What the finding's outgoing state is, and why it cannot be published when it cannot.
 *
 * Publishing requires an accepted source for the finding. Comments stay anchored at that
 * reviewed commit even when a newer PR head or base is available.
 */
export function findingPublication(review: AgentReviewDto, finding: ReviewFindingDto): FindingPublication {
  if (review.number === null) return { kind: 'blocked', reason: 'Private local review' };
  const item = draftItemFor(review.draft, finding.id);
  if (item?.publication?.state === 'UNCERTAIN') return { kind: 'uncertain', item };
  if (item?.publication?.state === 'RESERVED') return { kind: 'sending', item };
  if (item !== null && item.published) return { kind: 'published', item };
  if (item !== null) return { kind: 'draft', item };
  if (review.draft?.postedSummaryFindingIds.includes(finding.id)) return { kind: 'blocked', reason: 'Posted in your review summary' };
  if (finding.state === 'WITHDRAWN') return { kind: 'blocked', reason: 'The agent withdrew this finding' };
  if (finding.state === 'RESOLVED') return { kind: 'blocked', reason: 'Excluded from publication' };
  const accepted = review.runs.some(run => run.state === 'ACCEPTED' && completeCoverage(run) && run.worktreeTreeSha === null && run.headSha === finding.validatedHeadSha
      && ((run.id === finding.runId && (finding.origin === 'DISCUSSION'
        || run.steps.findLast(step => step.validation !== null)?.validation?.selectedFindingIds.includes(finding.id)))
        || run.steps.findLast(step => step.validation !== null)?.validation?.candidates.some(candidate => candidate.outcome === 'SUPPORTED'
          && candidate.findingId === finding.id && candidate.findingRevision === finding.revision)));
  if (!accepted) {
    return { kind: 'blocked', reason: 'Publishing unlocks when validation and complete coverage are accepted' };
  }
  // A finding about the whole pull request has no line to comment on: it joins the review's own text instead of becoming an item.
  if (finding.path === null && review.draft !== null && review.draft.body.includes(finding.body.trim())) return { kind: 'inReviewText' };
  return { kind: 'publishable' };
}

/** The pill on a finding card header; at most one, in the order the design ranks them. */
export function findingPill(review: AgentReviewDto, finding: ReviewFindingDto): { text: string; tone: 'neutral' | 'warn' | 'ready' | 'danger' } {
  const publication = findingPublication(review, finding);
  if (publication.kind === 'published' || review.draft?.postedSummaryFindingIds.includes(finding.id)) return { text: 'Accepted', tone: 'ready' };
  if (finding.state === 'WITHDRAWN') return { text: 'Withdrawn', tone: 'neutral' };
  if (finding.state === 'RESOLVED') return { text: 'Resolved locally', tone: 'neutral' };
  if (publication.kind === 'uncertain') return { text: 'Delivery unconfirmed', tone: 'warn' };
  if (publication.kind === 'sending') return { text: 'Sending…', tone: 'neutral' };
  if (finding.outdated) return { text: 'Needs reassessment', tone: 'warn' };
  if (draftItemFor(review.draft, finding.id) !== null
    || (finding.path === null && review.draft?.body.includes(finding.body.trim()))) return { text: 'Pending · in your review', tone: 'warn' };
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

/** History may arrive before the review saves the input's discussion-card identity. */
export function reviewConversationEvents(events: AgentEventEnvelope[], requests: ReviewRequest[], reviewSequence: number): AgentEventEnvelope[] {
  const requestTurns = new Set(requests.map(request => request.turnId));
  const requestEvents = new Set(requests.map(request => request.messageEventId));
  const visible = events.filter(event => event.type !== 'user_message' || event.sequence <= reviewSequence
    || requestEvents.has(event.eventId) || (event.payload.deliveryKind !== 'STEER' && event.turnId !== null && requestTurns.has(event.turnId)));
  return visible.length === events.length ? events : visible;
}

/** Saved discussion and delivery identities select cards, never words in the generated prompt. */
export function reviewRequests(review: AgentReviewDto): ReviewRequest[] {
  const seen = new Set<string>();
  const questions = review.messages.filter(message => message.author === 'USER').map((message): ReviewRequest => {
    const finding = review.findings.find(item => item.id === message.findingId);
    const run = review.runs.find(item => item.id === (message.runId ?? finding?.runId));
    const section = run && message.cardId?.startsWith(`${run.id}:`) ? message.cardId.slice(run.id.length + 1) : null;
    const titles: Record<string, string> = {
      purpose: 'Change intention', 'combined-result': 'Combined result', evidence: 'Supporting evidence & tests',
      coverage: 'Review coverage', 'critical-issues': 'Critical issues', limitations: 'Limitations',
    };
    const commit = section?.startsWith('commit:') ? run?.commits.find(item => item.sha === section.slice(7)) : undefined;
    const topic = finding ? `${finding.label} · ${finding.revision === message.targetRevision ? finding.title : 'Review finding'}`
      : commit ? `${shortSha(commit.sha)} · ${commit.message.split('\n')[0]}`
        : titles[section ?? ''] ?? (message.findingId !== null ? 'Review finding' : 'Review summary');
    const target: ReviewRequestTarget | undefined = run ? message.findingId !== null
      ? { kind: 'finding', runId: run.id, findingId: message.findingId }
      : { kind: 'card', runId: run.id, cardId: message.cardId ?? `${run.id}:purpose` } : undefined;
    const targetKey = JSON.stringify(target ?? [message.runId, message.findingId, message.cardId]);
    const action = seen.has(targetKey) ? 'Reply to review' : 'Ask about review';
    seen.add(targetKey);
    return {
      action, topic, body: message.body, turnId: message.turnId, messageEventId: message.messageEventId, target,
      detail: `Private · Revision ${message.targetRevision}${run ? ` · Reviewed head ${shortSha(run.headSha)}` : ''}`,
    };
  });
  const generatedTurnIds = new Set([...review.byteQuayTurnIds, ...review.runs.flatMap(run => run.turnIds)]);
  const generated = [...generatedTurnIds].map((turnId): ReviewRequest => {
    const run = review.runs.find(item => item.turnIds.includes(turnId));
    const followUp = run !== undefined && run.turnIds.indexOf(turnId) > 0;
    const incremental = run?.scope === 'INCREMENTAL';
    const explaining = run !== undefined && isExplanationRun(run);
    const scope = run ? `${run.commits.length} ${incremental ? 'new ' : ''}${run.commits.length === 1 ? 'commit' : 'commits'} across `
      + `${run.changedFiles} ${run.changedFiles === 1 ? 'file' : 'files'}` : 'the pull request';
    return {
      action: explaining ? 'Refresh explanation' : followUp ? 'Follow-up review' : incremental ? 'Review new commits' : review.number === null ? 'Review changes' : 'Review pull request',
      generated: true,
      topic: review.number !== null ? `${review.repo}#${review.number}` : `${review.repo} · ${review.replay ? 'Past change' : 'Local changes'}`,
      body: explaining ? `Regenerate the change intention and commit summaries for ${scope}.` : followUp ? 'Continue the investigation, validate suspected issues, and complete the review report.' : `Review ${scope}.`,
      detail: run ? `Private · ${explaining ? 'Explanation' : 'Review'} ${run.ordinal} · Head ${shortSha(run.headSha)}` : 'Private review',
      turnId,
      target: run ? { kind: 'card', runId: run.id, cardId: `${run.id}:${run.summary === null || explaining && run.state !== 'ACCEPTED' ? 'details' : 'purpose'}` } : undefined,
    };
  });
  return [...questions, ...generated];
}

export { shortSha } from '@bytequay/ui/displayText';
