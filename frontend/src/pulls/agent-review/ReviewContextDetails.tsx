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
import type { ContextContributionDto, ContextDeliveryDto } from '../../repository-context/contracts';
import type { ContextDeliveryStateDto, ContextNoteSearchDto, ReviewFindingDto, ReviewRunDto } from './contracts';
import { reviewCitations, EvidenceCitationLinks, type EvidenceCitations } from './ReviewEvidenceLinks';
import ReviewPreparationActivity from './ReviewPreparationActivity';

type SourceRead = NonNullable<ReviewRunDto['context']>['sourceReads'][number];
const deliveryLabels: Record<ContextDeliveryStateDto['outcome'], string> = {
  PREPARED: 'Prepared', SUBMITTED: 'Waiting for confirmation', CONFIRMED: 'Provided to agent', UNCERTAIN: 'Delivery uncertain',
};
const deliveryExplanations: Record<ContextDeliveryStateDto['outcome'], string> = {
  PREPARED: 'Input was prepared; no provider submission is confirmed.',
  SUBMITTED: 'Input was submitted; provider confirmation is pending.',
  CONFIRMED: 'Provider acceptance confirms this packet was included in the turn input.',
  UNCERTAIN: 'Submission or provider acceptance could not be established.',
};

function deliveryState(run: ReviewRunDto, delivery: ContextDeliveryDto): ContextDeliveryStateDto {
  return run.contextDeliveryStates[delivery.turnId] ?? { outcome: 'UNCERTAIN', detail: 'The delivery receipt is unavailable for this turn.' };
}

function Evidence({ run, reference, citations }: { run: ReviewRunDto; reference: string | null; citations: EvidenceCitations }) {
  if (reference === null) return <p>No nonblank excerpt was recorded for this read.</p>;
  const evidence = run.evidence.find(item => item.ref === reference);
  return <details className="agr-context__evidence">
    <summary>Saved evidence · <code>{reference}</code></summary>
    {evidence === undefined ? <p>The saved excerpt is unavailable in this run.</p> : <>
      <p>{evidence.source}</p><p>{evidence.provenance}</p>
      <pre className="agr-evidence__excerpt">{evidence.excerpt}</pre>
      {evidence.explanation && <p>{evidence.explanation}</p>}
    </>}
    <EvidenceCitationLinks reference={reference} citations={citations} />
  </details>;
}

function SourceReceipt({ read, run, citations }: { read: SourceRead; run: ReviewRunDto; citations: EvidenceCitations }) {
  const title = read.kind === 'DOCUMENT' ? read.reference.url : read.kind === 'REPOSITORY_FILE' ? read.reference.path
    : `${read.reference.kind === 'PR' ? 'Merged PR' : 'Closed issue'} #${read.reference.number}`;
  return <details className="agr-context__item">
    <summary>{title}
      {' · '}{read.utf8Bytes.toLocaleString()} bytes{read.truncated ? ' · truncated' : ''}</summary>
    <dl className="agr-context__facts">
      <dt>Captured</dt><dd>{read.readAt}</dd>
      <dt>Read receipt</dt><dd><code>{read.id}</code></dd>
      {read.kind === 'REPOSITORY_FILE' ? <>
        <dt>Repository</dt><dd>{read.reference.repository.owner}/{read.reference.repository.repo} · ID {read.reference.repositoryId}</dd>
        <dt>Frozen commit</dt><dd><code>{read.reference.commit}</code></dd>
        <dt>Blob</dt><dd><code>{read.blobId}</code></dd>
        <dt>Lines</dt><dd>{read.totalLines === 0 ? 'Empty file' : `${read.startLine}–${read.endLine} of ${read.totalLines}`}</dd>
      </> : read.kind === 'DOCUMENT' ? <>
        <dt>Registered source</dt><dd>{read.registration.id} · {read.registration.url}</dd>
        <dt>Notebook revision</dt><dd>{read.notebookRevision}</dd>
        <dt>Capture</dt><dd><code>{read.captureId}</code></dd>
        <dt>Content hash</dt><dd><code>{read.contentHash}</code></dd>
        <dt>Page</dt><dd>{read.page} of {read.totalPages}{read.blank ? ' · blank' : ''}</dd>
      </> : <>
        <dt>Repository</dt><dd>{read.reference.repository.owner}/{read.reference.repository.repo} · ID {read.reference.repositoryId}</dd>
        <dt>Recorded state</dt><dd>{read.state === 'MERGED' ? 'Merged pull request' : 'Closed issue'} #{read.reference.number}</dd>
        <dt>Source updated</dt><dd>{read.sourceUpdatedAt ?? 'Unknown'}</dd>
        {read.sourceRevision !== null && <><dt>Observed source revision</dt><dd><code>{read.sourceRevision}</code></dd></>}
        <dt>Content hash</dt><dd><code>{read.contentHash}</code></dd>
      </>}
    </dl>
    {read.truncated && <p>The excerpt does not contain the complete source window.</p>}
    {read.kind === 'DOCUMENT' && read.refreshFailed && <p>Refresh failed. This is the earlier capture, with its original capture time.</p>}
    {read.kind === 'HISTORY' && <>
      {read.refreshFailed && <p>A selected source refresh failed. See the limitations for missing or earlier stored data.</p>}
      <p>Merged or closed status does not establish current behavior or prove that an issue was fixed.</p>
      {read.gaps.length > 0 && <ul>{read.gaps.map(gap => <li key={gap}>{gap}</li>)}</ul>}
    </>}
    <Evidence run={run} reference={read.evidenceRef} citations={citations} />
  </details>;
}

function Contribution({ contribution, index, run, citations }: { contribution: ContextContributionDto; index: number; run: ReviewRunDto; citations: EvidenceCitations }) {
  return <details className="agr-context__item">
    <summary>{index === 0 ? 'Completed context brief' : `Evidence addition ${index}`} · investigation step {contribution.investigationStep}</summary>
    <p>This is the agent’s saved explanation. Its claims and applicability remain unverified.</p>
    <pre className="agr-evidence__excerpt">{contribution.text}</pre>
    {contribution.limitations.length > 0 && <><strong>Limitations</strong><ul>{contribution.limitations.map(item => <li key={item}>{item}</li>)}</ul></>}
    {contribution.evidenceRefs.map(reference => <Evidence key={reference} run={run} reference={reference} citations={citations} />)}
    {contribution.evidenceRefs.length === 0 && <p>No evidence references were included in this explanation.</p>}
    {contribution.preparationRefs.length > 0 && <details className="agr-context__evidence">
      <summary>Shared preparation references · {contribution.preparationRefs.length}</summary>
      <ul>{contribution.preparationRefs.map(reference => <li key={reference}><code>{reference}</code></li>)}</ul>
    </details>}
  </details>;
}

function DeliveryReceipt({ delivery, run, index, citations }: { delivery: ContextDeliveryDto; run: ReviewRunDto; index: number; citations: EvidenceCitations }) {
  const state = deliveryState(run, delivery);
  const supplement = delivery.discussionSupplement;
  const additions = delivery.contributionCount - 1;
  const contents = delivery.contributionCount === 0 ? 'Initial saved packet'
    : delivery.contributionCount === 1 ? 'Completed context brief' : `Completed context brief and ${additions} evidence ${additions === 1 ? 'addition' : 'additions'}`;
  return <details className="agr-context__item">
    <summary>Delivery {index + 1} · {deliveryLabels[state.outcome]} · {delivery.utf8Bytes.toLocaleString()} bytes</summary>
    <p>{deliveryExplanations[state.outcome]}</p>
    {state.detail !== null && <p>{state.detail}</p>}
    <dl className="agr-context__facts">
      <dt>Context contents</dt><dd>{contents}</dd>
      <dt>Turn</dt><dd><code>{delivery.turnId}</code></dd>
      <dt>Prepared</dt><dd>{delivery.preparedAt}</dd>
      <dt>Base context bytes</dt><dd>{delivery.utf8Bytes.toLocaleString()} UTF-8 bytes</dd>
      <dt>Context digest</dt><dd><code>{delivery.packetDigest}</code></dd>
      <dt>Complete input bytes</dt><dd>{delivery.inputUtf8Bytes.toLocaleString()} UTF-8 bytes</dd>
      <dt>Complete input digest</dt><dd><code>{delivery.inputDigest}</code></dd>
      {delivery.discussionMessageId !== null && <><dt>Original discussion</dt><dd><code>{delivery.discussionMessageId}</code></dd></>}
    </dl>
    {supplement !== null && <details className="agr-context__evidence">
      <summary>Exact discussion context · {supplement.packet.utf8Bytes.toLocaleString()} additional bytes</summary>
      <p>Captured from local discussion data at {supplement.capturedAt}. The base packet above remains unchanged.</p>
      <p className="agr-context__identity"><code>{supplement.packet.digest}</code></p>
      <pre className="agr-evidence__excerpt">{supplement.packet.text}</pre>
      {supplement.evidenceRefs.map(reference => <Evidence key={reference} run={run} reference={reference} citations={citations} />)}
      {supplement.gaps.length > 0 && <ul>{supplement.gaps.map(gap => <li key={gap}>{gap}</li>)}</ul>}
    </details>}
  </details>;
}

function NoteSearchReceipt({ search, index, run, citations }: { search: ContextNoteSearchDto; index: number; run: ReviewRunDto; citations: EvidenceCitations }) {
  const confirmed = search.delivery === 'CONFIRMED';
  const label = search.status === 'RECEIPT_UNAVAILABLE' ? 'Receipt unavailable' : confirmed ? search.hits.length > 0 ? 'Found later' : 'Search delivered'
    : search.delivery === 'PREPARED' ? 'Search result prepared' : 'Delivery uncertain';
  return <details className="agr-context__item">
    <summary>Notebook search {index + 1} · {label} · {search.hits.length} {search.hits.length === 1 ? 'hit' : 'hits'}</summary>
    {search.status === 'RECEIPT_UNAVAILABLE' ? <p>The saved search receipt is unavailable. No current notebook results were substituted.</p> : <>
      <p>{confirmed ? 'The provider received this search result.' : 'The saved result does not confirm that the provider received it.'}
        {' '}Note summaries and match reasons are pointers; they do not prove the note was read or useful.</p>
      <dl className="agr-context__facts">
        <dt>Search text</dt><dd>{search.query || 'No text query'}</dd>
        <dt>Retained agent path hints</dt><dd>{search.paths.length === 0 ? 'None' : search.paths.join(', ')}</dd>
        <dt>Notebook revision</dt><dd>{search.notebookRevision ?? 'Unavailable'}{search.newerThanSnapshot ? ' · newer than the initial snapshot' : ''}</dd>
        <dt>Initial notebook revision</dt><dd>{search.originalNotebookRevision ?? 'Unavailable'}</dd>
        <dt>Path hints used</dt><dd>{search.pathHintCount} total · {search.frozenPathHints} frozen review paths</dd>
        <dt>Search receipt</dt><dd><code>{search.searchRef}</code></dd>
      </dl>
      {search.omittedPathHints > 0 && <p>{search.omittedPathHints} path {search.omittedPathHints === 1 ? 'hint was' : 'hints were'} omitted.</p>}
      {search.omittedCount > 0 && <p>{search.omittedCount} additional {search.omittedCount === 1 ? 'hit was' : 'hits were'} omitted by the result limit.</p>}
      {search.contextState === 'NO_MATCH' && <p>No saved note matched this search.</p>}
      {search.contextState === 'UNKNOWN' && <p>The search match state is unavailable.</p>}
      {search.hits.map(hit => {
        const read = hit.read;
        const readLabel = read?.status === 'RECEIPT_UNAVAILABLE' ? 'Read receipt unavailable'
          : read?.delivery === 'CONFIRMED' ? 'Opened' : read?.delivery === 'PREPARED' ? 'Exact read prepared'
            : read === null ? null : 'Exact read delivery uncertain';
        return <details className="agr-context__item" key={`${hit.entryId}:${hit.entryVersion}`}>
          <summary>{hit.title} · version {hit.entryVersion} · {hit.initiallySelected ? 'also in initial selection' : confirmed ? 'found later' : 'saved search hit'}</summary>
          <p>{hit.kind === 'KNOWLEDGE' ? 'Knowledge' : 'Saved lesson'} · scope <code>{hit.scopeId}</code> · applicability unverified</p>
          <p>{hit.summary}</p>
          <strong>Why matched</strong><ul>{hit.matchReasons.map(reason => <li key={reason}>{reason}</li>)}</ul>
          {read === null ? <p>No exact note read was saved for this hit.</p> : <details className="agr-context__evidence">
            <summary>{readLabel} · exact version {hit.entryVersion}</summary>
            <p>{read.status === 'RECEIPT_UNAVAILABLE' ? 'The saved exact read cannot be inspected.'
              : read.delivery === 'CONFIRMED' ? 'The provider received this exact note read.'
                : 'A read result was saved, but provider delivery is not confirmed.'} Its claims remain unverified.</p>
            <p>Read receipt · <code>{read.receiptRef}</code></p>
            {read.status === 'RECEIPT_UNAVAILABLE' ? <p>The exact saved read is unavailable. No current note was substituted.</p> : <>
              <Evidence run={run} reference={read.evidenceRef} citations={citations} />
              {Object.entries(read.sourceEvidenceRefs).map(([source, reference]) => <div key={source}>
                <p>Notebook source: <code>{source}</code></p><Evidence run={run} reference={reference} citations={citations} />
              </div>)}
            </>}
          </details>}
        </details>;
      })}
    </>}
  </details>;
}

export default function ReviewContextDetails({ run, findings = [] }: { run: ReviewRunDto; findings?: ReviewFindingDto[] }) {
  const citations = reviewCitations(run, findings);
  const context = run.context;
  if (context == null) return <section className="agr-context" aria-label="Repository context">
    <h3>Repository context · Not recorded</h3>
    <p>This run has no saved context snapshot. Its context setting and packet are unknown.</p>
  </section>;
  if (!context.enabled) return <section className="agr-context" aria-label="Repository context">
    <h3>Repository context · Off for this run</h3>
    <p>No repository context packet was prepared. The setting was captured at notebook revision {context.notebookRevision}.</p>
  </section>;
  const references = new Set([
    ...context.selectedEntries.flatMap(entry => Object.values(entry.sourceEvidenceRefs)), ...context.relatedItems.map(item => item.sourceId),
    ...context.sourceReads.flatMap(read => read.evidenceRef === null ? [] : [read.evidenceRef]), ...context.contributions.flatMap(item => item.evidenceRefs),
    ...context.deliveries.flatMap(item => item.discussionSupplement?.evidenceRefs ?? []),
    ...run.contextNoteReads.flatMap(read => [...(read.evidenceRef === null ? [] : [read.evidenceRef]), ...Object.values(read.sourceEvidenceRefs)]),
    ...run.contextNoteSearches.flatMap(search => search.hits.flatMap(hit => hit.read === null ? []
      : [...(hit.read.evidenceRef === null ? [] : [hit.read.evidenceRef]), ...Object.values(hit.read.sourceEvidenceRefs)])),
  ]);
  const laterReadReceipts = new Map(run.contextNoteSearches.flatMap(search => search.hits.flatMap(hit => hit.read === null ? []
    : [[hit.read.receiptRef, hit.read] as const])));
  const cited = [...references].filter(reference => citations.has(reference)).length;
  const capturedBytes = context.sourceReads.reduce((sum, read) => sum + read.utf8Bytes, 0);
  const included = context.selectedEntries.filter(entry => entry.inclusion === 'INCLUDED').length;
  const latestDelivery = context.deliveries.at(-1);
  const latestState = latestDelivery === undefined ? null : deliveryState(run, latestDelivery);
  return <section className="agr-context" aria-label="Repository context">
    <h3>Repository context · {latestState === null ? 'Prepared' : deliveryLabels[latestState.outcome]}</h3>
    <p>{(latestDelivery?.utf8Bytes ?? context.bootstrap?.utf8Bytes ?? 0).toLocaleString()} UTF-8 bytes
      {latestDelivery !== undefined && (latestDelivery.discussionSupplement === null ? ' in the latest prepared turn' : ' in the frozen base packet')}</p>
    {latestDelivery?.discussionSupplement != null && <p>{latestDelivery.discussionSupplement.packet.utf8Bytes.toLocaleString()} additional UTF-8 bytes of discussion context</p>}
    <p>{included} {included === 1 ? 'note' : 'notes'} selected for the initial packet
      {' · '}{context.relatedItems.length} related {context.relatedItems.length === 1 ? 'source' : 'sources'}</p>
    {latestState === null ? <p>Saved for this run. No provider submission has been recorded.</p>
      : <p>{deliveryExplanations[latestState.outcome]}</p>}
    <p>Delivery and source reads do not establish that context was understood or improved the review.</p>
    <dl className="agr-context__facts">
      <dt>Captured source text</dt><dd>{capturedBytes.toLocaleString()} UTF-8 bytes across {context.sourceReads.length} read receipts</dd>
      <dt>Initial selection note reads</dt><dd>{run.contextNoteReads.reduce((sum, read) => sum + new TextEncoder().encode(read.text ?? '').length, 0).toLocaleString()} UTF-8 bytes in saved exact reads</dd>
      <dt>Later exact read receipts</dt><dd>{[...laterReadReceipts.values()].filter(read => read.delivery === 'CONFIRMED').length} confirmed · {laterReadReceipts.size} saved</dd>
      <dt>Cited sources</dt><dd>{cited} of {references.size} saved context references cited by findings or validation conclusions</dd>
      <dt>Token usage</dt><dd>Not recorded for repository context</dd>
      <dt>Notebook revision</dt><dd>{context.notebookRevision}</dd>
      <dt>Repository ID</dt><dd>{context.repositoryId}</dd>
      <dt>Frozen baseline</dt><dd><code>{context.baselineSha ?? 'Unavailable'}</code></dd>
    </dl>
    <ReviewPreparationActivity run={run} />
    {context.bootstrap !== null && <details className="agr-context__item">
      <summary>Exact initial saved packet · {context.bootstrap.utf8Bytes.toLocaleString()} bytes</summary>
      <p className="agr-context__identity"><code>{context.bootstrap.digest}</code></p>
      <pre className="agr-evidence__excerpt">{context.bootstrap.text}</pre>
    </details>}
    <details className="agr-context__item">
      <summary>Turn delivery · {context.deliveries.length} {context.deliveries.length === 1 ? 'record' : 'records'}</summary>
      {context.deliveries.length === 0 && <p>No turn input has been bound to this packet yet.</p>}
      {context.deliveries.map((delivery, index) => <DeliveryReceipt key={delivery.turnId} delivery={delivery} run={run} index={index} citations={citations} />)}
    </details>
    {context.contributions.map((contribution, index) => <Contribution key={contribution.investigationStep} contribution={contribution} index={index} run={run} citations={citations} />)}
    <details className="agr-context__item">
      <summary>Selected notes · {context.selectedEntries.length}</summary>
      {context.selectedEntries.length === 0 && <p>No notebook notes were selected for this run; check preparation outcomes to distinguish missing knowledge from a completed empty result.</p>}
      {context.selectedEntries.map(entry => <details className="agr-context__item" key={entry.entryId}>
        <summary>{entry.title} · version {entry.entryVersion} · {entry.inclusion === 'INCLUDED' ? 'included' : 'deferred'}</summary>
        <p><code>{entry.entryId}</code> · {{ MATCHED: 'Source identities matched', VERSION_MISMATCH: 'Version mismatch', UNVERIFIED: 'Applicability unverified' }[entry.applicability]}</p>
        <strong>Why selected</strong><ul>{entry.matchReasons.map(reason => <li key={reason}>{reason}</li>)}</ul>
        {entry.text !== null ? <pre className="agr-evidence__excerpt">{entry.text}</pre> : <p>This note was deferred; its text was not included in the packet.</p>}
        {run.contextNoteReads.filter(read => read.entryId === entry.entryId && read.entryVersion === entry.entryVersion).map(read => <div key={read.entryId}>
          {read.status === 'RECEIPT_UNAVAILABLE' ? <p>The saved exact-version read is unavailable. No current note has been substituted.</p> : <details className="agr-context__evidence">
            <summary>Saved exact read · version {read.entryVersion}</summary>
            <p>This saved tool read is separate from the initial packet. Its provider delivery and the note’s claims remain unverified here.</p>
            <Evidence run={run} reference={read.evidenceRef} citations={citations} />
            {Object.entries(read.sourceEvidenceRefs).map(([source, reference]) => <div key={source}>
              <p>Notebook source: <code>{source}</code></p><Evidence run={run} reference={reference} citations={citations} />
            </div>)}
          </details>}
        </div>)}
        {Object.entries(entry.sourceEvidenceRefs).map(([source, reference]) => <div key={source}>
          <p>Notebook source: <code>{source}</code></p><Evidence run={run} reference={reference} citations={citations} />
        </div>)}
      </details>)}
    </details>
    <details className="agr-context__item">
      <summary>Later notebook searches · {run.contextNoteSearches.length}</summary>
      {run.contextNoteSearches.length === 0 && <p>No later notebook search was saved for this run.</p>}
      {run.contextNoteSearches.map((search, index) => <NoteSearchReceipt key={search.searchRef} search={search} index={index} run={run} citations={citations} />)}
    </details>
    <details className="agr-context__item">
      <summary>Related history · {context.relatedItems.length}</summary>
      {context.relatedItems.length === 0 && <p>No related history was included.</p>}
      {context.relatedItems.map(item => <div className="agr-context__item" key={item.sourceId}>
        <ul>{item.matchReasons.map(reason => <li key={reason}>{reason}</li>)}</ul>
        <p>{item.presentInReviewedBase === null ? 'Presence in the reviewed base is unknown.' : item.presentInReviewedBase
          ? 'The associated history commit is present in the reviewed base.' : 'The associated history commit is absent from the reviewed base.'}</p>
        <Evidence run={run} reference={item.sourceId} citations={citations} />
      </div>)}
    </details>
    <details className="agr-context__item">
      <summary>Captured source reads · {context.sourceReads.length}</summary>
      <p>These records identify successful tool reads. They do not establish that a source was understood or its claims verified.</p>
      {context.sourceReads.length === 0 && <p>No source reads have been recorded.</p>}
      {context.sourceReads.map(read => <SourceReceipt key={read.id} read={read} run={run} citations={citations} />)}
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
