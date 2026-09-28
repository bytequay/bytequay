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
import { useEffect, useState } from 'react';
import AttentionList from '../home/AttentionList';
import { assignedIssues, attentionCandidates, loadHandled, saveHandled, type AttentionItem, type Handled } from '../home/attention';
import { zoomReference } from '../githubReference';
import type { PullRequestDto } from '../types';
import { ISSUE_DEVELOPMENT_CHANGED } from '../workspaces/issueDevelopment';
import { workspaceIssueTaskRoute } from '../workspaces/model';

export default function TeamsAttention({ accountLogin, circleId, memberId }: { accountLogin: string; circleId?: string; memberId?: number }) {
  const [candidates, setCandidates] = useState<AttentionItem[]>([]);
  const [handled, setHandled] = useState<Handled>(loadHandled);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let generation = 0;
    let active = true;
    setCandidates([]);
    const reload = async () => {
      const request = ++generation;
      try {
        const [prs, workspaces] = await Promise.all([window.bridge.fetchAttentionPrs(), window.bridge.listWorkspaces()]);
        const results = await Promise.allSettled(workspaces.map(async workspace =>
          assignedIssues(await window.bridge.fetchWorkspaceIssueAttention(workspace.id), workspace.id, accountLogin)));
        if (!active || request !== generation) return;
        setCandidates(attentionCandidates(prs, results.flatMap(result => result.status === 'fulfilled' ? result.value : []), accountLogin));
        setError(results.some(result => result.status === 'rejected') ? 'Some assigned issues could not be loaded.' : null);
      }
      catch (failure) { if (active && request === generation) setError(failure instanceof Error ? failure.message : String(failure)); }
    };
    const changed = () => { void reload(); };
    const syncPrs = (event: Event) => {
      const prs = (event as CustomEvent<PullRequestDto[]>).detail;
      setCandidates(current => attentionCandidates(prs, current.filter(item => item.type === 'issue'), accountLogin));
    };
    const handling = () => setHandled(loadHandled());
    changed();
    const stop = window.bridge.onPullRequestsSynced(changed);
    window.addEventListener('bytequay:pr-attention-updated', syncPrs);
    window.addEventListener('bytequay:attention-handled-changed', handling);
    window.addEventListener(ISSUE_DEVELOPMENT_CHANGED, changed);
    return () => {
      active = false; generation++; stop();
      window.removeEventListener('bytequay:pr-attention-updated', syncPrs);
      window.removeEventListener('bytequay:attention-handled-changed', handling);
      window.removeEventListener(ISSUE_DEVELOPMENT_CHANGED, changed);
    };
  }, [accountLogin]);
  return <>
    <p className="teams-muted">Pending reviews and assigned work from your circles. Other updates stay in Activity.</p>
    {error && <div className="teams-error" role="alert">{error}</div>}
    <AttentionList candidates={candidates} handled={handled} viewerLogin={accountLogin} teamScope={{ circleId, memberId }} cardLimit={Number.POSITIVE_INFINITY}
      onHandledChange={next => { setHandled(next); saveHandled(next); }}
      onOpen={item => {
        if (item.type === 'issue') {
          if (item.workspaceId) zoomReference({ kind: 'issue', repo: item.repo, number: item.number, workspaceId: item.workspaceId });
        }
        else zoomReference({ kind: 'pull', repo: item.repo, number: item.number });
      }}
      onStartIssue={item => { if (item.workspaceId) window.location.hash = workspaceIssueTaskRoute(item.workspaceId, item.number); }} />
  </>;
}
