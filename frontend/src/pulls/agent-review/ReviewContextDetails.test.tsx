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
import { reviewCitations } from './ReviewEvidenceLinks';
import type { ContextDeliveryStateDto, ContextPreparationActivityDto, ContextNoteReadDto, ReviewCandidateValidationDto, ReviewRunDto } from './contracts';

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
      registration: { id: 'docs', kind: 'DOCUMENTATION_ROOT', url: 'https://example.com/docs/', origin: 'USER', discoveryEvidenceRef: null }, notebookRevision: 4,
      captureId: 'capture-1', contentHash: 'c'.repeat(64), readAt: '2026-01-02T00:00:00Z', page: 1, totalPages: 2, utf8Bytes: 0,
      blank: true, truncated: false, refreshFailed: true, evidenceRef: null },
  ], gaps: ['Selected notes have not been verified for this PR.'], contributions: [], deliveries: [],
};
const run = { ...original, context, evidence: [{ ref: 'E1', source: 'Frozen source', provenance: 'Observed at the saved baseline', excerpt: 'exact evidence\n', explanation: '' }] };
const deliveredContext: ReviewContextSnapshotDto = { ...context, contributions: [
  { investigationStep: 1, text: 'Saved brief\n<script>reference text</script>', evidenceRefs: ['E1'], preparationRefs: ['prepared:project'], limitations: ['Version applicability is unknown.'] },
  { investigationStep: 3, text: 'Additional evidence preserves the first brief.', evidenceRefs: ['missing-evidence'], preparationRefs: [], limitations: [] },
], deliveries: [{ turnId: 'turn-validation', packetDigest: 'sha256:brief-packet', utf8Bytes: 456, contributionCount: 1,
  inputDigest: 'sha256:complete-input', inputUtf8Bytes: 789, discussionMessageId: 'original-message', discussionSupplement: null, preparedAt: '2026-01-03T00:00:00Z' }] };

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

  it('shows durable preparation outcomes without treating waiting or empty as a current running state', () => {
    const events: ContextPreparationActivityDto[] = [
      { operationRef: 'begin', status: 'OWNED', attemptId: 'attempt', recordedAt: '2026-01-03T00:00:00Z', elapsedMillis: 12, scopes: [], gaps: [] },
      { operationRef: 'join', status: 'JOINED', attemptId: 'attempt', recordedAt: null, elapsedMillis: null, scopes: [], gaps: ['Another agent owns preparation.'] },
      { operationRef: 'defer', status: 'DEFERRED', attemptId: null, recordedAt: null, elapsedMillis: 0, scopes: [], gaps: ['Selected input is missing.'] },
      { operationRef: 'reuse', status: 'REUSED', attemptId: null, recordedAt: null, elapsedMillis: 4, scopes: [
        { scopeId: 'project', outcome: 'EMPTY', explanation: 'No useful note from checked sources.', noteIds: [], checkedSourceIds: ['read-1'] }], gaps: [] },
      { operationRef: 'submit', status: 'APPLIED', attemptId: 'attempt', recordedAt: null, elapsedMillis: 20, scopes: [
        { scopeId: 'connector', outcome: 'FAILED', explanation: 'Source unavailable.', noteIds: [], checkedSourceIds: [] }], gaps: [] },
    ];
    const html = renderToStaticMarkup(<ReviewContextDetails run={{ ...run, contextPreparationActivities: events }} />);
    for (const text of ['Preparation started', 'Waiting for shared preparation', 'Preparation deferred', 'Reused saved preparation',
      'Checked — no useful knowledge found', 'Preparation failed', 'No useful note from checked sources.', 'Another agent owns preparation.',
      '12 ms', 'not current running state', 'Token usage', 'Not recorded for repository context']) expect(html).toContain(text);
    expect(html).toContain('12 UTF-8 bytes across 2 read receipts');
    expect(html).not.toContain('Cache hit');
  });

  it('keeps discussion supplements separate from the frozen base packet and renders exact inert source text', () => {
    const delivery = { ...deliveredContext.deliveries[0], discussionSupplement: { packet: { text: 'Later comment\n<script>inert</script>',
      utf8Bytes: 36, digest: 'sha256:discussion' }, evidenceRefs: ['E1'], capturedAt: '2026-01-04T00:00:00Z', gaps: ['Local comments only.'] } };
    const html = renderToStaticMarkup(<ReviewContextDetails run={{ ...run, context: { ...deliveredContext, deliveries: [delivery] } }} />);
    expect(html).toContain('Exact discussion context · 36 additional bytes');
    expect(html).toContain('Later comment\n&lt;script&gt;inert&lt;/script&gt;');
    expect(html).toContain('sha256:discussion');
    expect(html).toContain('Local comments only.');
    expect(html).toContain('Base context bytes</dt><dd>456 UTF-8 bytes');
    expect(html).not.toContain('<script>');
  });

  it('links sources to saved conclusions and same-run findings without claiming brief references are citations', () => {
    const validation: ReviewCandidateValidationDto = { candidate: 1, outcome: 'REJECTED' as const, conclusion: 'Guard disproves this claim.', evidenceRefs: ['E1'],
      counterevidence: 'The guard rejects repeated fields.', findingId: null, findingRevision: null, request: null };
    const validated: ReviewRunDto = { ...run, steps: [{ number: 2, kind: 'VALIDATE', repair: false, investigation: null,
      validation: { investigationStep: 1, candidates: [validation], coverage: { complete: true, omissions: [], recordedAt: '2026-01-01T00:00:00Z' }, newCandidates: [] } }] };
    const finding = { ...reviewFixture().findings[0], state: 'RESOLVED' as const };
    const findings = [finding, { ...finding, id: 'other-run', runId: 'newer-run', label: 'F-new' }];
    const targets = reviewCitations(validated, findings).get('E1');
    expect(targets?.map(item => item.target)).toEqual(['agent-review-finding-finding-1', 'review-validation-run-1-2-1']);
    const html = renderToStaticMarkup(<ReviewDetails run={validated} repository="trino/trino" findings={findings} />);
    expect(html).toContain('Cited by');
    expect(html).toContain('Pass 2 · hypothesis 1 · rejected');
    expect(html).toContain('href="#review-validation-run-1-2-1"');
    expect(html).toContain('id="review-validation-run-1-2-1"');
    expect(html).toContain('href="#review-evidence-run-1-E1"');
    expect(html).toContain('id="review-evidence-run-1-E1"');
    expect(html).not.toContain('F-new');
    expect(reviewCitations({ ...run, context: deliveredContext }, []).size).toBe(0);
  });

  it('shows an exact deferred-note receipt under its original selection and includes its sources in citation metrics', () => {
    const read: ContextNoteReadDto = { entryId: 'knowledge:iceberg', entryVersion: 5, status: 'AVAILABLE', text: 'Exact deferred note',
      evidenceRef: 'note:5', sourceEvidenceRefs: { 'new-source': 'E2' } };
    const evidence = [...run.evidence, { ref: 'note:5', source: 'Selected note v5', provenance: 'Exact selected version', excerpt: read.text ?? '', explanation: '' },
      { ref: 'E2', source: 'Captured note source', provenance: 'Original source', excerpt: 'source', explanation: '' }];
    const finding = { ...reviewFixture().findings[0], evidence: [evidence[1]] };
    const html = renderToStaticMarkup(<ReviewContextDetails run={{ ...run, evidence, contextNoteReads: [read] }} findings={[finding]} />);
    expect(html).toContain('Iceberg contracts · version 5 · deferred');
    expect(html).toContain('Retrieved by agent · exact version 5');
    expect(html).toContain('Exact deferred note');
    expect(html).toContain('Captured note source');
    expect(html).toContain('1 of 3 saved context references cited');
    const unavailable = renderToStaticMarkup(<ReviewContextDetails run={{ ...run, contextNoteReads: [{ ...read, status: 'RECEIPT_UNAVAILABLE',
      text: null, evidenceRef: null, sourceEvidenceRefs: {} }] }} />);
    expect(unavailable).toContain('The saved exact-version read is unavailable. No current note has been substituted.');
    expect(unavailable).not.toContain('Retrieved by agent');
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
