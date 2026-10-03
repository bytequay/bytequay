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
import MarkdownBody from '../MarkdownBody';
import { evidenceTarget, validationTarget, ReviewEvidenceLink } from './ReviewEvidenceLinks';
import type { ReviewCandidateValidationDto, ReviewInvestigationDto, ReviewRunDto } from './contracts';
import { shortSha } from './model';

const OUTCOMES = { SUPPORTED: 'Supported', REJECTED: 'Rejected', NEEDS_EVIDENCE: 'Needs evidence', UNRESOLVED: 'Unresolved' } as const;

function ValidationConclusions({ validations, repository, runId, step }: { validations: ReviewCandidateValidationDto[]; repository: string; runId: string; step: number }) {
  const counts = Object.entries(OUTCOMES).flatMap(([outcome, label]) => {
    const count = validations.filter(validation => validation.outcome === outcome).length;
    return count > 0 ? [`${count} ${label.toLowerCase()}`] : [];
  });
  return <>
    {counts.length > 0 && <p>{counts.join(' · ')}</p>}
    <ol className="agr-review-details__conclusions">{validations.map(validation => <li key={validation.candidate} value={validation.candidate} id={validationTarget(runId, step, validation.candidate)} tabIndex={-1}>
      <strong>{OUTCOMES[validation.outcome]}</strong>
      <MarkdownBody body={validation.conclusion} repository={repository} />
      {validation.evidenceRefs.length > 0 && <p>Evidence: {validation.evidenceRefs.map(reference => <ReviewEvidenceLink key={reference} target={evidenceTarget(runId, reference)}>{reference}</ReviewEvidenceLink>)}</p>}
      {validation.counterevidence && <><strong>Counterevidence considered</strong>
        <MarkdownBody body={validation.counterevidence} repository={repository} /></>}
      {validation.request !== null && <>
        <strong>Missing evidence</strong><MarkdownBody body={validation.request.question} repository={repository} />
        <strong>Requested check</strong><MarkdownBody body={validation.request.check} repository={repository} />
        <strong>What would settle it</strong><MarkdownBody body={validation.request.resolvesWhen} repository={repository} />
      </>}
    </li>)}</ol>
  </>;
}

function EvidenceAnswers({ answers, repository }: { answers: ReviewInvestigationDto['answers']; repository: string }) {
  return <ol className="agr-review-details__conclusions">{answers.map(answer => <li key={answer.candidate} value={answer.candidate}>
    <MarkdownBody body={answer.attempted} repository={repository} />
    {answer.evidenceRefs.length > 0 && <p>Evidence: <code>{answer.evidenceRefs.join(', ')}</code></p>}
    {answer.limitation !== null && <><strong>Limitations</strong><MarkdownBody body={answer.limitation} repository={repository} /></>}
  </li>)}</ol>;
}

export default function ReviewDetails({ run, repository, compact = false }: { run: ReviewRunDto; repository: string; compact?: boolean }) {
  const investigation = run.investigation;
  const validations = investigation?.validations ?? [];
  const evidence = run.evidence;
  return <details className={`agr-review-details${compact ? ' is-compact' : ''}`}>
    <summary>{compact ? <>
      <span>{run.commits.length} {run.commits.length === 1 ? 'commit' : 'commits'}</span>
      <span>base <code>{run.baseRef}@{shortSha(run.baseSha)}</code></span>
      <span>head <code>{shortSha(run.headSha)}</code></span>
      <span className="agr-link" title={investigation === null ? 'Review scope and evidence'
        : `${investigation.reviewedFiles.length} changed ${investigation.reviewedFiles.length === 1 ? 'file' : 'files'} assessed`}>Review details</span>
    </> : <>Review details <span className="agr-learning-cards__scope">
      · {investigation === null ? 'scope' : `${investigation.reviewedFiles.length} changed ${investigation.reviewedFiles.length === 1 ? 'file' : 'files'} assessed`}
    </span></>}</summary>
    <div className="agr-review-details__body">
      <p className="agr-learning-cards__scope">
        {run.scope === 'FULL' ? 'Full review' : 'Incremental review'} · {run.priorHeadSha === null ? run.baseRef : shortSha(run.priorHeadSha)} → {shortSha(run.headSha)}
      </p>
      {investigation !== null && <>
        <h3>Change understanding</h3>
        <MarkdownBody body={investigation.changeMap} repository={repository} />
        <details>
          <summary>Changed files assessed · {investigation.reviewedFiles.length}</summary>
          <ul className="agr-review-details__files">{investigation.reviewedFiles.map(path => <li key={path}><code>{path}</code></li>)}</ul>
        </details>
      </>}
      <details>
        <summary>Context &amp; validation evidence · {evidence.length} {evidence.length === 1 ? 'reference' : 'references'}</summary>
        {evidence.length === 0 && <p>No context or validation evidence was recorded.</p>}
        {evidence.map(item => <details className="agr-evidence__item" key={item.ref} id={evidenceTarget(run.id, item.ref)} tabIndex={-1}>
          <summary className="agr-evidence__head"><span className="agr-card__refs">{item.ref}</span>
            <span className="agr-evidence__source" title={item.source}>{item.source}</span></summary>
          <div className="agr-evidence__head">{item.provenance}</div>
          <pre className="agr-evidence__excerpt">{item.excerpt}</pre>
          {item.explanation && <div className="agr-evidence__why">{item.explanation}</div>}
        </details>)}
      </details>
      {run.steps.length > 0 && <details>
        <summary>Review passes · {run.steps.length}</summary>
        {run.steps.map(step => <details key={step.number}>
          <summary>{step.number}. {{ INVESTIGATE: 'Investigation', COLLECT_EVIDENCE: 'Evidence collection', VALIDATE: 'Validation' }[step.kind]}
            {step.repair ? ' · report correction' : ''}{step.investigation === null && step.validation === null ? ' · no report recorded' : ''}</summary>
          {step.investigation !== null && <>
            <p>{step.investigation.reviewedFiles.length} changed {step.investigation.reviewedFiles.length === 1 ? 'file' : 'files'} assessed
              {' · '}{step.investigation.candidates.length} candidate {step.investigation.candidates.length === 1 ? 'claim' : 'claims'}</p>
            <EvidenceAnswers answers={step.investigation.answers} repository={repository} />
          </>}
          {step.validation !== null && <>
            <p>Checked the evidence from pass {step.validation.investigationStep}.</p>
            {step.validation.newCandidates.length > 0 && <>
              <strong>New hypotheses to investigate</strong>
              <ul>{step.validation.newCandidates.map(candidate => <li key={candidate}><MarkdownBody body={candidate} repository={repository} /></li>)}</ul>
            </>}
            {step.validation.candidates.length === 0 && <p>No candidate defects required validation.</p>}
            <ValidationConclusions validations={step.validation.candidates} repository={repository} runId={run.id} step={step.number} />
          </>}
        </details>)}
      </details>}
      {run.steps.length === 0 && investigation !== null && <details>
          <summary>Validation · {validations.length} of {investigation.candidates.length} {investigation.candidates.length === 1 ? 'candidate' : 'candidates'} checked</summary>
          {validations.length === 0 && <p>{run.coverage !== null && investigation.candidates.length === 0
            ? 'No candidate defects required validation.' : 'Validation has not been recorded.'}</p>}
          <ValidationConclusions validations={validations} repository={repository} runId={run.id} step={0} />
      </details>}
      <p>{run.coverage?.complete === true && run.coverage.omissions.length === 0
        ? 'Complete coverage was recorded for the review scope.' : 'Complete coverage has not been recorded.'}</p>
      {(run.coverage?.omissions.length ?? 0) > 0 && <><h3>Not reviewed</h3>
        <ul>{run.coverage?.omissions.map(omission => <li key={omission}>{omission}</li>)}</ul></>}
    </div>
  </details>;
}
