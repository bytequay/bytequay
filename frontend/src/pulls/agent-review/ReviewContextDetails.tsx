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
import type { ReviewRunDto } from './contracts';

type SourceRead = NonNullable<ReviewRunDto['context']>['sourceReads'][number];

function Evidence({ run, reference }: { run: ReviewRunDto; reference: string | null }) {
  if (reference === null) return <p>No nonblank excerpt was recorded for this read.</p>;
  const evidence = run.evidence.find(item => item.ref === reference);
  return <details className="agr-context__evidence">
    <summary>Saved evidence · <code>{reference}</code></summary>
    {evidence === undefined ? <p>The saved excerpt is unavailable in this run.</p> : <>
      <p>{evidence.source}</p><p>{evidence.provenance}</p>
      <pre className="agr-evidence__excerpt">{evidence.excerpt}</pre>
      {evidence.explanation && <p>{evidence.explanation}</p>}
    </>}
  </details>;
}

function SourceReceipt({ read, run }: { read: SourceRead; run: ReviewRunDto }) {
  return <details className="agr-context__item">
    <summary>{read.kind === 'DOCUMENT' ? read.reference.url : read.reference.path}
      {' · '}{read.utf8Bytes.toLocaleString()} bytes{read.truncated ? ' · truncated' : ''}</summary>
    <dl className="agr-context__facts">
      <dt>Captured</dt><dd>{read.readAt}</dd>
      <dt>Read receipt</dt><dd><code>{read.id}</code></dd>
      {read.kind === 'REPOSITORY_FILE' ? <>
        <dt>Repository</dt><dd>{read.reference.repository.owner}/{read.reference.repository.repo} · ID {read.reference.repositoryId}</dd>
        <dt>Frozen commit</dt><dd><code>{read.reference.commit}</code></dd>
        <dt>Blob</dt><dd><code>{read.blobId}</code></dd>
        <dt>Lines</dt><dd>{read.totalLines === 0 ? 'Empty file' : `${read.startLine}–${read.endLine} of ${read.totalLines}`}</dd>
      </> : <>
        <dt>Registered source</dt><dd>{read.registration.id} · {read.registration.url}</dd>
        <dt>Notebook revision</dt><dd>{read.notebookRevision}</dd>
        <dt>Capture</dt><dd><code>{read.captureId}</code></dd>
        <dt>Content hash</dt><dd><code>{read.contentHash}</code></dd>
        <dt>Page</dt><dd>{read.page} of {read.totalPages}{read.blank ? ' · blank' : ''}</dd>
      </>}
    </dl>
    {read.truncated && <p>The excerpt does not contain the complete source window.</p>}
    {read.kind === 'DOCUMENT' && read.refreshFailed && <p>Refresh failed. This is the earlier capture, with its original capture time.</p>}
    <Evidence run={run} reference={read.evidenceRef} />
  </details>;
}

export default function ReviewContextDetails({ run }: { run: ReviewRunDto }) {
  const context = run.context;
  if (context == null) return <section className="agr-context" aria-label="Repository context">
    <h3>Repository context · Not recorded</h3>
    <p>This run has no saved context snapshot. Its context setting and packet are unknown.</p>
  </section>;
  if (!context.enabled) return <section className="agr-context" aria-label="Repository context">
    <h3>Repository context · Off for this run</h3>
    <p>No repository context packet was prepared. The setting was captured at notebook revision {context.notebookRevision}.</p>
  </section>;
  const included = context.selectedEntries.filter(entry => entry.inclusion === 'INCLUDED').length;
  return <section className="agr-context" aria-label="Repository context">
    <h3>Repository context · Prepared</h3>
    <p>{context.bootstrap?.utf8Bytes.toLocaleString() ?? '0'} UTF-8 bytes · {included} {included === 1 ? 'note' : 'notes'} included
      {' · '}{context.relatedItems.length} related {context.relatedItems.length === 1 ? 'source' : 'sources'}</p>
    <p>Prepared means saved for this run. Delivery to the agent and use in its conclusions have not been established.</p>
    <dl className="agr-context__facts">
      <dt>Notebook revision</dt><dd>{context.notebookRevision}</dd>
      <dt>Repository ID</dt><dd>{context.repositoryId}</dd>
      <dt>Frozen baseline</dt><dd><code>{context.baselineSha ?? 'Unavailable'}</code></dd>
    </dl>
    {context.bootstrap !== null && <details className="agr-context__item">
      <summary>Exact saved packet · {context.bootstrap.utf8Bytes.toLocaleString()} bytes</summary>
      <p className="agr-context__identity"><code>{context.bootstrap.digest}</code></p>
      <pre className="agr-evidence__excerpt">{context.bootstrap.text}</pre>
    </details>}
    <details className="agr-context__item">
      <summary>Selected notes · {context.selectedEntries.length}</summary>
      {context.selectedEntries.length === 0 && <p>No notebook notes matched this run.</p>}
      {context.selectedEntries.map(entry => <details className="agr-context__item" key={entry.entryId}>
        <summary>{entry.title} · version {entry.entryVersion} · {entry.inclusion === 'INCLUDED' ? 'included' : 'deferred'}</summary>
        <p><code>{entry.entryId}</code> · {{ MATCHED: 'Applicability matched', VERSION_MISMATCH: 'Version mismatch', UNVERIFIED: 'Applicability unverified' }[entry.applicability]}</p>
        <strong>Why selected</strong><ul>{entry.matchReasons.map(reason => <li key={reason}>{reason}</li>)}</ul>
        {entry.text !== null ? <pre className="agr-evidence__excerpt">{entry.text}</pre> : <p>This note was deferred; its text was not included in the packet.</p>}
        {Object.entries(entry.sourceEvidenceRefs).map(([source, reference]) => <div key={source}>
          <p>Notebook source: <code>{source}</code></p><Evidence run={run} reference={reference} />
        </div>)}
      </details>)}
    </details>
    <details className="agr-context__item">
      <summary>Related history · {context.relatedItems.length}</summary>
      {context.relatedItems.length === 0 && <p>No related history was included.</p>}
      {context.relatedItems.map(item => <div className="agr-context__item" key={item.sourceId}>
        <ul>{item.matchReasons.map(reason => <li key={reason}>{reason}</li>)}</ul>
        <p>{item.presentInReviewedBase === null ? 'Presence in the reviewed base is unknown.' : item.presentInReviewedBase
          ? 'The associated history commit is present in the reviewed base.' : 'The associated history commit is absent from the reviewed base.'}</p>
        <Evidence run={run} reference={item.sourceId} />
      </div>)}
    </details>
    <details className="agr-context__item">
      <summary>Captured source reads · {context.sourceReads.length}</summary>
      <p>These records identify successful tool reads. They do not establish that a source was understood or its claims verified.</p>
      {context.sourceReads.length === 0 && <p>No source reads have been recorded.</p>}
      {context.sourceReads.map(read => <SourceReceipt key={read.id} read={read} run={run} />)}
    </details>
    <details className="agr-context__item">
      <summary>Input identities · {context.inputFingerprints.length} {context.inputFingerprints.length === 1 ? 'scope' : 'scopes'}</summary>
      <p>Matching identities can establish unchanged inputs; they do not verify a note’s claims.</p>
      {context.inputFingerprints.map(input => <details className="agr-context__item" key={input.scopeId}>
        <summary>{input.scopeId} · {input.digest === null ? 'incomplete' : 'captured'}</summary>
        {input.digest !== null && <p><code>{input.digest}</code></p>}
        <dl className="agr-context__facts">{Object.entries(input.inputs).map(([key, value]) => <div key={key}><dt>{key}</dt><dd><code>{value}</code></dd></div>)}</dl>
        {input.gaps.length > 0 && <ul>{input.gaps.map(gap => <li key={gap}>{gap}</li>)}</ul>}
      </details>)}
    </details>
    <details className="agr-context__item" open={context.gaps.length > 0}>
      <summary>Known gaps · {context.gaps.length}</summary>
      {context.gaps.length === 0 ? <p>No gaps were recorded.</p> : <ul>{context.gaps.map(gap => <li key={gap}>{gap}</li>)}</ul>}
    </details>
  </section>;
}
