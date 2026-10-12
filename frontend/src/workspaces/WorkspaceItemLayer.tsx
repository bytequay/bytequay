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
import AgentTaskScreen from '../agents/AgentTaskScreen';
import { zoomReference, type ZoomedReference } from '../githubReference';
import { settingsRoute } from '../settings/routes';
import type { WorkspaceDto } from '../types';
import { LocalWorkItems } from '../workitems/WorkItemsScreen';
import { useZoom, ZoomActions, ZoomScrim } from '../Zoom';

type Target = Extract<ZoomedReference, { kind: 'work' | 'task' }>;

/** Workspace-owned details share the reference stack, leaving the Epic and any work item underneath intact. */
export default function WorkspaceItemLayer({ target, onClose }: { target: Target; onClose: () => void }) {
  const zoom = useZoom(onClose);
  const [workspace, setWorkspace] = useState<WorkspaceDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let active = true;
    void window.bridge.getWorkspace(target.workspaceId).then(value => { if (active) setWorkspace(value); })
      .catch(cause => { if (active) setError(cause instanceof Error ? cause.message : String(cause)); });
    return () => { active = false; };
  }, [target.workspaceId]);
  const actions = <ZoomActions {...zoom} label={target.kind === 'work' ? 'work item' : 'task'} className={target.kind === 'task' ? 'agt-headbtn' : 'pull-detail__icon-action'} />;
  return <>
    {!zoom.full && <ZoomScrim onClose={zoom.close} />}
    <section className={zoom.cardClass} role="dialog" aria-modal="true" aria-label={target.kind === 'work' ? 'Local work item' : 'Task'}>
      {workspace === null ? <>
        <header className="pull-detail__header"><div className="pull-detail__toolbar"><div className="pull-detail__header-actions">{actions}</div></div></header>
        <div className="pull-detail__body" role={error === null ? 'status' : 'alert'}>{error ?? 'Loading…'}</div>
      </> : target.kind === 'work'
        ? <LocalWorkItems workspace={workspace} zoomedItemId={target.itemId} headerAction={actions} />
        : <AgentTaskScreen workspaceId={workspace.id} taskId={target.taskId} workspace={workspace} onBack={zoom.close} headerAction={actions}
          onOpenPullRequest={number => zoomReference({ kind: 'pull', workspaceId: workspace.id, repo: `${workspace.monitoredOwner}/${workspace.monitoredRepo}`, number })}
          onOpenSettings={() => { window.location.hash = settingsRoute('ws-agents', workspace.id); }} />}
    </section>
  </>;
}
