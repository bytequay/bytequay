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
import { CircleDot, Eye, EyeOff, ListChecks, Pencil, SquareCheck, type LucideIcon } from 'lucide-react';
import RepoAvatar from '../RepoAvatar';
import AttentionList, { type GoalToggle } from '../home/AttentionList';
import { Card, CardActions, IconButton, InGoalBadge, Pill } from '../cards/Card';
import type { AttentionItem, Handled } from '../home/attention';
import type { WorkspaceAttention } from '../workspaces/workspaceAttention';
import { zoomReference } from '../githubReference';
import { jiraIssueLocation } from '../jira/contracts';
import { relativeTime } from '../relativeTime';
import { useRenameDraft } from '../renameDraft';
import { displayModelName, laneLabel } from '../settings/model';
import { BackportButton } from './BackportRequestHint';
import type { AgentReviewDto } from '../pulls/agent-review/contracts';
import {
  ComposerDock, StartingAgentTask, TaskComposer, taskArchived, useAgentTaskCreation, useTaskChanges, useWorkspaceWork, type DraftImage, type TaskModelSelection,
} from './AgentTasksView';
import type { TaskAttention } from './attention';
import { developmentItem, reviewItem, type TaskListItem } from './taskList';
import type { AgentBridgePort } from './model';
import type {
  AgentTaskActivityDto, AgentTaskChangesDto, AgentTaskDto, AgentTaskStartupPhase, ModelOptionDto, ModelProviderDto,
} from '../types';

/** Starting points that fill the composer; the user edits or sends them as they are. */
const SUGGESTIONS: { icon: LucideIcon; title: string; prompt: string }[] = [
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

/**
 * Whether a task the user put away asks to come back. Only attention that started after they put it
 * away does, so hiding a task that already wants the user is not undone on the spot.
 */
export function wantsAttention(hiddenAt: number | null, recoveryAt: number | null, approvalAt: number | null): boolean {
  return hiddenAt !== null && Math.max(recoveryAt ?? 0, approvalAt ?? 0) > hiddenAt;
}

function saveHiddenTasks(workspaceId: string, hidden: ReadonlyMap<string, number>): void {
  try {
    window.localStorage.setItem(hiddenTasksKey(workspaceId), JSON.stringify(Object.fromEntries(hidden)));
  }
  catch { /* A full or disabled store just means the window comes back next time. */ }
}

interface AgentHomeProps {
  workspaceId: string;
  headerAction?: React.ReactNode;
  /** "owner/repo", the monitored repository the page belongs to. */
  repository: string;
  /** The workspace's tasks and what they wait on, read once for all its pages. */
  taskAttention: TaskAttention;
  /** What needs the user in this workspace, read once for all its pages. */
  attention: WorkspaceAttention;
  /** The pinned goal each attention card can join or leave. */
  goal?: (item: AttentionItem) => GoalToggle | null;
  /** Whether a task is in the pinned goal. */
  inGoal?: (task: AgentTaskDto) => boolean;
  bridge?: AgentBridgePort;
  onOpenTask: (taskId: string) => void;
  onOpenReview: (review: AgentReviewDto) => void;
  onOpenSettings?: () => void;
  onShowAll: () => void;
  onOpenPullRequest?: (number: number) => void;
  onOpenIssue?: (number: number) => void;
  onStartIssue?: (number: number) => void;
}

export default function AgentHome({
  workspaceId, headerAction, repository, taskAttention, attention, goal, inGoal, bridge: explicitBridge, onOpenTask, onOpenReview, onOpenSettings, onShowAll, onOpenPullRequest, onOpenIssue, onStartIssue,
}: AgentHomeProps): React.JSX.Element {
  const live = useCallback((): AgentBridgePort => explicitBridge ?? window.bridge, [explicitBridge]);
  const creation = useAgentTaskCreation({ workspaceId, bridge: live, onOpenTask });
  // The polled list is fresher; until its first read lands, the list this page last painted stands in.
  const listed = taskAttention.tasks.length > 0 ? taskAttention.tasks.map((entry) => entry.task) : creation.tasks;
  const openTasks = useMemo(() => listed.filter((task) => task.state === 'OPEN' && !taskArchived(task)), [listed]);
  const changes = useTaskChanges(workspaceId, openTasks, live);
  const work = useWorkspaceWork(workspaceId, live);
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
  // A task put away from Home comes back on its own when it needs the user after that.
  useEffect(() => {
    for (const task of openTasks) {
      const approval = taskAttention.activity[task.id]?.pendingApproval ?? null;
      const recoveryAt = task.mutationClaim?.kind === 'RECOVERY' ? Date.parse(task.mutationClaim.acquiredAt) : null;
      if (wantsAttention(hidden.get(task.id) ?? null, recoveryAt, approval === null ? null : Date.parse(approval.occurredAt))) reveal(task);
    }
  }, [hidden, openTasks, reveal, taskAttention.activity]);
  const { refresh } = taskAttention;
  const rename = useCallback((task: AgentTaskDto, title: string) => {
    void live().renameAgentTask(task.id, task.workspaceId, task.version, title).then(refresh).catch(() => {});
  }, [live, refresh]);
  const openAttention = useCallback((item: AttentionItem) => {
    if (item.task) onOpenTask(item.task.dto.id);
    else if (item.jira) zoomReference({ kind: 'jira', ...jiraIssueLocation(item.jira) });
    else if (item.type === 'issue') onOpenIssue?.(item.number);
    else onOpenPullRequest?.(item.number);
  }, [onOpenIssue, onOpenPullRequest, onOpenTask]);
  return (
    <AgentHomeView
      headerAction={headerAction}
      {...creation.composer}
      repository={repository}
      workspaceId={workspaceId}
      tasks={openTasks}
      reviews={work.reviews}
      activity={taskAttention.activity}
      changes={changes}
      hiddenTasks={hidden}
      onHideTask={hide}
      onRenameTask={rename}
      attention={attention.candidates}
      handled={attention.handled}
      viewerLogin={attention.login}
      jiraConnected={attention.jiraConnected}
      goal={goal}
      inGoal={inGoal}
      onHandledChange={attention.applyHandled}
      onOpenAttention={openAttention}
      onStartIssue={onStartIssue ? item => {
        if (item.jira) zoomReference({ kind: 'jira', ...jiraIssueLocation(item.jira), start: true });
        else onStartIssue(item.number);
      } : undefined}
      attentionError={attention.error}
      error={creation.error ?? (work.failed.includes('reviews') ? 'Could not read reviews; trying again.' : null)}
      startup={creation.startup}
      onOpenTask={onOpenTask}
      onOpenReview={onOpenReview}
      onOpenSettings={onOpenSettings}
      onShowAll={onShowAll}
    />
  );
}

export function AgentHomeView({
  repository, workspaceId = '', headerAction, laneId, providers, tasks, reviews = [], models, selection, draft, blocker,
  onDraftChange, onSelectProvider, onSelectModel, onCreate, onOpenTask, onOpenReview, onOpenSettings, onShowAll,
  activity = {}, changes = {}, hiddenTasks, onHideTask, onRenameTask, now = Date.now(),
  attention = [], handled = {}, viewerLogin = null, onHandledChange = () => {}, onOpenAttention = () => {},
  onStartIssue, attentionError, goal, inGoal, jiraConnected = false,
  images = [], busy = false, startup = null, attaching = false, onAttach, onPasteImages, onRemoveImage, error = null, automation,
}: {
  repository: string;
  workspaceId?: string;
  laneId: string;
  providers: ModelProviderDto[];
  /** The open tasks, drawn as compact cards above the composer. */
  tasks: AgentTaskDto[];
  reviews?: AgentReviewDto[];
  /** What each task last said and waits on, by task id. */
  activity?: Record<string, AgentTaskActivityDto>;
  /** What each task's checkout holds, by task id, for cards without a pull request to name. */
  changes?: Record<string, AgentTaskChangesDto>;
  /** Which cards the user put away, each with when. */
  hiddenTasks?: ReadonlyMap<string, number>;
  onHideTask?: (task: AgentTaskDto) => void;
  onRenameTask?: (task: AgentTaskDto, title: string) => void;
  /** The clock the cards' ages are drawn against. */
  now?: number;
  /** The repository's pull requests and issues that need the user, before ack and snooze are applied. */
  attention?: AttentionItem[];
  handled?: Handled;
  viewerLogin?: string | null;
  onHandledChange?: (handled: Handled) => void;
  onOpenAttention?: (item: AttentionItem) => void;
  onStartIssue?: (item: AttentionItem) => void;
  attentionError?: string | null;
  goal?: (item: AttentionItem) => GoalToggle | null;
  inGoal?: (task: AgentTaskDto) => boolean;
  /** Whether Jira is connected, so Needs attention offers a Jira tab. */
  jiraConnected?: boolean;
  models: ModelOptionDto[];
  selection: TaskModelSelection | null;
  draft: string;
  blocker: string | null;
  onDraftChange: (draft: string) => void;
  onSelectProvider: (laneId: string) => void;
  onSelectModel: (model: string, effort: string | null, serviceTier: string | null) => void;
  onCreate: () => void;
  onOpenTask: (taskId: string) => void;
  onOpenReview: (review: AgentReviewDto) => void;
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
  const items = [
    ...tasks.map(task => developmentItem(task, activity[task.id] ?? null, changes[task.id] ?? null, providers)),
    ...reviews.map(reviewItem).filter(item => item.state.label === 'Running' || item.state.label === 'Needs you'),
  ].sort((left, right) => Number(right.state.spinning) - Number(left.state.spinning));
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
          scope={{ workspaceId, repository }}
          candidates={attention}
          handled={handled}
          viewerLogin={viewerLogin}
          jiraConnected={jiraConnected}
          onHandledChange={onHandledChange}
          onOpen={onOpenAttention}
          onStartIssue={onStartIssue}
          goal={goal}
        />
        <section className="agt-open-tasks" aria-label="Open tasks">
          <div className="agt-open-tasks__head">
            <span className="agt-label">Open tasks · {items.length}</span>
            <span className="agt-spacer" />
            <button type="button" className="agt-link" onClick={onShowAll}>
              Show all <kbd className="agt-kbd">{'⇧⌘\\'}</kbd>
            </button>
          </div>
          <div className="agt-open-grid">
            {items.filter(item => item.task === null || !hiddenTasks?.has(item.task.id)).map(item => {
              const { task, review } = item;
              return (
                <HomeTaskCard
                  key={item.key}
                  item={item}
                  providers={providers}
                  now={now}
                  inGoal={task !== null && (inGoal?.(task) ?? false)}
                  onOpen={() => { if (review !== null) onOpenReview(review); else if (task !== null) onOpenTask(task.id); }}
                  onRename={task === null || onRenameTask === undefined ? undefined : (title) => onRenameTask(task, title)}
                  onHide={task === null || onHideTask === undefined ? undefined : () => onHideTask(task)}
                />
              );
            })}
          </div>
        </section>
        <ComposerDock repository={repository} chips={<>
          <BackportButton draft={draft} onDraftChange={onDraftChange} disabled={busy} />
          {SUGGESTIONS.map((suggestion) => {
            const Icon = suggestion.icon;
            return (
              <button type="button" className="agt-start" key={suggestion.title} onClick={() => onDraftChange(suggestion.prompt)}>
                <Icon size={13} strokeWidth={1.9} aria-hidden="true" />
                {suggestion.title}
              </button>
            );
          })}
        </>}>
          {error !== null && <div className="cmt-error" role="alert">{error}</div>}
          <TaskComposer
            backport
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

/**
 * One open task as a compact card: its name and state, what it last said or why it waits on the
 * user, and its lane, age and what its checkout holds. Development cards offer Rename and Hide from Home.
 */
function HomeTaskCard({ item, providers, now, inGoal, onOpen, onRename, onHide }: {
  item: TaskListItem;
  providers: ModelProviderDto[];
  now: number;
  inGoal: boolean;
  onOpen: () => void;
  onRename?: (title: string) => void;
  onHide?: () => void;
}): React.JSX.Element {
  const { task, review, title, state } = item;
  const rename = useRenameDraft(title, onRename);
  const laneId = task?.laneId ?? review?.task.laneId ?? '';
  const modelId = task?.defaults.model ?? review?.task.model;
  const provider = providers.find((candidate) => candidate.id === laneId);
  const model = displayModelName(provider?.models.find((option) => option.id === modelId)?.name ?? modelId ?? '');
  const meta = [review === null ? '' : 'Review', provider?.label ?? review?.task.laneLabel ?? laneLabel(laneId), model, relativeTime(item.at, { suffix: false, now })]
    .filter((part) => part !== '').join(' · ');
  const pullRequest = task?.pullRequest ?? null;
  const change = pullRequest !== null ? `#${pullRequest.number} ${pullRequest.draft ? 'draft' : pullRequest.state}` : item.change;
  const Icon = review === null ? SquareCheck : Eye;
  return (
    <Card compact className="agt-open-card" label={`Open ${review === null ? 'task' : 'review'}: ${title}`} onOpen={onOpen}>
      <div className="agt-open-card__head">
        <Icon className="agt-open-card__icon" size={14} strokeWidth={1.8} aria-hidden="true" />
        {rename.editing ? (
          <input className="agt-open-card__rename" {...rename.inputProps} onClick={(event) => event.stopPropagation()} />
        ) : (
          <span className="agt-open-card__title" title={onRename === undefined ? title : 'Double-click to rename'}
            onDoubleClick={(event) => { event.stopPropagation(); rename.start(); }}>
            {title}
          </span>
        )}
        {!rename.editing && (onRename !== undefined || onHide !== undefined) && (
          <CardActions>
            {onRename !== undefined && (
              <IconButton label={`Rename task: ${title}`} title="Rename" onClick={() => rename.start()}>
                <Pencil size={13} aria-hidden="true" />
              </IconButton>
            )}
            {onHide !== undefined && (
              <IconButton label={`Hide task from Home: ${title}`} title="Hide from Home (task stays in Tasks)" onClick={onHide}>
                <EyeOff size={13} aria-hidden="true" />
              </IconButton>
            )}
          </CardActions>
        )}
        {inGoal && <InGoalBadge />}
        <Pill tone={state.tone} spinning={state.spinning}>{state.label}</Pill>
      </div>
      <div className={`agt-open-card__last${state.reason === null ? '' : ' is-needs'}`}>{state.reason ?? item.message?.text ?? ''}</div>
      <div className="agt-open-card__foot">
        <span className="agt-open-card__meta">{meta}</span>
        {change !== null && <span className={`agt-open-card__change${pullRequest === null ? '' : ' is-pr'}`}>{change}</span>}
      </div>
    </Card>
  );
}
