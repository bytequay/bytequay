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
import { plural } from '@bytequay/ui/displayText';
import { taskCardState, taskLastMessage, type WorkspaceTask } from '../agents/attention';
import { taskArchived, taskStatePill } from '../agents/taskStatus';
import { pullRequestIsMine } from '../pulls/model';
import { laneLabel } from '../settings/model';
import type { AgendaTargetDto, AgentTaskActivityDto, AgentTaskDto, AuthoredIssueDto, PullRequestDto } from '../types';
import type { WorkItemDto } from '../workitems/contracts';
import type { AttentionItem, AttentionTone } from './attention';
import { attentionKey } from './today';

/** Where a piece of work comes from; it picks the card's glyph and filter tab. */
export type WorkSource = 'jira' | 'issue' | 'local' | 'pr';

export const WORK_SOURCES: Record<WorkSource, { label: string; filter: string }> = {
  jira: { label: 'Jira', filter: 'Jira' },
  issue: { label: 'Issue', filter: 'Issues' },
  local: { label: 'Local', filter: 'Local' },
  pr: { label: 'PR', filter: 'PR' },
};

export type WorkLane = 'progress' | 'review' | 'needs' | 'ready';

export const WORK_LANES: { id: WorkLane; name: string; hint: string }[] = [
  { id: 'progress', name: 'In progress', hint: 'no PR or draft' },
  { id: 'review', name: 'In review', hint: 'waiting on others' },
  { id: 'needs', name: 'Needs you', hint: 'your move' },
  { id: 'ready', name: 'Ready to merge', hint: '' },
];

/** A pull request a piece of work delivers through. */
export type WorkPr = {
  repo: string;
  number: number;
  title: string;
  state: 'open' | 'draft' | 'merged' | 'closed';
  /** The stored row when the attention list holds it; a task's own record names only its state. */
  row: PullRequestDto | null;
};

export type WorkItem = {
  key: string;
  source: WorkSource;
  /** ENG-1, repository#42, or nothing for local work. */
  label: string;
  title: string;
  updatedAt: string;
  target: AgendaTargetDto;
  /** The task working on it: the running one, else the one waiting on you, else the newest open one. */
  task: AgentTaskDto | null;
  running: { laneId: string; agent: string; text: string; since: string } | null;
  /** The branch an open task works on while there is no pull request yet. */
  branch: string | null;
  prs: WorkPr[];
  lane: WorkLane;
  /** One line on what to do, only when it is the viewer's move. */
  next: string | null;
  /** Every Needs attention and Today key this work stands for: its own, its pull requests' and its tasks'. */
  keys: string[];
  /** How the work ended, such as Done, Closed or Merged; null while it is open. Only All my work lists ended work. */
  ended: string | null;
  /** The repositories it touches: its own, its pull requests' and its tasks'. */
  repos: string[];
  /** Every agent that worked on it. */
  agents: string[];
  /** An issue the viewer opened and nobody assigned to them: it waits on others. */
  authored?: true;
};

function target(kind: AgendaTargetDto['kind'], fields: Partial<AgendaTargetDto>): AgendaTargetDto {
  return { kind, workspaceId: null, repo: null, number: null, id: null, connectionId: null, cloudId: null, ...fields };
}

const prKey = (repo: string, number: number) => `${repo}#${number}`.toLowerCase();

const repoName = (repo: string) => repo.slice(repo.indexOf('/') + 1);

function prState(pr: Pick<PullRequestDto, 'state' | 'draft'>): WorkPr['state'] {
  const state = pr.state?.toLowerCase();
  return state === 'merged' || state === 'closed' ? state : pr.draft ? 'draft' : 'open';
}

function needsYou(row: PullRequestDto): boolean {
  return row.reviewDecision === 'CHANGES_REQUESTED' || row.ciStatus === 'FAILING' || row.mergeableState === 'dirty';
}

function readyToMerge(pr: WorkPr): boolean {
  const row = pr.row;
  return pr.state === 'open' && row !== null && row.reviewDecision === 'APPROVED' && row.mergeable === true && row.mergeableState === 'clean'
    && row.ciStatus !== 'FAILING' && row.ciStatus !== 'PENDING';
}

/** Where a pull request stands, as the second line of its row says it. */
export function workPrStatus(pr: WorkPr): { text: string; tone: AttentionTone } {
  if (pr.state === 'merged') return { text: 'Merged', tone: 'purple' };
  if (pr.state === 'closed') return { text: 'Closed', tone: 'neutral' };
  if (pr.state === 'draft') return { text: 'Draft', tone: 'neutral' };
  const row = pr.row;
  if (row === null) return { text: 'Open', tone: 'neutral' };
  if (row.reviewDecision === 'CHANGES_REQUESTED') return { text: 'Changes requested', tone: 'red' };
  if (row.ciStatus === 'FAILING') {
    const failing = row.failingChecks?.length ?? 0;
    return { text: failing === 0 ? 'Checks failing' : `${plural(failing, 'check')} failing`, tone: 'red' };
  }
  if (row.mergeableState === 'dirty') return { text: 'Merge conflicts', tone: 'red' };
  if (row.reviewDecision === 'APPROVED') return { text: 'Approved', tone: 'green' };
  return { text: row.requestedReviewers.length === 0 ? 'Waiting on review' : `Waiting on ${row.requestedReviewers.length}`, tone: 'amber' };
}

/** The first lane whose rule holds: ready to merge, needs you, in review, else in progress. */
export function workLane(prs: WorkPr[], waiting: boolean): WorkLane {
  const live = prs.filter(pr => pr.state === 'open' || pr.state === 'draft');
  if (live.length > 0 && live.every(readyToMerge)) return 'ready';
  if (waiting || live.some(pr => pr.row !== null && pr.state === 'open' && needsYou(pr.row))) return 'needs';
  return live.some(pr => pr.state === 'open') ? 'review' : 'progress';
}

function nextAction(lane: WorkLane, prs: WorkPr[], waiting: string | null): string | null {
  if (lane === 'ready') return 'Merge · no conflicts';
  if (lane !== 'needs') return null;
  if (waiting !== null) return waiting;
  const row = prs.find(pr => pr.row !== null && pr.state === 'open' && needsYou(pr.row))?.row;
  if (row === undefined || row === null) return null;
  if (row.reviewDecision === 'CHANGES_REQUESTED') return 'Address the requested changes';
  if (row.ciStatus === 'FAILING') return row.failingChecks?.[0] === undefined ? 'Fix the failing checks' : `Fix ${row.failingChecks[0]}`;
  return 'Resolve the merge conflicts';
}

const later = (left: string, right: string) => Date.parse(right) > Date.parse(left) ? right : left;

/**
 * Builds one piece of work from what it is and the tasks working on it. Pull requests come from the tasks' records, read in full from
 * the attention rows when they hold them, plus any the work names itself.
 */
function workItem(
  base: Pick<WorkItem, 'key' | 'source' | 'label' | 'title' | 'updatedAt' | 'target' | 'ended'>,
  tasks: WorkspaceTask[],
  named: WorkPr[],
  rows: ReadonlyMap<string, PullRequestDto>,
  activity: Record<string, AgentTaskActivityDto>,
): WorkItem {
  const prs = new Map(named.map(pr => [prKey(pr.repo, pr.number), pr]));
  for (const { task, workspace } of tasks) {
    const pr = task.pullRequest;
    if (pr == null || prs.has(prKey(workspace.repository, pr.number))) continue;
    const row = rows.get(prKey(workspace.repository, pr.number)) ?? null;
    prs.set(prKey(workspace.repository, pr.number), { repo: workspace.repository, number: pr.number, title: pr.title, state: prState(row ?? pr), row });
  }
  const open = tasks.map(({ task }) => ({ task, state: taskCardState(task, activity[task.id] ?? null) })).filter(entry => entry.task.state === 'OPEN')
    .sort((left, right) => Number(right.state.spinning) - Number(left.state.spinning)
      || Number(right.state.label === 'Needs you') - Number(left.state.label === 'Needs you')
      || Date.parse(right.task.updatedAt) - Date.parse(left.task.updatedAt));
  const chosen = open[0] ?? null;
  const task = chosen?.task ?? null;
  const waiting = chosen !== null && chosen.state.label === 'Needs you' ? chosen.state.reason : null;
  const message = task === null ? null : taskLastMessage(task, activity[task.id] ?? null);
  const list = [...prs.values()];
  const lane = workLane(list, waiting !== null);
  return {
    ...base,
    updatedAt: [...tasks.map(({ task: linked }) => linked.updatedAt), ...list.flatMap(pr => pr.row === null ? [] : [pr.row.updatedAt])].reduce(later, base.updatedAt),
    task,
    running: task === null || chosen?.state.spinning !== true ? null : {
      laneId: task.laneId, agent: laneLabel(task.laneId), since: task.mutationClaim?.acquiredAt ?? task.updatedAt,
      text: message?.author === 'AGENT' ? message.text.split('\n')[0] : 'Working',
    },
    branch: list.length === 0 ? task?.generatedBranch ?? null : null,
    prs: list,
    lane,
    next: nextAction(lane, list, waiting),
    keys: [attentionKey(base.target), ...list.map(pr => prKey(pr.repo, pr.number)), ...tasks.map(({ task: linked }) => `task:${linked.id}`)]
      .filter((key): key is string => key !== null).map(key => key.toLowerCase()),
    repos: [...new Set([base.target.repo, ...list.map(pr => pr.repo), ...tasks.map(({ workspace }) => workspace.repository)]
      .filter((repo): repo is string => repo !== null))],
    agents: [...new Set(tasks.map(({ task: linked }) => laneLabel(linked.laneId)))],
  };
}

/** Whether an attention item is the viewer's own work, which My Work lists rather than Needs attention. */
export function isOwnWork(item: AttentionItem, viewerLogin: string | null): boolean {
  if (item.task !== undefined || item.jira !== undefined) return true;
  if (item.pr !== null) return pullRequestIsMine(item.pr, viewerLogin);
  return item.kind === 'assigned' || viewerLogin !== null && item.author?.toLowerCase() === viewerLogin.toLowerCase();
}

/**
 * Everything the viewer is working on: open Jira tickets and GitHub issues assigned to them, issues they opened anywhere, local work in
 * progress, open tasks of their own, and their open pull requests that none of those already deliver. Each task and pull request belongs
 * to one piece of work.
 */
export function myWorkItems({ candidates, tasks, activity, work, viewerLogin, authored = [], ended = false }: {
  candidates: AttentionItem[];
  tasks: WorkspaceTask[];
  activity: Record<string, AgentTaskActivityDto>;
  /** Local work items of every watched repository. */
  work: WorkItemDto[];
  viewerLogin: string | null;
  /** Open issues the viewer opened in any repository and that changed in the last thirty days. */
  authored?: AuthoredIssueDto[];
  /** Also lists done tickets, closed issues and pull requests, finished local work, and ended or archived tasks, as All my work does. */
  ended?: boolean;
}): WorkItem[] {
  const rows = new Map(candidates.flatMap(item => item.pr === null ? [] : [[prKey(item.repo, item.number), item.pr] as const]));
  const live = ended ? tasks : tasks.filter(({ task }) => !taskArchived(task));
  const claimed = new Set<string>();
  const claim = (linked: WorkspaceTask[]) => {
    linked.forEach(({ task }) => claimed.add(task.id));
    return linked;
  };
  const items: WorkItem[] = [];

  for (const item of candidates) {
    const jira = item.jira;
    if (jira === undefined || !ended && jira.status.category === 'done') continue;
    const linked = claim(live.filter(({ task }) => task.id === jira.task?.id || task.jiraSource?.cloudId === jira.cloudId && task.jiraSource.issueId === jira.id));
    items.push(workItem({
      key: item.key, source: 'jira', label: jira.key, title: item.title, updatedAt: item.updatedAt,
      ended: jira.status.category === 'done' ? jira.status.name : null,
      target: target('JIRA', { id: jira.id, connectionId: jira.connectionId, cloudId: jira.cloudId }),
    }, linked, [], rows, activity));
  }

  for (const item of candidates) {
    if (item.type !== 'issue' || item.jira !== undefined || item.kind !== 'assigned' || !ended && item.issueState === 'closed') continue;
    const linked = claim(live.filter(({ task, workspace }) => task.issueSource?.number === item.number && workspace.repository.toLowerCase() === item.repo.toLowerCase()));
    items.push(workItem({
      key: item.key, source: 'issue', label: `${repoName(item.repo)}#${item.number}`, title: item.title, updatedAt: item.updatedAt,
      ended: item.issueState === 'closed' ? 'Closed' : null,
      target: target('ISSUE', { workspaceId: item.workspaceId, repo: item.repo, number: item.number }),
    }, linked, [], rows, activity));
  }

  // An issue assigned to the viewer is their work and is already listed; one they only opened waits on others.
  const listedIssues = new Set(items.flatMap(item => item.source === 'issue' ? [item.key.toLowerCase()] : []));
  for (const issue of authored) {
    const key = `${issue.repo}#${issue.number}`;
    if (listedIssues.has(key.toLowerCase())) continue;
    const workspaceId = candidates.find(item => item.type === 'issue' && item.key.toLowerCase() === key.toLowerCase())?.workspaceId ?? null;
    const linked = claim(live.filter(({ task, workspace }) => task.issueSource?.number === issue.number
      && workspace.repository.toLowerCase() === issue.repo.toLowerCase()));
    const card = workItem({
      key, source: 'issue', label: `${repoName(issue.repo)}#${issue.number}`, title: issue.title, updatedAt: issue.updatedAt, ended: null,
      target: target('ISSUE', { workspaceId, repo: issue.repo, number: issue.number }),
    }, linked, [], rows, activity);
    items.push({ ...card, lane: card.lane === 'progress' ? 'review' : card.lane, authored: true });
  }

  for (const entry of work) {
    if (entry.isEpic || entry.state === 'PLANNED' || !ended && entry.state !== 'WORKING') continue;
    const taskIds = new Set(entry.links.filter(link => link.kind === 'TASK').map(link => link.key));
    const linked = claim(live.filter(({ task }) => taskIds.has(task.id)));
    const named = entry.links.flatMap((link): WorkPr[] => {
      if (link.kind !== 'PULL_REQUEST') return [];
      const row = rows.get(link.key.toLowerCase());
      if (row !== undefined) return [{ repo: row.repo, number: row.number, title: row.title, state: prState(row), row }];
      const split = link.key.lastIndexOf('#');
      return [{ repo: link.key.slice(0, split), number: Number(link.key.slice(split + 1)), title: link.title, row: null,
        state: link.state === 'MERGED' ? 'merged' : link.state === 'ENDED' ? 'closed' : 'open' }];
    });
    items.push(workItem({
      key: `work:${entry.id}`, source: 'local', label: '', title: entry.title, updatedAt: entry.updatedAt,
      ended: entry.state === 'DONE' ? 'Done' : entry.state === 'DROPPED' ? 'Dropped' : null,
      target: target('WORK', { workspaceId: entry.workspaceId, id: entry.id }),
    }, linked, named, rows, activity));
  }

  for (const entry of live) {
    if (claimed.has(entry.task.id) || !ended && entry.task.state !== 'OPEN') continue;
    claim([entry]);
    items.push(workItem({
      key: `task:${entry.task.id}`, source: 'local', label: '', title: entry.task.title, updatedAt: entry.task.updatedAt,
      ended: entry.task.state !== 'OPEN' ? taskStatePill(entry.task).label : taskArchived(entry.task) ? 'Archived' : null,
      target: target('TASK', { workspaceId: entry.task.workspaceId, id: entry.task.id }),
    }, [entry], [], rows, activity));
  }

  const delivered = new Set(items.flatMap(item => item.prs.map(pr => prKey(pr.repo, pr.number))));
  for (const item of candidates) {
    const pr = item.pr;
    if (pr === null || !ended && pr.state !== 'open' || !pullRequestIsMine(pr, viewerLogin) || delivered.has(prKey(item.repo, item.number))) continue;
    const linked = live.filter(({ task, workspace }) => !claimed.has(task.id) && task.pullRequest?.number === item.number
      && workspace.repository.toLowerCase() === item.repo.toLowerCase());
    items.push(workItem({
      key: item.key, source: 'pr', label: `${repoName(item.repo)}#${item.number}`, title: item.title, updatedAt: item.updatedAt,
      ended: prState(pr) === 'merged' ? 'Merged' : prState(pr) === 'closed' ? 'Closed' : null,
      target: target('PULL', { repo: item.repo, number: item.number }),
    }, claim(linked), [{ repo: item.repo, number: item.number, title: item.title, state: prState(pr), row: pr }], rows, activity));
  }

  return items.sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt));
}
