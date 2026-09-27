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

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { CircleDot, ListChecks, Search, type LucideIcon } from 'lucide-react';
import RepoAvatar from '../RepoAvatar';
import { getCached, setCached } from '../dataCache';
import AttentionList from '../home/AttentionList';
import { ISSUE_DEVELOPMENT_CHANGED } from '../workspaces/issueDevelopment';
import {
  ATTENTION_SEED,
  assignedIssues,
  attentionCandidates,
  loadHandled,
  saveHandled,
  type AttentionItem,
  type Handled,
} from '../home/attention';
import { ComposerDock, StartingAgentTask, TaskComposer, taskArchived, useAgentTaskCreation, type DraftImage, type TaskModelSelection } from './AgentTasksView';
import { HOME_WINDOW_MIN, TaskWindows } from './TaskCard';
import { linkTasks, useTaskAttention } from './attention';
import type { AgentBridgePort } from './model';
import type { AgentTaskDto, AgentTaskStartupPhase, ModelOptionDto, ModelProviderDto, PullRequestDto } from '../types';

/** Starting points that fill the composer; the user edits or sends them as they are. */
const SUGGESTIONS: { icon: LucideIcon; title: string; prompt: string }[] = [
  { icon: Search, title: 'Explore and understand code', prompt: 'Explore the code and explain how a feature is implemented.' },
  { icon: CircleDot, title: 'Fix issues and failures', prompt: 'Investigate a failing test and fix the root cause.' },
  { icon: ListChecks, title: 'Review code and suggest changes', prompt: 'Review recent changes and suggest safe improvements.' },
];

/**
 * Windows the user put away, per workspace, each with when. Renderer-local: the task itself is
 * untouched. The time is what a window compares its attention against, so putting away a task that
 * already wants the user keeps it away.
 */
function hiddenTasksKey(workspaceId: string): string {
  return `bq.home.hiddenTasks.${workspaceId}`;
}

function loadHiddenTasks(workspaceId: string): Map<string, number> {
  try {
    const parsed: unknown = JSON.parse(window.localStorage.getItem(hiddenTasksKey(workspaceId)) ?? '{}');
    // An older store held bare ids with no time; they count as put away now.
    if (Array.isArray(parsed)) {
      const now = Date.now();
      return new Map(parsed.filter((value): value is string => typeof value === 'string').map((id) => [id, now]));
    }
    if (typeof parsed !== 'object' || parsed === null) return new Map();
    return new Map(Object.entries(parsed).filter((entry): entry is [string, number] => typeof entry[1] === 'number'));
  }
  catch {
    return new Map();
  }
}

function saveHiddenTasks(workspaceId: string, hidden: ReadonlyMap<string, number>): void {
  try {
    window.localStorage.setItem(hiddenTasksKey(workspaceId), JSON.stringify(Object.fromEntries(hidden)));
  }
  catch { /* A full or disabled store just means the window comes back next time. */ }
}

/** The workspace's slice of what Home lists: pull requests and issues of its monitored repository. */
function useWorkspaceAttention(workspaceId: string, repository: string, bridge: () => AgentBridgePort): {
  candidates: AttentionItem[];
  handled: Handled;
  applyHandled: (handled: Handled) => void;
  error: string | null;
  login: string | null;
} {
  // Every tab switch remounts this page; the last reads any Home made paint the first frame, and
  // the mount still re-reads them.
  const [prs, setPrs] = useState<PullRequestDto[]>(() => getCached<PullRequestDto[]>(ATTENTION_SEED.prs) ?? []);
  const [login, setLogin] = useState<string | null>(() => getCached<{ login: string }>(ATTENTION_SEED.profile)?.login ?? null);
  const [issues, setIssues] = useState<AttentionItem[]>(
    () => (getCached<AttentionItem[]>(ATTENTION_SEED.issues) ?? []).filter(item => item.workspaceId === workspaceId));
  const [handled, setHandled] = useState<Handled>(loadHandled);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let active = true;
    const apply = (rows: PullRequestDto[]) => {
      setCached(ATTENTION_SEED.prs, rows);
      if (active) setPrs(rows);
    };
    const reload = () => { void window.bridge.fetchAttentionPrs().then(apply).catch(() => {}); };
    const updated = (event: Event) => apply((event as CustomEvent<PullRequestDto[]>).detail);
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
        const assigned = assignedIssues(snapshot, workspaceId, login);
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
  const workspaces = useMemo(() => [{ id: workspaceId, name: '', repository }], [repository, workspaceId]);
  const tasks = useTaskAttention(workspaces, bridge);
  const candidates = useMemo(() => linkTasks(attentionCandidates(
    prs.filter(pr => pr.repo.toLowerCase() === repository.toLowerCase()), issues.concat(tasks.items), login), tasks.tasks), [issues, login, prs, repository, tasks]);
  // The same store as Home, so acknowledging here clears the row there too.
  const applyHandled = useCallback((next: Handled) => {
    setHandled(next);
    saveHandled(next);
  }, []);
  return { candidates, handled, applyHandled, error, login };
}

interface AgentHomeProps {
  workspaceId: string;
  headerAction?: React.ReactNode;
  /** "owner/repo", the monitored repository the page belongs to. */
  repository: string;
  bridge?: AgentBridgePort;
  onOpenTask: (taskId: string) => void;
  onOpenSettings?: () => void;
  onShowAll: () => void;
  onOpenPullRequest?: (number: number) => void;
  onOpenIssue?: (number: number) => void;
  onStartIssue?: (number: number) => void;
}

export default function AgentHome({
  workspaceId, headerAction, repository, bridge: explicitBridge, onOpenTask, onOpenSettings, onShowAll, onOpenPullRequest, onOpenIssue, onStartIssue,
}: AgentHomeProps): React.JSX.Element {
  const live = useCallback((): AgentBridgePort => explicitBridge ?? window.bridge, [explicitBridge]);
  const creation = useAgentTaskCreation({ workspaceId, bridge: live, onOpenTask });
  const attention = useWorkspaceAttention(workspaceId, repository, live);
  const [hidden, setHidden] = useState(() => loadHiddenTasks(workspaceId));
  const hide = useCallback((task: AgentTaskDto) => setHidden((previous) => {
    const next = new Map(previous).set(task.id, Date.now());
    saveHiddenTasks(workspaceId, next);
    return next;
  }), [workspaceId]);
  const reveal = useCallback((task: AgentTaskDto) => setHidden((previous) => {
    if (!previous.has(task.id)) return previous;
    const next = new Map(previous);
    next.delete(task.id);
    saveHiddenTasks(workspaceId, next);
    return next;
  }), [workspaceId]);
  const openAttention = useCallback((item: AttentionItem) => {
    if (item.task) onOpenTask(item.task.dto.id);
    else if (item.type === 'issue') onOpenIssue?.(item.number);
    else onOpenPullRequest?.(item.number);
  }, [onOpenIssue, onOpenPullRequest, onOpenTask]);
  return (
    <AgentHomeView
      headerAction={headerAction}
      {...creation.composer}
      repository={repository}
      bridge={live}
      tasks={creation.tasks.filter((task) => task.state === 'OPEN' && !taskArchived(task))}
      hiddenTasks={hidden}
      onHideTask={hide}
      onRevealTask={reveal}
      attention={attention.candidates}
      handled={attention.handled}
      viewerLogin={attention.login}
      onHandledChange={attention.applyHandled}
      onOpenAttention={openAttention}
      onStartIssue={onStartIssue ? item => onStartIssue(item.number) : undefined}
      attentionError={attention.error}
      error={creation.error}
      startup={creation.startup}
      onOpenTask={onOpenTask}
      onOpenSettings={onOpenSettings}
      onShowAll={onShowAll}
    />
  );
}

export function AgentHomeView({
  repository, headerAction, laneId, providers, bridge, tasks, models, selection, draft, blocker,
  onDraftChange, onSelectProvider, onSelectModel, onCreate, onOpenTask, onOpenSettings, onShowAll,
  hiddenTasks, onHideTask, onRevealTask,
  attention = [], handled = {}, viewerLogin = null, onHandledChange = () => {}, onOpenAttention = () => {},
  onStartIssue, attentionError,
  images = [], busy = false, startup = null, attaching = false, onAttach, onPasteImages, onRemoveImage, error = null, automation,
}: {
  repository: string;
  laneId: string;
  providers: ModelProviderDto[];
  bridge: () => AgentBridgePort;
  /** The open tasks, drawn as windows under the composer. */
  tasks: AgentTaskDto[];
  /** Which windows the user put away, each with when. */
  hiddenTasks?: ReadonlyMap<string, number>;
  onHideTask?: (task: AgentTaskDto) => void;
  onRevealTask?: (task: AgentTaskDto) => void;
  /** The repository's pull requests and issues that need the user, before ack and snooze are applied. */
  attention?: AttentionItem[];
  handled?: Handled;
  viewerLogin?: string | null;
  onHandledChange?: (handled: Handled) => void;
  onOpenAttention?: (item: AttentionItem) => void;
  onStartIssue?: (item: AttentionItem) => void;
  attentionError?: string | null;
  models: ModelOptionDto[];
  selection: TaskModelSelection | null;
  draft: string;
  blocker: string | null;
  onDraftChange: (draft: string) => void;
  onSelectProvider: (laneId: string) => void;
  onSelectModel: (model: string, effort: string | null, serviceTier: string | null) => void;
  onCreate: () => void;
  onOpenTask: (taskId: string) => void;
  onOpenSettings?: () => void;
  onShowAll: () => void;
  automation?: React.ReactNode;
  headerAction?: React.ReactNode;
  error?: string | null;
  images?: DraftImage[];
  busy?: boolean;
  /** Which creation step the in-flight create request is in, for the starting screen. */
  startup?: AgentTaskStartupPhase | null;
  attaching?: boolean;
  onAttach?: () => void;
  onPasteImages?: () => void;
  onRemoveImage?: (id: string) => void;
}): React.JSX.Element {
  if (busy && !attaching) return <StartingAgentTask {...{ draft, images, laneId, providers, models, selection, startup, headerAction }} />;
  const [owner, repo = ''] = repository.split('/');
  return (
    <div className="agt-home">
      <div className="agt-page">
        <div className="agt-page-head">
          <RepoAvatar owner={owner} repo={repo} size={28} radius={8} />
          <h1>{repository}</h1>
          <span className="agt-spacer" />
          {headerAction}
        </div>
        {attentionError && <div className="ws-error" role="alert">{attentionError}</div>}
        <AttentionList
          variant="workspace"
          candidates={attention}
          handled={handled}
          viewerLogin={viewerLogin}
          onHandledChange={onHandledChange}
          onOpen={onOpenAttention}
          onStartIssue={onStartIssue}
        />
        <section className="agt-open-tasks" aria-label="Open tasks">
          <div className="agt-open-tasks__head">
            <span className="agt-label">Open tasks · {tasks.length}</span>
            <span className="agt-spacer" />
            <button type="button" className="agt-link" onClick={onShowAll}>
              Show all <kbd className="agt-kbd">{'⇧⌘\\'}</kbd>
            </button>
          </div>
          <TaskWindows
            fluid
            renamable
            tasks={tasks}
            providers={providers}
            width={HOME_WINDOW_MIN}
            bridge={bridge}
            hiddenTasks={hiddenTasks}
            onHide={onHideTask}
            onAttention={onRevealTask}
            onOpen={onOpenTask}
          />
        </section>
        <ComposerDock repository={repository} chips={SUGGESTIONS.map((suggestion) => {
          const Icon = suggestion.icon;
          return (
            <button type="button" className="agt-start" key={suggestion.title} onClick={() => onDraftChange(suggestion.prompt)}>
              <Icon size={13} strokeWidth={1.9} aria-hidden="true" />
              {suggestion.title}
            </button>
          );
        })}>
          {error !== null && <div className="cmt-error" role="alert">{error}</div>}
          <TaskComposer
            automation={automation}
            draft={draft}
            blocker={blocker}
            busy={busy}
            attaching={attaching}
            images={images}
            onAttach={onAttach}
            onPasteImages={onPasteImages}
            onRemoveImage={onRemoveImage}
            laneId={laneId}
            providers={providers}
            models={models}
            selection={selection}
            onDraftChange={onDraftChange}
            onSelectProvider={onSelectProvider}
            onSelectModel={onSelectModel}
            onCreate={onCreate}
            onOpenSettings={onOpenSettings}
          />
        </ComposerDock>
      </div>
    </div>
  );
}
