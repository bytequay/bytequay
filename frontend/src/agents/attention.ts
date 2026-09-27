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
import type { AttentionItem, AttentionKind, AttentionLink, AttentionTone } from '../home/attention';
import { displayModelName, laneLabel } from '../settings/model';
import type { AgentTaskActivityDto, AgentTaskDto, AgentTaskPullRequestDto, Bridge } from '../types';
import { taskArchived, taskStatePill } from './AgentTasksView';

/** How often a Home re-reads what its tasks wait on: a local read, frequent enough for an approval to show promptly. */
const TASK_ATTENTION_POLL_MS = 5000;

export type TaskAttentionWorkspace = { id: string; name: string; repository: string };

/** A task in the workspace it belongs to, so a pull request's card can find the task that opened it. */
export type WorkspaceTask = { task: AgentTaskDto; workspace: TaskAttentionWorkspace };

/** Why an open task waits on the user, or null while it works or rests without news. */
export function taskAttentionKind(task: AgentTaskDto, activity: AgentTaskActivityDto | null): AttentionKind | null {
  if (task.state !== 'OPEN' || taskArchived(task)) return null;
  if (activity?.pendingApproval != null) return 'approval';
  if (task.mutationClaim?.kind === 'RECOVERY') return 'failed';
  // A running turn or unfinished delivery automation will say more when it stops.
  if (task.mutationClaim != null || task.developmentContinuationUnfinished) return null;
  const turn = activity?.latestTurn ?? null;
  return turn?.state === 'FAILED' ? 'failed' : turn?.state === 'COMPLETED' ? 'finished' : null;
}

/** When the thing the card reports happened; a later one brings an acknowledged card back. */
function eventTime(task: AgentTaskDto, activity: AgentTaskActivityDto | null): string {
  return activity?.pendingApproval?.occurredAt
    ?? (task.mutationClaim?.kind === 'RECOVERY' ? task.mutationClaim.acquiredAt : null)
    ?? activity?.latestTurn?.endedAt
    ?? task.updatedAt;
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

/** The open tasks of one workspace that wait on the user, as attention cards. */
export function taskAttentionItems(
  tasks: AgentTaskDto[],
  activity: Record<string, AgentTaskActivityDto>,
  workspace: TaskAttentionWorkspace,
  /** Provider labels by lane id and model names by "lane:model", from the model catalog. */
  modelNames: ReadonlyMap<string, string> = new Map(),
): AttentionItem[] {
  return tasks.flatMap((task): AttentionItem[] => {
    const facts = activity[task.id] ?? null;
    const kind = taskAttentionKind(task, facts);
    if (kind === null) return [];
    const lane = modelNames.get(task.laneId) ?? laneLabel(task.laneId);
    const model = displayModelName(modelNames.get(`${task.laneId}:${task.defaults.model ?? ''}`) ?? '');
    return [{
      key: `task:${task.id}`,
      type: 'task',
      kind,
      task: { dto: task, activity: facts },
      reference: model === '' ? lane : `${lane} · ${model}`,
      repo: workspace.repository,
      number: 0,
      title: task.title,
      author: lane,
      authorAvatarUrl: null,
      createdAt: task.createdAt,
      updatedAt: eventTime(task, facts),
      labels: [],
      pr: null,
      body: '',
      commentCount: 0,
      workspaceId: workspace.id,
      workspaceName: workspace.name,
      links: taskLinks(task),
    }];
  });
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
): { items: AttentionItem[]; tasks: WorkspaceTask[] } {
  const [state, setState] = useState<{ items: AttentionItem[]; tasks: WorkspaceTask[] }>({ items: [], tasks: [] });
  const [modelNames, setModelNames] = useState<ReadonlyMap<string, string>>(() => new Map());
  const key = JSON.stringify(workspaces);
  useEffect(() => {
    let active = true;
    void bridge().getModelSettings().then((settings) => {
      // Provider labels by lane id, and model names by "lane:model".
      if (active) setModelNames(new Map(settings.providers.flatMap((provider) => [
        [provider.id, provider.label] as const,
        ...provider.models.map((model) => [`${provider.id}:${model.id}`, model.name] as const),
      ])));
    }).catch(() => { /* Cards name the lane alone without the catalog. */ });
    return () => { active = false; };
  }, [bridge]);
  useEffect(() => {
    const listed = JSON.parse(key) as TaskAttentionWorkspace[];
    if (listed.length === 0) {
      setState({ items: [], tasks: [] });
      return;
    }
    let active = true;
    const read = (): void => {
      if (document.hidden) return;
      void Promise.allSettled(listed.map((workspace) => Promise.all([
        bridge().listAgentTasks(workspace.id),
        bridge().listAgentTaskActivity(workspace.id),
      ]).then(([tasks, activity]) => ({ workspace, tasks, activity })))).then((results) => {
        if (!active) return;
        // A workspace that could not be read drops out until the next tick rather than failing the others.
        const loaded = results.flatMap((result) => result.status === 'fulfilled' ? [result.value] : []);
        setState({
          items: loaded.flatMap(({ workspace, tasks, activity }) => taskAttentionItems(tasks, activity, workspace, modelNames)),
          tasks: loaded.flatMap(({ workspace, tasks }) => tasks.map((task) => ({ task, workspace }))),
        });
      });
    };
    read();
    const timer = window.setInterval(read, TASK_ATTENTION_POLL_MS);
    return () => { active = false; window.clearInterval(timer); };
  }, [bridge, key, modelNames]);
  return state;
}
