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
export type ContextOrigin = 'USER' | 'GENERATED';
export type ContextMatchDto = { kind: 'REPOSITORY' } | { kind: 'SELECTED'; pathPrefixes: string[]; terms: string[] };
export type ContextScopeDto = { id: string; title: string; match: ContextMatchDto; enabled: boolean; discoveryEvidenceRef: string | null };
export type ContextSourceDto = { id: string; kind: 'DOCUMENTATION_ROOT' | 'DOCUMENTATION_PAGE'; url: string; origin: ContextOrigin; discoveryEvidenceRef: string | null };
export type ContextSourceConfigurationDto = Pick<ContextSourceDto, 'id' | 'kind' | 'url'>;
export type ContextClaimDto = {
  text: string;
  basis: 'DOCUMENTED' | 'OBSERVED' | 'RECORDED_DECISION' | 'INFERENCE' | 'QUESTION' | 'USER_LESSON';
  sourceIds: string[];
};
export type ContextEntryDto = {
  id: string; kind: 'KNOWLEDGE' | 'MEMORY'; scopeId: string; title: string; summary: string; body: string;
  claims: ContextClaimDto[]; sourceIds: string[]; origin: ContextOrigin; enabled: boolean; version: number;
};
type RepositoryIdentity = { owner: string; repo: string };
export type ContextReferenceDto =
  | { kind: 'REPOSITORY_FILE'; repositoryId: number; repository: RepositoryIdentity; path: string; commit: string }
  | { kind: 'PR' | 'ISSUE'; repositoryId: number; repository: RepositoryIdentity; number: number; commentId: string | null }
  | { kind: 'TASK'; workspaceId: string; taskId: string; turnId: string | null }
  | { kind: 'REVIEW'; reviewId: string; runId: string; evidenceRef: string | null; messageId: string | null }
  | { kind: 'DOCUMENT'; sourceId: string; url: string };
export type ContextSourceEvidenceDto = {
  id: string; reference: ContextReferenceDto; provenance: string; capture: 'BYTEQUAY_READ' | 'AGENT_REPORTED'; retrievedAt: string | null;
};
/** {@code replaced} holds, for some entries, the version their last text change replaced; Restore makes it current again. */
export type RepositoryNotebookDto = {
  schemaVersion: number; revision: number; useInReviews: boolean; useInDevelopment: boolean; sources: ContextSourceDto[]; scopes: ContextScopeDto[];
  entries: ContextEntryDto[]; replaced: ContextEntryDto[]; sourceEvidence: ContextSourceEvidenceDto[];
};
export type ContextSelectedEntryDto = {
  entryId: string; entryVersion: number; title: string; text: string | null; sourceEvidenceRefs: Record<string, string>;
  matchReasons: string[]; applicability: 'MATCHED' | 'VERSION_MISMATCH' | 'UNVERIFIED'; inclusion: 'INCLUDED' | 'DEFERRED';
};
type SourceRead = { id: string; readAt: string; utf8Bytes: number; evidenceRef: string | null; truncated: boolean };
export type ContextRepositoryReadDto = SourceRead & {
  kind: 'REPOSITORY_FILE'; reference: Extract<ContextReferenceDto, { kind: 'REPOSITORY_FILE' }>; blobId: string;
  startLine: number; endLine: number; totalLines: number;
};
export type ContextDocumentReadDto = SourceRead & {
  kind: 'DOCUMENT'; reference: Extract<ContextReferenceDto, { kind: 'DOCUMENT' }>; registration: ContextSourceDto;
  notebookRevision: number; captureId: string; contentHash: string; page: number; totalPages: number; blank: boolean; refreshFailed: boolean;
};
export type ContextHistoryReadDto = SourceRead & {
  kind: 'HISTORY'; reference: Extract<ContextReferenceDto, { kind: 'PR' | 'ISSUE' }>; state: 'MERGED' | 'CLOSED';
  sourceUpdatedAt: string | null; sourceRevision: string | null; contentHash: string; refreshFailed: boolean; gaps: string[];
};
export type ContextSourceReadDto = ContextRepositoryReadDto | ContextDocumentReadDto | ContextHistoryReadDto;
export type ContextInputFingerprintDto = { scopeId: string; inputs: Record<string, string>; digest: string | null; gaps: string[] };
/** Every run-starting request names the exact notebook revision and build sequence it saw. */
export type ContextRunFence = { requestKey: string; expectedRevision: number; expectedBuildSequence: number };
export type StartContextDiscoveryRequest = ContextRunFence & { continuation: boolean };
export type ContextCoverageDto = { areas: Array<{ path: string; surveyed: number; total: number }>; unsurveyed: string[]; unsurveyedTruncated: boolean };
export type ContextDiscoveryReportDto = {
  id: string; workspaceId: string; baselineSha: string;
  topics: Array<{ id: string; title: string; questions: string[]; paths: string[]; notePaths: string[] | null; sourceIds: string[]; evidenceReadIds: string[]; why: string }>;
  sources: Array<{ id: string; kind: 'REPOSITORY_FILE' | 'DOCUMENTATION_ROOT' | 'DOCUMENTATION_PAGE'; location: string }>;
  evidence: Array<{ readId: string; path: string; startLine: number; endLine: number }>;
  remaining: string[]; partial: boolean; coverage: ContextCoverageDto; createdAt: string;
};
/** Proposed sources the user unchecked while inspecting topics are left out of the approval. */
export type StartContextLearningRequest = ContextRunFence & { reportId: string; topicIds: string[]; excludedSourceIds: string[] };
export type ContinueContextLearningRequest = ContextRunFence & { planId: string };
export type DiscardContextLearningPlanRequest = { planId: string; expectedRevision: number; expectedBuildSequence: number };
export type ContextSourceBoundary = 'PAGE' | 'ROOT';
export type AllowContextLearningSourceRequest = DiscardContextLearningPlanRequest & { suggestedUrl: string; boundary: ContextSourceBoundary };
export type DeclineContextLearningSourceRequest = { planId: string; suggestedUrl: string };
export type LearnContextMergedCandidateRequest = ContextRunFence & { candidateId: string };
/** What the page shows of a merged PR's learning bundle; the agent receives the full bundle. */
export type ContextMergedCandidateDto = {
  id: string; repo: string; number: number; title: string; mergedAt: string; taskTitle: string | null; taskResult: string | null;
  issue: null | { key: string; title: string }; review: null | { combinedResult: string }; checks: Array<{ conclusion: string }>;
};
export type ContextMergedCandidatesDto = { candidates: ContextMergedCandidateDto[] };
export type ContextQuestionStatus = 'PENDING' | 'UNFINISHED' | 'COMPLETED';
export type ContextPlanTopicDto = { id: string; title: string; scopeId: string; paths: string[]; notePaths: string[] | null; repositoryFiles: string[];
  sourceIds: string[]; questions: Array<{ text: string; status: ContextQuestionStatus; progress: string }> };
export type ContextSuggestionDto = { url: string; parentUrl: string; topicId: string; question: number; taskId: string; foundAt: string };
export type ContextLearningPlanDto = {
  id: string; workspaceId: string; reportId: string; baselineSha: string; gaps: string[];
  topics: ContextPlanTopicDto[];
  documentationSources: ContextSourceDto[];
  documentInventory: { items: Array<{ sourceId: string; resolvedUrl: string; relativePage: string | null; depth: number;
    status: 'PENDING' | 'READ' | 'FAILED'; detail: string }>; suggestions: ContextSuggestionDto[]; truncated: boolean };
};
export type ContextQuestionTarget = { topicId: string; question: number };
/** The backend names the question Continue learning takes, so the page never re-derives the order. */
export type ContextLearningPlanViewDto = { plan: ContextLearningPlanDto | null; next: ContextQuestionTarget | null };
export type ContextRunOutcome = 'RUNNING' | 'SUBMITTED' | 'ANSWERED' | 'NO_NOTE' | 'UNFINISHED' | 'LIMIT' | 'STOPPED' | 'FAILED';
export type ContextActivityDto = { at: string; text: string; tone: 'DONE' | 'FAILED' | 'LIMIT' };
export type ContextRunDto = {
  sequence: number; taskId: string; mode: 'DISCOVER' | 'LEARN'; requestedAt: string; endedAt: string | null;
  question: null | { planId: string; topicId: string; topicTitle: string; index: number; text: string };
  pullRequest: number | null; outcome: ContextRunOutcome; sourceOperations: number; documentOpens: number;
  noteIds: string[]; activity: ContextActivityDto[]; detail: string | null;
};
export type ContextBaselineDto = { branch: string; sha: string };
export type ContextTimeStampDto = { at: string; taskId: string | null };
/** When each note version was saved, and by which agent task; content saved before stamping began has none. */
export type ContextTimesDto = { entries: Record<string, ContextTimeStampDto> };
export type ContextBuildViewDto = {
  buildRequestSequence: number; notebookRevision: number; canStart: boolean; runs: ContextRunDto[];
  build: null | {
    requestKey: string; taskId: string;
    task: null | { id: string; workspaceId: string; state: 'OPEN' | 'COMPLETED' | 'FAILED'; mutationClaim: string | null; worktreeRemovedAt: string | null };
    activeTurn: null | { cancellationRequestedAt: string | null };
    pendingApprovals: number; continuation: boolean;
  };
};
export type ContextUsageWindow = '30d' | 'all';
/** Found and opened count each note's current version; graded counts distinct tasks across its versions; a removed note has no current version. */
export type ContextUsageOverviewDto = {
  trackingSince: string | null; window: ContextUsageWindow;
  summary: { eligibleDevelopmentTasks: number; eligibleReviewRuns: number; contextOffTasks: number; developmentTasksFoundOrOpened: number;
    reviewRunsFoundOrOpened: number };
  entries: Array<{ entryId: string; title: string; currentVersion: number | null; foundTasks: number; openedTasks: number; lastConfirmedUseAt: string | null;
    helpedTasks: number; notRelevantTasks: number; wrongTasks: number }>;
};
/** One task's grade of an exact note version, or the user's Wrong report of it; a user report quotes and cites nothing. */
export type ContextGradeDto = {
  workspaceId: string; taskId: string; reviewRunId: string | null; taskKind: 'DEVELOPMENT' | 'PR_REVIEW'; grader: 'AGENT' | 'USER';
  grade: 'HELPED' | 'NOT_RELEVANT' | 'WRONG' | 'HOLDS';
  note: { id: string; version: number; kind: ContextEntryDto['kind']; scopeId: string; title: string };
  reason: string; quote: string | null; citations: Array<{ path: string; startLine: number; endLine: number; commit: string; excerpt: string }>;
  correction: { version: number; statement: string } | null; gradedAt: string;
};
/** A note whose current version has an open Wrong report, with every grade of that version, newest first. */
export type ContextReportedNoteDto = { entryId: string; entryVersion: number; title: string; userWritten: boolean; grades: ContextGradeDto[] };
export type ContextContributionDto = {
  investigationStep: number; text: string; evidenceRefs: string[]; preparationRefs: string[]; limitations: string[];
};
export type ContextDiscussionSupplementDto = {
  packet: { text: string; digest: string; utf8Bytes: number }; evidenceRefs: string[]; capturedAt: string; gaps: string[];
};
export type ContextDeliveryDto = {
  turnId: string; packetDigest: string; utf8Bytes: number; contributionCount: number; inputDigest: string; inputUtf8Bytes: number;
  discussionMessageId: string | null; discussionSupplement: ContextDiscussionSupplementDto | null; preparedAt: string;
};
export type ReviewContextSnapshotDto = {
  id: string; enabled: boolean; notebookRevision: number; repositoryId: number; baselineSha: string | null;
  bootstrap: { text: string; digest: string; utf8Bytes: number } | null;
  selectedEntries: ContextSelectedEntryDto[];
  relatedItems: Array<{ sourceId: string; matchReasons: string[]; presentInReviewedBase: boolean | null }>;
  inputFingerprints: ContextInputFingerprintDto[];
  sourceReads: ContextSourceReadDto[]; gaps: string[];
  contributions: ContextContributionDto[]; deliveries: ContextDeliveryDto[];
};

export type ConfigureRepositoryContextRequest = {
  expectedRevision: number; useInReviews?: boolean; useInDevelopment?: boolean; sources?: ContextSourceConfigurationDto[];
};
export type EditRepositoryContextEntryRequest = {
  expectedRevision: number; id: string; scopeId: string; title: string; summary: string; body: string; claims: ContextClaimDto[]; sourceIds: string[];
};
export type SetRepositoryContextEntryEnabledRequest = { expectedRevision: number; entryId: string; enabled: boolean };
export type RestoreRepositoryContextEntryRequest = { expectedRevision: number; entryId: string };
/** Keep saves the note's current text and owner again as a new version, which closes its open reports. */
export type KeepRepositoryContextEntryRequest = RestoreRepositoryContextEntryRequest;
/** The user's Wrong on a note version the task received or opened; the reason is optional. */
export type ReportRepositoryContextNoteRequest = { taskId: string; entryId: string; entryVersion: number; reason: string };
/** Disables the notes the run's task added that nobody changed since. */
export type DisableRepositoryContextRunNotesRequest = { expectedRevision: number; taskId: string };
export type SaveRepositoryContextLessonRequest = {
  expectedRevision: number; scopeId: string; title: string; summary: string; body: string; reviewId: string; runId: string;
  evidenceRefs: string[]; messageId?: string | null;
};

function malformed(field: string): never { throw new Error(`Malformed repository context: ${field}`); }
function object(value: unknown, field: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) malformed(field);
  return value as Record<string, unknown>;
}
function text(value: unknown, field: string): string {
  if (typeof value !== 'string') malformed(field);
  return value;
}
function optionalText(value: unknown, field: string): string | null { return value == null ? null : text(value, field); }
function nullableText(value: unknown, field: string): string | null { return value === null ? null : text(value, field); }
function count(value: unknown, field: string, minimum = 0): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < minimum) malformed(field);
  return value;
}
function flag(value: unknown, field: string): boolean {
  if (typeof value !== 'boolean') malformed(field);
  return value;
}
export function parseContextUsageWindow(value: unknown): ContextUsageWindow { return oneOf(value, ['30d', 'all'], 'usage window'); }
function array<T>(value: unknown, field: string, parse: (item: unknown) => T): T[] {
  if (!Array.isArray(value)) malformed(field);
  return value.map(parse);
}
function strings(value: unknown, field: string): string[] { return array(value, field, item => text(item, field)); }
function oneOf<T extends string>(value: unknown, allowed: readonly T[], field: string): T {
  const found = allowed.find(item => item === value);
  if (found === undefined) malformed(field);
  return found;
}
function stringMap(value: unknown, field: string): Record<string, string> {
  return Object.fromEntries(Object.entries(object(value, field)).map(([key, item]) => [key, text(item, field)]));
}
function source(value: unknown): ContextSourceDto {
  const item = object(value, 'source');
  return { id: text(item.id, 'source id'), kind: oneOf(item.kind, ['DOCUMENTATION_ROOT', 'DOCUMENTATION_PAGE'], 'source kind'),
    url: text(item.url, 'source URL'), origin: oneOf(item.origin, ['USER', 'GENERATED'], 'source origin'),
    discoveryEvidenceRef: item.discoveryEvidenceRef === null ? null : text(item.discoveryEvidenceRef, 'source discovery evidence') };
}
function scope(value: unknown): ContextScopeDto {
  const item = object(value, 'scope');
  const match = object(item.match, 'scope match');
  const kind = oneOf(match.kind, ['REPOSITORY', 'SELECTED'], 'scope match kind');
  return { id: text(item.id, 'scope id'), title: text(item.title, 'scope title'), enabled: flag(item.enabled, 'scope enabled'),
    match: kind === 'REPOSITORY' ? { kind } : { kind, pathPrefixes: strings(match.pathPrefixes, 'path prefixes'), terms: strings(match.terms, 'terms') },
    discoveryEvidenceRef: item.discoveryEvidenceRef === null ? null : text(item.discoveryEvidenceRef, 'scope discovery evidence') };
}
function claim(value: unknown): ContextClaimDto {
  const item = object(value, 'claim');
  return { text: text(item.text, 'claim text'), basis: oneOf(item.basis,
    ['DOCUMENTED', 'OBSERVED', 'RECORDED_DECISION', 'INFERENCE', 'QUESTION', 'USER_LESSON'], 'claim basis'), sourceIds: strings(item.sourceIds, 'claim source IDs') };
}
function entry(value: unknown): ContextEntryDto {
  const item = object(value, 'entry');
  return { id: text(item.id, 'entry id'), kind: oneOf(item.kind, ['KNOWLEDGE', 'MEMORY'], 'entry kind'),
    scopeId: text(item.scopeId, 'entry scope'), title: text(item.title, 'entry title'), summary: text(item.summary, 'entry summary'),
    body: text(item.body, 'entry body'), claims: array(item.claims, 'entry claims', claim), sourceIds: strings(item.sourceIds, 'entry sources'),
    origin: oneOf(item.origin, ['USER', 'GENERATED'], 'entry origin'), enabled: flag(item.enabled, 'entry enabled'), version: count(item.version, 'entry version', 1) };
}
function reference(value: unknown): ContextReferenceDto {
  const item = object(value, 'source reference');
  const kind = oneOf(item.kind, ['REPOSITORY_FILE', 'PR', 'ISSUE', 'TASK', 'REVIEW', 'DOCUMENT'], 'source reference kind');
  if (kind === 'DOCUMENT') return { kind, sourceId: text(item.sourceId, 'document source'), url: text(item.url, 'document URL') };
  if (kind === 'TASK') return { kind, workspaceId: text(item.workspaceId, 'task workspace'), taskId: text(item.taskId, 'task id'),
    turnId: optionalText(item.turnId, 'task turn') };
  if (kind === 'REVIEW') return { kind, reviewId: text(item.reviewId, 'review id'), runId: text(item.runId, 'review run'),
    evidenceRef: optionalText(item.evidenceRef, 'review evidence'), messageId: optionalText(item.messageId, 'review message') };
  const coordinates = object(item.repository, 'repository coordinates');
  const repository = { owner: text(coordinates.owner, 'repository owner'), repo: text(coordinates.repo, 'repository name') };
  const repositoryId = count(item.repositoryId, 'repository id', 1);
  return kind === 'REPOSITORY_FILE'
    ? { kind, repository, repositoryId, path: text(item.path, 'source path'), commit: text(item.commit, 'source commit') }
    : { kind, repository, repositoryId, number: count(item.number, 'source number', 1), commentId: optionalText(item.commentId, 'source comment') };
}
function evidence(value: unknown): ContextSourceEvidenceDto {
  const item = object(value, 'source evidence');
  return { id: text(item.id, 'evidence id'), reference: reference(item.reference), provenance: text(item.provenance, 'evidence provenance'),
    capture: oneOf(item.capture, ['BYTEQUAY_READ', 'AGENT_REPORTED'], 'capture kind'), retrievedAt: optionalText(item.retrievedAt, 'retrieval time') };
}

/** Parsing never turns malformed content into an empty notebook or an off preference. */
export function parseRepositoryNotebook(value: unknown): RepositoryNotebookDto {
  const item = object(value, 'notebook');
  if (item.schemaVersion !== 1) malformed('notebook schema version');
  return { schemaVersion: 1, revision: count(item.revision, 'notebook revision'), useInReviews: flag(item.useInReviews, 'review preference'),
    useInDevelopment: flag(item.useInDevelopment, 'development preference'),
    sources: array(item.sources, 'sources', source), scopes: array(item.scopes, 'scopes', scope), entries: array(item.entries, 'entries', entry),
    replaced: array(item.replaced, 'replaced versions', entry), sourceEvidence: array(item.sourceEvidence, 'source evidence', evidence) };
}
function inputFingerprint(value: unknown): ContextInputFingerprintDto {
  const input = object(value, 'input fingerprint');
  return { scopeId: text(input.scopeId, 'input scope'), inputs: stringMap(input.inputs, 'input identities'),
    digest: optionalText(input.digest, 'input digest'), gaps: strings(input.gaps, 'input limitations') };
}
function selectedEntry(value: unknown): ContextSelectedEntryDto {
  const item = object(value, 'selected entry');
  const result: ContextSelectedEntryDto = { entryId: text(item.entryId, 'selected entry id'), entryVersion: count(item.entryVersion, 'selected entry version', 1),
    title: text(item.title, 'selected entry title'), text: optionalText(item.text, 'selected text'),
    sourceEvidenceRefs: stringMap(item.sourceEvidenceRefs, 'selected evidence'), matchReasons: strings(item.matchReasons, 'matching reasons'),
    applicability: oneOf(item.applicability, ['MATCHED', 'VERSION_MISMATCH', 'UNVERIFIED'], 'applicability'),
    inclusion: oneOf(item.inclusion, ['INCLUDED', 'DEFERRED'], 'inclusion') };
  if ((result.inclusion === 'INCLUDED') !== (result.text !== null)) malformed('selected entry text and inclusion disagree');
  return result;
}
function sourceRead(value: unknown): ContextSourceReadDto {
  const item = object(value, 'source receipt');
  const kind = oneOf(item.kind, ['REPOSITORY_FILE', 'DOCUMENT', 'HISTORY'], 'source receipt kind');
  const observed = reference(item.reference);
  const common: SourceRead = { id: text(item.id, 'source receipt id'), readAt: text(item.readAt, 'source read time'),
    utf8Bytes: count(item.utf8Bytes, 'source bytes'), evidenceRef: optionalText(item.evidenceRef, 'source evidence reference'),
    truncated: flag(item.truncated, 'source truncation') };
  if (kind === 'REPOSITORY_FILE' && observed.kind === 'REPOSITORY_FILE') {
    return { ...common, kind, reference: observed, blobId: text(item.blobId, 'blob id'),
      startLine: count(item.startLine, 'start line', 1), endLine: count(item.endLine, 'end line'), totalLines: count(item.totalLines, 'total lines') };
  }
  if (kind === 'DOCUMENT' && observed.kind === 'DOCUMENT') {
    return { ...common, kind, reference: observed, registration: source(item.registration), notebookRevision: count(item.notebookRevision, 'source notebook revision'),
      captureId: text(item.captureId, 'document capture id'), contentHash: text(item.contentHash, 'document content hash'),
      page: count(item.page, 'document page', 1), totalPages: count(item.totalPages, 'document pages', 1),
      blank: flag(item.blank, 'blank document'), refreshFailed: flag(item.refreshFailed, 'refresh failure') };
  }
  if (kind === 'HISTORY' && (observed.kind === 'PR' || observed.kind === 'ISSUE')) {
    const state = oneOf(item.state, ['MERGED', 'CLOSED'], 'history source state');
    const sourceRevision = optionalText(item.sourceRevision, 'history source revision');
    if (common.evidenceRef === null || common.evidenceRef.trim() === '') malformed('history evidence reference is missing');
    if ((observed.kind === 'PR') !== (state === 'MERGED') || observed.commentId !== null
      || (observed.kind === 'ISSUE' && sourceRevision !== null)) malformed('history source identity and state disagree');
    return { ...common, kind, reference: observed, state, sourceRevision, sourceUpdatedAt: optionalText(item.sourceUpdatedAt, 'history source update'),
      contentHash: text(item.contentHash, 'history content hash'), refreshFailed: flag(item.refreshFailed, 'history refresh failure'),
      gaps: strings(item.gaps, 'history limitations') };
  }
  return malformed('source receipt and reference kinds disagree');
}

function contribution(value: unknown): ContextContributionDto {
  const item = object(value, 'context contribution');
  return { investigationStep: count(item.investigationStep, 'contribution investigation step', 1), text: text(item.text, 'contribution text'),
    evidenceRefs: strings(item.evidenceRefs, 'contribution evidence references'), preparationRefs: strings(item.preparationRefs, 'contribution preparation references'),
    limitations: strings(item.limitations, 'contribution limitations') };
}

function delivery(value: unknown): ContextDeliveryDto {
  const item = object(value, 'context delivery');
  const discussionMessageId = optionalText(item.discussionMessageId, 'delivery discussion message');
  const supplement = item.discussionSupplement === null ? null : object(item.discussionSupplement, 'discussion supplement');
  const packet = supplement === null ? null : object(supplement.packet, 'discussion packet');
  const discussionSupplement = supplement === null || packet === null ? null : {
    packet: { text: text(packet.text, 'discussion packet text'), digest: text(packet.digest, 'discussion packet digest'), utf8Bytes: count(packet.utf8Bytes, 'discussion packet bytes') },
    evidenceRefs: strings(supplement.evidenceRefs, 'discussion evidence references'), capturedAt: text(supplement.capturedAt, 'discussion capture time'),
    gaps: strings(supplement.gaps, 'discussion limitations'),
  };
  if (discussionSupplement !== null && (discussionMessageId === null || discussionSupplement.packet.utf8Bytes > 8192
    || discussionSupplement.evidenceRefs.length > 6 || discussionSupplement.gaps.length > 10)) malformed('invalid discussion supplement bounds or delivery');
  return { turnId: text(item.turnId, 'delivery turn'), packetDigest: text(item.packetDigest, 'delivery packet digest'), utf8Bytes: count(item.utf8Bytes, 'delivery packet bytes'),
    contributionCount: count(item.contributionCount, 'delivery contribution count'), inputDigest: text(item.inputDigest, 'delivery input digest'),
    inputUtf8Bytes: count(item.inputUtf8Bytes, 'delivery input bytes'), discussionMessageId, discussionSupplement,
    preparedAt: text(item.preparedAt, 'delivery preparation time') };
}

/** Older runs can lack context. A present but invalid snapshot is an error, never a false "off" or "used" result. */
export function parseReviewContextSnapshot(value: unknown): ReviewContextSnapshotDto | null {
  if (value == null) return null;
  const item = object(value, 'review snapshot');
  const packet = item.bootstrap == null ? null : object(item.bootstrap, 'bootstrap packet');
  const result: ReviewContextSnapshotDto = {
    id: text(item.id, 'snapshot id'), enabled: flag(item.enabled, 'captured preference'), notebookRevision: count(item.notebookRevision, 'snapshot revision'),
    repositoryId: count(item.repositoryId, 'snapshot repository', 1), baselineSha: optionalText(item.baselineSha, 'baseline commit'),
    bootstrap: packet === null ? null : { text: text(packet.text, 'bootstrap text'), digest: text(packet.digest, 'bootstrap digest'), utf8Bytes: count(packet.utf8Bytes, 'packet bytes') },
    selectedEntries: array(item.selectedEntries, 'selected entries', selectedEntry),
    relatedItems: array(item.relatedItems, 'related items', value => {
      const related = object(value, 'related source');
      return { sourceId: text(related.sourceId, 'related source id'), matchReasons: strings(related.matchReasons, 'related reasons'),
        presentInReviewedBase: related.presentInReviewedBase == null ? null : flag(related.presentInReviewedBase, 'source ancestry') };
    }),
    inputFingerprints: array(item.inputFingerprints, 'input fingerprints', inputFingerprint),
    sourceReads: array(item.sourceReads, 'source receipts', sourceRead), gaps: strings(item.gaps, 'snapshot limitations'),
    contributions: array(item.contributions, 'context contributions', contribution), deliveries: array(item.deliveries, 'context deliveries', delivery),
  };
  if (result.enabled !== (result.bootstrap !== null)) malformed('snapshot enabled state and packet disagree');
  if (!result.enabled && (result.selectedEntries.length > 0 || result.relatedItems.length > 0
    || result.inputFingerprints.length > 0 || result.sourceReads.length > 0 || result.gaps.length > 0
    || result.contributions.length > 0 || result.deliveries.length > 0)) malformed('disabled snapshot contains context');
  if (result.deliveries.some(item => item.contributionCount > result.contributions.length)) malformed('delivery refers to unavailable contributions');
  return result;
}

function request(value: unknown, fields: string[]): Record<string, unknown> {
  const item = object(value, 'request');
  for (const key of Object.keys(item)) if (!fields.includes(key)) malformed(`unsupported request field ${key}`);
  return item;
}
function runFence(item: Record<string, unknown>, field: string): ContextRunFence {
  const result = { requestKey: text(item.requestKey, `${field} request key`), expectedRevision: count(item.expectedRevision, 'expected revision'),
    expectedBuildSequence: count(item.expectedBuildSequence, 'expected build sequence') };
  if (result.requestKey.trim() === '') malformed(`${field} request key`);
  return result;
}
function nonblank(value: unknown, field: string): string {
  const result = text(value, field);
  if (result.trim() === '') malformed(field);
  return result;
}
export function parseStartContextDiscoveryRequest(value: unknown): StartContextDiscoveryRequest {
  const item = request(value, ['requestKey', 'expectedRevision', 'expectedBuildSequence', 'continuation']);
  return { ...runFence(item, 'discovery'), continuation: flag(item.continuation, 'discovery continuation') };
}
export function parseStartContextLearningRequest(value: unknown): StartContextLearningRequest {
  const item = request(value, ['requestKey', 'expectedRevision', 'expectedBuildSequence', 'reportId', 'topicIds', 'excludedSourceIds']);
  const result = { ...runFence(item, 'learning'), reportId: text(item.reportId, 'learning report id'), topicIds: strings(item.topicIds, 'learning topic IDs'),
    excludedSourceIds: strings(item.excludedSourceIds, 'excluded source IDs') };
  if (result.reportId.trim() === '' || result.topicIds.length === 0 || result.topicIds.length > 20 || result.excludedSourceIds.length > 200
    || result.topicIds.some(id => id.trim() === '') || new Set(result.topicIds).size !== result.topicIds.length) malformed('learning selection');
  return result;
}
export function parseContinueContextLearningRequest(value: unknown): ContinueContextLearningRequest {
  const item = request(value, ['requestKey', 'expectedRevision', 'expectedBuildSequence', 'planId']);
  return { ...runFence(item, 'continuation'), planId: nonblank(item.planId, 'learning plan id') };
}
export function parseDiscardContextLearningPlanRequest(value: unknown): DiscardContextLearningPlanRequest {
  const item = request(value, ['planId', 'expectedRevision', 'expectedBuildSequence']);
  return { planId: nonblank(item.planId, 'discarded plan id'), expectedRevision: count(item.expectedRevision, 'expected revision'),
    expectedBuildSequence: count(item.expectedBuildSequence, 'expected build sequence') };
}
function suggestedUrl(value: unknown): string {
  const url = text(value, 'suggested source URL');
  if (!url.startsWith('https://')) malformed('suggested source URL');
  return url;
}
export function parseAllowContextLearningSourceRequest(value: unknown): AllowContextLearningSourceRequest {
  const item = request(value, ['planId', 'suggestedUrl', 'boundary', 'expectedRevision', 'expectedBuildSequence']);
  const result = { ...parseDiscardContextLearningPlanRequest({ planId: item.planId, expectedRevision: item.expectedRevision,
    expectedBuildSequence: item.expectedBuildSequence }), suggestedUrl: suggestedUrl(item.suggestedUrl),
  boundary: oneOf(item.boundary, ['PAGE', 'ROOT'], 'source boundary') };
  if (result.boundary === 'ROOT' && !result.suggestedUrl.endsWith('/')) malformed('documentation root must end in /');
  return result;
}
export function parseDeclineContextLearningSourceRequest(value: unknown): DeclineContextLearningSourceRequest {
  const item = request(value, ['planId', 'suggestedUrl']);
  return { planId: nonblank(item.planId, 'plan id'), suggestedUrl: suggestedUrl(item.suggestedUrl) };
}
export function parseLearnContextMergedCandidateRequest(value: unknown): LearnContextMergedCandidateRequest {
  const item = request(value, ['candidateId', 'requestKey', 'expectedRevision', 'expectedBuildSequence']);
  return { ...runFence(item, 'candidate'), candidateId: nonblank(item.candidateId, 'candidate ID') };
}
export function parseContextMergedCandidates(value: unknown): ContextMergedCandidatesDto {
  const response = object(value, 'merged candidates');
  const candidates = array(response.candidates, 'merged candidates', value => {
    const candidate = object(value, 'merged candidate');
    const issue = candidate.issue == null ? null : object(candidate.issue, 'candidate issue');
    const review = candidate.review == null ? null : object(candidate.review, 'candidate review');
    return { id: text(candidate.id, 'candidate ID'), repo: text(candidate.repo, 'candidate repository'),
      number: count(candidate.number, 'candidate PR number', 1), title: text(candidate.title, 'candidate title'),
      mergedAt: text(candidate.mergedAt, 'candidate merge time'), taskTitle: nullableText(candidate.taskTitle, 'candidate task title'),
      taskResult: nullableText(candidate.taskResult, 'candidate task result'),
      issue: issue === null ? null : { key: text(issue.key, 'issue key'), title: text(issue.title, 'issue title') },
      review: review === null ? null : { combinedResult: text(review.combinedResult, 'review result') },
      checks: array(candidate.checks, 'candidate checks', value => ({ conclusion: text(object(value, 'candidate check').conclusion, 'check conclusion') })) };
  });
  if (new Set(candidates.map(item => item.id)).size !== candidates.length
    || candidates.some(item => item.id !== `pr:${item.repo.toLowerCase()}#${item.number}`)) malformed('candidate identity');
  return { candidates };
}
function coverage(value: unknown): ContextCoverageDto {
  const item = object(value, 'discovery coverage');
  return { areas: array(item.areas, 'coverage areas', value => {
    const area = object(value, 'coverage area');
    const result = { path: text(area.path, 'coverage path'), surveyed: count(area.surveyed, 'surveyed directories'), total: count(area.total, 'module directories', 1) };
    if (result.surveyed > result.total) malformed('coverage count');
    return result;
  }), unsurveyed: strings(item.unsurveyed, 'unsurveyed directories'), unsurveyedTruncated: flag(item.unsurveyedTruncated, 'unsurveyed truncation') };
}
export function parseContextDiscoveryReport(value: unknown, workspaceId: string): ContextDiscoveryReportDto | null {
  const response = object(value, 'discovery report response');
  if (response.report === null) return null;
  const report = object(response.report, 'discovery report');
  const result: ContextDiscoveryReportDto = {
    id: text(report.id, 'discovery report id'), workspaceId: text(report.workspaceId, 'discovery workspace'),
    baselineSha: text(report.baselineSha, 'discovery baseline'),
    topics: array(report.topics, 'discovery topics', value => { const topic = object(value, 'discovery topic');
      const questions = strings(topic.questions, 'topic questions');
      if (questions.length === 0 || questions.length > 6) malformed('topic questions');
      return { id: text(topic.id, 'topic id'), title: text(topic.title, 'topic title'), questions,
        paths: strings(topic.paths, 'topic paths'), notePaths: topic.notePaths == null ? null : strings(topic.notePaths, 'topic note paths'),
        sourceIds: strings(topic.sourceIds, 'topic source IDs'),
        evidenceReadIds: strings(topic.evidenceReadIds, 'topic evidence IDs'), why: text(topic.why, 'topic rationale') }; }),
    sources: array(report.sources, 'discovery sources', value => { const source = object(value, 'discovery source');
      return { id: text(source.id, 'discovery source id'), kind: oneOf(source.kind,
        ['REPOSITORY_FILE', 'DOCUMENTATION_ROOT', 'DOCUMENTATION_PAGE'], 'discovery source kind'),
      location: text(source.location, 'discovery source location') }; }),
    evidence: array(report.evidence, 'discovery evidence', value => { const evidence = object(value, 'discovery evidence');
      return { readId: text(evidence.readId, 'discovery read ID'), path: text(evidence.path, 'discovery read path'),
        startLine: count(evidence.startLine, 'discovery start line', 1), endLine: count(evidence.endLine, 'discovery end line', 1) }; }),
    remaining: strings(report.remaining, 'remaining discovery'),
    partial: flag(report.partial, 'partial discovery'), coverage: coverage(report.coverage), createdAt: text(report.createdAt, 'discovery report time'),
  };
  if (result.workspaceId !== workspaceId || result.evidence.some(item => item.endLine < item.startLine)) malformed('discovery report ownership or evidence');
  const readIds = new Set(result.evidence.map(item => item.readId));
  const sourceIds = new Set(result.sources.map(item => item.id));
  if (sourceIds.size !== result.sources.length || readIds.size !== result.evidence.length
    || new Set(result.topics.map(item => item.id)).size !== result.topics.length
    || result.topics.some(topic => topic.sourceIds.some(id => !sourceIds.has(id)) || topic.evidenceReadIds.some(id => !readIds.has(id))))
    malformed('discovery report references');
  return result;
}
function planTopic(value: unknown): ContextPlanTopicDto {
  const topic = object(value, 'plan topic');
  const questions = array(topic.questions, 'plan questions', value => {
    const question = object(value, 'plan question');
    const result = { text: text(question.text, 'plan question text'), status: oneOf(question.status, ['PENDING', 'UNFINISHED', 'COMPLETED'], 'question status'),
      progress: text(question.progress, 'question progress') };
    if (result.status === 'PENDING' && result.progress !== '') malformed('unstarted question progress');
    return result;
  });
  if (questions.length === 0 || questions.length > 6) malformed('plan questions');
  return { id: text(topic.id, 'plan topic id'), title: text(topic.title, 'plan topic title'), scopeId: text(topic.scopeId, 'plan scope id'),
    paths: strings(topic.paths, 'plan paths'), notePaths: topic.notePaths == null ? null : strings(topic.notePaths, 'plan note paths'),
    repositoryFiles: strings(topic.repositoryFiles, 'plan repository files'),
    sourceIds: strings(topic.sourceIds, 'plan source IDs'), questions };
}
function questionTarget(value: unknown): ContextQuestionTarget {
  const item = object(value, 'question target');
  return { topicId: text(item.topicId, 'target topic'), question: count(item.question, 'target question') };
}
export function parseContextLearningPlanView(value: unknown, workspaceId: string): ContextLearningPlanViewDto {
  const response = object(value, 'learning plan response');
  const next = response.next == null ? null : questionTarget(response.next);
  if (response.plan == null) {
    if (next !== null) malformed('next question without a plan');
    return { plan: null, next: null };
  }
  const plan = object(response.plan, 'learning plan');
  const inventory = object(plan.documentInventory, 'document inventory');
  const result: ContextLearningPlanDto = {
    id: text(plan.id, 'plan id'), workspaceId: text(plan.workspaceId, 'plan workspace'), reportId: text(plan.reportId, 'plan report'),
    baselineSha: text(plan.baselineSha, 'plan baseline'), gaps: strings(plan.gaps, 'learning gaps'),
    topics: array(plan.topics, 'plan topics', planTopic),
    documentationSources: array(plan.documentationSources, 'plan documentation sources', source),
    documentInventory: {
      items: array(inventory.items, 'inventory items', value => { const item = object(value, 'inventory item');
        return { sourceId: text(item.sourceId, 'inventory source'), resolvedUrl: text(item.resolvedUrl, 'inventory URL'),
          relativePage: nullableText(item.relativePage, 'inventory relative page'), depth: count(item.depth, 'inventory depth'),
          status: oneOf(item.status, ['PENDING', 'READ', 'FAILED'], 'inventory status'), detail: text(item.detail, 'inventory detail') }; }),
      suggestions: array(inventory.suggestions, 'suggested sources', value => { const item = object(value, 'suggested source');
        return { url: text(item.url, 'suggestion URL'), parentUrl: text(item.parentUrl, 'suggestion parent'), topicId: text(item.topicId, 'suggestion topic'),
          question: count(item.question, 'suggestion question'), taskId: text(item.taskId, 'suggestion task'), foundAt: text(item.foundAt, 'suggestion time') }; }),
      truncated: flag(inventory.truncated, 'inventory truncation') },
  };
  const topicIds = result.topics.map(item => item.id);
  if (result.workspaceId !== workspaceId || new Set(topicIds).size !== topicIds.length
    || result.documentInventory.suggestions.some(item => !topicIds.includes(item.topicId))) malformed('learning plan identity');
  if (next !== null && result.topics.find(item => item.id === next.topicId)?.questions[next.question] === undefined) malformed('next question');
  return { plan: result, next };
}
function activity(value: unknown): ContextActivityDto {
  const item = object(value, 'run activity');
  return { at: text(item.at, 'activity time'), text: text(item.text, 'activity text'), tone: oneOf(item.tone, ['DONE', 'FAILED', 'LIMIT'], 'activity tone') };
}
function run(value: unknown): ContextRunDto {
  const item = object(value, 'context run');
  const question = item.question == null ? null : object(item.question, 'run question');
  return { sequence: count(item.sequence, 'run sequence', 1), taskId: text(item.taskId, 'run task'), mode: oneOf(item.mode, ['DISCOVER', 'LEARN'], 'run mode'),
    requestedAt: text(item.requestedAt, 'run request time'), endedAt: optionalText(item.endedAt, 'run end time'),
    question: question === null ? null : { planId: text(question.planId, 'run plan'), topicId: text(question.topicId, 'run topic'),
      topicTitle: text(question.topicTitle, 'run topic title'), index: count(question.index, 'run question index'), text: text(question.text, 'run question') },
    pullRequest: item.pullRequest == null ? null : count(item.pullRequest, 'run pull request', 1),
    outcome: oneOf(item.outcome, ['RUNNING', 'SUBMITTED', 'ANSWERED', 'NO_NOTE', 'UNFINISHED', 'LIMIT', 'STOPPED', 'FAILED'], 'run outcome'),
    sourceOperations: count(item.sourceOperations, 'run operations'), documentOpens: count(item.documentOpens, 'run document opens'),
    noteIds: strings(item.noteIds, 'run notes'), activity: array(item.activity, 'run activity', activity), detail: optionalText(item.detail, 'run detail') };
}
export function parseContextBaseline(value: unknown): ContextBaselineDto | null {
  if (value == null) return null;
  const item = object(value, 'monitored baseline');
  const sha = text(item.sha, 'baseline commit');
  if (!/^[0-9a-f]{40}([0-9a-f]{24})?$/.test(sha)) malformed('baseline commit');
  return { branch: text(item.branch, 'baseline branch'), sha };
}
export function parseContextTimes(value: unknown): ContextTimesDto {
  const item = object(value, 'context times');
  return { entries: Object.fromEntries(Object.entries(object(item.entries, 'entries')).map(([key, raw]) => {
    const stamp = object(raw, 'time stamp');
    return [key, { at: text(stamp.at, 'stamp time'), taskId: optionalText(stamp.taskId, 'stamp task') }];
  })) };
}
export function parseContextBuildView(value: unknown, workspaceId: string): ContextBuildViewDto {
  const item = object(value, 'build view');
  const result: ContextBuildViewDto = { buildRequestSequence: count(item.buildRequestSequence, 'build sequence'),
    notebookRevision: count(item.notebookRevision, 'build notebook revision'), canStart: flag(item.canStart, 'build availability'),
    runs: array(item.runs, 'context runs', run), build: null };
  if (item.build === null) return result;
  const build = object(item.build, 'build');
  const task = build.task === null ? null : object(build.task, 'build task');
  const turn = build.activeTurn === null ? null : object(build.activeTurn, 'build turn');
  result.build = { requestKey: text(build.requestKey, 'build request key'), taskId: text(build.taskId, 'build task id'),
    task: task === null ? null : { id: text(task.id, 'task id'), workspaceId: text(task.workspaceId, 'task workspace'),
      state: oneOf(task.state, ['OPEN', 'COMPLETED', 'FAILED'], 'task state'), mutationClaim: optionalText(task.mutationClaim, 'task claim'),
      worktreeRemovedAt: optionalText(task.worktreeRemovedAt, 'task cleanup') },
    activeTurn: turn === null ? null : { cancellationRequestedAt: optionalText(turn.cancellationRequestedAt, 'turn cancellation') },
    pendingApprovals: count(build.pendingApprovals, 'pending approvals'), continuation: flag(build.continuation, 'discovery continuation') };
  if (result.build.task !== null && (result.build.task.id !== result.build.taskId || result.build.task.workspaceId !== workspaceId)) malformed('build task ownership');
  return result;
}
export function parseContextUsageOverview(value: unknown, window: ContextUsageWindow): ContextUsageOverviewDto {
  const item = object(value, 'usage overview');
  if (item.window !== window) malformed('usage window');
  const summary = object(item.summary, 'usage summary');
  return { trackingSince: nullableText(item.trackingSince, 'tracking start'), window,
    summary: { eligibleDevelopmentTasks: count(summary.eligibleDevelopmentTasks, 'eligible development tasks'),
      eligibleReviewRuns: count(summary.eligibleReviewRuns, 'eligible review runs'), contextOffTasks: count(summary.contextOffTasks, 'context off tasks'),
      developmentTasksFoundOrOpened: count(summary.developmentTasksFoundOrOpened, 'development tasks that found or opened a note'),
      reviewRunsFoundOrOpened: count(summary.reviewRunsFoundOrOpened, 'review runs that found or opened a note') },
    entries: array(item.entries, 'usage entries', value => {
      const row = object(value, 'usage entry');
      const currentVersion = row.currentVersion === null ? null : count(row.currentVersion, 'current version', 1);
      const result = { entryId: text(row.entryId, 'usage entry id'), title: text(row.title, 'usage title'), currentVersion,
        foundTasks: count(row.foundTasks, 'found tasks'), openedTasks: count(row.openedTasks, 'opened tasks'),
        lastConfirmedUseAt: nullableText(row.lastConfirmedUseAt, 'last confirmed use'), helpedTasks: count(row.helpedTasks, 'helped tasks'),
        notRelevantTasks: count(row.notRelevantTasks, 'not relevant tasks'), wrongTasks: count(row.wrongTasks, 'wrong tasks') };
      if (currentVersion === null && (result.foundTasks > 0 || result.openedTasks > 0)) malformed('counts for a removed note');
      return result;
    }) };
}
function grade(value: unknown): ContextGradeDto {
  const item = object(value, 'grade');
  const note = object(item.note, 'graded note');
  const correction = item.correction == null ? null : object(item.correction, 'grade correction');
  return { workspaceId: text(item.workspaceId, 'grade workspace'), taskId: text(item.taskId, 'grading task'),
    reviewRunId: optionalText(item.reviewRunId, 'grading review run'), taskKind: oneOf(item.taskKind, ['DEVELOPMENT', 'PR_REVIEW'], 'grading task kind'),
    grader: oneOf(item.grader, ['AGENT', 'USER'], 'grader'), grade: oneOf(item.grade, ['HELPED', 'NOT_RELEVANT', 'WRONG', 'HOLDS'], 'grade'),
    note: { id: text(note.id, 'graded note id'), version: count(note.version, 'graded note version', 1), kind: oneOf(note.kind, ['KNOWLEDGE', 'MEMORY'], 'graded note kind'),
      scopeId: text(note.scopeId, 'graded note scope'), title: text(note.title, 'graded note title') },
    reason: text(item.reason, 'grade reason'), quote: optionalText(item.quote, 'grade quote'),
    citations: array(item.citations, 'grade citations', value => {
      const citation = object(value, 'grade citation');
      const result = { path: text(citation.path, 'cited path'), startLine: count(citation.startLine, 'cited start line', 1),
        endLine: count(citation.endLine, 'cited end line', 1), commit: text(citation.commit, 'cited commit'), excerpt: text(citation.excerpt, 'cited excerpt') };
      if (result.endLine < result.startLine) malformed('cited line range');
      return result;
    }),
    correction: correction === null ? null : { version: count(correction.version, 'correction version', 1), statement: text(correction.statement, 'corrected statement') },
    gradedAt: text(item.gradedAt, 'grade time') };
}
/** Every grade must be of the workspace and the exact version it is listed under. */
export function parseContextReportedNotes(value: unknown, workspaceId: string): ContextReportedNoteDto[] {
  return array(value, 'reported notes', raw => {
    const item = object(raw, 'reported note');
    const result: ContextReportedNoteDto = { entryId: text(item.entryId, 'reported note id'), entryVersion: count(item.entryVersion, 'reported note version', 1),
      title: text(item.title, 'reported note title'), userWritten: flag(item.userWritten, 'reported note author'), grades: array(item.grades, 'reported note grades', grade) };
    if (result.grades.some(value => value.workspaceId !== workspaceId || value.note.id !== result.entryId || value.note.version !== result.entryVersion))
      malformed('reported note grades');
    return result;
  });
}
export function parseConfigureRepositoryContextRequest(value: unknown): ConfigureRepositoryContextRequest {
  const item = request(value, ['expectedRevision', 'useInReviews', 'useInDevelopment', 'sources']);
  const result: ConfigureRepositoryContextRequest = { expectedRevision: count(item.expectedRevision, 'expected revision') };
  if (item.useInReviews !== undefined) result.useInReviews = flag(item.useInReviews, 'review preference');
  if (item.useInDevelopment !== undefined) result.useInDevelopment = flag(item.useInDevelopment, 'development preference');
  if (item.sources !== undefined) result.sources = array(item.sources, 'sources', value => {
    const source = request(value, ['id', 'kind', 'url']);
    return { id: text(source.id, 'source id'), kind: oneOf(source.kind, ['DOCUMENTATION_ROOT', 'DOCUMENTATION_PAGE'], 'source kind'), url: text(source.url, 'source URL') };
  });
  return result;
}
export function parseEditRepositoryContextEntryRequest(value: unknown): EditRepositoryContextEntryRequest {
  const item = request(value, ['expectedRevision', 'id', 'scopeId', 'title', 'summary', 'body', 'claims', 'sourceIds']);
  return { expectedRevision: count(item.expectedRevision, 'expected revision'), id: text(item.id, 'entry id'), scopeId: text(item.scopeId, 'entry scope'),
    title: text(item.title, 'entry title'), summary: text(item.summary, 'entry summary'), body: text(item.body, 'entry body'),
    claims: array(item.claims, 'claims', claim), sourceIds: strings(item.sourceIds, 'entry sources') };
}
export function parseSetRepositoryContextEntryEnabledRequest(value: unknown): SetRepositoryContextEntryEnabledRequest {
  const item = request(value, ['expectedRevision', 'entryId', 'enabled']);
  return { expectedRevision: count(item.expectedRevision, 'expected revision'), entryId: text(item.entryId, 'entry id'), enabled: flag(item.enabled, 'entry enabled') };
}
export function parseRestoreRepositoryContextEntryRequest(value: unknown): RestoreRepositoryContextEntryRequest {
  const item = request(value, ['expectedRevision', 'entryId']);
  return { expectedRevision: count(item.expectedRevision, 'expected revision'), entryId: nonblank(item.entryId, 'restored entry id') };
}
export function parseReportRepositoryContextNoteRequest(value: unknown): ReportRepositoryContextNoteRequest {
  const item = request(value, ['taskId', 'entryId', 'entryVersion', 'reason']);
  return { taskId: nonblank(item.taskId, 'reporting task'), entryId: nonblank(item.entryId, 'reported entry id'),
    entryVersion: count(item.entryVersion, 'reported entry version', 1), reason: text(item.reason, 'report reason') };
}
export function parseDisableRepositoryContextRunNotesRequest(value: unknown): DisableRepositoryContextRunNotesRequest {
  const item = request(value, ['expectedRevision', 'taskId']);
  return { expectedRevision: count(item.expectedRevision, 'expected revision'), taskId: nonblank(item.taskId, 'run task') };
}
export function parseSaveRepositoryContextLessonRequest(value: unknown): SaveRepositoryContextLessonRequest {
  const item = request(value, ['expectedRevision', 'scopeId', 'title', 'summary', 'body', 'reviewId', 'runId', 'evidenceRefs', 'messageId']);
  return { expectedRevision: count(item.expectedRevision, 'expected revision'), scopeId: text(item.scopeId, 'lesson scope'),
    title: text(item.title, 'lesson title'), summary: text(item.summary, 'lesson summary'), body: text(item.body, 'lesson body'),
    reviewId: text(item.reviewId, 'lesson review'), runId: text(item.runId, 'lesson run'), evidenceRefs: strings(item.evidenceRefs, 'lesson evidence'),
    messageId: optionalText(item.messageId, 'lesson message') };
}
