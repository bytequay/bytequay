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
import { INTERACTIVE_LANE_ID } from '../settings/model';
import RepoAvatar from '../RepoAvatar';
import AttentionList from '../home/AttentionList';
import {
  assignedIssues,
  attentionCandidates,
  loadHandled,
  saveHandled,
  type AttentionItem,
  type Handled,
} from '../home/attention';
import { StartingAgentTask, TaskComposer, taskArchived, useAgentTaskCreation, type DraftImage, type TaskModelSelection } from './AgentTasksView';
import { HOME_WINDOW_MIN, TaskWindows } from './TaskCard';
import type { AgentBridgePort } from './model';
import type { AgentTaskDto, ModelOptionDto, ModelProviderDto, PullRequestDto } from '../types';

/** Starting points that fill the composer; the user edits or sends them as they are. */
const SUGGESTIONS: { icon: LucideIcon; title: string; prompt: string }[] = [
  { icon: Search, title: 'Explore and understand code', prompt: 'Explore the code and explain how a feature is implemented.' },
  { icon: CircleDot, title: 'Fix issues and failures', prompt: 'Investigate a failing test and fix the root cause.' },
  { icon: ListChecks, title: 'Review code and suggest changes', prompt: 'Review recent changes and suggest safe improvements.' },
];

/** Home shows at most this many attention rows; the workspace's Pull requests tab has the rest. */
const HOME_MAX_ROWS = 3;

/** Windows the user put away, per workspace. Renderer-local: the task itself is untouched. */
function hiddenTasksKey(workspaceId: string): string {
  return `bq.home.hiddenTasks.${workspaceId}`;
}

function loadHiddenTasks(workspaceId: string): Set<string> {
  try {
    const parsed: unknown = JSON.parse(window.localStorage.getItem(hiddenTasksKey(workspaceId)) ?? '[]');
    return new Set(Array.isArray(parsed) ? parsed.filter((value): value is string => typeof value === 'string') : []);
  }
  catch {
    return new Set();
  }
}

function saveHiddenTasks(workspaceId: string, hidden: ReadonlySet<string>): void {
  try {
    window.localStorage.setItem(hiddenTasksKey(workspaceId), JSON.stringify([...hidden]));
  }
  catch { /* A full or disabled store just means the window comes back next time. */ }
}

/** The workspace's slice of what Home lists: pull requests and issues of its monitored repository. */
function useWorkspaceAttention(workspaceId: string, repository: string): {
  candidates: AttentionItem[];
  handled: Handled;
  applyHandled: (handled: Handled) => void;
} {
  const [prs, setPrs] = useState<PullRequestDto[]>([]);
  const [login, setLogin] = useState<string | null>(null);
  const [issues, setIssues] = useState<AttentionItem[]>([]);
  const [handled, setHandled] = useState<Handled>(loadHandled);
  useEffect(() => {
    let active = true;
    const apply = (rows: PullRequestDto[]) => {
      if (active) setPrs(rows.filter(pr => pr.repo.toLowerCase() === repository.toLowerCase()));
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
        if (active) setLogin(profile.login);
        return window.bridge.fetchWorkspaceIssues(workspaceId, 0, 100)
          .then(snapshot => { if (active) setIssues(assignedIssues(snapshot, workspaceId, profile.login)); });
      })
      .catch(() => {});
    return () => {
      active = false;
      stopSync();
      window.removeEventListener('bytequay:pr-attention-updated', updated);
      window.removeEventListener('bytequay:pr-monitor-changed', reload);
      window.removeEventListener('bytequay:attention-handled-changed', handledChanged);
    };
  }, [repository, workspaceId]);
  const candidates = useMemo(() => attentionCandidates(prs, issues, login), [issues, login, prs]);
  // The same store as Home, so acknowledging here clears the row there too.
  const applyHandled = useCallback((next: Handled) => {
    setHandled(next);
    saveHandled(next);
  }, []);
  return { candidates, handled, applyHandled };
}

interface AgentHomeProps {
  workspaceId: string;
  /** "owner/repo", the monitored repository the page belongs to. */
  repository: string;
  bridge?: AgentBridgePort;
  onOpenTask: (taskId: string) => void;
  onOpenSettings?: () => void;
  onShowAll: () => void;
  /** Where "See all" of the attention list goes: the workspace's Pull requests tab. */
  onOpenPullRequests?: () => void;
  onOpenPullRequest?: (number: number) => void;
  onOpenIssue?: (number: number) => void;
  laneId?: string;
}

export default function AgentHome({
  workspaceId, repository, bridge: explicitBridge, onOpenTask, onOpenSettings, onShowAll, onOpenPullRequests, onOpenPullRequest, onOpenIssue,
  laneId = INTERACTIVE_LANE_ID,
}: AgentHomeProps): React.JSX.Element {
  const live = useCallback((): AgentBridgePort => explicitBridge ?? window.bridge, [explicitBridge]);
  const creation = useAgentTaskCreation({ workspaceId, initialLaneId: laneId, bridge: live, onOpenTask });
  const attention = useWorkspaceAttention(workspaceId, repository);
  const [hidden, setHidden] = useState(() => loadHiddenTasks(workspaceId));
  const hide = useCallback((task: AgentTaskDto) => setHidden((previous) => {
    const next = new Set(previous);
    next.add(task.id);
    saveHiddenTasks(workspaceId, next);
    return next;
  }), [workspaceId]);
  const reveal = useCallback((task: AgentTaskDto) => setHidden((previous) => {
    if (!previous.has(task.id)) return previous;
    const next = new Set(previous);
    next.delete(task.id);
    saveHiddenTasks(workspaceId, next);
    return next;
  }), [workspaceId]);
  const openAttention = useCallback((item: AttentionItem) => {
    if (item.type === 'issue') onOpenIssue?.(item.number);
    else onOpenPullRequest?.(item.number);
  }, [onOpenIssue, onOpenPullRequest]);
  return (
    <AgentHomeView
      automation={creation.automationControl}
      repository={repository}
      laneId={creation.laneId}
      providers={creation.catalog?.providers ?? []}
      bridge={live}
      tasks={creation.tasks.filter((task) => task.state === 'OPEN' && !taskArchived(task))}
      hiddenTaskIds={hidden}
      onHideTask={hide}
      onRevealTask={reveal}
      attention={attention.candidates}
      handled={attention.handled}
      onHandledChange={attention.applyHandled}
      onOpenAttention={openAttention}
      onSeeAllAttention={onOpenPullRequests ?? (() => {})}
      models={creation.models}
      selection={creation.selection}
      draft={creation.draft}
      blocker={creation.blocker}
      error={creation.error}
      busy={creation.busy}
      attaching={creation.attaching}
      images={creation.images}
      onAttach={() => { void creation.attach(); }}
      onPasteImages={() => { void creation.attach('clipboard'); }}
      onRemoveImage={creation.removeImage}
      onDraftChange={creation.setDraft}
      onSelectProvider={creation.selectProvider}
      onSelectModel={(model, effort, serviceTier) => creation.setSelection({ model, effort, serviceTier })}
      onCreate={() => { void creation.create(); }}
      onOpenTask={onOpenTask}
      onOpenSettings={onOpenSettings}
      onShowAll={onShowAll}
    />
  );
}

export function AgentHomeView({
  repository, laneId, providers, bridge, tasks, models, selection, draft, blocker,
  onDraftChange, onSelectProvider, onSelectModel, onCreate, onOpenTask, onOpenSettings, onShowAll,
  hiddenTaskIds, onHideTask, onRevealTask,
  attention = [], handled = {}, onHandledChange = () => {}, onOpenAttention = () => {}, onSeeAllAttention = () => {},
  images = [], busy = false, attaching = false, onAttach, onPasteImages, onRemoveImage, error = null, automation,
}: {
  repository: string;
  laneId: string;
  providers: ModelProviderDto[];
  bridge: () => AgentBridgePort;
  /** The open tasks, drawn as windows under the composer. */
  tasks: AgentTaskDto[];
  hiddenTaskIds?: ReadonlySet<string>;
  onHideTask?: (task: AgentTaskDto) => void;
  onRevealTask?: (task: AgentTaskDto) => void;
  /** The repository's pull requests and issues that need the user, before ack and snooze are applied. */
  attention?: AttentionItem[];
  handled?: Handled;
  onHandledChange?: (handled: Handled) => void;
  onOpenAttention?: (item: AttentionItem) => void;
  onSeeAllAttention?: () => void;
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
  error?: string | null;
  images?: DraftImage[];
  busy?: boolean;
  attaching?: boolean;
  onAttach?: () => void;
  onPasteImages?: () => void;
  onRemoveImage?: (id: string) => void;
}): React.JSX.Element {
  if (busy && !attaching) return <StartingAgentTask {...{ draft, images, laneId, providers, models, selection }} />;
  const slash = repository.indexOf('/');
  const owner = slash < 0 ? repository : repository.slice(0, slash);
  const repo = slash < 0 ? '' : repository.slice(slash + 1);
  return (
    <div className="agt-home">
      <div className="agt-home__scroll">
        <div className="agt-home__avatar">
          <RepoAvatar owner={owner} repo={repo} size={64} radius={16} />
        </div>
        <div className="agt-home__repo">{repository}</div>
        <div className="agt-home__composer">
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
        </div>
        <div className="agt-starts">
          {SUGGESTIONS.map((suggestion) => {
            const Icon = suggestion.icon;
            return (
              <button type="button" className="agt-start" key={suggestion.title} onClick={() => onDraftChange(suggestion.prompt)}>
                <span className="agt-start__icon" aria-hidden="true"><Icon size={15} strokeWidth={1.8} /></span>
                <span className="agt-start__text">{suggestion.title}</span>
              </button>
            );
          })}
        </div>
        <AttentionList
          variant="workspace"
          maxRows={HOME_MAX_ROWS}
          candidates={attention}
          handled={handled}
          onHandledChange={onHandledChange}
          onOpen={onOpenAttention}
          onSeeAll={onSeeAllAttention}
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
            hiddenTaskIds={hiddenTaskIds}
            onHide={onHideTask}
            onAttention={onRevealTask}
            onOpen={onOpenTask}
          />
        </section>
      </div>
    </div>
  );
}
