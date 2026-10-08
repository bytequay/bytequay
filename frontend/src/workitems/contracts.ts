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
export type WorkItemBridge = {
  listUnifiedWorkItems: (workspaceId: string) => Promise<UnifiedWorkItem[]>;
  publishWorkItem: (workspaceId: string, id: string, request: Publication) => Promise<WorkItemDto>;
  reconcileWorkItem: (workspaceId: string, id: string, destination: Publication, key: string) => Promise<WorkItemDto>;
  workItemJiraTypes: (workspaceId: string, connectionId: string, cloudId: string, project: string) => Promise<{ id: string; name: string }[]>;
  specWorkItem: (workspaceId: string, checkId: string, candidateId: string) => Promise<WorkItemDto>;
};
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
