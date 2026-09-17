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
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { ContextHistoryReadDto, ReviewContextSnapshotDto } from '../../repository-context/contracts';
import ReviewContextDetails from './ReviewContextDetails';
import ReviewDetails from './ReviewDetails';
import ReviewThread from './ReviewThread';
import { reviewFixture } from './fixtures/review';
import type { ContextDeliveryStateDto } from './contracts';

const original = reviewFixture().runs[0];
const packet = 'Exact packet\n<script>source text</script>\n  retain indentation\n';
const context: ReviewContextSnapshotDto = {
  id: 'context:run-1', enabled: true, notebookRevision: 4, repositoryId: 7, baselineSha: 'a'.repeat(40),
  bootstrap: { text: packet, utf8Bytes: new TextEncoder().encode(packet).length, digest: 'sha256:captured-digest' },
  selectedEntries: [{ entryId: 'knowledge:project', title: 'Architecture', entryVersion: 2, text: 'Exact selected note\nwith its source.',
    sourceEvidenceRefs: { 'source-1': 'E1' }, matchReasons: ['Project scope'], applicability: 'UNVERIFIED', inclusion: 'INCLUDED' },
  { entryId: 'knowledge:iceberg', title: 'Iceberg contracts', entryVersion: 5, text: null, sourceEvidenceRefs: {},
    matchReasons: ['Changed connector path'], applicability: 'VERSION_MISMATCH', inclusion: 'DEFERRED' }],
  relatedItems: [{ sourceId: 'E1', matchReasons: ['Same component'], presentInReviewedBase: null }],
  inputFingerprints: [{ scopeId: 'project', inputs: { 'git:README.md': 'blob identity' }, digest: null, gaps: ['Manifest missing'] }],
  sourceReads: [
    { kind: 'REPOSITORY_FILE', id: 'context-read:repository', reference: { kind: 'REPOSITORY_FILE', repositoryId: 7,
      repository: { owner: 'trino', repo: 'trino' }, path: 'README.md', commit: 'a'.repeat(40) }, blobId: 'b'.repeat(40), readAt: '2026-01-01T00:00:00Z',
      startLine: 1, endLine: 2, totalLines: 8, utf8Bytes: 12, truncated: true, evidenceRef: 'E1' },
    { kind: 'DOCUMENT', id: 'context-read:document', reference: { kind: 'DOCUMENT', sourceId: 'docs', url: 'https://example.com/docs/concepts' },
      registration: { id: 'docs', kind: 'DOCUMENTATION_ROOT', url: 'https://example.com/docs/', origin: 'USER' }, notebookRevision: 4,
      captureId: 'capture-1', contentHash: 'c'.repeat(64), readAt: '2026-01-02T00:00:00Z', page: 1, totalPages: 2, utf8Bytes: 0,
      blank: true, truncated: false, refreshFailed: true, evidenceRef: null },
  ], gaps: ['Selected notes have not been verified for this PR.'], contributions: [], deliveries: [],
};
const run = { ...original, context, evidence: [{ ref: 'E1', source: 'Frozen source', provenance: 'Observed at the saved baseline', excerpt: 'exact evidence\n', explanation: '' }] };
const deliveredContext: ReviewContextSnapshotDto = { ...context, contributions: [
  { investigationStep: 1, text: 'Saved brief\n<script>reference text</script>', evidenceRefs: ['E1'], preparationRefs: ['prepared:project'], limitations: ['Version applicability is unknown.'] },
  { investigationStep: 3, text: 'Additional evidence preserves the first brief.', evidenceRefs: ['missing-evidence'], preparationRefs: [], limitations: [] },
], deliveries: [{ turnId: 'turn-validation', packetDigest: 'sha256:brief-packet', utf8Bytes: 456, contributionCount: 1,
  inputDigest: 'sha256:complete-input', inputUtf8Bytes: 789, discussionMessageId: 'original-message', preparedAt: '2026-01-03T00:00:00Z' }] };

describe('saved review repository context', () => {
  it('distinguishes absent snapshots, captured off and prepared packets without asserting delivery', () => {
    expect(renderToStaticMarkup(<ReviewContextDetails run={{ ...run, context: null }} />)).toContain('Not recorded');
    const off = renderToStaticMarkup(<ReviewContextDetails run={{ ...run, context: { ...context, enabled: false, bootstrap: null } }} />);
    expect(off).toContain('Off for this run');
    expect(off).not.toContain('Exact initial saved packet');
    const html = renderToStaticMarkup(<ReviewContextDetails run={run} />);
    expect(html).toContain('Repository context · Prepared');
    expect(html).toContain('No provider submission has been recorded.');
    expect(html).not.toContain('Provided to agent');
  });

  it.each<[ContextDeliveryStateDto['outcome'], string]>([
    ['PREPARED', 'Prepared'], ['SUBMITTED', 'Waiting for confirmation'], ['CONFIRMED', 'Provided to agent'], ['UNCERTAIN', 'Delivery uncertain'],
  ])('derives the %s label only from the provider delivery state', (outcome, label) => {
    const html = renderToStaticMarkup(<ReviewContextDetails run={{ ...run, context: deliveredContext,
      contextDeliveryStates: { 'turn-validation': { outcome, detail: 'Recorded delivery detail.' } } }} />);
    expect(html).toContain(`Repository context · ${label}`);
    expect(html).toContain('456 UTF-8 bytes in the latest prepared turn');
    expect(html).toContain('Recorded delivery detail.');
    expect(html).toContain('sha256:brief-packet');
    expect(html).toContain('sha256:complete-input');
    expect(html).toContain('789 UTF-8 bytes');
    expect(html).toContain('turn-validation');
    expect(html).toContain('original-message');
    expect(html).toContain('Delivery and source reads do not establish that context was understood or improved the review.');
    if (outcome !== 'CONFIRMED') expect(html).not.toContain('Provided to agent');
  });

  it('keeps a missing receipt uncertain and displays append-only brief text and limitations alongside the bootstrap', () => {
    const html = renderToStaticMarkup(<ReviewContextDetails run={{ ...run, context: deliveredContext }} />);
    expect(html).toContain('Repository context · Delivery uncertain');
    expect(html).toContain('The delivery receipt is unavailable for this turn.');
    expect(html).toContain('Exact packet\n&lt;script&gt;source text&lt;/script&gt;\n  retain indentation\n');
    expect(html).toContain('Completed context brief · investigation step 1');
    expect(html).toContain('Evidence addition 1 · investigation step 3');
    expect(html).toContain('Saved brief\n&lt;script&gt;reference text&lt;/script&gt;');
    expect(html).toContain('Additional evidence preserves the first brief.');
    expect(html).toContain('Version applicability is unknown.');
    expect(html).toContain('prepared:project');
    expect(html).toContain('The saved excerpt is unavailable in this run.');
    expect(html).toContain('Its claims and applicability remain unverified.');
    expect(html).not.toContain('<script>');
    expect(html).not.toContain('Provided to agent');
  });

  it('reports the latest turn independently while preserving earlier confirmed receipts', () => {
    const first = deliveredContext.deliveries[0];
    const html = renderToStaticMarkup(<ReviewContextDetails run={{ ...run,
      context: { ...deliveredContext, deliveries: [first, { ...first, turnId: 'turn-discussion', contributionCount: 2 }] },
      contextDeliveryStates: { 'turn-validation': { outcome: 'CONFIRMED', detail: null }, 'turn-discussion': { outcome: 'PREPARED', detail: 'Prepared but not sent.' } },
    }} />);
    expect(html).toContain('Repository context · Prepared');
    expect(html).toContain('Delivery 1 · Provided to agent');
    expect(html).toContain('Delivery 2 · Prepared');
    expect(html).toContain('Prepared but not sent.');
    expect(html).toContain('Completed context brief and 1 evidence addition');
  });

  it('renders the exact captured packet as escaped text and keeps selected versions and matching reasons', () => {
    const html = renderToStaticMarkup(<ReviewDetails run={run} repository="trino/trino" />);
    expect(html).toContain('Exact packet\n&lt;script&gt;source text&lt;/script&gt;\n  retain indentation\n');
    expect(html).toContain('sha256:captured-digest');
    expect(html).toContain('Architecture · version 2 · included');
    expect(html).toContain('Iceberg contracts · version 5 · deferred');
    expect(html).toContain('Changed connector path');
    expect(html).toContain('Exact selected note\nwith its source.');
    expect(html).toContain('Selected notes have not been verified for this PR.');
  });

  it('shows source identity, original capture time and exact run evidence with no inferred freshness', () => {
    const html = renderToStaticMarkup(<ReviewContextDetails run={run} />);
    expect(html).toContain('context-read:repository');
    expect(html).toContain('b'.repeat(40));
    expect(html).toContain('2026-01-02T00:00:00Z');
    expect(html).toContain('Refresh failed. This is the earlier capture, with its original capture time.');
    expect(html).toContain('exact evidence\n');
    expect(html).toContain('No nonblank excerpt was recorded for this read.');
    expect(html).toContain('Manifest missing');
    expect(html).toContain('Presence in the reviewed base is unknown.');
  });

  it('shows retained history state and exact evidence without equating merge or closure with a fix', () => {
    const merged: ContextHistoryReadDto = { kind: 'HISTORY', id: 'history:merged',
      reference: { kind: 'PR', repositoryId: 7, repository: { owner: 'trino', repo: 'trino' }, number: 18, commentId: null },
      state: 'MERGED', sourceUpdatedAt: '2026-01-03T00:00:00Z', sourceRevision: 'd'.repeat(40), contentHash: 'e'.repeat(64),
      readAt: '2026-01-04T00:00:00Z', utf8Bytes: 120, truncated: true, refreshFailed: true, gaps: ['Final diff completeness is unverified.'], evidenceRef: 'E1' };
    const closed: ContextHistoryReadDto = { ...merged, id: 'history:closed', reference: { ...merged.reference, kind: 'ISSUE', number: 9 },
      state: 'CLOSED', sourceUpdatedAt: null, sourceRevision: null, truncated: false, refreshFailed: false, gaps: ['Closure reason is unknown.'] };
    const html = renderToStaticMarkup(<ReviewContextDetails run={{ ...run, context: { ...context, sourceReads: [merged, closed] } }} />);
    expect(html).toContain('Merged PR #18');
    expect(html).toContain('Closed issue #9');
    expect(html).toContain('trino/trino · ID 7');
    expect(html).toContain('2026-01-03T00:00:00Z');
    expect(html).toContain('2026-01-04T00:00:00Z');
    expect(html).toContain('d'.repeat(40));
    expect(html).toContain('e'.repeat(64));
    expect(html).toContain('Source updated</dt><dd>Unknown');
    expect(html).toContain('A selected source refresh failed. See the limitations for missing or earlier stored data.');
    expect(html).toContain('Merged or closed status does not establish current behavior or prove that an issue was fixed.');
    expect(html).toContain('Final diff completeness is unverified.');
    expect(html).toContain('Closure reason is unknown.');
    expect(html).toContain('exact evidence\n');
  });

  it('offers Save lesson only for messages bound to a retained run, including older runs', () => {
    const base = reviewFixture();
    const message = { ...base.messages[0], runId: original.id };
    const review = { ...base, runs: [original, { ...original, id: 'newer', ordinal: 2 }],
      messages: [message, { ...message, id: 'missing', runId: undefined }, { ...message, id: 'unknown', runId: 'deleted-run' }] };
    const html = renderToStaticMarkup(<ReviewThread review={review} messages={review.messages} viewerLogin="reader" viewerAvatarUrl={null} />);
    expect(html.match(/>Save lesson<\/button>/g)).toHaveLength(1);
  });
});
