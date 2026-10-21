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
import CommitsTab from '../commits/CommitsTab';
import MemoryPage from '../memory/MemoryPage';
import RepoAvatar from '../RepoAvatar';
import { zoomReference } from '../githubReference';
import { settingsRoute } from '../settings/routes';
import { unavailableReason, useRelationships } from './RelatedWorkspaces';

export function RelatedWorkspaceView({ workspaceId, relatedWorkspaceId, tab }: {
  workspaceId: string; relatedWorkspaceId: string; tab: 'commits' | 'context';
}) {
  const { data, error } = useRelationships(workspaceId);
  const link = data?.related.find(item => item.workspaceId === relatedWorkspaceId);
  const workspace = data?.workspaces.find(item => item.id === relatedWorkspaceId);
  const unavailable = error ?? (data === null ? null : link === undefined ? 'Related workspace unavailable' : unavailableReason(link, workspace));
  if (unavailable !== null) return <div className="ws-tab-page" role="status">{unavailable}</div>;
  if (workspace === undefined) return <div className="ws-tab-page" role="status">Loading related workspace…</div>;
  return <div className="related-view">
    <div className="related-view-heading">
      <RepoAvatar owner={workspace.monitoredOwner} repo={workspace.monitoredRepo} size={18} />
      <strong>{workspace.name}</strong><span>Related repository</span>
    </div>
    {tab === 'context' ? <MemoryPage key={workspace.id} workspace={workspace} /> : <CommitsTab key={workspace.id} workspace={workspace}
      onSourceSelectionChange={() => {}}
      onOpenPullRequest={(number, repo) => zoomReference({ kind: 'pull', repo: repo ?? `${workspace.monitoredOwner}/${workspace.monitoredRepo}`, number, workspaceId: workspace.id })}
      onOpenSettings={() => { window.location.hash = settingsRoute('ws-agents', workspace.id); }} />}
  </div>;
}
