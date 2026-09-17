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
import { formatDuration } from '../../agents/transcript';
import type { ContextPreparationActivityDto, ReviewRunDto } from './contracts';

const labels: Record<ContextPreparationActivityDto['status'], string> = {
  OWNED: 'Preparation started', REUSED: 'Reused saved preparation', JOINED: 'Waiting for shared preparation',
  DEFERRED: 'Preparation deferred', APPLIED: 'Preparation result saved', UNAVAILABLE: 'Preparation receipt unavailable',
};
const outcomes = { READY: 'Ready', EMPTY: 'Checked — no useful knowledge found', FAILED: 'Preparation failed' } as const;

export default function ReviewPreparationActivity({ run }: { run: ReviewRunDto }) {
  return <details className="agr-context__item">
    <summary>Context preparation · run {run.ordinal} · {run.contextPreparationActivities.length} saved {run.contextPreparationActivities.length === 1 ? 'event' : 'events'}</summary>
    <p>Saved tool outcomes survive reconnects. Started and waiting entries describe what happened then, not current running state.</p>
    {run.contextPreparationActivities.length === 0 && <p>No preparation outcome was recorded. This does not mean the repository has no useful knowledge.</p>}
    {run.contextPreparationActivities.map(activity => <details className="agr-context__item" key={activity.operationRef}>
      <summary>{labels[activity.status]}{activity.elapsedMillis !== null && ` · ${activity.elapsedMillis < 1000 ? `${activity.elapsedMillis} ms` : formatDuration(activity.elapsedMillis)}`}</summary>
      <dl className="agr-context__facts">
        <dt>Recorded</dt><dd>{activity.recordedAt ?? 'Not recorded'}</dd>
        <dt>Tool call duration</dt><dd>{activity.elapsedMillis === null ? 'Not recorded' : `${activity.elapsedMillis.toLocaleString()} ms`}</dd>
        {activity.attemptId !== null && <><dt>Preparation</dt><dd><code>{activity.attemptId}</code></dd></>}
        <dt>Receipt</dt><dd><code>{activity.operationRef}</code></dd>
      </dl>
      {activity.scopes.map((scope, index) => <div key={`${scope.scopeId}:${index}`}>
        <strong>{scope.scopeId}{scope.outcome === null ? '' : ` · ${outcomes[scope.outcome]}`}</strong>
        {scope.explanation && <p>{scope.explanation}</p>}
        {scope.noteIds.length > 0 && <p>Saved notes: {scope.noteIds.join(', ')}</p>}
        {scope.checkedSourceIds.length > 0 && <p>Checked sources: {scope.checkedSourceIds.join(', ')}</p>}
      </div>)}
      {activity.gaps.length > 0 && <ul>{activity.gaps.map(gap => <li key={gap}>{gap}</li>)}</ul>}
    </details>)}
    <p>Durations cover recorded preparation tool calls, not the agent’s entire investigation or time spent waiting between calls.</p>
  </details>;
}
