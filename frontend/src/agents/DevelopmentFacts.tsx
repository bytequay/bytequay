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

import React from 'react';
import type { AgentDevelopmentFactsDto, AgentTurnDto } from '../types';

export function developmentTurnLabel(turn: AgentTurnDto | null | undefined): string | null {
  if (turn?.developmentOrigin?.kind === 'AUTO_COMMIT') return 'Auto commit';
  if (turn?.developmentOrigin?.kind === 'CREATE_DRAFT_PR') return 'Create draft PR';
  return null;
}

function boundedError(error: string): string {
  const compact = error.replace(/\s+/g, ' ').trim();
  return compact.length > 240 ? `${compact.slice(0, 237)}…` : compact;
}

/** A dated observation remains useful during a turn without claiming that its files stopped changing. */
export function DevelopmentFacts({ facts, loading, error, running }: {
  facts: AgentDevelopmentFactsDto | null;
  loading: boolean;
  error: string | null;
  running: boolean;
}): React.JSX.Element {
  const local = facts?.local ?? null;
  const remote = facts?.remote ?? null;
  const pr = remote?.pullRequest ?? null;
  const errors = [error, facts?.localError, facts?.remoteError].filter((value): value is string => value != null && value !== '');
  return (
    <div className="agt-development-facts" aria-label="Observed development results">
      {facts === null
        ? <div>{loading ? 'Reading development results…' : running ? 'Results will be checked when the agent stops.' : 'Development results unavailable.'}</div>
        : <>
          <div className="agt-development-facts__observed">
            <time dateTime={facts.observedAt} title={new Date(facts.observedAt).toLocaleString()}>Last checked {new Date(facts.observedAt).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}</time>
            {running ? ' · Agent running; showing previous observations' : loading ? ' · Refreshing…' : ''}
          </div>
          <div className="agt-development-facts__values">
            {local === null ? <span>Local result unknown</span> : <>
              <span title={`${local.stagedFiles} staged · ${local.unstagedFiles} unstaged · ${local.untrackedFiles} untracked`}>
                {local.uncommittedFileCount === 0 ? 'No uncommitted files' : `${local.uncommittedFileCount} uncommitted ${local.uncommittedFileCount === 1 ? 'file' : 'files'}`}
              </span>
              <span>{local.commitCount} local {local.commitCount === 1 ? 'commit' : 'commits'}</span>
              <span title={`Branch ${local.branch} · HEAD ${local.headSha}`}>HEAD <code>{local.headSha.slice(0, 8)}</code></span>
            </>}
            {remote === null ? <span>Remote result unknown</span> : <>
              <span title={remote.headSha ?? undefined}>{remote.headSha === null ? 'No remote branch' : <>Pushed <code>{remote.headSha.slice(0, 8)}</code></>}</span>
              {local !== null && remote.headSha !== null && remote.headSha !== local.headSha && <span>Local HEAD differs from remote</span>}
              {pr === null ? <span>No PR</span> : <a href={pr.url} title={`PR head ${pr.headSha}`}>
                {pr.state.toLowerCase() === 'open' ? pr.draft ? 'Draft PR' : 'PR' : `${pr.state.toLowerCase()} PR`} #{pr.number}
              </a>}
            </>}
          </div>
        </>}
      {errors.map((message, index) => <div className="agt-development-facts__error" key={index}>{boundedError(message)}</div>)}
    </div>
  );
}
