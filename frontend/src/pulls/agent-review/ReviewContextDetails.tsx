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
import type { ContextDeliveryStateDto, ReviewRunDto } from './contracts';

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

function Contribution({ contribution, index, run }: { contribution: ContextContributionDto; index: number; run: ReviewRunDto }) {
  return <details className="agr-context__item">
    <summary>{index === 0 ? 'Completed context brief' : `Evidence addition ${index}`} · investigation step {contribution.investigationStep}</summary>
    <p>This is the agent’s saved explanation. Its claims and applicability remain unverified.</p>
    <pre className="agr-evidence__excerpt">{contribution.text}</pre>
    {contribution.limitations.length > 0 && <><strong>Limitations</strong><ul>{contribution.limitations.map(item => <li key={item}>{item}</li>)}</ul></>}
    {contribution.evidenceRefs.map(reference => <Evidence key={reference} run={run} reference={reference} />)}
    {contribution.evidenceRefs.length === 0 && <p>No evidence references were included in this explanation.</p>}
    {contribution.preparationRefs.length > 0 && <details className="agr-context__evidence">
      <summary>Shared preparation references · {contribution.preparationRefs.length}</summary>
      <ul>{contribution.preparationRefs.map(reference => <li key={reference}><code>{reference}</code></li>)}</ul>
    </details>}
  </details>;
}

function DeliveryReceipt({ delivery, run, index }: { delivery: ContextDeliveryDto; run: ReviewRunDto; index: number }) {
  const state = deliveryState(run, delivery);
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
      <dt>Context bytes</dt><dd>{delivery.utf8Bytes.toLocaleString()} UTF-8 bytes</dd>
      <dt>Context digest</dt><dd><code>{delivery.packetDigest}</code></dd>
      <dt>Complete input bytes</dt><dd>{delivery.inputUtf8Bytes.toLocaleString()} UTF-8 bytes</dd>
      <dt>Complete input digest</dt><dd><code>{delivery.inputDigest}</code></dd>
      {delivery.discussionMessageId !== null && <><dt>Original discussion</dt><dd><code>{delivery.discussionMessageId}</code></dd></>}
    </dl>
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
  const latestDelivery = context.deliveries.at(-1);
  const latestState = latestDelivery === undefined ? null : deliveryState(run, latestDelivery);
  return <section className="agr-context" aria-label="Repository context">
    <h3>Repository context · {latestState === null ? 'Prepared' : deliveryLabels[latestState.outcome]}</h3>
    <p>{(latestDelivery?.utf8Bytes ?? context.bootstrap?.utf8Bytes ?? 0).toLocaleString()} UTF-8 bytes
      {latestDelivery !== undefined && ' in the latest prepared turn'}</p>
    <p>{included} {included === 1 ? 'note' : 'notes'} selected for the initial packet
      {' · '}{context.relatedItems.length} related {context.relatedItems.length === 1 ? 'source' : 'sources'}</p>
    {latestState === null ? <p>Saved for this run. No provider submission has been recorded.</p>
      : <p>{deliveryExplanations[latestState.outcome]}</p>}
    <p>Delivery and source reads do not establish that context was understood or improved the review.</p>
    <dl className="agr-context__facts">
      <dt>Notebook revision</dt><dd>{context.notebookRevision}</dd>
      <dt>Repository ID</dt><dd>{context.repositoryId}</dd>
      <dt>Frozen baseline</dt><dd><code>{context.baselineSha ?? 'Unavailable'}</code></dd>
    </dl>
    {context.bootstrap !== null && <details className="agr-context__item">
      <summary>Exact initial saved packet · {context.bootstrap.utf8Bytes.toLocaleString()} bytes</summary>
      <p className="agr-context__identity"><code>{context.bootstrap.digest}</code></p>
      <pre className="agr-evidence__excerpt">{context.bootstrap.text}</pre>
    </details>}
    <details className="agr-context__item">
      <summary>Turn delivery · {context.deliveries.length} {context.deliveries.length === 1 ? 'record' : 'records'}</summary>
      {context.deliveries.length === 0 && <p>No turn input has been bound to this packet yet.</p>}
      {context.deliveries.map((delivery, index) => <DeliveryReceipt key={delivery.turnId} delivery={delivery} run={run} index={index} />)}
    </details>
    {context.contributions.map((contribution, index) => <Contribution key={contribution.investigationStep} contribution={contribution} index={index} run={run} />)}
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
