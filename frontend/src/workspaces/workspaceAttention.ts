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

import { useCallback, useEffect, useMemo, useState } from 'react';
import { linkTasks, type TaskAttention } from '../agents/attention';
import { useJiraAttention } from '../jira/attention';
import { getCached, setCached } from '../dataCache';
import {
  ATTENTION_SEED,
  issueAttentionItems,
  attentionCandidates,
  loadHandled,
  saveHandled,
  type AttentionItem,
  type Handled,
} from '../home/attention';
import type { PullRequestDto } from '../types';
import { ISSUE_DEVELOPMENT_CHANGED } from './issueDevelopment';

export type WorkspaceAttention = {
  candidates: AttentionItem[];
  handled: Handled;
  applyHandled: (handled: Handled) => void;
  error: string | null;
  login: string | null;
  /** Whether Jira is connected, so a Home without it offers no Jira tab. */
  jiraConnected: boolean;
};

/**
 * The workspace's slice of what Home lists: pull requests and issues of its monitored repository,
 * Jira tickets assigned to the workspace, and its tasks. Read once for the whole workspace, so its
 * Home and its Epics agree on what needs you.
 */
export function useWorkspaceAttention(workspaceId: string, repository: string, tasks: TaskAttention): WorkspaceAttention {
  // Opening a workspace again paints the last reads any Home made; the mount still re-reads them.
  const [prs, setPrs] = useState<PullRequestDto[]>(() => getCached<PullRequestDto[]>(ATTENTION_SEED.prs) ?? []);
  const [login, setLogin] = useState<string | null>(() => getCached<{ login: string }>(ATTENTION_SEED.profile)?.login ?? null);
  const [issues, setIssues] = useState<AttentionItem[]>(
    () => (getCached<AttentionItem[]>(ATTENTION_SEED.issues) ?? []).filter(item => item.workspaceId === workspaceId));
  const [handled, setHandled] = useState<Handled>(loadHandled);
  const [error, setError] = useState<string | null>(null);
  // Only a ticket explicitly assigned to this workspace belongs to it; project names are never matched.
  const jira = useJiraAttention();
  const tickets = useMemo(() => jira.items.filter(item => item.jira?.workspace?.id === workspaceId), [jira.items, workspaceId]);
  useEffect(() => {
    let active = true;
    let request = 0;
    const apply = (rows: PullRequestDto[]) => {
      setCached(ATTENTION_SEED.prs, rows);
      setPrs(rows);
    };
    const reload = () => {
      const current = ++request;
      void window.bridge.fetchAttentionPrs().then(rows => { if (active && current === request) apply(rows); }).catch(() => {});
    };
    const updated = (event: Event) => {
      request++;
      apply((event as CustomEvent<PullRequestDto[]>).detail);
    };
    const handledChanged = () => setHandled(loadHandled());
    reload();
    const stopSync = window.bridge.onPullRequestsSynced(reload);
    window.addEventListener('bytequay:pr-attention-updated', updated);
    window.addEventListener('bytequay:pr-monitor-changed', reload);
    window.addEventListener('bytequay:attention-handled-changed', handledChanged);
    window.bridge.getUserProfile()
      .then(profile => {
        setCached(ATTENTION_SEED.profile, profile);
        if (active) setLogin(profile.login);
      })
      .catch(() => {});
    return () => {
      active = false;
      stopSync();
      window.removeEventListener('bytequay:pr-attention-updated', updated);
      window.removeEventListener('bytequay:pr-monitor-changed', reload);
      window.removeEventListener('bytequay:attention-handled-changed', handledChanged);
    };
  }, [workspaceId]);
  useEffect(() => {
    if (!login) return;
    let active = true;
    let request = 0;
    const reload = () => {
      const current = ++request;
      void window.bridge.fetchWorkspaceIssueAttention(workspaceId).then(snapshot => {
        if (!active || current !== request) return;
        const assigned = issueAttentionItems(snapshot, workspaceId, login);
        setCached(ATTENTION_SEED.issues, (getCached<AttentionItem[]>(ATTENTION_SEED.issues) ?? [])
          .filter(item => item.workspaceId !== workspaceId).concat(assigned));
        setIssues(assigned);
        setError(null);
      }).catch(() => {
        if (active && current === request) setError('Work items could not be loaded. Open Issues to retry.');
      });
    };
    const changed = () => { setIssues([]); reload(); };
    reload();
    window.addEventListener(ISSUE_DEVELOPMENT_CHANGED, changed);
    window.addEventListener('bytequay:pr-attention-updated', reload);
    return () => {
      active = false;
      window.removeEventListener(ISSUE_DEVELOPMENT_CHANGED, changed);
      window.removeEventListener('bytequay:pr-attention-updated', reload);
    };
  }, [login, workspaceId]);
  const candidates = useMemo(() => linkTasks(attentionCandidates(
    prs.filter(pr => pr.repo.toLowerCase() === repository.toLowerCase()), issues.concat(tickets, tasks.items), login), tasks.tasks),
  [issues, login, prs, repository, tasks.items, tasks.tasks, tickets]);
  // The same store as Home, so acknowledging here clears the row there too.
  const applyHandled = useCallback((next: Handled) => {
    setHandled(next);
    saveHandled(next);
  }, []);
  return { candidates, handled, applyHandled, error, login, jiraConnected: jira.connected };
}
