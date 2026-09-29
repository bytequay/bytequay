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
  parseContextBaseline,
  parseContextLearningPlanView,
  parseContextTimes,
  parseContextUsageOverview,
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
  parseDeclineContextLearningSourceRequest,
  parseContextMergedCandidates,
  parseLearnContextMergedCandidateRequest,
  type ContextBuildViewDto,
  type ContextDiscoveryReportDto,
  type ContextLearningPlanDto,
  type ContextMergedCandidateDto,
  type ContextDeliveryDto,
  type ContextHistoryReadDto,
  type ContextRunDto,
  type ContextTimesDto,
  type ContextUsageOverviewDto,
  type ContextSourceDto,
  type RepositoryNotebookDto,
  type ReviewContextSnapshotDto,
} from './contracts';

const source: ContextSourceDto = { id: 'docs', kind: 'DOCUMENTATION_ROOT', url: 'https://example.com/docs/', origin: 'USER', discoveryEvidenceRef: null };
const notebook: RepositoryNotebookDto = {
  schemaVersion: 1, revision: 7, useInReviews: true, useInDevelopment: true, sources: [source],
  scopes: [{ id: 'project', title: 'Project', match: { kind: 'REPOSITORY' }, enabled: true, discoveryEvidenceRef: null }],
  entries: [{ id: 'knowledge:project', kind: 'KNOWLEDGE', scopeId: 'project', title: 'Project', summary: 'Summary', body: 'Keep the condition.',
    claims: [{ text: 'Conditional claim', basis: 'INFERENCE', sourceIds: ['source'] }], sourceIds: ['source'], origin: 'GENERATED', enabled: false, version: 2 }],
  pendingDrafts: [], sourceEvidence: [{ id: 'source', reference: { kind: 'DOCUMENT', sourceId: 'docs', url: 'https://example.com/docs/page' },
    provenance: 'Captured page', capture: 'BYTEQUAY_READ', retrievedAt: null }],
};
const usage: ContextUsageOverviewDto = {
  trackingSince: '2026-09-01T00:00:00Z', window: '30d',
  summary: { eligibleDevelopmentTasks: 2, eligibleReviewRuns: 1, contextOffTasks: 1, developmentTasksFoundOrOpened: 1, reviewRunsFoundOrOpened: 0 },
  entries: [{ entryId: 'knowledge:project', title: 'Project', currentVersion: 2, foundTasks: 1, openedTasks: 1, lastConfirmedUseAt: '2026-09-02T00:00:00Z' }],
};
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
      entries: [{ ...usage.entries[0], currentVersion: null, foundTasks: 0, openedTasks: 0, lastConfirmedUseAt: null }] }, '30d').entries[0].currentVersion)
      .toBeNull();
    expect(parseContextUsageWindow('all')).toBe('all');
  });

  it('rejects malformed usage identities, evidence stages and counts', () => {
    for (const malformed of [{ ...usage, window: 'all' }, { ...usage, trackingSince: undefined },
      { ...usage, summary: { ...usage.summary, developmentTasksFoundOrOpened: undefined } },
      { ...usage, summary: { ...usage.summary, eligibleReviewRuns: -1 } },
      { ...usage, entries: [{ ...usage.entries[0], currentVersion: 0 }] },
      { ...usage, entries: [{ ...usage.entries[0], currentVersion: null }] },
      { ...usage, entries: [{ ...usage.entries[0], foundTasks: -1 }] }])
      expect(() => parseContextUsageOverview(malformed, '30d')).toThrow('Malformed repository context');
    for (const bad of ['month', null]) expect(() => parseContextUsageWindow(bad)).toThrow();
  });

  it('retains ownership, disabled notes, conditional claims and incomplete source provenance', () => {
    expect(parseRepositoryNotebook(notebook)).toEqual(notebook);
    expect(parseRepositoryNotebook({ ...notebook, useInReviews: false }).useInReviews).toBe(false);
    expect(parseRepositoryNotebook({ ...notebook, useInDevelopment: false }).useInDevelopment).toBe(false);
  });

  it('keeps pending drafts separate from active entries and validates draft edits', () => {
    const draft = { id: 'draft:one', entry: { ...notebook.entries[0], enabled: true }, replacesVersion: 2, baselineSha: 'a'.repeat(40) };
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

  it('retains merged PR and closed issue receipts in the run', () => {
    const sourceReads = [merged, closed];
    expect(parseReviewContextSnapshot({ ...snapshot, sourceReads })?.sourceReads).toEqual(sourceReads);
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
    expect(() => parseConfigureRepositoryContextRequest({ expectedRevision: 7, scopes: [] })).toThrow('unsupported request field');
    expect(() => parseEditRepositoryContextEntryRequest({ expectedRevision: 7, ...notebook.entries[0] })).toThrow('unsupported request field');
  });

  it('retains builder status without manufacturing tasks and validates task ownership', () => {
    const empty: ContextBuildViewDto = { buildRequestSequence: 0, notebookRevision: 7, canStart: true, runs: [], build: null };
    expect(parseContextBuildView(empty, 'workspace')).toEqual(empty);
    const build: NonNullable<ContextBuildViewDto['build']> = { requestKey: 'learn-key', taskId: 'learn-task',
      task: { id: 'learn-task', workspaceId: 'workspace', state: 'OPEN', mutationClaim: 'TURN', worktreeRemovedAt: null },
      activeTurn: { cancellationRequestedAt: null }, pendingApprovals: 0, continuation: false };
    const run: ContextRunDto = { sequence: 1, taskId: 'learn-task', mode: 'LEARN', requestedAt: '2026-09-01T00:00:00Z', endedAt: null,
      question: { planId: 'plan-1', topicId: 'topic:1', topicTitle: 'Iceberg connector', index: 0, text: 'How are tables read?' },
      pullRequest: null, outcome: 'RUNNING', sourceOperations: 3, documentOpens: 1, noteIds: [],
      activity: [{ at: '2026-09-01T00:00:01Z', text: 'Read README.md', tone: 'DONE' }], detail: null };
    const view = { ...empty, buildRequestSequence: 1, canStart: false, runs: [run], build };
    expect(parseContextBuildView(view, 'workspace')).toEqual(view);
    expect(parseContextBuildView({ ...view, build: { ...view.build, task: null, activeTurn: null } }, 'workspace').build?.task).toBeNull();
    for (const value of [{ ...view, canStart: 'true' }, { ...view, build: undefined },
      { ...view, build: { ...view.build, task: { ...view.build.task, workspaceId: 'other' } } },
      { ...view, build: { ...view.build, task: { ...view.build.task, id: 'other' } } },
      { ...view, build: { ...view.build, task: { ...view.build.task, state: 'VERIFIED' } } },
      { ...view, build: { ...view.build, task: { ...view.build.task, mutationClaim: 7 } } },
      { ...view, build: { ...view.build, pendingApprovals: -1 } }, { ...view, build: { ...view.build, continuation: 'yes' } },
      { ...view, runs: [{ ...run, outcome: 'HELPED' }] }, { ...view, runs: [{ ...run, sequence: 0 }] },
      { ...view, runs: [{ ...run, activity: [{ ...run.activity[0], tone: 'GUESS' }] }] }])
      expect(() => parseContextBuildView(value, 'workspace')).toThrow('Malformed repository context');
  });

  it('keeps agent discovery and accepted learning tied to saved identities and source bounds', () => {
    const report: ContextDiscoveryReportDto = { id: 'discovery-task', workspaceId: 'workspace', baselineSha: 'a'.repeat(40),
      topics: [{ id: 'topic:1', title: 'Iceberg connector', questions: ['How are tables read?', 'How are snapshots committed?'],
        paths: ['plugin/trino-iceberg/'], sourceIds: ['source:1'], evidenceReadIds: ['read:1'], why: 'README identifies the connector.' }],
      sources: [{ id: 'source:1', kind: 'DOCUMENTATION_ROOT', location: 'https://trino.io/docs/current/connector/' }],
      evidence: [{ readId: 'read:1', path: 'plugin/trino-iceberg/README.md', startLine: 1, endLine: 2 }], remaining: ['Other connectors'], partial: true,
      coverage: { areas: [{ path: 'plugin/', surveyed: 1, total: 40 }], unsurveyed: ['plugin/trino-hive/'], unsurveyedTruncated: false },
      createdAt: '2026-09-01T00:00:00Z' };
    expect(parseContextDiscoveryReport({ report }, 'workspace')).toEqual(report);
    expect(parseContextDiscoveryReport({ report: null }, 'workspace')).toBeNull();
    for (const invalid of [{ report: { ...report, workspaceId: 'another' } },
      { report: { ...report, topics: [{ ...report.topics[0], sourceIds: ['unproposed'] }] } },
      { report: { ...report, evidence: [{ ...report.evidence[0], endLine: 0 }] } },
      { report: { ...report, topics: [{ ...report.topics[0], questions: [] }] } },
      { report: { ...report, topics: [{ ...report.topics[0], questions: Array(7).fill('Why?') }] } }])
      expect(() => parseContextDiscoveryReport(invalid, 'workspace')).toThrow('Malformed repository context');
    const plan: ContextLearningPlanDto = { id: 'plan-1', workspaceId: 'workspace', reportId: report.id, baselineSha: report.baselineSha,
      topics: [{ id: 'topic:1', title: 'Iceberg connector', scopeId: 'path:plugin/trino-iceberg/', paths: ['plugin/trino-iceberg/'],
        repositoryFiles: [], sourceIds: ['source:1'], questions: [{ text: 'How are tables read?', status: 'UNFINISHED', progress: 'Read the split manager.' },
          { text: 'How are snapshots committed?', status: 'PENDING', progress: '' }] }],
      documentationSources: [source], documentInventory: { items: [{ sourceId: source.id, resolvedUrl: source.url,
        relativePage: null, depth: 0, status: 'PENDING', detail: '' }],
        suggestions: [{ url: 'https://example.org/iceberg/', parentUrl: source.url, topicId: 'topic:1', question: 0, taskId: 'learn-task',
          foundAt: '2026-09-01T00:00:00Z' }], truncated: false } };
    const planView = { plan, next: { topicId: 'topic:1', question: 0 } };
    expect(parseContextLearningPlanView(planView, 'workspace')).toEqual(planView);
    expect(parseContextLearningPlanView({ plan: null, next: null }, 'workspace')).toEqual({ plan: null, next: null });
    for (const invalid of [{ plan, next: { topicId: 'topic:1', question: 2 } }, { plan: null, next: planView.next },
      { plan: { ...plan, topics: [{ ...plan.topics[0], questions: [{ ...plan.topics[0].questions[0], status: 'GUESS' }] }] }, next: null },
      { plan: { ...plan, topics: [{ ...plan.topics[0], questions: [{ ...plan.topics[0].questions[1], progress: 'Started' }] }] }, next: null },
      { plan: { ...plan, documentInventory: { ...plan.documentInventory, suggestions: [{ ...plan.documentInventory.suggestions[0], topicId: 'other' }] } },
        next: null }] as unknown[])
      expect(() => parseContextLearningPlanView(invalid, 'workspace')).toThrow('Malformed repository context');
    const base = { requestKey: 'key', expectedRevision: 7, expectedBuildSequence: 1 };
    expect(parseStartContextDiscoveryRequest({ ...base, continuation: true }).continuation).toBe(true);
    expect(parseStartContextLearningRequest({ ...base, reportId: report.id, topicIds: ['topic:1'], excludedSourceIds: ['source:1'] }).excludedSourceIds)
      .toEqual(['source:1']);
    expect(parseContinueContextLearningRequest({ ...base, planId: plan.id }).planId).toBe(plan.id);
    expect(parseDiscardContextLearningPlanRequest({ planId: plan.id, expectedRevision: 7, expectedBuildSequence: 1 }).planId).toBe(plan.id);
    const allow = { planId: plan.id, suggestedUrl: 'https://example.org/iceberg/', boundary: 'ROOT', expectedRevision: 7, expectedBuildSequence: 1 };
    expect(parseAllowContextLearningSourceRequest(allow)).toEqual(allow);
    expect(parseAllowContextLearningSourceRequest({ ...allow, suggestedUrl: 'https://example.org/page.html', boundary: 'PAGE' }).boundary).toBe('PAGE');
    for (const invalid of [{ ...allow, suggestedUrl: 'https://example.org/page.html' }, { ...allow, boundary: 'SITE' },
      { ...allow, suggestedUrl: 'http://example.org/iceberg/' }, { ...allow, sourceId: source.id }])
      expect(() => parseAllowContextLearningSourceRequest(invalid)).toThrow('Malformed repository context');
    expect(parseDeclineContextLearningSourceRequest({ planId: plan.id, suggestedUrl: allow.suggestedUrl }).suggestedUrl).toBe(allow.suggestedUrl);
    expect(() => parseDeclineContextLearningSourceRequest({ planId: plan.id, suggestedUrl: allow.suggestedUrl, boundary: 'PAGE' })).toThrow();
    for (const invalid of [{ ...base, continuation: false, prompt: 'explore' }, { ...base, continuation: false, expectedRevision: -1 }, base])
      expect(() => parseStartContextDiscoveryRequest(invalid)).toThrow('Malformed repository context');
    expect(() => parseStartContextLearningRequest({ ...base, reportId: report.id, topicIds: [], excludedSourceIds: [] })).toThrow();
    expect(() => parseStartContextLearningRequest({ ...base, reportId: report.id, topicIds: ['topic:1'] })).toThrow();
    expect(() => parseContinueContextLearningRequest({ ...base, planId: '' })).toThrow();
    expect(() => parseDiscardContextLearningPlanRequest({ planId: plan.id, expectedRevision: 7, expectedBuildSequence: 1,
      deleteNotes: true })).toThrow();
  });

  it('reads baseline and approval times without inventing them', () => {
    expect(parseContextBaseline(null)).toBeNull();
    expect(parseContextBaseline({ branch: 'master', sha: 'a'.repeat(40) })).toEqual({ branch: 'master', sha: 'a'.repeat(40) });
    expect(() => parseContextBaseline({ branch: 'master', sha: 'abc' })).toThrow('baseline commit');
    const times: ContextTimesDto = { entries: { '2:knowledge:project': { at: '2026-09-01T00:00:00Z', taskId: null } },
      drafts: { draft: { at: '2026-09-02T00:00:00Z', taskId: 'learn-task' } } };
    expect(parseContextTimes(times)).toEqual(times);
    expect(() => parseContextTimes({ entries: {}, drafts: { draft: { taskId: null } } })).toThrow('Malformed repository context');
  });

  it('keeps merged PR selection separate from reading retained candidates', () => {
    const candidate: ContextMergedCandidateDto = { id: 'pr:trinodb/trino#123', repo: 'trinodb/trino', number: 123, title: 'Refresh Iceberg metadata',
      mergedAt: '2026-09-01T00:00:00Z', taskTitle: 'Refresh metadata', taskResult: null, issue: { key: '#120', title: 'Stale metadata' },
      review: { combinedResult: 'Approved' }, checks: [{ conclusion: 'success' }] };
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
