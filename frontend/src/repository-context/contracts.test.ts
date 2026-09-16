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
import { parseAgentReview } from '../pulls/agent-review/contracts';
import { reviewFixture } from '../pulls/agent-review/fixtures/review';
import {
  parseConfigureRepositoryContextRequest,
  parseEditRepositoryContextEntryRequest,
  parseRepositoryNotebook,
  parseReviewContextSnapshot,
  parseSaveRepositoryContextLessonRequest,
  type ContextSourceDto,
  type RepositoryNotebookDto,
  type ReviewContextSnapshotDto,
} from './contracts';

const source: ContextSourceDto = { id: 'docs', kind: 'DOCUMENTATION_ROOT', url: 'https://example.com/docs/', origin: 'USER' };
const notebook: RepositoryNotebookDto = {
  schemaVersion: 1, revision: 7, useInReviews: true, sources: [source],
  scopes: [{ id: 'project', title: 'Project', match: { kind: 'REPOSITORY' }, enabled: true }],
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
  gaps: ['Source applicability has not been verified.'],
};

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

  it('keeps optimistic revisions and absent configuration fields unchanged', () => {
    expect(parseConfigureRepositoryContextRequest({ expectedRevision: 7, useInReviews: false }))
      .toEqual({ expectedRevision: 7, useInReviews: false });
    expect(() => parseConfigureRepositoryContextRequest({ useInReviews: false })).toThrow('expected revision');
    expect(() => parseConfigureRepositoryContextRequest({ expectedRevision: 7, sources: [source] })).toThrow('origin');
    expect(() => parseEditRepositoryContextEntryRequest({ expectedRevision: 7, ...notebook.entries[0] })).toThrow('unsupported request field');
  });

  it('preserves the selected discussion run and evidence rather than substituting the latest run', () => {
    const request = { expectedRevision: 7, scopeId: 'project', title: 'Lesson', summary: 'Conditional lesson', body: 'Only under these conditions.',
      reviewId: 'review', runId: 'older-run', evidenceRefs: ['E3'], messageId: 'old-message' };
    expect(parseSaveRepositoryContextLessonRequest(request)).toEqual(request);
    expect(() => parseSaveRepositoryContextLessonRequest({ ...request, sourceEvidence: [] })).toThrow('unsupported request field');
  });
});
