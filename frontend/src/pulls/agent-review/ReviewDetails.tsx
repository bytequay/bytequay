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
import { evidenceTarget, ReviewEvidenceLink } from './ReviewEvidenceLinks';
import type { ReviewRunDto, ReviewThreadVerdictDto } from './contracts';
import { isExplanationRun, shortSha } from './model';

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

/** What a reader can act on beyond the selected findings; the agent's working report stays out, and the Evidence tab lists what it read. */
export default function ReviewDetails({ run, repository, retainedFindings }: { run: ReviewRunDto; repository: string; retainedFindings?: ReactNode }) {
  if (isExplanationRun(run)) return <details className="agr-review-details">
    <summary>Explanation details · {run.commits.length} {run.commits.length === 1 ? 'commit' : 'commits'} · head <code>{shortSha(run.headSha)}</code></summary>
    <div className="agr-review-details__body">
      <p>Change intention and commit summaries · {run.baseRef}@{shortSha(run.baseSha)} → {shortSha(run.headSha)}</p>
      <p>{run.state === 'ACCEPTED' ? 'Explanation refresh completed.' : run.state === 'RUNNING' ? 'Explanation refresh is in progress.' : 'Explanation refresh did not complete.'}</p>
      {run.outcome !== null && <MarkdownBody body={run.outcome} repository={repository} />}
    </div>
  </details>;
  const threadVerdicts = run.steps.findLast(step => (step.validation?.threadVerdicts?.length ?? 0) > 0)?.validation?.threadVerdicts ?? [];
  const omissions = run.coverage?.omissions ?? [];
  if (threadVerdicts.length === 0 && omissions.length === 0 && retainedFindings === undefined) return null;
  return <details className="agr-review-details">
    <summary>Review details <span className="agr-learning-cards__scope">
      {omissions.length > 0 ? ` · ${omissions.length} ${omissions.length === 1 ? 'item' : 'items'} not reviewed` : ''}
      {retainedFindings !== undefined ? ' · other recorded findings' : ''}
      {threadVerdicts.length > 0 ? ` · ${threadVerdicts.length} of your ${threadVerdicts.length === 1 ? 'comment' : 'comments'} followed up` : ''}
    </span></summary>
    <div className="agr-review-details__body">
      {threadVerdicts.length > 0 && <ThreadVerdicts verdicts={threadVerdicts} repository={repository} runId={run.id} />}
      {omissions.length > 0 && <><h3>Not reviewed</h3>
        <ul>{omissions.map(omission => <li key={omission}>{omission}</li>)}</ul></>}
      {retainedFindings}
    </div>
  </details>;
}
