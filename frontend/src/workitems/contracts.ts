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

import type { GoalItemKind, WorkItemDto } from '../goals/contracts';
import { parseWorkItem } from '../goals/contracts';

export type WorkSource = 'LOCAL' | 'GITHUB' | 'JIRA';
export type UnifiedWorkItem = {
  id: string; source: WorkSource; title: string; description: string; status: string; closed: boolean; updatedAt: string;
  reference: { kind: GoalItemKind; key: string; label: string; title: string; addedAt: string } | null;
  work: WorkItemDto | null;
};
export type Publication = {
  source: 'GITHUB' | 'JIRA'; expectedRevision: number; connectionId?: string; cloudId?: string; project?: string; issueTypeId?: string;
};
export type WorkItemContextLink = {
  targetId: string; id: string; source: WorkSource; title: string; status: string; available: boolean;
  reference: { kind: 'ISSUE' | 'JIRA'; key: string; label: string } | null;
};
export type WorkItemBridge = {
  listUnifiedWorkItems: (workspaceId: string) => Promise<UnifiedWorkItem[]>;
  listWorkItemContextLinks: (workspaceId: string, itemId: string) => Promise<WorkItemContextLink[]>;
  linkWorkItemContext: (workspaceId: string, itemId: string, expectedRevision: number, targetId: string) => Promise<WorkItemDto>;
  unlinkWorkItemContext: (workspaceId: string, itemId: string, expectedRevision: number, targetId: string) => Promise<WorkItemDto>;
  publishWorkItem: (workspaceId: string, id: string, request: Publication) => Promise<WorkItemDto>;
  reconcileWorkItem: (workspaceId: string, id: string, destination: Publication, key: string) => Promise<WorkItemDto>;
  workItemJiraTypes: (workspaceId: string, connectionId: string, cloudId: string, project: string) => Promise<{ id: string; name: string }[]>;
  specWorkItem: (workspaceId: string, checkId: string, candidateId: string) => Promise<WorkItemDto>;
};
export function parseWorkItemContextLink(value: unknown): WorkItemContextLink {
  if (value === null || typeof value !== 'object') throw new Error('Malformed context link');
  const link = value as Record<string, unknown>;
  if (typeof link.targetId !== 'string' || typeof link.id !== 'string' || typeof link.title !== 'string' || typeof link.status !== 'string'
      || typeof link.available !== 'boolean' || !['LOCAL', 'GITHUB', 'JIRA'].includes(String(link.source))) throw new Error('Malformed context link');
  let reference: WorkItemContextLink['reference'] = null;
  if (link.reference != null) {
    if (typeof link.reference !== 'object') throw new Error('Malformed context link source');
    const ref = link.reference as Record<string, unknown>;
    if ((ref.kind !== 'ISSUE' && ref.kind !== 'JIRA') || typeof ref.key !== 'string' || typeof ref.label !== 'string') throw new Error('Malformed context link source');
    reference = { kind: ref.kind, key: ref.key, label: ref.label };
  }
  return { targetId: link.targetId, id: link.id, source: link.source as WorkSource, title: link.title, status: link.status, available: link.available, reference };
}
export function parseUnifiedWorkItem(value: unknown): UnifiedWorkItem {
  if (value === null || typeof value !== 'object') throw new Error('Malformed work item');
  const v = value as Record<string, unknown>;
  if (typeof v.id !== 'string' || typeof v.title !== 'string' || typeof v.description !== 'string' || typeof v.status !== 'string'
      || typeof v.closed !== 'boolean' || typeof v.updatedAt !== 'string' || !['LOCAL', 'GITHUB', 'JIRA'].includes(String(v.source))) throw new Error('Malformed work item');
  let reference: UnifiedWorkItem['reference'] = null;
  if (v.reference != null) {
    const r = v.reference as Record<string, unknown>;
    if (!['ISSUE', 'JIRA'].includes(String(r.kind)) || typeof r.key !== 'string' || typeof r.label !== 'string' || typeof r.title !== 'string' || typeof r.addedAt !== 'string') throw new Error('Malformed work item source');
    reference = { kind: r.kind as GoalItemKind, key: r.key, label: r.label, title: r.title, addedAt: r.addedAt };
  }
  return { id: v.id, source: v.source as WorkSource, title: v.title, description: v.description, status: v.status, closed: v.closed,
    updatedAt: v.updatedAt, reference, work: v.work == null ? null : parseWorkItem(v.work) };
}
export function workItemRoute(workspaceId: string, id?: string): string {
  return `#/workspaces/${encodeURIComponent(workspaceId)}/issues?source=local${id ? `&work=${encodeURIComponent(id)}` : ''}`;
}
