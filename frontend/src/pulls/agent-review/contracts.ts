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
import { parseReviewContextSnapshot, type ReviewContextSnapshotDto } from '../../repository-context/contracts';

import { count, object, oneOf, optionalCount, optionalText, text } from '../wireParse';

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

export type ContextDeliveryStateDto = { outcome: 'PREPARED' | 'SUBMITTED' | 'CONFIRMED' | 'UNCERTAIN'; detail: string | null };

export type ContextPreparationActivityDto = {
  operationRef: string; status: 'OWNED' | 'REUSED' | 'JOINED' | 'DEFERRED' | 'APPLIED' | 'UNAVAILABLE';
  attemptId: string | null; recordedAt: string | null; elapsedMillis: number | null;
  scopes: Array<{ scopeId: string; outcome: 'READY' | 'EMPTY' | 'FAILED' | null; explanation: string; noteIds: string[]; checkedSourceIds: string[] }>;
  gaps: string[];
};

export type ContextNoteReadDto = {
  entryId: string; entryVersion: number; status: 'AVAILABLE' | 'RECEIPT_UNAVAILABLE'; text: string | null;
  evidenceRef: string | null; sourceEvidenceRefs: Record<string, string>;
};

type ContextNoteToolDeliveryDto = 'PREPARED' | 'CONFIRMED' | 'UNCERTAIN' | 'UNKNOWN';
type ContextNoteSearchReadDto = { status: 'AVAILABLE' | 'RECEIPT_UNAVAILABLE'; evidenceRef: string | null;
  sourceEvidenceRefs: Record<string, string>; delivery: ContextNoteToolDeliveryDto; receiptRef: string };

export type ContextNoteSearchDto = {
  searchRef: string; status: 'AVAILABLE' | 'RECEIPT_UNAVAILABLE'; notebookRevision: number | null;
  originalNotebookRevision: number | null; newerThanSnapshot: boolean; contextState: 'MATCHES' | 'NO_MATCH' | 'UNKNOWN'; query: string; paths: string[];
  omittedPathHints: number; pathHintCount: number; frozenPathHints: number; omittedCount: number;
  delivery: ContextNoteToolDeliveryDto;
  hits: Array<{ entryId: string; entryVersion: number; kind: 'KNOWLEDGE' | 'MEMORY'; scopeId: string;
    title: string; summary: string; matchReasons: string[]; applicability: 'UNVERIFIED';
    initiallySelected: boolean; read: ContextNoteSearchReadDto | null }>;
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
  context: ReviewContextSnapshotDto | null;
  contextDeliveryStates: Record<string, ContextDeliveryStateDto>;
  contextPreparationActivities: ContextPreparationActivityDto[];
  contextNoteReads: ContextNoteReadDto[];
  contextNoteSearches: ContextNoteSearchDto[];
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
  /** The diff lines the finding is about. All null for a finding about the whole pull request, such as a commit message. */
  path: string | null;
  side: ReviewSide | null;
  startLine: number | null;
  endLine: number | null;
  range: string | null;
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

/** Where a finding sits in the diff, or null for a finding about the whole pull request. */
export type FindingLines = { path: string; side: ReviewSide; startLine: number; endLine: number; range: string };

export function findingLines(finding: ReviewFindingDto): FindingLines | null {
  const { path, side, startLine, endLine, range } = finding;
  return path === null || side === null || startLine === null || endLine === null || range === null
    ? null
    : { path, side, startLine, endLine, range };
}

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

export type ReviewLessonDraftDto = { title: string; summary: string; body: string };

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
  commitId: string | null;
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

function parseContextDeliveryStates(value: unknown): Record<string, ContextDeliveryStateDto> {
  return Object.fromEntries(Object.entries(object(value, 'context delivery states')).map(([turnId, value]) => {
    const state = object(value, 'context delivery state');
    return [turnId, { outcome: oneOf(state.outcome, ['PREPARED', 'SUBMITTED', 'CONFIRMED', 'UNCERTAIN'] as const, 'context delivery outcome'),
      detail: state.detail == null ? null : text(state.detail, 'context delivery detail') }];
  }));
}

function parsePreparationActivity(value: unknown): ContextPreparationActivityDto {
  const item = object(value, 'context preparation activity');
  const elapsedMillis = item.elapsedMillis;
  if (elapsedMillis !== null && (typeof elapsedMillis !== 'number' || !Number.isSafeInteger(elapsedMillis) || elapsedMillis < 0)) {
    throw new Error('Invalid context preparation elapsed time');
  }
  if (!Array.isArray(item.scopes) || !Array.isArray(item.gaps) || item.gaps.some(gap => typeof gap !== 'string')) throw new Error('Malformed preparation lists');
  return { operationRef: text(item.operationRef, 'context preparation reference'),
    status: oneOf(item.status, ['OWNED', 'REUSED', 'JOINED', 'DEFERRED', 'APPLIED', 'UNAVAILABLE'] as const, 'context preparation status'),
    attemptId: item.attemptId === null ? null : text(item.attemptId, 'preparation attempt'),
    recordedAt: item.recordedAt === null ? null : text(item.recordedAt, 'preparation recorded time'),
    elapsedMillis: elapsedMillis === null ? null : count(elapsedMillis, 'preparation elapsed time'),
    scopes: list(item.scopes).map(value => {
      const scope = object(value, 'context preparation scope');
      for (const values of [scope.noteIds, scope.checkedSourceIds]) {
        if (!Array.isArray(values) || values.some(value => typeof value !== 'string')) throw new Error('Malformed preparation scope references');
      }
      return { scopeId: text(scope.scopeId, 'preparation scope'), outcome: scope.outcome === null ? null : oneOf(scope.outcome, ['READY', 'EMPTY', 'FAILED'] as const, 'preparation outcome'),
        explanation: text(scope.explanation, 'preparation explanation'), noteIds: strings(scope.noteIds), checkedSourceIds: strings(scope.checkedSourceIds) };
    }), gaps: strings(item.gaps) };
}

function parseNoteRead(value: unknown): ContextNoteReadDto {
  const item = object(value, 'saved context note read');
  const entryVersion = count(item.entryVersion, 'saved note version');
  if (!Number.isSafeInteger(entryVersion) || entryVersion < 1) throw new Error('Malformed saved note version');
  const result: ContextNoteReadDto = { entryId: text(item.entryId, 'saved note id'), entryVersion,
    status: oneOf(item.status, ['AVAILABLE', 'RECEIPT_UNAVAILABLE'] as const, 'saved note status'),
    text: item.text === null ? null : text(item.text, 'saved note text'), evidenceRef: item.evidenceRef === null ? null : text(item.evidenceRef, 'saved note evidence'),
    sourceEvidenceRefs: Object.fromEntries(Object.entries(object(item.sourceEvidenceRefs, 'saved note source references')).map(([id, value]) => [id, text(value, 'saved note source reference')])) };
  if ((result.status === 'AVAILABLE') !== (result.text !== null && result.evidenceRef !== null)
    || (result.status === 'RECEIPT_UNAVAILABLE' && (result.text !== null || result.evidenceRef !== null || Object.keys(result.sourceEvidenceRefs).length > 0))) {
    throw new Error('Malformed saved note status and content');
  }
  return result;
}

function parseNoteSearchRead(value: unknown): ContextNoteSearchReadDto {
  const read = object(value, 'context note search read');
  const result: ContextNoteSearchReadDto = { status: oneOf(read.status, ['AVAILABLE', 'RECEIPT_UNAVAILABLE'] as const, 'found note read status'),
    receiptRef: text(read.receiptRef, 'found note read reference'),
    evidenceRef: read.evidenceRef === null ? null : text(read.evidenceRef, 'found note evidence'),
    sourceEvidenceRefs: Object.fromEntries(Object.entries(object(read.sourceEvidenceRefs, 'found note source references'))
      .map(([id, source]) => [id, text(source, 'found note source reference')])),
    delivery: oneOf(read.delivery, ['PREPARED', 'CONFIRMED', 'UNCERTAIN', 'UNKNOWN'] as const, 'context note tool delivery') };
  if ((result.status === 'AVAILABLE') !== (result.evidenceRef !== null)
    || (result.status === 'RECEIPT_UNAVAILABLE' && Object.keys(result.sourceEvidenceRefs).length > 0)) {
    throw new Error('Malformed found note read status and evidence');
  }
  return result;
}

function parseNoteSearch(value: unknown): ContextNoteSearchDto {
  const item = object(value, 'saved context note search');
  const integer = (value: unknown, what: string, minimum = 0) => {
    const parsed = count(value, what);
    if (!Number.isSafeInteger(parsed) || parsed < minimum) throw new Error(`Malformed ${what} from the review service`);
    return parsed;
  };
  const revision = (value: unknown, what: string) => value === null ? null : integer(value, what);
  const stringList = (value: unknown, what: string) => {
    if (!Array.isArray(value) || value.some(part => typeof part !== 'string')) throw new Error(`Malformed ${what} from the review service`);
    return value as string[];
  };
  const delivery = (value: unknown) => oneOf(value, ['PREPARED', 'CONFIRMED', 'UNCERTAIN', 'UNKNOWN'] as const, 'context note tool delivery');
  if (typeof item.newerThanSnapshot !== 'boolean' || !Array.isArray(item.hits)) throw new Error('Malformed saved context note search');
  return { searchRef: text(item.searchRef, 'context note search reference'),
    status: oneOf(item.status, ['AVAILABLE', 'RECEIPT_UNAVAILABLE'] as const, 'context note search status'),
    notebookRevision: revision(item.notebookRevision, 'context note notebook revision'),
    originalNotebookRevision: revision(item.originalNotebookRevision, 'original context note notebook revision'),
    newerThanSnapshot: item.newerThanSnapshot,
    contextState: oneOf(item.contextState, ['MATCHES', 'NO_MATCH', 'UNKNOWN'] as const, 'context note search state'),
    query: text(item.query, 'context note query'), paths: stringList(item.paths, 'context note paths'),
    omittedPathHints: integer(item.omittedPathHints, 'omitted context note paths'),
    pathHintCount: integer(item.pathHintCount, 'context note path count'),
    frozenPathHints: integer(item.frozenPathHints, 'frozen context note paths'),
    omittedCount: integer(item.omittedCount, 'omitted context note hits'), delivery: delivery(item.delivery),
    hits: item.hits.map(value => {
      const hit = object(value, 'context note search hit');
      if (typeof hit.initiallySelected !== 'boolean') throw new Error('Malformed found note initial selection');
      return { entryId: text(hit.entryId, 'found note id'), entryVersion: integer(hit.entryVersion, 'found note version', 1),
        kind: oneOf(hit.kind, ['KNOWLEDGE', 'MEMORY'] as const, 'found note kind'), scopeId: text(hit.scopeId, 'found note scope'),
        title: text(hit.title, 'found note title'), summary: text(hit.summary, 'found note summary'),
        matchReasons: stringList(hit.matchReasons, 'found note reasons'),
        applicability: oneOf(hit.applicability, ['UNVERIFIED'] as const, 'found note applicability'),
        initiallySelected: hit.initiallySelected,
        read: hit.read === null ? null : parseNoteSearchRead(hit.read),
      };
    }),
  };
}

function parseRun(value: unknown): ReviewRunDto {
  const run = object(value, 'review run');
  if (!Array.isArray(run.contextPreparationActivities)) throw new Error('Malformed context preparation activities');
  if (!Array.isArray(run.contextNoteReads)) throw new Error('Malformed saved context note reads');
  if (!Array.isArray(run.contextNoteSearches)) throw new Error('Malformed saved context note searches');
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
    context: parseReviewContextSnapshot(run.context),
    contextDeliveryStates: parseContextDeliveryStates(run.contextDeliveryStates),
    contextPreparationActivities: list(run.contextPreparationActivities).map(parsePreparationActivity),
    contextNoteReads: list(run.contextNoteReads).map(parseNoteRead),
    contextNoteSearches: list(run.contextNoteSearches).map(parseNoteSearch),
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
    ...(optionalText(finding.path) === null
      ? { path: null, side: null, startLine: null, endLine: null, range: null }
      : {
          path: text(finding.path, 'finding path'),
          side: oneOf(finding.side, ['LEFT', 'RIGHT'] as const, 'finding side'),
          startLine: count(finding.startLine, 'finding start line'),
          endLine: count(finding.endLine, 'finding end line'),
          range: text(finding.range, 'finding range'),
        }),
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

export function parseReviewLessonDraft(value: unknown): ReviewLessonDraftDto {
  const draft = object(value, 'lesson draft');
  return {
    title: text(draft.title, 'lesson title'),
    summary: text(draft.summary, 'lesson summary'),
    body: text(draft.body, 'lesson details'),
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
        commitId: optionalText(draftItem.commitId),
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
