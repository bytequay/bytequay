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
/** Wire shapes for the Agent review tab. Parsed once, so a malformed field never reaches a card. */

type ReviewRunState = 'RUNNING' | 'ACCEPTED' | 'INCOMPLETE' | 'STOPPED' | 'FAILED';
type ReviewFindingState = 'OPEN' | 'RESOLVED' | 'WITHDRAWN';
export type ReviewEvent = 'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES';
export type ReviewSide = 'LEFT' | 'RIGHT';

type ReviewCommitDto = { sha: string; message: string; author: string };

type ReviewSummaryDto = {
  revision: number;
  purpose: string;
  commits: Array<{ sha: string; explanation: string }>;
  combinedResult: string;
  evidence?: string | null;
  criticalFindingIds: string[];
};

type ReviewCoverageDto = {
  complete: boolean;
  omissions: string[];
  warnings: string[];
  recordedAt: string;
};

export type ReviewCandidateValidationDto = {
  candidate: number;
  outcome: 'SUPPORTED' | 'REJECTED' | 'NEEDS_EVIDENCE' | 'UNRESOLVED';
  conclusion: string;
  evidenceRefs: string[];
  counterevidence: string;
  findingId: string | null;
  findingRevision: number | null;
  request: { question: string; check: string; resolvesWhen: string } | null;
};

export type ReviewInvestigationDto = {
  changeMap: string;
  reviewedFiles: string[];
  contextEvidenceRefs: string[];
  candidates: string[];
  validations: ReviewCandidateValidationDto[];
  answers: Array<{ candidate: number; attempted: string; evidenceRefs: string[]; limitation: string | null }>;
};

export type ReviewStepDto = {
  number: number;
  kind: 'INVESTIGATE' | 'COLLECT_EVIDENCE' | 'VALIDATE';
  repair: boolean;
  investigation: ReviewInvestigationDto | null;
  validation: { investigationStep: number; candidates: ReviewCandidateValidationDto[]; coverage: ReviewCoverageDto; newCandidates: string[] } | null;
};

export type ReviewRunDto = {
  id: string;
  ordinal: number;
  scope: 'FULL' | 'INCREMENTAL';
  baseRef: string;
  baseSha: string;
  headSha: string;
  priorHeadSha: string | null;
  commits: ReviewCommitDto[];
  changedFiles: number;
  turnIds: string[];
  state: ReviewRunState;
  summary: ReviewSummaryDto | null;
  coverage: ReviewCoverageDto | null;
  investigation: ReviewInvestigationDto | null;
  steps: ReviewStepDto[];
  evidence: ReviewEvidenceDto[];
  outcome: string | null;
  createdAt: string;
  endedAt: string | null;
};

type ReviewEvidenceDto = {
  ref: string;
  source: string;
  provenance: string;
  excerpt: string;
  explanation: string;
};

export type ReviewFindingDto = {
  id: string;
  label: string;
  runId: string;
  title: string;
  body: string;
  severity: string | null;
  path: string;
  side: ReviewSide;
  startLine: number;
  endLine: number;
  range: string;
  excerpt: string[];
  evidence: ReviewEvidenceDto[];
  revision: number;
  state: ReviewFindingState;
  stateReason: string | null;
  outdated: boolean;
  validatedHeadSha: string;
  version: number;
  updatedAt: string;
};

export type ReviewMessageDto = {
  id: string;
  runId?: string;
  cardId?: string | null;
  findingId: string | null;
  author: 'USER' | 'AGENT';
  body: string;
  targetRevision: number;
  turnId: string | null;
  queuedInputId: string | null;
  messageEventId?: string | null;
  createdAt: string;
};

type ReviewTaskDto = {
  id: string;
  workspaceId: string;
  state: string;
  version: number;
  laneId: string;
  laneLabel: string;
  model: string | null;
  mutationClaim: string | null;
  steering: boolean;
  nestedAgents: boolean;
};

type ReviewTurnDto = {
  id: string;
  state: string;
  startedAt: string | null;
  cancellationRequestedAt: string | null;
};

type ReviewPublicationDto = {
  state: 'RESERVED' | 'PUBLISHED' | 'UNCERTAIN';
  remoteReviewId: number | null;
  remoteCommentId: number | null;
  url: string | null;
  at: string;
};

export type ReviewDraftItemDto = {
  id: string;
  source: 'MANUAL' | 'AGENT_FINDING';
  findingId: string | null;
  findingRevision: number | null;
  path: string;
  side: ReviewSide;
  line: number;
  startLine: number | null;
  body: string;
  included: boolean;
  published: boolean;
  publication: ReviewPublicationDto | null;
};

export type ReviewDraftDto = {
  repo: string;
  number: number;
  body: string;
  event: ReviewEvent;
  items: ReviewDraftItemDto[];
  version: number;
  updatedAt: string;
};

export type AgentReviewDto = {
  id: string;
  workspaceId: string;
  repo: string;
  number: number;
  state: 'OPEN' | 'CLOSED';
  followUpPaused: boolean;
  observedHeadSha: string | null;
  latestCompletedRunId: string | null;
  version: number;
  createdAt: string;
  closedAt: string | null;
  runs: ReviewRunDto[];
  findings: ReviewFindingDto[];
  messages: ReviewMessageDto[];
  task: ReviewTaskDto;
  activeTurn: ReviewTurnDto | null;
  queuedInputIds: string[];
  pendingApprovals: number;
  byteQuayTurnIds: string[];
  draft: ReviewDraftDto | null;
  /** The stored pull request's title and state; null until ByteQuay has stored the pull request. */
  pullRequest: { title: string; state: string; draft: boolean } | null;
};

export type ReviewPublishResultDto = {
  draft: ReviewDraftDto;
  publishedItemIds: string[];
  uncertainReason: string | null;
};

export type ReviewIneligibleDto = { itemId: string; reason: string };

function object(value: unknown, what: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error(`Malformed ${what} from the review service`);
  }
  return value as Record<string, unknown>;
}

function text(value: unknown, what: string): string {
  if (typeof value !== 'string') throw new Error(`Malformed ${what} from the review service`);
  return value;
}

function optionalText(value: unknown): string | null {
  return typeof value === 'string' ? value : null;
}

function count(value: unknown, what: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error(`Malformed ${what} from the review service`);
  return value;
}

function optionalCount(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function flag(value: unknown): boolean {
  return value === true;
}

function list(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function strings(value: unknown): string[] {
  return list(value).filter((item): item is string => typeof item === 'string');
}

/** A closed set the UI switches on; an unknown value is a bug, not a state to render. */
function oneOf<T extends string>(value: unknown, allowed: readonly T[], what: string): T {
  const found = allowed.find(candidate => candidate === value);
  if (found === undefined) throw new Error(`Malformed ${what} from the review service`);
  return found;
}

function parseSummary(value: unknown): ReviewSummaryDto | null {
  if (value === null || value === undefined) return null;
  const summary = object(value, 'summary');
  return {
    revision: count(summary.revision, 'summary revision'),
    purpose: text(summary.purpose, 'summary purpose'),
    commits: list(summary.commits).map(item => {
      const commit = object(item, 'commit explanation');
      return { sha: text(commit.sha, 'commit sha'), explanation: text(commit.explanation, 'commit explanation') };
    }),
    combinedResult: text(summary.combinedResult, 'combined result'),
    evidence: optionalText(summary.evidence),
    criticalFindingIds: strings(summary.criticalFindingIds),
  };
}

function parseCoverage(value: unknown): ReviewCoverageDto | null {
  if (value === null || value === undefined) return null;
  const coverage = object(value, 'coverage');
  return {
    complete: flag(coverage.complete),
    omissions: strings(coverage.omissions),
    warnings: strings(coverage.warnings),
    recordedAt: text(coverage.recordedAt, 'coverage time'),
  };
}

function parseCandidateValidation(value: unknown, retained = false): ReviewCandidateValidationDto {
  const validation = object(value, 'candidate validation');
  const findingId = optionalText(validation.findingId);
  const request = validation.request === null || validation.request === undefined ? null : object(validation.request, 'evidence request');
  return {
    candidate: count(validation.candidate, 'candidate number'),
    outcome: retained && (validation.outcome === null || validation.outcome === undefined) ? 'UNRESOLVED'
      : oneOf(validation.outcome, ['SUPPORTED', 'REJECTED', 'NEEDS_EVIDENCE', 'UNRESOLVED'] as const, 'validation outcome'),
    conclusion: text(validation.conclusion, 'validation conclusion'),
    evidenceRefs: strings(validation.evidenceRefs),
    counterevidence: optionalText(validation.counterevidence) ?? '',
    findingId,
    findingRevision: optionalCount(validation.findingRevision),
    request: request === null ? null : {
      question: text(request.question, 'evidence question'),
      check: text(request.check, 'requested check'),
      resolvesWhen: text(request.resolvesWhen, 'evidence resolution criteria'),
    },
  };
}

function parseInvestigation(value: unknown): ReviewInvestigationDto | null {
  if (value === null || value === undefined) return null;
  const investigation = object(value, 'review investigation');
  return {
    changeMap: text(investigation.changeMap, 'change map'),
    reviewedFiles: strings(investigation.reviewedFiles),
    contextEvidenceRefs: strings(investigation.contextEvidenceRefs),
    candidates: strings(investigation.candidates),
    validations: list(investigation.validations).map(item => parseCandidateValidation(item, true)),
    answers: list(investigation.answers).map(item => {
      const answer = object(item, 'evidence answer');
      return { candidate: count(answer.candidate, 'candidate number'), attempted: text(answer.attempted, 'evidence check attempted'),
        evidenceRefs: strings(answer.evidenceRefs), limitation: optionalText(answer.limitation) };
    }),
  };
}

function parseStep(value: unknown): ReviewStepDto {
  const step = object(value, 'review step');
  const validation = step.validation === null || step.validation === undefined ? null : object(step.validation, 'review validation');
  const coverage = validation === null ? null : parseCoverage(validation.coverage);
  if (validation !== null && coverage === null) throw new Error('Malformed validation coverage from the review service');
  return {
    number: count(step.number, 'review step number'),
    kind: oneOf(step.kind, ['INVESTIGATE', 'COLLECT_EVIDENCE', 'VALIDATE'] as const, 'review step kind'),
    repair: flag(step.repair),
    investigation: parseInvestigation(step.investigation),
    validation: validation === null || coverage === null ? null : {
      investigationStep: count(validation.investigationStep, 'validated investigation step'),
      candidates: list(validation.candidates).map(item => parseCandidateValidation(item)),
      coverage,
      newCandidates: strings(validation.newCandidates),
    },
  };
}

function parseEvidence(value: unknown): ReviewEvidenceDto {
  const evidence = object(value, 'evidence');
  return {
    ref: text(evidence.ref, 'evidence reference'),
    source: text(evidence.source, 'evidence source'),
    provenance: text(evidence.provenance, 'evidence provenance'),
    excerpt: text(evidence.excerpt, 'evidence excerpt'),
    explanation: text(evidence.explanation, 'evidence explanation'),
  };
}

function parseRun(value: unknown): ReviewRunDto {
  const run = object(value, 'review run');
  return {
    id: text(run.id, 'run id'),
    ordinal: count(run.ordinal, 'run ordinal'),
    scope: oneOf(run.scope, ['FULL', 'INCREMENTAL'] as const, 'run scope'),
    baseRef: text(run.baseRef, 'base branch'),
    baseSha: text(run.baseSha, 'base sha'),
    headSha: text(run.headSha, 'head sha'),
    priorHeadSha: optionalText(run.priorHeadSha),
    commits: list(run.commits).map(item => {
      const commit = object(item, 'commit');
      return { sha: text(commit.sha, 'commit sha'), message: text(commit.message, 'commit message'), author: text(commit.author, 'commit author') };
    }),
    changedFiles: count(run.changedFiles, 'changed files'),
    turnIds: strings(run.turnIds),
    state: oneOf(run.state, ['RUNNING', 'ACCEPTED', 'INCOMPLETE', 'STOPPED', 'FAILED'] as const, 'run state'),
    summary: parseSummary(run.summary),
    coverage: parseCoverage(run.coverage),
    investigation: parseInvestigation(run.investigation),
    steps: list(run.steps).map(parseStep),
    evidence: list(run.evidence).map(parseEvidence),
    outcome: optionalText(run.outcome),
    createdAt: text(run.createdAt, 'run time'),
    endedAt: optionalText(run.endedAt),
  };
}

function parseFinding(value: unknown): ReviewFindingDto {
  const finding = object(value, 'finding');
  return {
    id: text(finding.id, 'finding id'),
    label: text(finding.label, 'finding label'),
    runId: text(finding.runId, 'finding run'),
    title: text(finding.title, 'finding title'),
    body: text(finding.body, 'finding body'),
    severity: optionalText(finding.severity),
    path: text(finding.path, 'finding path'),
    side: oneOf(finding.side, ['LEFT', 'RIGHT'] as const, 'finding side'),
    startLine: count(finding.startLine, 'finding start line'),
    endLine: count(finding.endLine, 'finding end line'),
    range: text(finding.range, 'finding range'),
    excerpt: strings(finding.excerpt),
    evidence: list(finding.evidence).map(parseEvidence),
    revision: count(finding.revision, 'finding revision'),
    state: oneOf(finding.state, ['OPEN', 'RESOLVED', 'WITHDRAWN'] as const, 'finding state'),
    stateReason: optionalText(finding.stateReason),
    outdated: flag(finding.outdated),
    validatedHeadSha: text(finding.validatedHeadSha, 'finding head'),
    version: count(finding.version, 'finding version'),
    updatedAt: text(finding.updatedAt, 'finding time'),
  };
}

function parseMessage(value: unknown): ReviewMessageDto {
  const message = object(value, 'discussion message');
  return {
    id: text(message.id, 'message id'),
    runId: optionalText(message.runId) ?? undefined,
    cardId: optionalText(message.cardId),
    findingId: optionalText(message.findingId),
    author: oneOf(message.author, ['USER', 'AGENT'] as const, 'message author'),
    body: text(message.body, 'message body'),
    targetRevision: count(message.targetRevision, 'message revision'),
    turnId: optionalText(message.turnId),
    queuedInputId: optionalText(message.queuedInputId),
    messageEventId: optionalText(message.messageEventId),
    createdAt: text(message.createdAt, 'message time'),
  };
}

export function parseReviewDraft(value: unknown): ReviewDraftDto {
  const draft = object(value, 'pending review');
  return {
    repo: text(draft.repo, 'repository'),
    number: count(draft.number, 'pull request number'),
    body: text(draft.body, 'review body'),
    event: oneOf(draft.event, ['COMMENT', 'APPROVE', 'REQUEST_CHANGES'] as const, 'review event'),
    items: list(draft.items).map(item => {
      const draftItem = object(item, 'pending comment');
      const publication = draftItem.publication === null || draftItem.publication === undefined
        ? null
        : object(draftItem.publication, 'publication');
      return {
        id: text(draftItem.id, 'comment id'),
        source: oneOf(draftItem.source, ['MANUAL', 'AGENT_FINDING'] as const, 'comment source'),
        findingId: optionalText(draftItem.findingId),
        findingRevision: optionalCount(draftItem.findingRevision),
        path: text(draftItem.path, 'comment path'),
        side: oneOf(draftItem.side, ['LEFT', 'RIGHT'] as const, 'comment side'),
        line: count(draftItem.line, 'comment line'),
        startLine: optionalCount(draftItem.startLine),
        body: text(draftItem.body, 'comment body'),
        included: flag(draftItem.included),
        published: flag(draftItem.published),
        publication: publication === null ? null : {
          state: oneOf(publication.state, ['RESERVED', 'PUBLISHED', 'UNCERTAIN'] as const, 'publication state'),
          remoteReviewId: optionalCount(publication.remoteReviewId),
          remoteCommentId: optionalCount(publication.remoteCommentId),
          url: optionalText(publication.url),
          at: text(publication.at, 'publication time'),
        },
      };
    }),
    version: count(draft.version, 'review version'),
    updatedAt: text(draft.updatedAt, 'review time'),
  };
}

export function parseAgentReviews(value: unknown): AgentReviewDto[] {
  if (!Array.isArray(value)) throw new Error('Invalid review list');
  return value.map(parseAgentReview);
}

export function parseAgentReview(value: unknown): AgentReviewDto {
  const review = object(value, 'review');
  const task = object(review.task, 'review task');
  const activeTurn = review.activeTurn === null || review.activeTurn === undefined ? null : object(review.activeTurn, 'review turn');
  const pullRequest = review.pullRequest === null || review.pullRequest === undefined ? null : object(review.pullRequest, 'review pull request');
  return {
    id: text(review.id, 'review id'),
    workspaceId: text(review.workspaceId, 'workspace'),
    repo: text(review.repo, 'repository'),
    number: count(review.number, 'pull request number'),
    state: oneOf(review.state, ['OPEN', 'CLOSED'] as const, 'review state'),
    followUpPaused: flag(review.followUpPaused),
    observedHeadSha: optionalText(review.observedHeadSha),
    latestCompletedRunId: optionalText(review.latestCompletedRunId),
    version: count(review.version, 'review version'),
    createdAt: text(review.createdAt, 'review time'),
    closedAt: optionalText(review.closedAt),
    runs: list(review.runs).map(parseRun),
    findings: list(review.findings).map(parseFinding),
    messages: list(review.messages).map(parseMessage),
    task: {
      id: text(task.id, 'task id'),
      workspaceId: text(task.workspaceId, 'task workspace'),
      state: text(task.state, 'task state'),
      version: count(task.version, 'task version'),
      laneId: text(task.laneId, 'provider lane'),
      laneLabel: text(task.laneLabel, 'provider name'),
      model: optionalText(task.model),
      mutationClaim: optionalText(task.mutationClaim),
      steering: flag(task.steering),
      nestedAgents: flag(task.nestedAgents),
    },
    activeTurn: activeTurn === null ? null : {
      id: text(activeTurn.id, 'turn id'),
      state: text(activeTurn.state, 'turn state'),
      startedAt: optionalText(activeTurn.startedAt),
      cancellationRequestedAt: optionalText(activeTurn.cancellationRequestedAt),
    },
    queuedInputIds: strings(review.queuedInputIds),
    pendingApprovals: count(review.pendingApprovals, 'pending approvals'),
    byteQuayTurnIds: strings(review.byteQuayTurnIds),
    draft: review.draft === null || review.draft === undefined ? null : parseReviewDraft(review.draft),
    pullRequest: pullRequest === null ? null : {
      title: text(pullRequest.title, 'pull request title'),
      state: text(pullRequest.state, 'pull request state'),
      draft: flag(pullRequest.draft),
    },
  };
}

export function parseReviewPublishResult(value: unknown): ReviewPublishResultDto {
  const result = object(value, 'publication result');
  return {
    draft: parseReviewDraft(result.draft),
    publishedItemIds: strings(result.publishedItemIds),
    uncertainReason: optionalText(result.uncertainReason),
  };
}

export function parseReviewIneligible(value: unknown): ReviewIneligibleDto[] {
  return list(value).map(item => {
    const entry = object(item, 'ineligible comment');
    return { itemId: text(entry.itemId, 'comment id'), reason: text(entry.reason, 'reason') };
  });
}
