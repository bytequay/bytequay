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

import type { EpicJoinDto, GoalItemDto, WorkItemDto } from '../goals/contracts';
import { GOALS_CHANGED } from '../goals/useGoals';
import type { AttentionItem } from '../home/attention';
import type { JiraIssue } from '../jira/contracts';
import type { Bridge } from '../types';

export type EpicBridge = Pick<Bridge, 'joinEpic' | 'orderEpicWork' | 'removeFromEpic'>;

/** The identity work items name a GitHub issue Epic by: the canonical one the backend gives it, from its stable issue id. */
export function issueEpicId(issueId: number | string): string {
  return `ISSUE:github.com:${issueId}`;
}

/** The same for a Jira ticket; the cloud id's length keeps a cloud id with colons unambiguous. */
export function ticketEpicId(ticket: Pick<JiraIssue, 'cloudId' | 'id'>): string {
  return `JIRA:${ticket.cloudId.length}:${ticket.cloudId}:${ticket.id}`;
}

export function epicIdentity(item: AttentionItem): string {
  return item.jira ? ticketEpicId(item.jira) : issueEpicId(item.sourceKey?.split(':').pop() ?? '');
}

/** An Epic's work in the order the user gave it; equal places keep creation order. */
export function epicWork(items: WorkItemDto[], epicId: string): WorkItemDto[] {
  return items.filter((item) => item.epicId === epicId)
    .sort((left, right) => (left.epicRank ?? 0) - (right.epicRank ?? 0) || Date.parse(left.createdAt) - Date.parse(right.createdAt));
}

/** The workspace's goals and work read again, so every view of the work sees the change. */
function changed(workspaceId: string): void {
  window.dispatchEvent(new CustomEvent(GOALS_CHANGED, { detail: workspaceId }));
}

export async function joinEpic(workspaceId: string, epicId: string, reference: GoalItemDto, bridge: EpicBridge = window.bridge): Promise<EpicJoinDto> {
  const joined = await bridge.joinEpic(workspaceId, epicId, reference.kind, reference.key);
  changed(workspaceId);
  return joined;
}

export async function orderEpic(workspaceId: string, epicId: string, ids: string[], bridge: EpicBridge = window.bridge): Promise<void> {
  await bridge.orderEpicWork(workspaceId, epicId, ids);
  changed(workspaceId);
}

export async function removeFromEpic(workspaceId: string, itemId: string, bridge: EpicBridge = window.bridge): Promise<void> {
  await bridge.removeFromEpic(workspaceId, itemId);
  changed(workspaceId);
}
