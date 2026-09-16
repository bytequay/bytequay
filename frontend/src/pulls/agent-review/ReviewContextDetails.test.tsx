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
import type { ReviewContextSnapshotDto } from '../../repository-context/contracts';
import ReviewContextDetails from './ReviewContextDetails';
import ReviewDetails from './ReviewDetails';
import ReviewThread from './ReviewThread';
import { reviewFixture } from './fixtures/review';

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
  ], gaps: ['Selected notes have not been verified for this PR.'],
};
const run = { ...original, context, evidence: [{ ref: 'E1', source: 'Frozen source', provenance: 'Observed at the saved baseline', excerpt: 'exact evidence\n', explanation: '' }] };

describe('saved review repository context', () => {
  it('distinguishes absent snapshots, captured off and prepared packets without asserting delivery', () => {
    expect(renderToStaticMarkup(<ReviewContextDetails run={{ ...run, context: null }} />)).toContain('Not recorded');
    const off = renderToStaticMarkup(<ReviewContextDetails run={{ ...run, context: { ...context, enabled: false, bootstrap: null } }} />);
    expect(off).toContain('Off for this run');
    expect(off).not.toContain('Exact saved packet');
    const html = renderToStaticMarkup(<ReviewContextDetails run={run} />);
    expect(html).toContain('Repository context · Prepared');
    expect(html).toContain('Delivery to the agent and use in its conclusions have not been established.');
    expect(html).not.toContain('Provided to agent');
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

  it('offers Save lesson only for messages bound to a retained run, including older runs', () => {
    const base = reviewFixture();
    const message = { ...base.messages[0], runId: original.id };
    const review = { ...base, runs: [original, { ...original, id: 'newer', ordinal: 2 }],
      messages: [message, { ...message, id: 'missing', runId: undefined }, { ...message, id: 'unknown', runId: 'deleted-run' }] };
    const html = renderToStaticMarkup(<ReviewThread review={review} messages={review.messages} viewerLogin="reader" viewerAvatarUrl={null} />);
    expect(html.match(/>Save lesson<\/button>/g)).toHaveLength(1);
  });
});
