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
import { parseAgentReview, type ContextDeliveryStateDto } from '../pulls/agent-review/contracts';
import { reviewFixture } from '../pulls/agent-review/fixtures/review';
import {
  parseConfigureRepositoryContextRequest,
  parseContextBuildView,
  parseEditRepositoryContextEntryRequest,
  parseRepositoryNotebook,
  parseReviewContextSnapshot,
  parseSaveRepositoryContextLessonRequest,
  parseStartContextBuildRequest,
  type ActiveContextPreparationDto,
  type ContextBuildViewDto,
  type ContextDeliveryDto,
  type ContextHistoryReadDto,
  type ContextPreparationResultDto,
  type ContextSourceDto,
  type RepositoryNotebookDto,
  type ReviewContextSnapshotDto,
} from './contracts';

const source: ContextSourceDto = { id: 'docs', kind: 'DOCUMENTATION_ROOT', url: 'https://example.com/docs/', origin: 'USER' };
const notebook: RepositoryNotebookDto = {
  schemaVersion: 1, revision: 7, useInReviews: true, sources: [source], activePreparation: null,
  scopes: [{ id: 'project', title: 'Project', match: { kind: 'REPOSITORY' }, enabled: true, preparation: null }],
  entries: [{ id: 'knowledge:project', kind: 'KNOWLEDGE', scopeId: 'project', title: 'Project', summary: 'Summary', body: 'Keep the condition.',
    claims: [{ text: 'Conditional claim', basis: 'INFERENCE', sourceIds: ['source'] }], sourceIds: ['source'], origin: 'GENERATED', enabled: false, version: 2 }],
  suppressedKnowledgeIds: [], sourceEvidence: [{ id: 'source', reference: { kind: 'DOCUMENT', sourceId: 'docs', url: 'https://example.com/docs/page' },
    provenance: 'Captured page', excerpt: 'Original text', capture: 'BYTEQUAY_READ', retrievedAt: null, contentHash: null, version: null, truncated: true }],
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
  it('retains ownership, disabled notes, conditional claims and incomplete source provenance', () => {
    expect(parseRepositoryNotebook(notebook)).toEqual(notebook);
    expect(parseRepositoryNotebook({ ...notebook, useInReviews: false }).useInReviews).toBe(false);
  });

  it('rejects malformed saved content instead of rendering an empty notebook or coercing preferences', () => {
    for (const value of [null, {}, { ...notebook, schemaVersion: 2 }, { ...notebook, useInReviews: 'false' },
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
      inputDigest: 'sha256:input', inputUtf8Bytes: 500, discussionMessageId: null, preparedAt: '2026-09-01T00:00:00Z' };
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

  it('keeps optimistic revisions and absent configuration fields unchanged', () => {
    expect(parseConfigureRepositoryContextRequest({ expectedRevision: 7, useInReviews: false }))
      .toEqual({ expectedRevision: 7, useInReviews: false });
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
