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
import { QUESTION_TOOL, type AttentionItem, type AttentionKind, type AttentionLink, type AttentionTone } from '../home/attention';
import { isDeepSeekKeyLane, laneLabel } from '../settings/model';
import type { AgentQueuedInputDto, AgentTaskActivityDto, AgentTaskDto, AgentTaskPullRequestDto, Bridge } from '../types';
import { pollVisible } from '../ide/pollVisible';
import { taskArchived, taskStatePill } from './taskStatus';

/** How often a Home re-reads what its tasks wait on: a local read, frequent enough for an approval to show promptly. */
const TASK_ATTENTION_POLL_MS = 5000;

export type TaskAttentionWorkspace = { id: string; name: string; repository: string };

/** A task in the workspace it belongs to, so a pull request's card can find the task that opened it. */
export type WorkspaceTask = { task: AgentTaskDto; workspace: TaskAttentionWorkspace };

/** The latest state of every visible task; archived tasks stay out of Home. */
export function taskAttentionKind(task: AgentTaskDto, activity: AgentTaskActivityDto | null): AttentionKind | null {
  if (taskArchived(task)) return null;
  if (task.state !== 'OPEN') return task.state === 'FAILED' ? 'failed' : 'finished';
  if (activity?.pendingApproval != null) return 'approval';
  if (task.mutationClaim?.kind === 'RECOVERY') return 'failed';
  if (task.mutationClaim != null || task.developmentContinuationUnfinished) return 'updated';
  const turn = activity?.latestTurn ?? null;
  return turn?.state === 'FAILED' ? 'failed' : turn?.state === 'COMPLETED' ? 'finished' : 'updated';
}

export type TaskCardState = {
  label: 'Needs you' | 'Running' | 'Idle' | 'Completed' | 'Finished' | 'Failed';
  tone: 'orange' | 'blue' | 'green' | 'neutral' | 'red';
  spinning: boolean;
  /** Why a task needs the user, when it does. */
  reason: string | null;
};

/** A task's state as its card names it: waiting on the user first, whatever else it is doing. */
export function taskCardState(task: AgentTaskDto, activity: AgentTaskActivityDto | null): TaskCardState {
  const approval = activity?.pendingApproval ?? null;
  if (task.state === 'OPEN' && approval !== null) {
    const reason = approval.scope.includes(QUESTION_TOOL) ? 'Question for you'
      : approval.approvalKind === 'COMMAND' ? 'Approval needed to run a command' : 'Approval needed';
    return { label: 'Needs you', tone: 'orange', spinning: false, reason };
  }
  if (task.state === 'OPEN' && task.mutationClaim?.kind === 'RECOVERY') {
    return { label: 'Needs you', tone: 'orange', spinning: false, reason: 'Could not be shown to have stopped' };
  }
  if (task.state === 'OPEN' && (task.mutationClaim?.kind === 'TURN' || task.developmentContinuationUnfinished)) {
    return { label: 'Running', tone: 'blue', spinning: true, reason: null };
  }
  if (task.state === 'OPEN') return { label: 'Idle', tone: 'green', spinning: false, reason: null };
  return task.state === 'FAILED'
    ? { label: 'Failed', tone: 'red', spinning: false, reason: null }
    : { label: 'Completed', tone: 'neutral', spinning: false, reason: null };
}

/**
 * The message a task card quotes. A task's generated first request reads as the request it stands
 * for, never as the prompt ByteQuay wrote for the provider.
 */
export function taskLastMessage(task: AgentTaskDto, activity: AgentTaskActivityDto | null): AgentTaskActivityDto['lastMessage'] {
  const message = activity?.lastMessage ?? null;
  const initialTurn = task.issueSource?.initialTurnId ?? task.jiraSource?.initialTurnId ?? null;
  if (message === null || message.author !== 'USER' || initialTurn === null || message.turnId !== initialTurn) return message;
  const source = task.jiraSource != null ? task.jiraSource.key : task.issueSource != null ? `${task.issueSource.repo}#${task.issueSource.number}` : '';
  return { ...message, text: `Start development · ${source}` };
}

/** When the thing the card reports happened; a later one brings an acknowledged card back. */
function eventTime(task: AgentTaskDto, activity: AgentTaskActivityDto | null): string {
  return [activity?.pendingApproval?.occurredAt, task.mutationClaim?.acquiredAt, activity?.latestTurn?.endedAt, activity?.lastMessage?.at]
    .reduce<string>((latest, at) => at != null && Date.parse(at) > Date.parse(latest) ? at : latest, task.updatedAt);
}

function pullRequestStatus(pullRequest: AgentTaskPullRequestDto): { status: string; tone: AttentionTone } {
  if (pullRequest.state === 'merged') return { status: 'Merged', tone: 'purple' };
  if (pullRequest.state === 'closed') return { status: 'Closed', tone: 'neutral' };
  return pullRequest.draft ? { status: 'Draft', tone: 'neutral' } : { status: 'Open', tone: 'green' };
}

/** A task's ticket, pull request and issue, in the order its card names them. */
export function taskLinks(task: AgentTaskDto): AttentionLink[] {
  const links: AttentionLink[] = [];
  if (task.jiraSource != null) links.push({ source: 'jira', key: task.jiraSource.key, title: task.jiraSource.title, status: null, tone: 'neutral' });
  if (task.pullRequest != null) links.push({ source: 'pr', key: `#${task.pullRequest.number}`, title: task.pullRequest.title, ...pullRequestStatus(task.pullRequest) });
  if (task.issueSource != null) links.push({ source: 'issue', key: `#${task.issueSource.number}`, title: task.issueSource.title, status: null, tone: 'neutral' });
  return links;
}

/** A task as another card's link names it. */
export function taskLink(task: AgentTaskDto): AttentionLink {
  const pill = taskStatePill(task);
  const tone: AttentionTone = pill.className === 'is-running' ? 'blue' : pill.className === 'is-idle' ? 'green' : pill.className === '' ? 'neutral' : 'red';
  return { source: 'task', key: 'Task', title: task.title, status: pill.label, tone };
}

/** Every unarchived task in one workspace, as an update card. */
export function taskAttentionItems(
  tasks: AgentTaskDto[],
  activity: Record<string, AgentTaskActivityDto>,
  workspace: TaskAttentionWorkspace,
  /** Provider labels by lane id, from the model catalog. */
  laneNames: ReadonlyMap<string, string> = new Map(),
): AttentionItem[] {
  return tasks.flatMap((task): AttentionItem[] => {
    const facts = activity[task.id] ?? null;
    return taskAttentionKind(task, facts) === null ? [] : [taskAttentionItem(task, facts, workspace, laneNames)];
  });
}

/** Detail views use the same identity and activity revision even while a task needs no attention. */
export function taskAttentionItem(
  task: AgentTaskDto,
  activity: AgentTaskActivityDto | null,
  workspace: TaskAttentionWorkspace,
  laneNames: ReadonlyMap<string, string> = new Map(),
): AttentionItem {
  const provider = laneNames.get(task.laneId) ?? laneLabel(task.laneId);
  // A card names the lane without its model, and a DeepSeek key lane by the key: "DeepSeek · ds-001" reads "ds-001".
  const lane = isDeepSeekKeyLane(task.laneId) && provider.includes(' · ') ? provider.slice(provider.indexOf(' · ') + 3) : provider;
  return {
    key: `task:${task.id}`,
    type: 'task',
    kind: taskAttentionKind(task, activity) ?? 'updated',
    task: { dto: task, activity },
    reference: lane,
    repo: workspace.repository,
    number: 0,
    title: task.title,
    author: lane,
    authorAvatarUrl: null,
    createdAt: task.createdAt,
    updatedAt: eventTime(task, activity),
    labels: [],
    pr: null,
    body: '',
    commentCount: 0,
    workspaceId: workspace.id,
    workspaceName: workspace.name,
    links: taskLinks(task),
  };
}

/** Queue creation advances the task at the returned timestamp, before the detail reload can finish. */
export function taskAttentionAfterQueue(item: AttentionItem | null, queued: Pick<AgentQueuedInputDto, 'taskId' | 'createdAt'>): AttentionItem | undefined {
  if (item?.task === undefined || item.task.dto.id !== queued.taskId) return undefined;
  if (Date.parse(queued.createdAt) <= Date.parse(item.task.dto.updatedAt)) return item;
  return { ...item, updatedAt: Date.parse(queued.createdAt) > Date.parse(item.updatedAt) ? queued.createdAt : item.updatedAt,
    task: { ...item.task, dto: { ...item.task.dto, updatedAt: queued.createdAt } } };
}

/** Ties each pull request's card to the tasks whose branch opened it. */
export function linkTasks(items: AttentionItem[], tasks: WorkspaceTask[]): AttentionItem[] {
  return items.map((item) => {
    if (item.type !== 'pr') return item;
    const linked = tasks.filter(({ task, workspace }) =>
      task.pullRequest?.number === item.number && workspace.repository.toLowerCase() === item.repo.toLowerCase());
    return linked.length === 0 ? item : { ...item, links: linked.map(({ task }) => taskLink(task)) };
  });
}

type TaskAttentionBridge = Pick<Bridge, 'listAgentTasks' | 'listAgentTaskActivity' | 'getModelSettings'>;

export type TaskAttention = {
  items: AttentionItem[];
  tasks: WorkspaceTask[];
  /** What each read task last said and waits on, by task id. */
  activity: Record<string, AgentTaskActivityDto>;
  /** Reads again now, after a change this page made itself. */
  refresh: () => void;
};

/** One function for every render, so the reads below do not restart on each one. */
const windowBridge = (): TaskAttentionBridge => window.bridge;

/**
 * The attention items of every listed workspace's tasks, re-read on a short tick while the page is
 * visible, and the tasks themselves for linking. Every read is local: the task list and what each
 * task last said and waits on.
 */
export function useTaskAttention(
  workspaces: TaskAttentionWorkspace[],
  bridge: () => TaskAttentionBridge = windowBridge,
): TaskAttention {
  const [state, setState] = useState<Omit<TaskAttention, 'refresh'>>({ items: [], tasks: [], activity: {} });
  const [tick, setTick] = useState(0);
  const refresh = useCallback(() => setTick((count) => count + 1), []);
  const [laneNames, setLaneNames] = useState<ReadonlyMap<string, string>>(() => new Map());
  const key = JSON.stringify(workspaces);
  useEffect(() => {
    let active = true;
    const listed = JSON.parse(key) as TaskAttentionWorkspace[];
    void Promise.allSettled(listed.map(workspace => bridge().getModelSettings(workspace.id))).then(results => {
      if (active) setLaneNames(new Map(results.flatMap(result => result.status === 'fulfilled'
        ? result.value.providers.map(provider => [provider.id, provider.label] as const) : [])));
    });
    return () => { active = false; };
  }, [bridge, key]);
  useEffect(() => {
    const listed = JSON.parse(key) as TaskAttentionWorkspace[];
    if (listed.length === 0) {
      setState({ items: [], tasks: [], activity: {} });
      return;
    }
    let active = true;
    const read = () =>
      Promise.allSettled(listed.map((workspace) => Promise.all([
        bridge().listAgentTasks(workspace.id),
        bridge().listAgentTaskActivity(workspace.id),
      ]).then(([tasks, activity]) => ({ workspace, tasks, activity })))).then((results) => {
        if (!active) return;
        // A workspace that could not be read drops out until the next tick rather than failing the others.
        const loaded = results.flatMap((result) => result.status === 'fulfilled' ? [result.value] : []);
        setState({
          items: loaded.flatMap(({ workspace, tasks, activity }) => taskAttentionItems(tasks, activity, workspace, laneNames)),
          tasks: loaded.flatMap(({ workspace, tasks }) => tasks.map((task) => ({ task, workspace }))),
          activity: Object.assign({}, ...loaded.map(({ activity }) => activity)),
        });
      });
    const stop = pollVisible(read, TASK_ATTENTION_POLL_MS);
    return () => { active = false; stop(); };
  }, [bridge, key, laneNames, tick]);
  return { ...state, refresh };
}

/**
 * One task's card state for a page that is not the task's own, re-read on the attention tick while the
 * page is visible. Null without a task, and until the first read lands.
 */
export function useTaskCardState(workspaceId: string | null, taskId: string | null): TaskCardState | null {
  const [read, setRead] = useState<{ taskId: string; state: TaskCardState } | null>(null);
  useEffect(() => {
    if (workspaceId === null || taskId === null) return;
    let active = true;
    // A failed read, even a synchronous one, keeps the last state; the next tick reads again.
    const stop = pollVisible(() => Promise.resolve().then(() => Promise.all([window.bridge.getAgentTask(taskId, workspaceId), window.bridge.listAgentTaskActivity(workspaceId)]))
      .then(([task, activity]) => { if (active) setRead({ taskId, state: taskCardState(task, activity[taskId] ?? null) }); })
      .catch(() => {}), TASK_ATTENTION_POLL_MS);
    return () => { active = false; stop(); };
  }, [workspaceId, taskId]);
  return read !== null && read.taskId === taskId ? read.state : null;
}

/** How many of a workspace's tasks wait on an answer or approval from the user: zero without a workspace and until the first read lands. */
export function useTasksWaiting(workspaceId: string | null): number {
  const [read, setRead] = useState<{ workspaceId: string; count: number } | null>(null);
  useEffect(() => {
    if (workspaceId === null) return;
    let active = true;
    const stop = pollVisible(() => Promise.resolve().then(() => window.bridge.listAgentTaskActivity(workspaceId))
      .then(activity => { if (active) setRead({ workspaceId, count: Object.values(activity).filter(facts => facts.pendingApproval != null).length }); })
      .catch(() => {}), TASK_ATTENTION_POLL_MS);
    return () => { active = false; stop(); };
  }, [workspaceId]);
  return read !== null && read.workspaceId === workspaceId ? read.count : 0;
}
