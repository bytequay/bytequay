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
export type ContextScopeConfigurationDto = { id: string; title: string; match: ContextMatchDto; enabled: boolean };
export type ContextScopeDto = ContextScopeConfigurationDto & { preparation: ContextPreparationResultDto | null; discoveryEvidenceRef: string | null };
export type ContextSourceDto = { id: string; kind: 'DOCUMENTATION_ROOT' | 'DOCUMENTATION_PAGE'; url: string; origin: ContextOrigin; discoveryEvidenceRef: string | null };
export type ContextSourceConfigurationDto = Pick<ContextSourceDto, 'id' | 'kind' | 'url'>;
export type ContextClaimDto = {
  text: string;
  basis: 'DOCUMENTED' | 'RECORDED_DECISION' | 'INFERENCE' | 'QUESTION' | 'USER_LESSON';
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
  id: string; reference: ContextReferenceDto; provenance: string; excerpt: string; capture: 'BYTEQUAY_READ' | 'AGENT_REPORTED';
  retrievedAt: string | null; contentHash: string | null; version: string | null; truncated: boolean;
};
export type RepositoryNotebookDto = {
  schemaVersion: number; revision: number; useInReviews: boolean; sources: ContextSourceDto[]; scopes: ContextScopeDto[];
  entries: ContextEntryDto[]; suppressedKnowledgeIds: string[]; sourceEvidence: ContextSourceEvidenceDto[];
  activePreparation: ActiveContextPreparationDto | null;
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
export type ContextPreparationResultDto = {
  attemptId: string; inputFingerprint: ContextInputFingerprintDto; outcome: 'READY' | 'EMPTY' | 'FAILED';
  noteIds: string[]; checkedReads: ContextSourceReadDto[]; explanation: string;
};
export type ActiveContextPreparationDto = {
  attemptId: string; requestKey: string; requestDigest: string; ownerTaskId: string; ownerTurnId: string; ownerTurnKey: string;
  reviewId: string | null; runId: string | null; baseContentRevision: number; inputs: ContextInputFingerprintDto[];
};
export type ContextBuildHistoryDto = { kind: 'PR' | 'ISSUE'; number: number };
export type DiscoverRepositoryContextRequest = { expectedRevision: number; scopeIds: string[]; query: string; includeRemote: boolean };
export type ApplyContextDiscoveryRequest = { expectedRevision: number; baselineSha: string; repositoryFingerprint: string; query: string; scopeIds: string[]; sourceIds: string[] };
export type ContextDiscoveryDto = {
  workspaceId: string; notebookRevision: number; baselineSha: string; repositoryFingerprint: string; query: string;
  scopes: Array<{ scope: ContextScopeDto; evidence: ContextSourceEvidenceDto; reasons: string[] }>;
  sources: Array<{ source: ContextSourceDto; evidence: ContextSourceEvidenceDto; reasons: string[] }>;
  history: Array<ContextBuildHistoryDto & { title: string; url: string; state: string; outcome: string; updatedAt: string; reasons: string[]; provenance: string }>;
  gaps: string[]; inspected: { directories: number; files: number; pullRequests: number; issues: number };
};
export type StartContextBuildRequest = {
  requestKey: string; expectedRevision: number; expectedBuildSequence: number; scopeIds: string[]; history: ContextBuildHistoryDto[];
};
export type ContextBuildViewDto = {
  buildRequestSequence: number; notebookRevision: number; canStart: boolean;
  build: null | {
    requestKey: string; taskId: string; baselineSha: string; scopeIds: string[]; history: ContextBuildHistoryDto[]; requestedAt: string;
    task: null | { id: string; workspaceId: string; state: 'OPEN' | 'COMPLETED' | 'FAILED'; version: number; laneId: string; model: string | null;
      mutationClaim: string | null; terminalReason: string | null; worktreePath: string; worktreeRemovedAt: string | null };
    activeTurn: null | { id: string; state: string; startedAt: string | null; cancellationRequestedAt: string | null };
    pendingApprovals: number; results: ContextPreparationResultDto[]; gaps: string[];
  };
};
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
  expectedRevision: number; useInReviews?: boolean; sources?: ContextSourceConfigurationDto[]; scopes?: ContextScopeConfigurationDto[];
};
export type EditRepositoryContextEntryRequest = {
  expectedRevision: number; id: string; scopeId: string; title: string; summary: string; body: string; claims: ContextClaimDto[]; sourceIds: string[];
};
export type SetRepositoryContextEntryEnabledRequest = { expectedRevision: number; entryId: string; enabled: boolean };
export type RemoveRepositoryContextEntryRequest = { expectedRevision: number; entryId: string };
export type ResetRepositoryContextRequest = { expectedRevision: number; scopeIds: string[] };
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
function count(value: unknown, field: string, minimum = 0): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < minimum) malformed(field);
  return value;
}
function flag(value: unknown, field: string): boolean {
  if (typeof value !== 'boolean') malformed(field);
  return value;
}
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
function scopeConfiguration(value: unknown): ContextScopeConfigurationDto {
  const item = object(value, 'scope');
  const match = object(item.match, 'scope match');
  const kind = oneOf(match.kind, ['REPOSITORY', 'SELECTED'], 'scope match kind');
  return { id: text(item.id, 'scope id'), title: text(item.title, 'scope title'), enabled: flag(item.enabled, 'scope enabled'),
    match: kind === 'REPOSITORY' ? { kind } : { kind, pathPrefixes: strings(match.pathPrefixes, 'path prefixes'), terms: strings(match.terms, 'terms') } };
}
function scope(value: unknown): ContextScopeDto {
  const item = object(value, 'scope');
  return { ...scopeConfiguration(item), preparation: item.preparation == null ? null : preparationResult(item.preparation),
    discoveryEvidenceRef: item.discoveryEvidenceRef === null ? null : text(item.discoveryEvidenceRef, 'scope discovery evidence') };
}
function claim(value: unknown): ContextClaimDto {
  const item = object(value, 'claim');
  return { text: text(item.text, 'claim text'), basis: oneOf(item.basis,
    ['DOCUMENTED', 'RECORDED_DECISION', 'INFERENCE', 'QUESTION', 'USER_LESSON'], 'claim basis'), sourceIds: strings(item.sourceIds, 'claim source IDs') };
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
    excerpt: text(item.excerpt, 'evidence excerpt'), capture: oneOf(item.capture, ['BYTEQUAY_READ', 'AGENT_REPORTED'], 'capture kind'),
    retrievedAt: optionalText(item.retrievedAt, 'retrieval time'), contentHash: optionalText(item.contentHash, 'content hash'),
    version: optionalText(item.version, 'source version'), truncated: flag(item.truncated, 'source truncation') };
}

/** Parsing never turns malformed content into an empty notebook or an off preference. */
export function parseRepositoryNotebook(value: unknown): RepositoryNotebookDto {
  const item = object(value, 'notebook');
  if (item.schemaVersion !== 1) malformed('notebook schema version');
  return { schemaVersion: 1, revision: count(item.revision, 'notebook revision'), useInReviews: flag(item.useInReviews, 'review preference'),
    sources: array(item.sources, 'sources', source), scopes: array(item.scopes, 'scopes', scope), entries: array(item.entries, 'entries', entry),
    suppressedKnowledgeIds: strings(item.suppressedKnowledgeIds, 'suppression'), sourceEvidence: array(item.sourceEvidence, 'source evidence', evidence),
    activePreparation: item.activePreparation == null ? null : activePreparation(item.activePreparation) };
}
function inputFingerprint(value: unknown): ContextInputFingerprintDto {
  const input = object(value, 'input fingerprint');
  return { scopeId: text(input.scopeId, 'input scope'), inputs: stringMap(input.inputs, 'input identities'),
    digest: optionalText(input.digest, 'input digest'), gaps: strings(input.gaps, 'input limitations') };
}
function preparationResult(value: unknown): ContextPreparationResultDto {
  const item = object(value, 'preparation result');
  const result: ContextPreparationResultDto = { attemptId: text(item.attemptId, 'preparation attempt'), inputFingerprint: inputFingerprint(item.inputFingerprint),
    outcome: oneOf(item.outcome, ['READY', 'EMPTY', 'FAILED'], 'preparation outcome'), noteIds: strings(item.noteIds, 'prepared note ids'),
    checkedReads: array(item.checkedReads, 'checked source reads', sourceRead), explanation: text(item.explanation, 'preparation explanation') };
  if (result.outcome === 'EMPTY' && result.checkedReads.some(read => read.kind === 'HISTORY' || read.truncated
    || (read.kind === 'DOCUMENT' && read.refreshFailed))) malformed('empty preparation has incomplete or historical source reads');
  return result;
}
function activePreparation(value: unknown): ActiveContextPreparationDto {
  const item = object(value, 'active preparation');
  const reviewId = optionalText(item.reviewId, 'preparation review');
  const runId = optionalText(item.runId, 'preparation run');
  if ((reviewId === null) !== (runId === null)) malformed('preparation review and run must be paired');
  return { attemptId: text(item.attemptId, 'preparation attempt'), requestKey: text(item.requestKey, 'preparation request key'),
    requestDigest: text(item.requestDigest, 'preparation request digest'), ownerTaskId: text(item.ownerTaskId, 'preparation task'),
    ownerTurnId: text(item.ownerTurnId, 'preparation turn'), ownerTurnKey: text(item.ownerTurnKey, 'preparation turn key'),
    reviewId, runId,
    baseContentRevision: count(item.baseContentRevision, 'preparation revision'), inputs: array(item.inputs, 'preparation inputs', inputFingerprint) };
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
function buildHistory(value: unknown): ContextBuildHistoryDto {
  const item = request(value, ['kind', 'number']);
  return { kind: oneOf(item.kind, ['PR', 'ISSUE'], 'history kind'), number: count(item.number, 'history number', 1) };
}
export function parseStartContextBuildRequest(value: unknown): StartContextBuildRequest {
  const item = request(value, ['requestKey', 'expectedRevision', 'expectedBuildSequence', 'scopeIds', 'history']);
  const result = { requestKey: text(item.requestKey, 'build request key'), expectedRevision: count(item.expectedRevision, 'expected revision'),
    expectedBuildSequence: count(item.expectedBuildSequence, 'expected build sequence'), scopeIds: strings(item.scopeIds, 'build scopes'),
    history: array(item.history, 'selected history', buildHistory) };
  if (result.requestKey.trim() === '' || result.scopeIds.length === 0 || result.scopeIds.length > 100
    || result.scopeIds.some(id => id.trim() === '') || new Set(result.scopeIds).size !== result.scopeIds.length) malformed('build request identity or scopes');
  if (result.history.length > 10 || new Set(result.history.map(item => `${item.kind}:${item.number}`)).size !== result.history.length
    || result.history.some(item => item.number > 2147483647)) malformed('selected history limit or identity');
  return result;
}
export function parseContextBuildView(value: unknown, workspaceId: string): ContextBuildViewDto {
  const item = object(value, 'build view');
  const result: ContextBuildViewDto = { buildRequestSequence: count(item.buildRequestSequence, 'build sequence'),
    notebookRevision: count(item.notebookRevision, 'build notebook revision'), canStart: flag(item.canStart, 'build availability'), build: null };
  if (item.build === null) return result;
  const build = object(item.build, 'build');
  const task = build.task === null ? null : object(build.task, 'build task');
  const turn = build.activeTurn === null ? null : object(build.activeTurn, 'build turn');
  result.build = { requestKey: text(build.requestKey, 'build request key'), taskId: text(build.taskId, 'build task id'),
    baselineSha: text(build.baselineSha, 'build baseline'), scopeIds: strings(build.scopeIds, 'build scopes'),
    history: array(build.history, 'build history', buildHistory), requestedAt: text(build.requestedAt, 'build request time'),
    task: task === null ? null : { id: text(task.id, 'task id'), workspaceId: text(task.workspaceId, 'task workspace'),
      state: oneOf(task.state, ['OPEN', 'COMPLETED', 'FAILED'], 'task state'), version: count(task.version, 'task version'),
      laneId: text(task.laneId, 'task provider'), model: optionalText(task.model, 'task model'), mutationClaim: optionalText(task.mutationClaim, 'task claim'),
      terminalReason: optionalText(task.terminalReason, 'task result'), worktreePath: text(task.worktreePath, 'task worktree'),
      worktreeRemovedAt: optionalText(task.worktreeRemovedAt, 'task cleanup') },
    activeTurn: turn === null ? null : { id: text(turn.id, 'turn id'), state: text(turn.state, 'turn state'),
      startedAt: optionalText(turn.startedAt, 'turn start'), cancellationRequestedAt: optionalText(turn.cancellationRequestedAt, 'turn cancellation') },
    pendingApprovals: count(build.pendingApprovals, 'pending approvals'), results: array(build.results, 'build results', preparationResult),
    gaps: strings(build.gaps, 'build limitations') };
  if (result.build.task !== null && (result.build.task.id !== result.build.taskId || result.build.task.workspaceId !== workspaceId)) malformed('build task ownership');
  return result;
}
export function parseDiscoverRepositoryContextRequest(value: unknown): DiscoverRepositoryContextRequest {
  const item = request(value, ['expectedRevision', 'scopeIds', 'query', 'includeRemote']);
  return { expectedRevision: count(item.expectedRevision, 'expected revision'), scopeIds: strings(item.scopeIds, 'discovery scopes'),
    query: text(item.query, 'discovery query'), includeRemote: flag(item.includeRemote, 'remote discovery') };
}
export function parseApplyContextDiscoveryRequest(value: unknown): ApplyContextDiscoveryRequest {
  const item = request(value, ['expectedRevision', 'baselineSha', 'repositoryFingerprint', 'query', 'scopeIds', 'sourceIds']);
  if (typeof item.repositoryFingerprint !== 'string' || !/^[a-f0-9]{64}$/.test(item.repositoryFingerprint)) malformed('repository fingerprint');
  return { expectedRevision: count(item.expectedRevision, 'expected revision'), baselineSha: text(item.baselineSha, 'discovery baseline'),
    repositoryFingerprint: item.repositoryFingerprint, query: text(item.query, 'discovery query'),
    scopeIds: strings(item.scopeIds, 'selected scope suggestions'), sourceIds: strings(item.sourceIds, 'selected source suggestions') };
}
export function parseContextDiscovery(value: unknown, workspaceId: string): ContextDiscoveryDto {
  const item = object(value, 'discovery');
  if (item.workspaceId !== workspaceId) malformed('discovery workspace');
  if (typeof item.repositoryFingerprint !== 'string' || !/^[a-f0-9]{64}$/.test(item.repositoryFingerprint)) malformed('repository fingerprint');
  const inspected = object(item.inspected, 'inspected sources');
  return { workspaceId, notebookRevision: count(item.notebookRevision, 'discovery revision'), baselineSha: text(item.baselineSha, 'discovery baseline'),
    repositoryFingerprint: item.repositoryFingerprint, query: text(item.query, 'discovery query'), scopes: array(item.scopes, 'scope suggestions', value => {
      const suggestion = object(value, 'scope suggestion');
      return { scope: scope(suggestion.scope), evidence: evidence(suggestion.evidence), reasons: strings(suggestion.reasons, 'scope reasons') };
    }), sources: array(item.sources, 'source suggestions', value => {
      const suggestion = object(value, 'source suggestion');
      return { source: source(suggestion.source), evidence: evidence(suggestion.evidence), reasons: strings(suggestion.reasons, 'source reasons') };
    }), history: array(item.history, 'history suggestions', value => {
      const suggestion = object(value, 'history suggestion');
      return { ...buildHistory({ kind: suggestion.kind, number: suggestion.number }), title: text(suggestion.title, 'history title'), url: text(suggestion.url, 'history URL'),
        state: text(suggestion.state, 'history state'), outcome: text(suggestion.outcome, 'history outcome'), updatedAt: text(suggestion.updatedAt, 'history update'),
        reasons: strings(suggestion.reasons, 'history reasons'), provenance: text(suggestion.provenance, 'history provenance') };
    }), gaps: strings(item.gaps, 'discovery gaps'), inspected: { directories: count(inspected.directories, 'inspected directories'), files: count(inspected.files, 'inspected files'),
      pullRequests: count(inspected.pullRequests, 'inspected PRs'), issues: count(inspected.issues, 'inspected issues') } };
}
export function parseConfigureRepositoryContextRequest(value: unknown): ConfigureRepositoryContextRequest {
  const item = request(value, ['expectedRevision', 'useInReviews', 'sources', 'scopes']);
  const result: ConfigureRepositoryContextRequest = { expectedRevision: count(item.expectedRevision, 'expected revision') };
  if (item.useInReviews !== undefined) result.useInReviews = flag(item.useInReviews, 'review preference');
  if (item.scopes !== undefined) result.scopes = array(item.scopes, 'scopes', scopeConfiguration);
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
export function parseRemoveRepositoryContextEntryRequest(value: unknown): RemoveRepositoryContextEntryRequest {
  const item = request(value, ['expectedRevision', 'entryId']);
  return { expectedRevision: count(item.expectedRevision, 'expected revision'), entryId: text(item.entryId, 'entry id') };
}
export function parseResetRepositoryContextRequest(value: unknown): ResetRepositoryContextRequest {
  const item = request(value, ['expectedRevision', 'scopeIds']);
  return { expectedRevision: count(item.expectedRevision, 'expected revision'), scopeIds: strings(item.scopeIds, 'reset scopes') };
}
export function parseSaveRepositoryContextLessonRequest(value: unknown): SaveRepositoryContextLessonRequest {
  const item = request(value, ['expectedRevision', 'scopeId', 'title', 'summary', 'body', 'reviewId', 'runId', 'evidenceRefs', 'messageId']);
  return { expectedRevision: count(item.expectedRevision, 'expected revision'), scopeId: text(item.scopeId, 'lesson scope'),
    title: text(item.title, 'lesson title'), summary: text(item.summary, 'lesson summary'), body: text(item.body, 'lesson body'),
    reviewId: text(item.reviewId, 'lesson review'), runId: text(item.runId, 'lesson run'), evidenceRefs: strings(item.evidenceRefs, 'lesson evidence'),
    messageId: optionalText(item.messageId, 'lesson message') };
}
