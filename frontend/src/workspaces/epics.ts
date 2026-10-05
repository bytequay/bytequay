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

import { useCallback, useEffect, useState } from 'react';
import type { EpicJoinDto, EpicRecordDto, GoalItemDto, WorkItemDto } from '../goals/contracts';
import { GOALS_CHANGED } from '../goals/useGoals';
import type { AttentionItem } from '../home/attention';
import type { JiraIssue } from '../jira/contracts';
import type { Bridge } from '../types';

export type EpicBridge = Pick<Bridge, 'joinEpic' | 'orderEpicWork' | 'removeFromEpic'>;
export type EpicRecordBridge = Pick<Bridge, 'getEpic' | 'refreshEpicChildren' | 'syncEpicChildren' | 'dismissEpicChildren' | 'concludeEpic' | 'listConcludedEpics'
  | 'proposeEpicWork' | 'acceptEpicProposal' | 'dismissEpicProposal'>;
/** How often an Epic is read again while its model proposes work; the read is local. */
const BREAKDOWN_POLL_MS = 1_000;
// Stable, so the record's reads run once per Epic rather than on every render.
const windowRecordBridge = (): EpicRecordBridge => window.bridge;

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

/**
 * An Epic's children: the last read at once, then a fresh read from its source each time the view opens, which stays quiet when it fails.
 * Sync makes the unsynced ones work; Not now hides them until another child appears.
 */
export function useEpicRecord(workspaceId: string, epicId: string, connectionId: string | null, bridge: () => EpicRecordBridge = windowRecordBridge): {
  record: EpicRecordDto | null;
  sync: () => Promise<void>;
  dismiss: () => Promise<void>;
  conclude: (outcome: string) => Promise<void>;
  propose: () => Promise<void>;
  acceptProposal: (proposalId: string) => Promise<void>;
  dismissProposal: (proposalId: string) => Promise<void>;
} {
  const [record, setRecord] = useState<EpicRecordDto | null>(null);
  useEffect(() => {
    let active = true;
    let refreshed = false;
    // A bridge without these reads leaves the strip out instead of breaking the view.
    void Promise.resolve().then(() => bridge().getEpic(workspaceId, epicId)).then((read) => { if (active && !refreshed) setRecord(read); }, () => {});
    void Promise.resolve().then(() => bridge().refreshEpicChildren(workspaceId, epicId, connectionId))
      .then((read) => { refreshed = true; if (active) setRecord(read); }, () => {});
    return () => { active = false; };
  }, [bridge, connectionId, epicId, workspaceId]);
  const sync = useCallback(async () => {
    setRecord(await bridge().syncEpicChildren(workspaceId, epicId));
    changed(workspaceId);
  }, [bridge, epicId, workspaceId]);
  const dismiss = useCallback(async () => { setRecord(await bridge().dismissEpicChildren(workspaceId, epicId)); }, [bridge, epicId, workspaceId]);
  const conclude = useCallback(async (outcome: string) => {
    setRecord(await bridge().concludeEpic(workspaceId, epicId, outcome));
    changed(workspaceId);
  }, [bridge, epicId, workspaceId]);
  const breakingDown = record?.breakingDown === true;
  useEffect(() => {
    if (!breakingDown) return;
    let active = true;
    const timer = window.setInterval(() => {
      void Promise.resolve().then(() => bridge().getEpic(workspaceId, epicId)).then((read) => { if (active) setRecord(read); }, () => {});
    }, BREAKDOWN_POLL_MS);
    return () => { active = false; window.clearInterval(timer); };
  }, [breakingDown, bridge, epicId, workspaceId]);
  const propose = useCallback(async () => { setRecord(await bridge().proposeEpicWork(workspaceId, epicId, connectionId)); }, [bridge, connectionId, epicId, workspaceId]);
  const acceptProposal = useCallback(async (proposalId: string) => {
    setRecord(await bridge().acceptEpicProposal(workspaceId, epicId, proposalId));
    changed(workspaceId);
  }, [bridge, epicId, workspaceId]);
  const dismissProposal = useCallback(async (proposalId: string) => {
    setRecord(await bridge().dismissEpicProposal(workspaceId, epicId, proposalId));
  }, [bridge, epicId, workspaceId]);
  return { record, sync, dismiss, conclude, propose, acceptProposal, dismissProposal };
}

const NONE: ReadonlySet<string> = new Set();

/**
 * The workspace's concluded Epics, which the drawer leaves out: read again when its work changes or another Epic is marked. A bridge
 * without the read concludes none.
 */
export function useConcludedEpics(workspaceId: string, marked: string, bridge: () => EpicRecordBridge = windowRecordBridge): ReadonlySet<string> {
  const [concluded, setConcluded] = useState<ReadonlySet<string>>(NONE);
  useEffect(() => {
    let active = true;
    const read = (): void => {
      void Promise.resolve().then(() => bridge().listConcludedEpics(workspaceId)).then((ids) => { if (active) setConcluded(new Set(ids)); }, () => {});
    };
    const onChange = (event: Event): void => { if ((event as CustomEvent<string>).detail === workspaceId) read(); };
    read();
    window.addEventListener(GOALS_CHANGED, onChange);
    return () => { active = false; window.removeEventListener(GOALS_CHANGED, onChange); };
  }, [bridge, marked, workspaceId]);
  return concluded;
}

export async function removeFromEpic(workspaceId: string, itemId: string, bridge: EpicBridge = window.bridge): Promise<void> {
  await bridge.removeFromEpic(workspaceId, itemId);
  changed(workspaceId);
}
