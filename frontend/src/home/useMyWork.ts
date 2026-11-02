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
import { linkTasks, useTaskAttention } from '../agents/attention';
import { getCached, setCached } from '../dataCache';
import { useJiraAttention } from '../jira/attention';
import type { AuthoredIssueDto, PullRequestDto, UserProfileDto, WorkspaceDto } from '../types';
import type { WorkItemDto } from '../workitems/contracts';
import { ISSUE_DEVELOPMENT_CHANGED } from '../workspaces/issueDevelopment';
import { workspaceIsUpdating } from '../workspaces/model';
import { pollVisible } from '../ide/pollVisible';
import { useNotices } from '../notices/useNotices';
import { ATTENTION_SEED, ISSUE_ATTENTION_REFRESH_MS, attentionCandidates, issueAttentionItems, type AttentionItem } from './attention';
import { decisionMarks, myWorkItems } from './work';

// The backend searches GitHub for the viewer's issues at most this often; asking sooner only rereads its answer.
const AUTHORED_ISSUES_REFRESH_MS = 5 * 60 * 1000;
const LOCAL_WORK_SEED = 'home:local-work';

// Home and the Assistant unmount often, so returning re-runs the same local reads from an empty screen. These hold the last rows purely
// to paint the first frame; every mount still fetches and overwrites them, so they carry no expiry.
const SEED = {
  profile: ATTENTION_SEED.profile,
  workspaces: 'home:workspaces',
  attention: ATTENTION_SEED.prs,
  issues: ATTENTION_SEED.issues,
  authoredIssues: 'home:authored-issues',
};

/** Local work items of the listed workspaces, ids joined by commas; read on Home's issue clock, since they change about as often. */
function useLocalWork(workspaceIds: string): WorkItemDto[] {
  const [work, setWork] = useState<WorkItemDto[]>(() => getCached<WorkItemDto[]>(LOCAL_WORK_SEED) ?? []);
  useEffect(() => {
    if (workspaceIds === '') {
      setWork([]);
      return;
    }
    let active = true;
    // A workspace that cannot be read drops out until the next read rather than failing the others.
    const stop = pollVisible(() => Promise.allSettled(workspaceIds.split(',').map(id => Promise.resolve().then(() => window.bridge.listWorkItems(id))))
      .then(results => {
        if (!active) return;
        const found = results.flatMap(result => result.status === 'fulfilled' ? result.value : []);
        setCached(LOCAL_WORK_SEED, found);
        setWork(shown => JSON.stringify(shown) === JSON.stringify(found) ? shown : found);
      }), ISSUE_ATTENTION_REFRESH_MS);
    return () => { active = false; stop(); };
  }, [workspaceIds]);
  return work;
}

/**
 * Everything Home and the Assistant's My work read: the viewer, the watched repositories, what asks for the viewer's attention, and the
 * viewer's own work built from it. `ended` also builds All my work, which lists finished work too.
 *
 * ponytail: the Assistant opened over Home reads the same sources a second time; share one reader if that load ever shows.
 */
export function useMyWork({ ended = false }: { ended?: boolean } = {}) {
  const [profile, setProfile] = useState<UserProfileDto | null>(() => getCached<UserProfileDto>(SEED.profile) ?? null);
  const [workspaces, setWorkspaces] = useState<WorkspaceDto[]>(() => getCached<WorkspaceDto[]>(SEED.workspaces) ?? []);
  const [loading, setLoading] = useState(() => getCached(SEED.workspaces) === undefined);
  const [workspaceError, setWorkspaceError] = useState<string | null>(null);
  const [attentionPrs, setAttentionPrs] = useState<PullRequestDto[] | null>(() => getCached<PullRequestDto[]>(SEED.attention) ?? null);
  const [attentionError, setAttentionError] = useState<string | null>(null);
  const [issueItems, setIssueItems] = useState<AttentionItem[]>(() => getCached<AttentionItem[]>(SEED.issues) ?? []);
  const [issueAttentionError, setIssueAttentionError] = useState<string | null>(null);
  const [authoredIssues, setAuthoredIssues] = useState<AuthoredIssueDto[]>(() => getCached<AuthoredIssueDto[]>(SEED.authoredIssues) ?? []);
  const jira = useJiraAttention();

  useEffect(() => {
    let active = true;
    // Workspaces are backend-owned; localStorage is never authoritative.
    void Promise.allSettled([window.bridge.getUserProfile(), window.bridge.listWorkspaces()]).then(([profileResult, reposResult]) => {
      if (!active) return;
      if (profileResult.status === 'fulfilled' && profileResult.value) {
        setCached(SEED.profile, profileResult.value);
        setProfile(profileResult.value);
      }
      if (reposResult.status === 'fulfilled') {
        setCached(SEED.workspaces, reposResult.value);
        setWorkspaces(reposResult.value);
      }
      else setWorkspaceError(reposResult.reason instanceof Error ? reposResult.reason.message : String(reposResult.reason));
      setLoading(false);
    });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    let active = true;
    let request = 0;
    const reload = () => {
      const current = ++request;
      void window.bridge.fetchAttentionPrs().then(rows => {
        if (!active || current !== request) return;
        setCached(SEED.attention, rows);
        setAttentionPrs(rows); setAttentionError(null);
      }).catch(error => {
        if (active && current === request) setAttentionError(error instanceof Error ? error.message : String(error));
      });
    };
    const updated = (event: Event) => {
      request++;
      const rows = (event as CustomEvent<PullRequestDto[] | undefined>).detail;
      if (rows === undefined) { reload(); return; }
      setCached(SEED.attention, rows);
      setAttentionPrs(rows);
      setAttentionError(null);
    };
    reload();
    const stopSync = window.bridge.onPullRequestsSynced(reload);
    window.addEventListener('bytequay:pr-attention-updated', updated);
    window.addEventListener('bytequay:pr-monitor-changed', reload);
    return () => {
      active = false;
      stopSync();
      window.removeEventListener('bytequay:pr-attention-updated', updated);
      window.removeEventListener('bytequay:pr-monitor-changed', reload);
    };
  }, []);

  const updating = workspaces.some(workspaceIsUpdating);
  useEffect(() => {
    if (!updating) return;
    // A clone that never finishes keeps this going, so an unchanged read keeps the shown list and Home does not re-render.
    const timer = window.setInterval(() => {
      if (document.hidden) return;
      void window.bridge.listWorkspaces()
        .then(rows => { setCached(SEED.workspaces, rows); setWorkspaces(shown => JSON.stringify(shown) === JSON.stringify(rows) ? shown : rows); })
        .catch(() => {});
    }, 1500);
    return () => window.clearInterval(timer);
  }, [updating]);

  // Issue snapshots are local reads per watched repository; keyed on the id list so the 1.5s workspace poll doesn't refire the fan-out.
  const workspaceKey = workspaces.map(workspace => workspace.id).join(',');
  const login = profile?.login;

  useEffect(() => {
    if (workspaceKey === '') {
      setIssueAttentionError(null);
      setIssueItems([]);
      setCached(SEED.issues, []);
      return;
    }
    let cancelled = false;
    let request = 0;
    // Each workspace's last read: an unchanged one answers without its issues, which stay as they were.
    const reads = new Map<string, { etag: string | null; items: AttentionItem[] }>();
    const reload = () => {
      const current = ++request;
      const ids = workspaceKey.split(',');
      void Promise.allSettled(ids.map(workspaceId =>
        Promise.all([window.bridge.fetchWorkspaceIssueAttention(workspaceId, reads.get(workspaceId)?.etag ?? null),
          window.bridge.getWorkspaceHostingViewer(workspaceId)])
          .then(([read, viewerLogin]) => {
            if (read.snapshot === null || current !== request) return false;
            reads.set(workspaceId, { etag: read.etag, items: issueAttentionItems(read.snapshot, workspaceId, viewerLogin ?? '') });
            return true;
          }, (failure: unknown) => {
            if (current === request) reads.delete(workspaceId);
            throw failure;
          })))
        .then(results => {
          if (cancelled || current !== request) return;
          setIssueAttentionError(results.some(result => result.status === 'rejected')
            ? 'Some work items could not be loaded. Open the workspace’s Issues tab to retry.' : null);
          if (!results.some(result => result.status === 'rejected' || result.value)) return;
          const items = ids.flatMap(workspaceId => reads.get(workspaceId)?.items ?? []);
          setCached(SEED.issues, items);
          setIssueItems(items);
        });
    };
    const changed = () => { reads.clear(); setIssueItems([]); reload(); };
    reload();
    // Its own clock: pull request updates are sent only when a row changes, and say nothing about issues.
    const timer = window.setInterval(() => { if (!document.hidden) reload(); }, ISSUE_ATTENTION_REFRESH_MS);
    window.addEventListener(ISSUE_DEVELOPMENT_CHANGED, changed);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
      window.removeEventListener(ISSUE_DEVELOPMENT_CHANGED, changed);
    };
  }, [workspaceKey]);

  // A failed read keeps the last list: My work's background refreshes stay quiet.
  useEffect(() => {
    if (!login) return;
    let cancelled = false;
    const refresh = () => {
      window.bridge.fetchAuthoredIssues()
        .then(issues => {
          setCached(SEED.authoredIssues, issues);
          if (!cancelled) setAuthoredIssues(issues);
        })
        .catch(() => {});
    };
    refresh();
    const timer = window.setInterval(() => { if (!document.hidden) refresh(); }, AUTHORED_ISSUES_REFRESH_MS);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [login]);

  const taskWorkspaces = useMemo(() => workspaces.map(workspace => ({
    id: workspace.id, name: workspace.name, repository: `${workspace.monitoredOwner}/${workspace.monitoredRepo}`,
  })), [workspaces]);
  const tasks = useTaskAttention(taskWorkspaces);

  const candidates = useMemo(
    () => linkTasks(attentionCandidates(attentionPrs ?? [], issueItems.map<AttentionItem>(item => ({
      ...item, workspaceName: workspaces.find(workspace => workspace.id === item.workspaceId)?.name,
    })).concat(jira.items, tasks.items), login), tasks.tasks),
    [attentionPrs, issueItems, jira.items, login, tasks.items, tasks.tasks, workspaces]);

  const localWork = useLocalWork(workspaceKey);
  const notices = useNotices();
  const decisions = useMemo(() => decisionMarks(notices), [notices]);
  const work = useMemo(() => myWorkItems({
    candidates, tasks: tasks.tasks, activity: tasks.activity, work: localWork, viewerLogin: login ?? null, authored: authoredIssues, ended, decisions,
  }), [authoredIssues, candidates, decisions, ended, localWork, login, tasks.activity, tasks.tasks]);

  const addWorkspace = useCallback((dto: WorkspaceDto) => setWorkspaces(previous => {
    const next = [dto, ...previous.filter(row => row.id !== dto.id)].sort((left, right) => left.displayOrder - right.displayOrder);
    setCached(SEED.workspaces, next);
    return next;
  }), []);

  return { profile, workspaces, loading, workspaceError, addWorkspace, candidates, work, jiraError: jira.error, attentionError, issueAttentionError };
}
