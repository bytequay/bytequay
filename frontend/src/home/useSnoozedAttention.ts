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
import { useEffect, useMemo, useState } from 'react';
import { taskAttentionItem } from '../agents/attention';
import { jiraAttentionItem } from '../jira/attention';
import { jiraIssueLocation } from '../jira/contracts';
import type { Bridge } from '../types';
import { completeHandled, issueAttentionItem, pullRequestItem, type AttentionItem, type Handled } from './attention';

export type AttentionScope = { workspaceId: string; repository: string };
type Reminder = { item: AttentionItem; until: number };

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

const textOrNull = (value: unknown) => value == null || typeof value === 'string';
const texts = (value: unknown) => Array.isArray(value) && value.every(entry => typeof entry === 'string');
const status = (value: unknown) => object(value) && typeof value.id === 'string' && typeof value.name === 'string' && typeof value.category === 'string';

function retainedItem(value: unknown, key: string): value is AttentionItem {
  if (!object(value) || value.key !== key || !['pr', 'issue', 'task'].includes(String(value.type))
    || !['approval', 'review', 'assigned', 'changes', 'failed', 'ci', 'blocked', 'commits', 'merge', 'finished', 'updated'].includes(String(value.kind))
    || typeof value.title !== 'string' || typeof value.repo !== 'string' || typeof value.number !== 'number'
    || typeof value.updatedAt !== 'string' || !Number.isFinite(Date.parse(value.updatedAt)) || typeof value.body !== 'string'
    || typeof value.commentCount !== 'number' || !(value.workspaceId === null || typeof value.workspaceId === 'string')
    || !(value.author === null || typeof value.author === 'string') || !textOrNull(value.authorAvatarUrl)
    || !textOrNull(value.reference) || !textOrNull(value.workspaceName) || !texts(value.labels)
    || (value.links !== undefined && (!Array.isArray(value.links) || !value.links.every(link => object(link)
      && ['pr', 'issue', 'jira', 'task'].includes(String(link.source)) && typeof link.key === 'string' && typeof link.title === 'string' && textOrNull(link.status))))) return false;
  if (value.task !== undefined) {
    if (value.type !== 'task' || !object(value.task) || !object(value.task.dto)) return false;
    const { dto, activity } = value.task;
    if (typeof dto.id !== 'string' || key !== `task:${dto.id}` || typeof dto.workspaceId !== 'string'
      || typeof dto.laneId !== 'string' || typeof dto.title !== 'string' || typeof dto.state !== 'string') return false;
    if (activity == null) return true;
    if (!object(activity)) return false;
    const approval = activity.pendingApproval;
    const message = activity.lastMessage;
    return (approval == null || (object(approval) && typeof approval.title === 'string' && typeof approval.detail === 'string' && texts(approval.scope)))
      && (message == null || (object(message) && typeof message.author === 'string' && typeof message.text === 'string'));
  }
  if (value.jira !== undefined) {
    const jira = value.jira;
    if (value.type !== 'issue' || value.pr !== null || !object(jira) || typeof jira.id !== 'string' || typeof jira.cloudId !== 'string' || key !== `jira:${jira.cloudId}:${jira.id}`
      || typeof jira.connectionId !== 'string' || typeof jira.key !== 'string' || !status(jira.status)
      || !(jira.task === null || (object(jira.task) && typeof jira.task.id === 'string' && typeof jira.task.workspaceId === 'string'))
      || !(jira.workspace === null || (object(jira.workspace) && typeof jira.workspace.id === 'string' && typeof jira.workspace.name === 'string'))) return false;
    const activity = jira.latestActivity;
    return activity == null || (object(activity) && typeof activity.kind === 'string' && textOrNull(activity.note) && texts(activity.fields)
      && (activity.from === null || status(activity.from)) && (activity.to === null || status(activity.to))
      && (activity.actor === null || (object(activity.actor) && typeof activity.actor.displayName === 'string' && textOrNull(activity.actor.avatarUrl))));
  }
  if (value.type === 'pr') {
    const pr = value.pr;
    if (!object(pr) || pr.repo !== value.repo || pr.number !== value.number || !texts(pr.labels) || !texts(pr.requestedReviewers)
      || !textOrNull(pr.state) || !(pr.author === null || typeof pr.author === 'string')
      || (pr.failingChecks !== undefined && !texts(pr.failingChecks))
      || (pr.commitsSinceReview !== undefined && (!Array.isArray(pr.commitsSinceReview)
        || !pr.commitsSinceReview.every(commit => object(commit) && typeof commit.sha === 'string' && typeof commit.message === 'string')))) return false;
    const activity = pr.latestActivity;
    return activity == null || (object(activity) && typeof activity.event === 'string'
      && ['actor', 'requestedReviewer', 'state', 'label', 'excerpt', 'path'].every(field => textOrNull(activity[field])));
  }
  return value.type === 'issue' && value.pr === null && key === `${value.repo}#${value.number}`;
}

/** Refresh the retained reminder even when the ordinary attention query no longer includes it. */
export async function refreshSnoozedItem(item: AttentionItem, bridge: Bridge): Promise<AttentionItem> {
  try {
    if (item.task) {
      const task = item.task.dto;
      const [updated, activity] = await Promise.all([bridge.getAgentTask(task.id, task.workspaceId), bridge.listAgentTaskActivity(task.workspaceId)]);
      return taskAttentionItem(updated, activity[task.id] ?? null, { id: task.workspaceId, name: item.workspaceName ?? '', repository: item.repo });
    }
    if (item.jira) return jiraAttentionItem((await bridge.getJiraIssue(jiraIssueLocation(item.jira))).issue);
    if (item.pr) {
      const updated = await bridge.findStoredPullRequest(item.repo, item.number);
      return updated === null ? item : { ...pullRequestItem(updated, 'updated'), workspaceId: item.workspaceId };
    }
    if (item.workspaceId !== null) {
      try {
        const updated = await bridge.fetchWorkspaceIssue(item.workspaceId, item.number);
        if (updated !== null) return { ...issueAttentionItem(updated, item.repo, item.workspaceId), workspaceName: item.workspaceName };
      }
      catch { /* Closed issues leave the open-issue cache; their exact GitHub preview remains readable. */ }
    }
    const [owner, repo] = item.repo.split('/');
    const updated = await bridge.fetchGitHubIssuePreview(owner, repo, item.number);
    return updated.pullRequest ? item : { ...item, title: updated.title, body: updated.body, author: updated.author,
      labels: updated.labels, updatedAt: updated.updatedAt, issueState: updated.state };
  }
  catch { return item; }
}

function inScope(item: AttentionItem, scope: AttentionScope | undefined): boolean {
  if (scope === undefined) return true;
  if (item.jira) return item.jira.workspace?.id === scope.workspaceId;
  if (item.type === 'pr') return item.repo.toLowerCase() === scope.repository.toLowerCase();
  return item.workspaceId === scope.workspaceId;
}

/** Expired reminders retain their own identity; ACK, a new snooze, or pinning still controls visibility. */
export function useSnoozedAttention(candidates: AttentionItem[], handled: Handled, now: number, scope?: AttentionScope): AttentionItem[] {
  // Every list draws through here, so a mark the assistant made gets the item's fingerprint the first time any list shows it.
  useEffect(() => completeHandled(candidates), [candidates, handled]);
  const serialized = JSON.stringify(Object.entries(handled).flatMap(([key, entry]): Reminder[] =>
    object(entry) && entry.kind === 'snooze' && typeof entry.until === 'number' && Number.isFinite(entry.until) && entry.until <= now
      && retainedItem(entry.snoozedItem, key) && inScope(entry.snoozedItem, scope)
      ? [{ item: entry.snoozedItem, until: entry.until }] : []));
  const reminders: Reminder[] = useMemo(() => JSON.parse(serialized) as Reminder[], [serialized]);
  const [snapshots, setSnapshots] = useState<Record<string, Reminder>>({});
  useEffect(() => {
    if (reminders.length === 0) return;
    let active = true;
    let latest = reminders;
    let request = 0;
    const refresh = () => {
      const current = ++request;
      void Promise.all(latest.map(async reminder => ({ ...reminder, item: await refreshSnoozedItem(reminder.item, window.bridge) })))
        .then(updated => {
          if (!active || current !== request) return;
          latest = updated;
          const next: Record<string, Reminder> = Object.fromEntries(updated.map(snapshot => [snapshot.item.key, snapshot]));
          setSnapshots(shown => JSON.stringify(shown) === JSON.stringify(next) ? shown : next);
        });
    };
    refresh();
    const timer = window.setInterval(refresh, 30_000);
    window.addEventListener('focus', refresh);
    return () => { active = false; window.clearInterval(timer); window.removeEventListener('focus', refresh); };
  }, [reminders]);
  // A new list on every render would make every pass over it run again.
  return useMemo(() => {
    const existing = new Set(candidates.map(item => item.key));
    return candidates.concat(reminders.flatMap(reminder => {
      const snapshot = snapshots[reminder.item.key];
      const item = snapshot?.until === reminder.until ? snapshot.item : reminder.item;
      return existing.has(item.key) || !inScope(item, scope) ? [] : [item];
    }));
  }, [candidates, reminders, snapshots, scope]);
}
