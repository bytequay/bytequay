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

import { Flag, ListTodo } from 'lucide-react';
import { useEffect, useState, type ReactNode } from 'react';
import { zoomReference, type ZoomedReference } from '../githubReference';
import { jiraRoute } from '../jira/contracts';
import { workspaceIssueRoute } from '../workspaces/model';
import { workItemRoute, type UnifiedWorkItem } from '../workitems/contracts';

/** Resolve the saved relationship, so ordinary messages never become cards because of their wording. */
export function WorkItemPrompt({ workspaceId, itemId, taskId, attachments, children }: {
  workspaceId?: string | null;
  itemId?: string;
  taskId?: string;
  attachments?: ReactNode;
  children: ReactNode;
}) {
  const [source, setSource] = useState<{ workspaceId: string; itemId?: string; taskId?: string; item: UnifiedWorkItem; epic: UnifiedWorkItem | null } | null>(null);
  useEffect(() => {
    if (!workspaceId || (!itemId && !taskId)) return;
    let active = true;
    void Promise.resolve().then(() => window.bridge.listUnifiedWorkItems(workspaceId)).then(items => {
      const found = findWorkItem(items, itemId !== undefined ? { itemId } : { taskId: taskId ?? '' });
      if (active) setSource(found === null ? null : { workspaceId, itemId, taskId, ...found });
    }, () => { if (active) setSource(null); });
    return () => { active = false; };
  }, [workspaceId, itemId, taskId]);

  if (source === null || source.workspaceId !== workspaceId || source.itemId !== itemId || source.taskId !== taskId) return children;
  return <WorkItemRequestCard workspaceId={source.workspaceId} item={source.item} epic={source.epic} attachments={attachments} />;
}

/** The work item by its id or by a task it started, and the Epic it belongs to. */
export function findWorkItem(items: UnifiedWorkItem[], by: { itemId: string } | { taskId: string }): { item: UnifiedWorkItem; epic: UnifiedWorkItem | null } | null {
  const item = items.find(candidate => candidate.work !== null && ('itemId' in by ? candidate.id === by.itemId
    : candidate.work.links.some(link => link.kind === 'TASK' && link.key === by.taskId)));
  if (item === undefined) return null;
  const epicId = item.work?.epicId;
  return { item, epic: items.find(candidate => candidate.id === (epicId?.startsWith('LOCAL:') ? epicId.slice(6) : epicId)) ?? null };
}

export function epicLink(workspaceId: string, epic: UnifiedWorkItem): { href: string; target: ZoomedReference } | null {
  if (epic.work?.isEpic) return { href: workItemRoute(workspaceId, epic.id), target: { kind: 'work', workspaceId, itemId: epic.id } };
  const reference = epic.reference;
  if (reference?.kind === 'ISSUE') {
    const split = reference.key.lastIndexOf('#');
    const repo = reference.key.slice(0, split);
    const number = Number(reference.key.slice(split + 1));
    if (split < 1 || !Number.isSafeInteger(number) || number < 1) return null;
    return { href: workspaceIssueRoute(workspaceId, number), target: { kind: 'issue', workspaceId, repo, number } };
  }
  if (reference?.kind === 'JIRA') {
    const [connectionId, cloudId, issueId] = reference.key.split(':');
    if (!connectionId || !cloudId || !issueId) return null;
    const location = { connectionId, cloudId, issueId };
    return { href: jiraRoute(location, workspaceId), target: { kind: 'jira', ...location } };
  }
  return epic.work === null ? null : { href: workItemRoute(workspaceId, epic.id), target: { kind: 'work', workspaceId, itemId: epic.id } };
}

export function WorkItemRequestCard({ workspaceId, item, epic, attachments }: {
  workspaceId: string;
  item: UnifiedWorkItem;
  epic: UnifiedWorkItem | null;
  attachments?: ReactNode;
}) {
  const parent = epic === null ? null : epicLink(workspaceId, epic);
  return <article className="agt-user__request agt-work-request" aria-label="Work item request">
    <div className="agt-work-request__label"><ListTodo size={14} aria-hidden="true" />Local work item</div>
    <a className="agt-work-request__title" href={workItemRoute(workspaceId, item.id)} onClick={event => {
      event.preventDefault(); zoomReference({ kind: 'work', workspaceId, itemId: item.id });
    }}>{item.work?.title ?? item.title}</a>
    {epic !== null && <div className="agt-work-request__epic"><Flag size={13} aria-hidden="true" /><span>Epic</span>
      {parent === null ? <span>{epic.title}</span> : <a href={parent.href} onClick={event => {
        event.preventDefault(); zoomReference(parent.target);
      }}>{[epic.reference?.label, epic.title].filter(Boolean).join(' · ')}</a>}
    </div>}
    {attachments}
  </article>;
}
