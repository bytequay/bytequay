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
import { parseAgentReview, type ContextNoteReadDto, type ContextPreparationActivityDto, type ContextDeliveryStateDto } from '../pulls/agent-review/contracts';
import { reviewFixture } from '../pulls/agent-review/fixtures/review';
import {
  parseApplyContextDiscoveryRequest,
  parseConfigureRepositoryContextRequest,
  parseContextBuildView,
  parseContextDiscovery,
  parseContextUsageActivities,
  parseContextUsageEntryId,
  parseContextUsageOverview,
  parseContextUsagePage,
  parseContextUsageWindow,
  parseDiscoverRepositoryContextRequest,
  parseEditRepositoryContextEntryRequest,
  parseRepositoryNotebook,
  parseReviewContextSnapshot,
  parseSaveRepositoryContextLessonRequest,
  parseStartContextBuildRequest,
  type ActiveContextPreparationDto,
  type ContextBuildViewDto,
  type ContextDiscoveryDto,
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
  suppressedKnowledgeIds: [], sourceEvidence: [{ id: 'source', reference: { kind: 'DOCUMENT', sourceId: 'docs', url: 'https://example.com/docs/page' },
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

  it('requires nullable discovery provenance on stored scopes and registered sources', () => {
    const malformed: unknown[] = [{ ...notebook, scopes: [{ ...notebook.scopes[0], discoveryEvidenceRef: undefined }] },
      { ...notebook, sources: [{ ...source, discoveryEvidenceRef: undefined }] }, { ...notebook, sources: [{ ...source, discoveryEvidenceRef: false }] }];
    for (const value of malformed) {
      expect(() => parseRepositoryNotebook(value)).toThrow('Malformed repository context');
    }
    const retained = { ...notebook, scopes: [{ ...notebook.scopes[0], discoveryEvidenceRef: 'source' }], sources: [{ ...source, discoveryEvidenceRef: 'source' }] };
    expect(parseRepositoryNotebook(retained)).toEqual(retained);
  });

  it('parses bounded discovery facts without inventing outcomes and validates workspace ownership', () => {
    const found: ContextDiscoveryDto = { workspaceId: 'workspace', notebookRevision: 7, baselineSha: 'a'.repeat(40), repositoryFingerprint: 'c'.repeat(64), query: 'Iceberg',
      scopes: [{ scope: notebook.scopes[0], evidence: notebook.sourceEvidence[0], reasons: ['Repository module'] }],
      sources: [{ source, evidence: notebook.sourceEvidence[0], reasons: ['Explicit documentation link'] }],
      history: [{ kind: 'ISSUE', number: 9, title: 'Issue', url: 'https://github.com/example/repo/issues/9', state: 'closed', outcome: 'not_planned',
        updatedAt: '2026-09-01T00:00:00Z', reasons: ['Title matches'], provenance: 'Bounded result; not proof of fix' }],
      gaps: ['Older pages excluded'], inspected: { directories: 3, files: 4, pullRequests: 20, issues: 5 } };
    expect(parseContextDiscovery(found, 'workspace')).toEqual(found);
    for (const value of [{ ...found, workspaceId: 'other' }, { ...found, history: null }, { ...found, inspected: { ...found.inspected, files: -1 } },
      { ...found, scopes: [{ ...found.scopes[0], evidence: null }] }, { ...found, history: [{ ...found.history[0], outcome: null }] }]) {
      expect(() => parseContextDiscovery(value, 'workspace')).toThrow('Malformed repository context');
    }
    expect(parseContextDiscovery({ ...found, history: [{ ...found.history[0], outcome: 'future_reason' }] }, 'workspace').history[0].outcome).toBe('future_reason');
    for (const repositoryFingerprint of [undefined, null, '', 'a'.repeat(63), 'A'.repeat(64), 'z'.repeat(64)]) {
      expect(() => parseContextDiscovery({ ...found, repositoryFingerprint }, 'workspace')).toThrow('repository fingerprint');
    }
  });

  it('accepts only explicit discovery selectors and chosen configuration identities', () => {
    const find = { expectedRevision: 7, scopeIds: ['project'], query: '', includeRemote: false };
    const apply = { expectedRevision: 7, baselineSha: 'a'.repeat(40), repositoryFingerprint: 'c'.repeat(64), query: '', scopeIds: ['component'], sourceIds: ['docs'] };
    expect(parseDiscoverRepositoryContextRequest(find)).toEqual(find);
    expect(parseApplyContextDiscoveryRequest(apply)).toEqual(apply);
    for (const value of [{ ...find, includeRemote: 'false' }, { ...find, prompt: 'launch' }, { ...find, expectedRevision: -1 }]) {
      expect(() => parseDiscoverRepositoryContextRequest(value)).toThrow('Malformed repository context');
    }
    const invalidApply: unknown[] = [{ ...apply, sources: [source] }, { ...apply, sourceEvidence: [] }, { ...apply, scopeIds: null }];
    for (const value of invalidApply) {
      expect(() => parseApplyContextDiscoveryRequest(value)).toThrow('Malformed repository context');
    }
    for (const repositoryFingerprint of [undefined, null, '', 'a'.repeat(63), 'A'.repeat(64), 'z'.repeat(64)]) {
      expect(() => parseApplyContextDiscoveryRequest({ ...apply, repositoryFingerprint })).toThrow('repository fingerprint');
    }
    expect(() => parseDiscoverRepositoryContextRequest({ ...find, repositoryFingerprint: 'a'.repeat(64) })).toThrow('unsupported request field');
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

  it('bounds explicit learning inputs and rejects replacement prompts, profiles and essential-source toggles', () => {
    const request = { requestKey: 'learn-key', expectedRevision: 7, expectedBuildSequence: 0, scopeIds: ['project'], history: [{ kind: 'PR', number: 18 }] };
    expect(parseStartContextBuildRequest(request)).toEqual(request);
    for (const value of [{ ...request, prompt: 'Override the assignment' }, { ...request, repositoryFiles: false }, { ...request, provider: 'other' },
      { ...request, requestKey: '' }, { ...request, scopeIds: [] }, { ...request, scopeIds: ['project', 'project'] },
      { ...request, expectedBuildSequence: -1 }, { ...request, history: [{ kind: 'COMMIT', number: 18 }] },
      { ...request, history: [{ kind: 'ISSUE', number: 0 }] }, { ...request, history: [{ kind: 'ISSUE', number: 2147483648 }] },
      { ...request, history: Array.from({ length: 11 }, (_, index) => ({ kind: 'PR', number: index + 1 })) },
      { ...request, history: [request.history[0], request.history[0]] }]) expect(() => parseStartContextBuildRequest(value)).toThrow('Malformed repository context');
  });

  it('retains builder status without manufacturing tasks and validates task ownership', () => {
    const empty: ContextBuildViewDto = { buildRequestSequence: 0, notebookRevision: 7, canStart: true, build: null };
    expect(parseContextBuildView(empty, 'workspace')).toEqual(empty);
    const build: NonNullable<ContextBuildViewDto['build']> = { requestKey: 'learn-key', taskId: 'learn-task', baselineSha: 'a'.repeat(40),
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
      { ...view, build: { ...view.build, results: null } }]) expect(() => parseContextBuildView(value, 'workspace')).toThrow('Malformed repository context');
  });

  it('preserves the selected discussion run and evidence rather than substituting the latest run', () => {
    const request = { expectedRevision: 7, scopeId: 'project', title: 'Lesson', summary: 'Conditional lesson', body: 'Only under these conditions.',
      reviewId: 'review', runId: 'older-run', evidenceRefs: ['E3'], messageId: 'old-message' };
    expect(parseSaveRepositoryContextLessonRequest(request)).toEqual(request);
    expect(() => parseSaveRepositoryContextLessonRequest({ ...request, sourceEvidence: [] })).toThrow('unsupported request field');
  });
});
