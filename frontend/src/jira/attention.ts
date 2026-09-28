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
import type { AttentionItem } from '../home/attention';
import { JIRA_CHANGED, anyJiraConnected, type JiraIssue } from './contracts';

export function jiraAttentionItems(issues: JiraIssue[]): AttentionItem[] {
  return issues.filter(issue => !issue.isGoal && !issue.hasChildren && issue.task === null && issue.status.category !== 'done').map<AttentionItem>(issue => ({
    key: `jira:${issue.cloudId}:${issue.id}`, sourceKey: `jira:${issue.cloudId}:${issue.id}`, type: 'issue', kind: 'assigned',
    jira: issue, reference: issue.key, repo: '', number: Number(issue.key.split('-').pop()) || 0,
    title: issue.title, author: issue.assignee?.displayName ?? null, authorAvatarUrl: issue.assignee?.avatarUrl ?? null,
    createdAt: null, updatedAt: issue.updatedAt, labels: issue.labels, pr: null, body: issue.body, commentCount: 0,
    workspaceId: null, workspaceName: 'Jira · Current sprint',
  }));
}
/** The Jira tickets that need you, and whether Jira is connected at all, so a Home without it names no Jira. */
export function useJiraAttention(): { items: AttentionItem[]; error: string | null; connected: boolean } {
  const [items, setItems] = useState<AttentionItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [connected, setConnected] = useState(false);
  useEffect(() => {
    let active = true;
    let request = 0;
    const reload = () => {
      const current = ++request;
      // Without an answer Jira stays unannounced; a bridge that cannot list connections has none.
      void Promise.resolve().then(() => window.bridge.listJiraConnections())
        .then(connections => { if (active && current === request) setConnected(anyJiraConnected(connections)); })
        .catch(() => {});
      void window.bridge.listJiraAttention().then(issues => { if (active && current === request) { setItems(jiraAttentionItems(issues)); setError(null); } })
        .catch(() => { if (active && current === request) setError('Jira work items could not be loaded. Open Jira to retry.'); });
    };
    reload();
    window.addEventListener(JIRA_CHANGED, reload);
    window.addEventListener('focus', reload);
    const timer = window.setInterval(reload, 60_000);
    return () => { active = false; window.removeEventListener(JIRA_CHANGED, reload); window.removeEventListener('focus', reload); window.clearInterval(timer); };
  }, []);
  return { items, error, connected };
}
