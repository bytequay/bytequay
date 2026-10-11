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
import type { ReactNode } from 'react';
import { evidenceTarget, validationTarget, ReviewEvidenceLink } from './ReviewEvidenceLinks';
import type { ReviewCandidateDto, ReviewCandidateValidationDto, ReviewInvestigationDto, ReviewRunDto, ReviewThreadVerdictDto } from './contracts';
import { completeCoverage, isExplanationRun, reviewScope, shortSha } from './model';

const OUTCOMES = { SUPPORTED: 'Supported', REJECTED: 'Rejected', NEEDS_EVIDENCE: 'Needs evidence', UNRESOLVED: 'Unresolved' } as const;
const PERSPECTIVES = { BEHAVIOR: 'Behavior and contracts', INTERACTIONS: 'Interactions and operating constraints', DESIGN: 'Design and verification' } as const;
// In the order a reader acts on them: what is still open comes first.
const THREAD_VERDICTS = { NOT_ADDRESSED: 'Not addressed', PARTLY_ADDRESSED: 'Partly addressed', AWAITING_REPLY: 'Awaiting your reply', ADDRESSED: 'Addressed' } as const;

function ThreadVerdicts({ verdicts, repository, runId }: { verdicts: ReviewThreadVerdictDto[]; repository: string; runId: string }) {
  const groups = Object.entries(THREAD_VERDICTS).map(([kind, label]) => ({ label, items: verdicts.filter(item => item.verdict === kind) })).filter(group => group.items.length > 0);
  return <details open>
    <summary>Your comments · {groups.map(group => `${group.items.length} ${group.label.toLowerCase()}`).join(' · ')}</summary>
    <ul className="agr-review-details__conclusions">{groups.flatMap(group => group.items.map(item => <li key={item.threadId}>
      <strong>{group.label}</strong>
      <p><code title={item.path}>{item.path.slice(item.path.lastIndexOf('/') + 1)}{item.line !== null ? `:${item.line}` : ''}</code>
        {item.resolved ? ' · marked resolved' : ''} · “{item.opening}”</p>
      <MarkdownBody body={item.explanation} repository={repository} />
      {item.evidenceRefs.length > 0 && <p>Evidence: {item.evidenceRefs.map(ref => <ReviewEvidenceLink key={ref} target={evidenceTarget(runId, ref)}>{ref}</ReviewEvidenceLink>)}</p>}
    </li>))}</ul>
  </details>;
}

function Candidates({ candidates, repository, runId }: { candidates: ReviewCandidateDto[]; repository: string; runId: string }) {
  return <ol className="agr-review-details__conclusions">{candidates.map(candidate => <li key={candidate.number} value={candidate.number}>
    <strong>{PERSPECTIVES[candidate.perspective]} · {candidate.responsibility}</strong>
    <MarkdownBody body={candidate.hypothesis} repository={repository} />
    <p>Recorded in pass {candidate.originStep}{candidate.attributedSource !== null ? ` · attributed source: ${candidate.attributedSource}` : ''}</p>
    {candidate.carriedFindingId !== null && <p>Reassesses an existing finding.</p>}
    {candidate.evidenceRefs.length > 0 && <p>Initial evidence: {candidate.evidenceRefs.map(ref => <ReviewEvidenceLink key={ref} target={evidenceTarget(runId, ref)}>{ref}</ReviewEvidenceLink>)}</p>}
    <strong>Next check</strong><MarkdownBody body={candidate.nextCheck} repository={repository} />
  </li>)}</ol>;
}

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

export default function ReviewDetails({ run, repository, compact = false, evidence: showEvidence = true, retainedFindings }: {
  run: ReviewRunDto; repository: string; compact?: boolean; retainedFindings?: ReactNode;
  /** False where the page lists evidence and coverage itself; details with nothing else to say are then omitted. */
  evidence?: boolean;
}) {
  if (isExplanationRun(run)) return <details className={`agr-review-details${compact ? ' is-compact' : ''}`}>
    <summary>Explanation details · {run.commits.length} {run.commits.length === 1 ? 'commit' : 'commits'} · head <code>{shortSha(run.headSha)}</code></summary>
    <div className="agr-review-details__body">
      <p>Change intention and commit summaries · {run.baseRef}@{shortSha(run.baseSha)} → {shortSha(run.headSha)}</p>
      <p>{run.state === 'ACCEPTED' ? 'Explanation refresh completed.' : run.state === 'RUNNING' ? 'Explanation refresh is in progress.' : 'Explanation refresh did not complete.'}</p>
      {run.outcome !== null && <MarkdownBody body={run.outcome} repository={repository} />}
    </div>
  </details>;
  const investigation = run.investigation;
  const validations = investigation?.validations ?? [];
  const evidence = run.evidence;
  const currentStep = run.steps.at(-1);
  const unsealed = currentStep !== undefined && currentStep.kind !== 'VALIDATE' && currentStep.investigation === null;
  const threadVerdicts = run.steps.findLast(step => (step.validation?.threadVerdicts?.length ?? 0) > 0)?.validation?.threadVerdicts ?? [];
  if (!showEvidence && investigation === null && threadVerdicts.length === 0 && retainedFindings === undefined
    && (run.coverage?.omissions.length ?? 0) === 0 && run.steps.every(step => step.investigation === null && step.validation === null)) return null;
  return <details className={`agr-review-details${compact ? ' is-compact' : ''}`}>
    <summary>{compact ? <>
      <span>{run.commits.length} {run.commits.length === 1 ? 'commit' : 'commits'}</span>
      <span>base <code>{run.baseRef}@{shortSha(run.baseSha)}</code></span>
      <span>head <code>{shortSha(run.headSha)}</code></span>
      <span className="agr-link" title={investigation === null ? 'Review scope and evidence'
        : `${investigation.reviewedFiles.length} changed ${investigation.reviewedFiles.length === 1 ? 'file' : 'files'} assessed`}>Review details</span>
    </> : <>Review details <span className="agr-learning-cards__scope">
      · {investigation === null ? 'scope' : `${investigation.reviewedFiles.length} changed ${investigation.reviewedFiles.length === 1 ? 'file' : 'files'} assessed`}
      {threadVerdicts.length > 0 ? ` · ${threadVerdicts.length} of your ${threadVerdicts.length === 1 ? 'comment' : 'comments'} followed up` : ''}
    </span></>}</summary>
    <div className="agr-review-details__body">
      <p className="agr-learning-cards__scope">{reviewScope(run)}</p>
      {threadVerdicts.length > 0 && <ThreadVerdicts verdicts={threadVerdicts} repository={repository} runId={run.id} />}
      {investigation !== null && <>
        <p><strong>Discovery · {unsealed ? 'Unsealed checkpoint' : 'Sealed investigation'}</strong></p>
        <h3>Change understanding</h3>
        <MarkdownBody body={investigation.changeMap} repository={repository} />
        <details>
          <summary>Review perspectives</summary>
          {Object.entries(PERSPECTIVES).map(([key, label]) => {
            const assessment = investigation.perspectives.find(item => item.perspective === key);
            return <section key={key}>
              <h4>{label} · {assessment === undefined ? 'Not recorded' : { CHECKED: 'Checked', NOT_APPLICABLE: 'Not applicable', BLOCKED: 'Blocked' }[assessment.status]}</h4>
              {assessment !== undefined && <>
                <MarkdownBody body={assessment.question} repository={repository} />
                <MarkdownBody body={assessment.explanation} repository={repository} />
                {assessment.responsibilities.length > 0 && <p>Responsibilities: {assessment.responsibilities.join(', ')}</p>}
                {assessment.paths.length > 0 && <ul className="agr-review-details__files">{assessment.paths.map(path => <li key={path}><code>{path}</code></li>)}</ul>}
                {assessment.evidenceRefs.length > 0 && <p>Evidence: {assessment.evidenceRefs.map(ref => <ReviewEvidenceLink key={ref} target={evidenceTarget(run.id, ref)}>{ref}</ReviewEvidenceLink>)}</p>}
              </>}
            </section>;
          })}
        </details>
        <details>
          <summary>Recorded candidates · {investigation.candidates.length}</summary>
          <Candidates candidates={investigation.candidates} repository={repository} runId={run.id} />
        </details>
        <details>
          <summary>Changed files assessed · {investigation.reviewedFiles.length}</summary>
          <ul className="agr-review-details__files">{investigation.reviewedFiles.map(path => <li key={path}><code>{path}</code></li>)}</ul>
        </details>
      </>}
      {showEvidence && <details>
        <summary>Context &amp; validation evidence · {evidence.length} {evidence.length === 1 ? 'reference' : 'references'}</summary>
        {evidence.length === 0 && <p>No context or validation evidence was recorded.</p>}
        {evidence.map(item => <details className="agr-evidence__item" key={item.ref} id={evidenceTarget(run.id, item.ref)} tabIndex={-1}>
          <summary className="agr-evidence__head"><span className="agr-card__refs">{item.ref}</span>
            <span className="agr-evidence__source" title={item.source}>{item.source}</span></summary>
          <div className="agr-evidence__head">{item.provenance}</div>
          <pre className="agr-evidence__excerpt">{item.excerpt}</pre>
          {item.explanation && <div className="agr-evidence__why">{item.explanation}</div>}
        </details>)}
      </details>}
      {run.steps.length > 0 && <details>
        <summary>Review passes · {run.steps.length}</summary>
        {run.steps.map(step => <details key={step.number}>
          <summary>{step.number}. {{ INVESTIGATE: 'Investigation', COLLECT_EVIDENCE: 'Evidence collection', VALIDATE: 'Validation', EXPLAIN: 'Explanation' }[step.kind]}
            {step.repair ? ' · report correction' : ''}{step.investigation === null && step.validation === null ? ' · no report recorded' : ''}</summary>
          {step.investigation !== null && <>
            <p>{step.investigation.reviewedFiles.length} changed {step.investigation.reviewedFiles.length === 1 ? 'file' : 'files'} assessed
              {' · '}{step.investigation.candidates.length} candidate {step.investigation.candidates.length === 1 ? 'claim' : 'claims'}</p>
            <EvidenceAnswers answers={step.investigation.answers} repository={repository} />
            <Candidates candidates={step.investigation.candidates} repository={repository} runId={run.id} />
          </>}
          {step.validation !== null && <>
            <p>Checked the evidence from pass {step.validation.investigationStep}.</p>
            {step.validation.newCandidates.length > 0 && <>
              <strong>New hypotheses to investigate</strong>
              <Candidates candidates={step.validation.newCandidates} repository={repository} runId={run.id} />
            </>}
            {step.validation.candidates.length === 0 && <p>No candidate defects required validation.</p>}
            <ValidationConclusions validations={step.validation.candidates} repository={repository} runId={run.id} step={step.number} />
            <p>{step.validation.selectedFindingIds.length} findings selected for the result.</p>
            {step.validation.selectionExplanation && <MarkdownBody body={step.validation.selectionExplanation} repository={repository} />}
          </>}
        </details>)}
      </details>}
      {run.steps.length === 0 && investigation !== null && <details>
          <summary>Validation · {validations.length} of {investigation.candidates.length} {investigation.candidates.length === 1 ? 'candidate' : 'candidates'} checked</summary>
          {validations.length === 0 && <p>{run.coverage !== null && investigation.candidates.length === 0
            ? 'No candidate defects required validation.' : 'Validation has not been recorded.'}</p>}
          <ValidationConclusions validations={validations} repository={repository} runId={run.id} step={0} />
      </details>}
      {showEvidence && <p>{completeCoverage(run) ? 'Complete coverage was recorded for the review scope.' : 'Complete coverage has not been recorded.'}</p>}
      {(run.coverage?.omissions.length ?? 0) > 0 && <><h3>Not reviewed</h3>
        <ul>{run.coverage?.omissions.map(omission => <li key={omission}>{omission}</li>)}</ul></>}
      {retainedFindings}
    </div>
  </details>;
}
