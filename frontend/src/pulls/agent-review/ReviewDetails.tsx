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
import type { ReviewRunDto } from './contracts';
import { shortSha } from './model';

export default function ReviewDetails({ run, repository }: { run: ReviewRunDto; repository: string }) {
  const investigation = run.investigation;
  const validations = investigation?.validations ?? [];
  const retained = validations.filter(validation => validation.findingId !== null).length;
  const evidence = run.evidence;
  return <details className="agr-review-details">
    <summary>Review details <span className="agr-learning-cards__scope">
      · {investigation === null ? 'scope' : `${investigation.reviewedFiles.length} changed ${investigation.reviewedFiles.length === 1 ? 'file' : 'files'} assessed`}
    </span></summary>
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
        {evidence.map(item => <details className="agr-evidence__item" key={item.ref}>
          <summary className="agr-evidence__head"><span className="agr-card__refs">{item.ref}</span>
            <span className="agr-evidence__source" title={item.source}>{item.source}</span></summary>
          <div className="agr-evidence__head">{item.provenance}</div>
          <pre className="agr-evidence__excerpt">{item.excerpt}</pre>
          {item.explanation && <div className="agr-evidence__why">{item.explanation}</div>}
        </details>)}
      </details>
      {investigation !== null && <details>
          <summary>Validation · {validations.length} of {investigation.candidates.length} {investigation.candidates.length === 1 ? 'candidate' : 'candidates'} checked</summary>
          {validations.length > 0 && <p>{retained} retained · {validations.length - retained} rejected</p>}
          {validations.length === 0 && <p>{run.coverage !== null && investigation.candidates.length === 0
            ? 'No candidate defects required validation.' : 'Validation has not been recorded.'}</p>}
          <ol className="agr-review-details__conclusions">{validations.map(validation => <li key={validation.candidate} value={validation.candidate}>
            <strong>{validation.findingId === null ? 'Rejected' : 'Retained'}</strong>
            <MarkdownBody body={validation.conclusion} repository={repository} />
          </li>)}</ol>
      </details>}
      <p>{run.coverage?.complete === true && run.coverage.omissions.length === 0
        ? 'Complete coverage was recorded for the review scope.' : 'Complete coverage has not been recorded.'}</p>
      {(run.coverage?.omissions.length ?? 0) > 0 && <><h3>Not reviewed</h3>
        <ul>{run.coverage?.omissions.map(omission => <li key={omission}>{omission}</li>)}</ul></>}
    </div>
  </details>;
}
