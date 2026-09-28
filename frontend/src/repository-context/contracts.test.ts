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
import { parseAgentReview, type ContextNoteReadDto, type ContextNoteSearchDto, type ContextPreparationActivityDto, type ContextDeliveryStateDto } from '../pulls/agent-review/contracts';
import { reviewFixture } from '../pulls/agent-review/fixtures/review';
import {
  parseConfigureRepositoryContextRequest,
  parseContextBuildView,
  parseContextDiscoveryReport,
  parseContextLearningPlan,
  parseContextUsageActivities,
  parseContextUsageEntryId,
  parseContextUsageOverview,
  parseContextUsagePage,
  parseContextUsageWindow,
  parseContextDraftActionRequest,
  parseEditContextDraftRequest,
  parseEditRepositoryContextEntryRequest,
  parseRepositoryNotebook,
  parseReviewContextSnapshot,
  parseSaveRepositoryContextLessonRequest,
  parseStartContextDiscoveryRequest,
  parseStartContextLearningRequest,
  parseContinueContextLearningRequest,
  parseDiscardContextLearningPlanRequest,
  parseAllowContextLearningSourceRequest,
  parseContextMergedCandidates,
  parseLearnContextMergedCandidateRequest,
  type ActiveContextPreparationDto,
  type ContextBuildViewDto,
  type ContextDiscoveryReportDto,
  type ContextLearningPlanDto,
  type ContextMergedCandidateDto,
  type ContextDeliveryDto,
  type ContextHistoryReadDto,
  type ContextUsageActivitiesDto,
  type ContextUsageOverviewDto,
  type ContextPreparationResultDto,
  type ContextSourceDto,
  type RepositoryNotebookDto,
  type ReviewContextSnapshotDto,
} from './contracts';

const source: ContextSourceDto = { id: 'docs', kind: 'DOCUMENTATION_ROOT', url: 'https://example.com/docs/', origin: 'USER', discoveryEvidenceRef: null };
const notebook: RepositoryNotebookDto = {
  schemaVersion: 1, revision: 7, useInReviews: true, useInDevelopment: true, sources: [source], activePreparation: null,
  scopes: [{ id: 'project', title: 'Project', match: { kind: 'REPOSITORY' }, enabled: true, preparation: null, discoveryEvidenceRef: null }],
  entries: [{ id: 'knowledge:project', kind: 'KNOWLEDGE', scopeId: 'project', title: 'Project', summary: 'Summary', body: 'Keep the condition.',
    claims: [{ text: 'Conditional claim', basis: 'INFERENCE', sourceIds: ['source'] }], sourceIds: ['source'], origin: 'GENERATED', enabled: false, version: 2 }],
  pendingDrafts: [], suppressedKnowledgeIds: [], sourceEvidence: [{ id: 'source', reference: { kind: 'DOCUMENT', sourceId: 'docs', url: 'https://example.com/docs/page' },
    provenance: 'Captured page', excerpt: 'Original text', capture: 'BYTEQUAY_READ', retrievedAt: null, contentHash: null, version: null, truncated: true }],
};
const usage: ContextUsageOverviewDto = {
  trackingSince: '2026-09-01T00:00:00Z', window: '30d', coverage: 'RETAINED_TASKS_AND_REVIEWS',
  summary: { eligibleDevelopmentTasks: 2, eligibleReviewRuns: 1, contextOffTasks: 1, tasksProvidedOrOpened: 2,
    activeNotesWithoutConfirmedDeliveryOrOpen: 0, scopesDemandedWhileMissing: 1, scopesStillMissingAfterDemand: 1,
    searchesWithNoMatchingScope: 1, searchesWithScopeButNoNote: 2 },
  byTaskAndNoteKind: [{ taskKind: 'DEVELOPMENT', noteKind: 'KNOWLEDGE', providedOrOpenedTasks: 1, foundTasks: 1, openedTasks: 1 }],
  entries: [{ entryId: 'knowledge:project', kind: 'KNOWLEDGE', scopeId: 'project', title: 'Project', currentVersion: 2, currentEnabled: false,
    currentVersionCounts: { selectedTasks: 2, providedTasks: 1, searchHits: 1, foundTasks: 1, openedTasks: 1, citedReviews: 0 },
    allVersionCounts: { providedTasks: 2, foundTasks: 1, openedTasks: 1 }, lastConfirmedUseAt: '2026-09-02T00:00:00Z' }],
  scopeGaps: [{ scopeId: 'iceberg', demandingTasks: 1, observedPreparationCounts: { NOT_PREPARED: 1, EMPTY: 0, FAILED: 0, READY: 0 },
    currentPreparation: 'NOT_PREPARED', currentKnowledge: 'MISSING' }], hasMoreEntries: false, hasMoreScopeGaps: false,
};
const activities: ContextUsageActivitiesDto = { entryId: 'knowledge:project', activities: [{ taskId: 'task', reviewRunId: null, turnId: 'turn',
  taskKind: 'DEVELOPMENT', entryVersion: 2, stage: 'OPENED', delivery: 'CONFIRMED', occurredAt: '2026-09-02T00:00:00Z',
  receiptRef: 'context-read:1' }], hasMore: false };
const snapshot: ReviewContextSnapshotDto = {
  id: 'snapshot', enabled: true, notebookRevision: 7, repositoryId: 42, baselineSha: 'a'.repeat(40),
  bootstrap: { text: 'Prepared only', digest: 'sha256:packet', utf8Bytes: 13 },
  selectedEntries: [{ entryId: 'knowledge:project', entryVersion: 2, title: 'Project', text: null, sourceEvidenceRefs: {},
    matchReasons: ['Repository scope'], applicability: 'UNVERIFIED', inclusion: 'DEFERRED' }],
  relatedItems: [{ sourceId: 'review:old', matchReasons: ['Changed path'], presentInReviewedBase: null }],
  inputFingerprints: [{ scopeId: 'project', inputs: { 'git:README.md': 'ABSENT' }, digest: null, gaps: ['Incomplete source inventory'] }],
  sourceReads: [
    { kind: 'REPOSITORY_FILE', id: 'context-read:file', reference: { kind: 'REPOSITORY_FILE', repositoryId: 42,
      repository: { owner: 'example', repo: 'repository' }, path: 'empty.txt', commit: 'a'.repeat(40) },
      blobId: 'b'.repeat(40), readAt: '2026-09-01T00:00:00Z', startLine: 1, endLine: 0, totalLines: 0, utf8Bytes: 0, truncated: false, evidenceRef: null },
    { kind: 'DOCUMENT', id: 'context-read:document', reference: { kind: 'DOCUMENT', sourceId: 'docs', url: 'https://example.com/docs/page' },
      registration: source, notebookRevision: 7, captureId: 'capture', contentHash: 'a'.repeat(64), readAt: '2026-09-01T00:00:00Z',
      page: 2, totalPages: 2, utf8Bytes: 2, blank: true, truncated: true, refreshFailed: true, evidenceRef: null },
  ],
  gaps: ['Source applicability has not been verified.'], contributions: [], deliveries: [],
};
const merged: ContextHistoryReadDto = { kind: 'HISTORY', id: 'context-read:merged-pr',
  reference: { kind: 'PR', repositoryId: 42, repository: { owner: 'example', repo: 'repository' }, number: 18, commentId: null },
  state: 'MERGED', sourceUpdatedAt: '2026-09-01T00:00:00Z', sourceRevision: 'a'.repeat(40), contentHash: 'b'.repeat(64),
  readAt: '2026-09-02T00:00:00Z', utf8Bytes: 120, truncated: true, refreshFailed: true,
  gaps: ['Stored reviews may be incomplete.'], evidenceRef: 'history:pr:18' };
const closed: ContextHistoryReadDto = { ...merged, id: 'context-read:closed-issue',
  reference: { ...merged.reference, kind: 'ISSUE', number: 9 }, state: 'CLOSED', sourceUpdatedAt: null, sourceRevision: null,
  truncated: false, refreshFailed: false, gaps: ['Closure reason is unknown.'], evidenceRef: 'history:issue:9' };

describe('repository context contracts', () => {
  it('parses usage without treating an absent, removed or disabled note as confirmed use', () => {
    expect(parseContextUsageOverview(usage, '30d')).toEqual(usage);
    expect(parseContextUsageOverview({ ...usage, trackingSince: null,
      entries: [{ ...usage.entries[0], currentVersion: null, currentEnabled: null, lastConfirmedUseAt: null }] }, '30d').entries[0].currentVersion).toBeNull();
    expect(parseContextUsageActivities(activities, activities.entryId)).toEqual(activities);
    expect(parseContextUsageWindow('all')).toBe('all');
    expect(parseContextUsagePage(2)).toBe(2);
    expect(parseContextUsageEntryId('lesson:field-ids')).toBe('lesson:field-ids');
  });

  it('rejects malformed usage identities, evidence stages and counts', () => {
    for (const malformed of [{ ...usage, window: 'all' }, { ...usage, trackingSince: undefined }, { ...usage, coverage: 'EVERY_TASK' },
      { ...usage, summary: { ...usage.summary, searchesWithNoMatchingScope: undefined } },
      { ...usage, summary: { ...usage.summary, eligibleReviewRuns: -1 } },
      { ...usage, entries: [{ ...usage.entries[0], currentVersion: 0 }] },
      { ...usage, entries: [{ ...usage.entries[0], currentVersion: null }] },
      { ...usage, entries: [{ ...usage.entries[0], currentEnabled: undefined }] },
      { ...usage, scopeGaps: [{ ...usage.scopeGaps[0], currentKnowledge: 'GUESS' }] }])
      expect(() => parseContextUsageOverview(malformed, '30d')).toThrow('Malformed repository context');
    for (const malformed of [{ ...activities, entryId: 'other' },
      { ...activities, activities: [{ ...activities.activities[0], stage: 'HELPED' }] },
      { ...activities, activities: [{ ...activities.activities[0], delivery: 'INFERRED' }] },
      { ...activities, activities: [{ ...activities.activities[0], reviewRunId: undefined }] }])
      expect(() => parseContextUsageActivities(malformed, activities.entryId)).toThrow('Malformed repository context');
    for (const bad of ['month', null]) expect(() => parseContextUsageWindow(bad)).toThrow();
    for (const bad of [-1, 0.5]) expect(() => parseContextUsagePage(bad)).toThrow();
    for (const bad of ['', 'x'.repeat(1201)]) expect(() => parseContextUsageEntryId(bad)).toThrow();
  });

  it('retains ownership, disabled notes, conditional claims and incomplete source provenance', () => {
    expect(parseRepositoryNotebook(notebook)).toEqual(notebook);
    expect(parseRepositoryNotebook({ ...notebook, useInReviews: false }).useInReviews).toBe(false);
    expect(parseRepositoryNotebook({ ...notebook, useInDevelopment: false }).useInDevelopment).toBe(false);
  });

  it('keeps pending drafts separate from active entries and validates draft edits', () => {
    const draft = { id: 'draft:one', entry: { ...notebook.entries[0], enabled: true }, replacesVersion: 2,
      baselineSha: 'a'.repeat(40), explanation: 'Compared the repository source.' };
    expect(parseRepositoryNotebook({ ...notebook, pendingDrafts: [draft] }).pendingDrafts).toEqual([draft]);
    expect(() => parseRepositoryNotebook({ ...notebook, pendingDrafts: undefined })).toThrow('pending drafts');
    expect(parseRepositoryNotebook({ ...notebook, pendingDrafts: [{ ...draft, entry: { ...draft.entry, kind: 'MEMORY' } }] }).pendingDrafts[0].entry.kind)
      .toBe('MEMORY');
    expect(() => parseRepositoryNotebook({ ...notebook, pendingDrafts: [{ ...draft, entry: { ...draft.entry, kind: 'UNKNOWN' } }] })).toThrow('entry kind');
    expect(parseContextDraftActionRequest({ expectedRevision: 7, draftId: draft.id })).toEqual({ expectedRevision: 7, draftId: draft.id });
    expect(parseEditContextDraftRequest({ expectedRevision: 7, draftId: draft.id, title: 'Revised', summary: 'Condition', body: 'Check the code', claims: [] }).title)
      .toBe('Revised');
    expect(() => parseContextDraftActionRequest({ expectedRevision: 7, draftId: draft.id, body: 'activate' })).toThrow('unsupported request field');
  });

  it('requires nullable discovery provenance on stored scopes and registered sources', () => {
    const malformed: unknown[] = [{ ...notebook, scopes: [{ ...notebook.scopes[0], discoveryEvidenceRef: undefined }] },
      { ...notebook, sources: [{ ...source, discoveryEvidenceRef: undefined }] }, { ...notebook, sources: [{ ...source, discoveryEvidenceRef: false }] }];
    for (const value of malformed) {
      expect(() => parseRepositoryNotebook(value)).toThrow('Malformed repository context');
    }
    const retained = { ...notebook, scopes: [{ ...notebook.scopes[0], discoveryEvidenceRef: 'source' }], sources: [{ ...source, discoveryEvidenceRef: 'source' }] };
    expect(parseRepositoryNotebook(retained)).toEqual(retained);
  });

  it('rejects malformed saved content instead of rendering an empty notebook or coercing preferences', () => {
    for (const value of [null, {}, { ...notebook, schemaVersion: 2 }, { ...notebook, useInReviews: 'false' }, { ...notebook, useInDevelopment: 'false' },
      { ...notebook, revision: 1.5 }, { ...notebook, entries: null }, { ...notebook, entries: [{ ...notebook.entries[0], origin: 'VERIFIED' }] }]) {
      expect(() => parseRepositoryNotebook(value)).toThrow('Malformed repository context');
    }
  });

  it('preserves receipt types, blank reads, unknown ancestry and deferred unverified notes', () => {
    expect(parseReviewContextSnapshot(snapshot)).toEqual(snapshot);
    const review = reviewFixture();
    const parsed = parseAgentReview({ ...review, runs: [{ ...review.runs[0], context: snapshot }] });
    expect(parsed.runs[0].context).toEqual(snapshot);
    expect(parseReviewContextSnapshot(null)).toBeNull();
    expect(parseReviewContextSnapshot(undefined)).toBeNull();
    const off: ReviewContextSnapshotDto = {
      ...snapshot, enabled: false, bootstrap: null, selectedEntries: [], relatedItems: [], inputFingerprints: [], sourceReads: [], gaps: [],
    };
    expect(parseReviewContextSnapshot(off)?.enabled).toBe(false);
  });

  it('rejects malformed context rather than claiming a disabled or successfully supplied packet', () => {
    for (const value of [{ ...snapshot, enabled: false }, { ...snapshot, enabled: 'true' }, { ...snapshot, bootstrap: null },
      { ...snapshot, sourceReads: [{ ...snapshot.sourceReads[0], kind: 'DOCUMENT' }] },
      { ...snapshot, selectedEntries: [{ ...snapshot.selectedEntries[0], inclusion: 'INCLUDED' }] },
      { ...snapshot, relatedItems: [{ ...snapshot.relatedItems[0], presentInReviewedBase: 'unknown' }] }]) {
      expect(() => parseReviewContextSnapshot(value)).toThrow('Malformed repository context');
    }
  });

  it('retains merged PR and closed issue receipts in both the run and shared preparation', () => {
    const sourceReads = [merged, closed];
    expect(parseReviewContextSnapshot({ ...snapshot, sourceReads })?.sourceReads).toEqual(sourceReads);
    const preparation: ContextPreparationResultDto = { attemptId: 'history-attempt', inputFingerprint: snapshot.inputFingerprints[0],
      outcome: 'READY', noteIds: ['knowledge:project'], checkedReads: sourceReads, explanation: 'A conditional lesson with retained history.' };
    const parsed = parseRepositoryNotebook({ ...notebook, scopes: [{ ...notebook.scopes[0], preparation }] });
    expect(parsed.scopes[0].preparation?.checkedReads).toEqual(sourceReads);
  });

  it('rejects history receipts whose reference, state or retained evidence disagree', () => {
    for (const read of [
      { ...merged, state: 'OPEN' }, { ...merged, state: 'CLOSED' }, { ...closed, state: 'MERGED' },
      { ...closed, sourceRevision: 'a'.repeat(40) }, { ...merged, reference: { ...merged.reference, commentId: 'comment-1' } },
      { ...merged, reference: snapshot.sourceReads[1].reference }, { ...merged, gaps: 'unknown' },
      { ...merged, refreshFailed: 'false' }, { ...merged, sourceUpdatedAt: 12 }, { ...merged, utf8Bytes: -1 },
      { ...merged, evidenceRef: null }, { ...merged, evidenceRef: '' },
    ]) expect(() => parseReviewContextSnapshot({ ...snapshot, sourceReads: [read] })).toThrow('Malformed repository context');
  });

  it('does not present history or incomplete source reads as an empty preparation', () => {
    const document = { ...snapshot.sourceReads[1], truncated: false, refreshFailed: false };
    const preparation: ContextPreparationResultDto = { attemptId: 'empty-attempt', inputFingerprint: { ...snapshot.inputFingerprints[0], digest: 'sha256:observed', gaps: [] },
      outcome: 'EMPTY', noteIds: [], checkedReads: [document], explanation: 'No useful note from the checked document.' };
    const saved = (checkedReads: unknown[]) => ({ ...notebook,
      scopes: [{ ...notebook.scopes[0], preparation: { ...preparation, checkedReads } }] });
    expect(parseRepositoryNotebook(saved([document])).scopes[0].preparation?.outcome).toBe('EMPTY');
    for (const read of [merged, closed, { ...snapshot.sourceReads[0], truncated: true },
      { ...document, truncated: true }, { ...document, refreshFailed: true }]) {
      expect(() => parseRepositoryNotebook(saved([read]))).toThrow('empty preparation has incomplete or historical source reads');
    }
  });

  it('retains completed brief contributions and exact delivery identities with receipt-derived state', () => {
    const contribution = { investigationStep: 1, text: 'Conditional contracts.', evidenceRefs: ['E1'], preparationRefs: ['prepared:project'], limitations: ['Unverified version'] };
    const delivery: ContextDeliveryDto = { turnId: 'turn-1', packetDigest: 'sha256:brief', utf8Bytes: 210, contributionCount: 1,
      inputDigest: 'sha256:input', inputUtf8Bytes: 500, discussionMessageId: null, discussionSupplement: null, preparedAt: '2026-09-01T00:00:00Z' };
    const context = { ...snapshot, contributions: [contribution], deliveries: [delivery] };
    const review = reviewFixture();
    const contextDeliveryStates: Record<string, ContextDeliveryStateDto> = { 'turn-1': { outcome: 'CONFIRMED', detail: null } };
    const parsed = parseAgentReview({ ...review, runs: [{ ...review.runs[0], context, contextDeliveryStates }] });
    expect(parsed.runs[0].context).toEqual(context);
    expect(parsed.runs[0].contextDeliveryStates).toEqual(contextDeliveryStates);
    for (const bad of [
      { ...context, contributions: undefined }, { ...context, deliveries: undefined },
      { ...context, contributions: [{ ...contribution, investigationStep: 0 }] },
      { ...context, contributions: [{ ...contribution, limitations: 'missing' }] },
      { ...context, deliveries: [{ ...delivery, contributionCount: 2 }] },
      { ...context, deliveries: [{ ...delivery, utf8Bytes: -1 }] },
      { ...context, deliveries: [{ ...delivery, inputUtf8Bytes: '500' }] },
    ]) expect(() => parseReviewContextSnapshot(bad)).toThrow('Malformed repository context');
    for (const badState of [{ outcome: 'UNDERSTOOD', detail: null }, { outcome: 'CONFIRMED', detail: 1 }, null]) {
      expect(() => parseAgentReview({ ...review, runs: [{ ...review.runs[0], context, contextDeliveryStates: { 'turn-1': badState } }] }))
        .toThrow('Malformed context delivery');
    }
    expect(() => parseAgentReview({ ...review, runs: [{ ...review.runs[0], contextDeliveryStates: undefined }] })).toThrow('context delivery states');
  });

  it('requires bounded discussion supplements on discussion deliveries and preserves exact packet text', () => {
    const supplement = { packet: { text: 'later source', digest: 'sha256:discussion', utf8Bytes: 12 }, evidenceRefs: ['E1'],
      capturedAt: '2026-09-01T00:00:00Z', gaps: ['Local cache only'] };
    const delivery: ContextDeliveryDto = { turnId: 'discussion', packetDigest: 'sha256:base', utf8Bytes: 120, contributionCount: 0,
      inputDigest: 'sha256:input', inputUtf8Bytes: 220, discussionMessageId: 'message', discussionSupplement: supplement, preparedAt: '2026-09-01T00:00:00Z' };
    const context = { ...snapshot, deliveries: [delivery] };
    expect(parseReviewContextSnapshot(context)?.deliveries[0].discussionSupplement).toEqual(supplement);
    for (const bad of [
      { ...delivery, discussionSupplement: undefined }, { ...delivery, discussionMessageId: null },
      { ...delivery, discussionSupplement: { ...supplement, packet: { ...supplement.packet, utf8Bytes: 8193 } } },
      { ...delivery, discussionSupplement: { ...supplement, evidenceRefs: Array(7).fill('E1') } },
      { ...delivery, discussionSupplement: { ...supplement, gaps: Array(11).fill('gap') } },
    ]) expect(() => parseReviewContextSnapshot({ ...context, deliveries: [bad] })).toThrow('Malformed repository context');
  });

  it('parses preparation observations strictly without inferring missing timings', () => {
    const review = reviewFixture();
    const activity: ContextPreparationActivityDto = { operationRef: 'context-activity:1', status: 'JOINED', attemptId: 'attempt', recordedAt: null, elapsedMillis: null,
      scopes: [{ scopeId: 'project', outcome: null, explanation: '', noteIds: [], checkedSourceIds: [] }], gaps: ['Shared preparation is owned by another agent.'] };
    const parse = (contextPreparationActivities: unknown) => parseAgentReview({ ...review, runs: [{ ...review.runs[0], contextPreparationActivities }] });
    expect(parse([activity]).runs[0].contextPreparationActivities).toEqual([activity]);
    for (const bad of [undefined, null, [{ ...activity, status: 'UNDERSTOOD' }], [{ ...activity, elapsedMillis: -1 }],
      [{ ...activity, elapsedMillis: '10' }], [{ ...activity, elapsedMillis: 0.5 }], [{ ...activity, scopes: 'unknown' }],
      [{ ...activity, recordedAt: 123 }], [{ ...activity, scopes: [{ ...activity.scopes[0], outcome: 'UNKNOWN' }] }]]) expect(() => parse(bad)).toThrow();
  });

  it('retains exact note-read identity and refuses ambiguous receipt content', () => {
    const review = reviewFixture();
    const read: ContextNoteReadDto = { entryId: 'knowledge:project', entryVersion: 3, status: 'AVAILABLE', text: 'exact note',
      evidenceRef: 'note:3', sourceEvidenceRefs: { source: 'E1' } };
    const parse = (contextNoteReads: unknown) => parseAgentReview({ ...review, runs: [{ ...review.runs[0], contextNoteReads }] });
    expect(parse([read]).runs[0].contextNoteReads).toEqual([read]);
    for (const bad of [undefined, [{ ...read, status: 'VERSION_UNAVAILABLE' }], [{ ...read, entryVersion: 0 }],
      [{ ...read, text: null }], [{ ...read, evidenceRef: undefined }], [{ ...read, sourceEvidenceRefs: ['invalid'] }],
      [{ ...read, status: 'RECEIPT_UNAVAILABLE' }]]) expect(() => parse(bad)).toThrow();
  });

  it('parses later search receipts without inventing provider delivery or note reads', () => {
    const review = reviewFixture();
    const search: ContextNoteSearchDto = { searchRef: 'context-search:1', status: 'AVAILABLE', notebookRevision: 5,
      originalNotebookRevision: 2, newerThanSnapshot: true, contextState: 'MATCHES', query: 'retry', paths: ['plugin/iceberg/'],
      omittedPathHints: 0, pathHintCount: 1, frozenPathHints: 1, omittedCount: 0, delivery: 'PREPARED', hits: [
        { entryId: 'memory:retry', entryVersion: 2, kind: 'MEMORY', scopeId: 'iceberg', title: 'Retry lesson', summary: 'Check retry state',
          matchReasons: ['Search text'], applicability: 'UNVERIFIED', initiallySelected: false, read: null },
      ] };
    const parse = (contextNoteSearches: unknown) => parseAgentReview({ ...review, runs: [{ ...review.runs[0], contextNoteSearches }] });
    expect(parse([search]).runs[0].contextNoteSearches).toEqual([search]);
    for (const bad of [undefined, null, [{ ...search, delivery: 'OPENED' }], [{ ...search, contextState: 'FIXED' }],
      [{ ...search, notebookRevision: -1 }], [{ ...search, paths: 'plugin/iceberg/' }], [{ ...search, omittedCount: -1 }],
      [{ ...search, hits: [{ ...search.hits[0], initiallySelected: 'yes' }] }],
      [{ ...search, hits: [{ ...search.hits[0], read: { status: 'AVAILABLE', evidenceRef: 'E1', sourceEvidenceRefs: {},
        delivery: 'CONFIRMED' } }] }],
      [{ ...search, hits: [{ ...search.hits[0], read: { status: 'AVAILABLE', evidenceRef: null as string | null, sourceEvidenceRefs: {},
        delivery: 'CONFIRMED', receiptRef: 'context-note:1' } }] }]]) expect(() => parse(bad)).toThrow();
  });

  it('keeps optimistic revisions and absent configuration fields unchanged', () => {
    expect(parseConfigureRepositoryContextRequest({ expectedRevision: 7, useInReviews: false }))
      .toEqual({ expectedRevision: 7, useInReviews: false });
    expect(parseConfigureRepositoryContextRequest({ expectedRevision: 7, useInDevelopment: false }))
      .toEqual({ expectedRevision: 7, useInDevelopment: false });
    expect(() => parseConfigureRepositoryContextRequest({ useInReviews: false })).toThrow('expected revision');
    expect(() => parseConfigureRepositoryContextRequest({ expectedRevision: 7, sources: [source] })).toThrow('origin');
    expect(() => parseEditRepositoryContextEntryRequest({ expectedRevision: 7, ...notebook.entries[0] })).toThrow('unsupported request field');
  });

  it('retains preparation ownership and negative evidence but never submits execution metadata as user configuration', () => {
    const input = { scopeId: 'project', inputs: { 'git:README.md': 'ABSENT' }, digest: 'sha256:observed', gaps: [] as string[] };
    const preparation: ContextPreparationResultDto = { attemptId: 'attempt', inputFingerprint: input, outcome: 'EMPTY', noteIds: [],
      checkedReads: [snapshot.sourceReads[0]], explanation: 'No useful note from the checked input.' };
    const activePreparation: ActiveContextPreparationDto = { attemptId: 'current-attempt', requestKey: 'request', requestDigest: 'sha256:request', ownerTaskId: 'task',
      ownerTurnId: 'turn', ownerTurnKey: 'turn-key', reviewId: 'review', runId: 'run', baseContentRevision: 7, inputs: [input] };
    const scopes = [{ ...notebook.scopes[0], preparation }];
    const parsed = parseRepositoryNotebook({ ...notebook, activePreparation, scopes });
    expect(parsed.activePreparation).toEqual(activePreparation);
    expect(parsed.scopes[0].preparation).toEqual(preparation);
    expect(parseConfigureRepositoryContextRequest({ expectedRevision: 7, scopes }).scopes)
      .toEqual([{ id: 'project', title: 'Project', match: { kind: 'REPOSITORY' }, enabled: true }]);
    expect(() => parseRepositoryNotebook({ ...notebook, scopes: [{ ...scopes[0], preparation: { ...preparation, outcome: 'VERIFIED' } }] }))
      .toThrow('preparation outcome');
    expect(() => parseRepositoryNotebook({ ...notebook, activePreparation: { ...activePreparation, baseContentRevision: '7' } }))
      .toThrow('preparation revision');
    expect(parseRepositoryNotebook({ ...notebook, activePreparation: { ...activePreparation, reviewId: null, runId: null } }).activePreparation?.reviewId).toBeNull();
    expect(() => parseRepositoryNotebook({ ...notebook, activePreparation: { ...activePreparation, reviewId: null } })).toThrow('must be paired');
  });

  it('retains builder status without manufacturing tasks and validates task ownership', () => {
    const empty: ContextBuildViewDto = { buildRequestSequence: 0, notebookRevision: 7, canStart: true, build: null };
    expect(parseContextBuildView(empty, 'workspace')).toEqual(empty);
    const build: NonNullable<ContextBuildViewDto['build']> = { mode: 'LEARN', requestKey: 'learn-key', taskId: 'learn-task', baselineSha: 'a'.repeat(40),
      scopeIds: ['project'], history: [], requestedAt: '2026-09-01T00:00:00Z', task: { id: 'learn-task', workspaceId: 'workspace', state: 'OPEN', version: 0,
        laneId: 'codex', model: 'test-model', mutationClaim: 'TURN', terminalReason: null, worktreePath: '/tmp/learn-task', worktreeRemovedAt: null },
      activeTurn: { id: 'learn-turn', state: 'RUNNING', startedAt: null, cancellationRequestedAt: null }, pendingApprovals: 0, results: [], gaps: [] };
    const view = { ...empty, buildRequestSequence: 1, canStart: false, build };
    expect(parseContextBuildView(view, 'workspace')).toEqual(view);
    expect(parseContextBuildView({ ...view, build: { ...view.build, task: null, activeTurn: null } }, 'workspace').build?.task).toBeNull();
    for (const value of [{ ...view, canStart: 'true' }, { ...view, build: undefined },
      { ...view, build: { ...view.build, task: { ...view.build.task, workspaceId: 'other' } } },
      { ...view, build: { ...view.build, task: { ...view.build.task, id: 'other' } } },
      { ...view, build: { ...view.build, task: { ...view.build.task, state: 'VERIFIED' } } },
      { ...view, build: { ...view.build, task: { ...view.build.task, worktreePath: undefined } } },
      { ...view, build: { ...view.build, task: { ...view.build.task, worktreePath: null } } },
      { ...view, build: { ...view.build, results: null } }, { ...view, build: { ...view.build, mode: 'PREVIEW' } }])
      expect(() => parseContextBuildView(value, 'workspace')).toThrow('Malformed repository context');
  });

  it('keeps agent discovery and accepted learning tied to saved identities and source bounds', () => {
    const report: ContextDiscoveryReportDto = { id: 'discovery-task', workspaceId: 'workspace', repositoryId: 42,
      repository: { owner: 'trinodb', repo: 'trino' }, baselineSha: 'a'.repeat(40), outline: 'SQL engine with connectors.',
      topics: [{ id: 'topic:1', title: 'Iceberg connector', question: 'How are tables read?', paths: ['plugin/trino-iceberg/'],
        sourceIds: ['source:1'], evidenceReadIds: ['read:1'], why: 'README identifies the connector.' }],
      sources: [{ id: 'source:1', kind: 'DOCUMENTATION_ROOT', location: 'https://trino.io/docs/current/connector/',
        title: 'Connector docs', evidenceReadIds: ['read:1'], why: 'README links to these docs.' }],
      evidence: [{ readId: 'read:1', path: 'plugin/trino-iceberg/README.md', blobId: 'b'.repeat(40), startLine: 1, endLine: 2,
        excerpt: 'Iceberg connector', truncated: false }], gaps: [], remaining: ['Other connectors'], partial: true, createdAt: '2026-09-01T00:00:00Z' };
    expect(parseContextDiscoveryReport({ report }, 'workspace')).toEqual(report);
    expect(parseContextDiscoveryReport({ report: null }, 'workspace')).toBeNull();
    for (const invalid of [{ report: { ...report, workspaceId: 'another' } },
      { report: { ...report, topics: [{ ...report.topics[0], sourceIds: ['unproposed'] }] } },
      { report: { ...report, evidence: [{ ...report.evidence[0], truncated: 'false' }] } }])
      expect(() => parseContextDiscoveryReport(invalid, 'workspace')).toThrow('Malformed repository context');
    const plan: ContextLearningPlanDto = { id: 'plan-1', workspaceId: 'workspace', reportId: report.id, acceptedRequestKey: 'learn-key',
      acceptedRequestDigest: 'sha256:' + 'a'.repeat(64), acceptedNotebookRevision: 8, initialBuildSequence: 1,
      acceptedTopicIds: ['topic:1'], repositoryId: 42, repository: report.repository, baselineSha: report.baselineSha,
      topics: [{ id: 'topic:1', title: 'Iceberg connector', question: 'How are tables read?', scopeId: 'path:plugin/trino-iceberg/',
        paths: ['plugin/trino-iceberg/'], repositoryFiles: [], sourceIds: ['source:1'], status: 'PENDING', remainingQuestion: 'How are tables read?' }],
      documentationSources: [source], documentInventory: { sources: [source], items: [{ sourceId: source.id, resolvedUrl: source.url,
        relativePage: null, parentUrl: null, depth: 0, status: 'PENDING', readId: null, contentHash: null, detail: '' }],
        suggestedExpansionUrls: [], truncated: false },
      gaps: [], acceptedAt: '2026-09-01T00:00:00Z' };
    expect(parseContextLearningPlan({ plan }, 'workspace')).toEqual(plan);
    expect(parseContextLearningPlan({ plan: null }, 'workspace')).toBeNull();
    expect(() => parseContextLearningPlan({ plan: { ...plan, topics: [{ ...plan.topics[0], status: 'GUESS' }] } }, 'workspace'))
      .toThrow('Malformed repository context');
    const base = { requestKey: 'key', expectedRevision: 7, expectedBuildSequence: 1 };
    expect(parseStartContextDiscoveryRequest(base)).toEqual(base);
    expect(parseStartContextLearningRequest({ ...base, reportId: report.id, topicIds: ['topic:1'] }).topicIds).toEqual(['topic:1']);
    expect(parseContinueContextLearningRequest({ ...base, planId: plan.id }).planId).toBe(plan.id);
    expect(parseDiscardContextLearningPlanRequest({ planId: plan.id, expectedRevision: 7, expectedBuildSequence: 1 }).planId).toBe(plan.id);
    expect(parseAllowContextLearningSourceRequest({ planId: plan.id, sourceId: source.id, expectedRevision: 7, expectedBuildSequence: 1 }).sourceId)
      .toBe(source.id);
    const suggestedUrl = 'https://example.org/iceberg/reference.html';
    expect(parseContextLearningPlan({ plan: { ...plan, documentInventory: { ...plan.documentInventory,
      suggestedExpansionUrls: [suggestedUrl] } } }, 'workspace')?.documentInventory.suggestedExpansionUrls).toEqual([suggestedUrl]);
    expect(parseAllowContextLearningSourceRequest({ planId: plan.id, suggestedUrl, expectedRevision: 7, expectedBuildSequence: 1 }).suggestedUrl)
      .toBe(suggestedUrl);
    expect(() => parseAllowContextLearningSourceRequest({ planId: plan.id, sourceId: source.id, suggestedUrl,
      expectedRevision: 7, expectedBuildSequence: 1 })).toThrow('source selection');
    expect(() => parseAllowContextLearningSourceRequest({ planId: plan.id, expectedRevision: 7, expectedBuildSequence: 1 }))
      .toThrow('source selection');
    for (const invalid of [{ ...base, prompt: 'explore' }, { ...base, expectedRevision: -1 }])
      expect(() => parseStartContextDiscoveryRequest(invalid)).toThrow('Malformed repository context');
    expect(() => parseStartContextLearningRequest({ ...base, reportId: report.id, topicIds: [] })).toThrow();
    expect(() => parseContinueContextLearningRequest({ ...base, planId: '' })).toThrow();
    expect(() => parseDiscardContextLearningPlanRequest({ planId: plan.id, expectedRevision: 7, expectedBuildSequence: 1,
      deleteNotes: true })).toThrow();
  });

  it('keeps merged PR selection separate from reading retained candidates', () => {
    const candidate: ContextMergedCandidateDto = { id: 'pr:trinodb/trino#123', repo: 'trinodb/trino', number: 123, title: 'Refresh Iceberg metadata',
      url: 'https://github.com/trinodb/trino/pull/123', mergedAt: '2026-09-01T00:00:00Z', taskId: 'task-123',
      taskTitle: 'Refresh metadata', taskResult: null, learningSummary: null, issue: null, review: null, checks: [],
      gaps: ['Merge does not establish current behavior.'] };
    expect(parseContextMergedCandidates({ candidates: [candidate], gaps: [] }).candidates[0]).toEqual(candidate);
    expect(() => parseContextMergedCandidates({ candidates: [candidate, candidate], gaps: [] })).toThrow('candidate identity');
    const request = { candidateId: candidate.id, requestKey: 'candidate-key', expectedRevision: 7, expectedBuildSequence: 2 };
    expect(parseLearnContextMergedCandidateRequest(request)).toEqual(request);
    expect(() => parseLearnContextMergedCandidateRequest({ ...request, prompt: 'Use this PR' })).toThrow('unsupported request field');
  });

  it('preserves the selected discussion run and evidence rather than substituting the latest run', () => {
    const request = { expectedRevision: 7, scopeId: 'project', title: 'Lesson', summary: 'Conditional lesson', body: 'Only under these conditions.',
      reviewId: 'review', runId: 'older-run', evidenceRefs: ['E3'], messageId: 'old-message' };
    expect(parseSaveRepositoryContextLessonRequest(request)).toEqual(request);
    expect(() => parseSaveRepositoryContextLessonRequest({ ...request, sourceEvidence: [] })).toThrow('unsupported request field');
  });
});
