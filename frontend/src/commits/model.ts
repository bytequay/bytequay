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
import type {
  CherryPickBranchCommit,
  CherryPickExecution,
  CherryPickExecutionItem,
  CherryPickStage,
  CiAutofixRunDto,
  GitCommitDto,
  WorkspaceDto,
  WorkspaceRelationshipsDto,
} from '../types';
import type { CardTone } from '@bytequay/ui/cards';
import { shortSha } from '@bytequay/ui/displayText';

/** One repository in the Commits tab's repo line. */
export type LineRepo = {
  workspaceId: string;
  name: string;
  /** `owner/repo` of its monitored project, where its pull requests live. */
  slug: string;
  defaultBranch: string;
  /** The git remote this workspace reads it through; null for this workspace and downstream ones. */
  remote: string | null;
  place: 'upstream' | 'here' | 'downstream';
  parentWorkspaceId: string | null;
  /** Continues from the repository before it; a downstream branch or an extra Fork source does not. */
  joined: boolean;
};

/** The line through this workspace, then any other Fork source it reads from. */
export function lineRepos(workspace: WorkspaceDto, relationships: WorkspaceRelationshipsDto, workspaces: WorkspaceDto[]): LineRepo[] {
  const line = relationships.line.some(node => node.workspaceId === workspace.id)
    ? relationships.line
    : [{ workspaceId: workspace.id, parentWorkspaceId: null }];
  const here = line.findIndex(node => node.workspaceId === workspace.id);
  const repo = (id: string, parentWorkspaceId: string | null, place: LineRepo['place'], joined: boolean): LineRepo => {
    const value = id === workspace.id ? workspace : workspaces.find(item => item.id === id);
    return {
      workspaceId: id,
      name: value?.name ?? id,
      slug: value ? `${value.monitoredOwner}/${value.monitoredRepo}` : id,
      defaultBranch: value?.upstreamDefaultBranch ?? value?.defaultBranch ?? 'main',
      remote: place === 'upstream' ? relationships.related.find(item => item.workspaceId === id)?.remote ?? null : null,
      place,
      parentWorkspaceId,
      joined,
    };
  };
  return [
    ...line.map((node, index) => repo(node.workspaceId, node.parentWorkspaceId,
      index < here ? 'upstream' : index === here ? 'here' : 'downstream',
      index > 0 && node.parentWorkspaceId === line[index - 1].workspaceId)),
    ...relationships.related
      .filter(item => item.remote !== null && !line.some(node => node.workspaceId === item.workspaceId))
      .map(item => repo(item.workspaceId, null, 'upstream', false)),
  ];
}

export type BackportHop = { repo: LineRepo; sourceHop: number };

/** Everything downstream of `sourceId` in the line, each copying from the source (-1) or an earlier hop. */
export function backportRoute(repos: LineRepo[], sourceId: string): BackportHop[] {
  const route: BackportHop[] = [];
  for (const repo of repos) {
    const parent = repo.parentWorkspaceId === sourceId ? -1 : route.findIndex(hop => hop.repo.workspaceId === repo.parentWorkspaceId);
    if (repo.parentWorkspaceId !== null && (repo.parentWorkspaceId === sourceId || parent >= 0)) route.push({ repo, sourceHop: parent });
  }
  return route;
}

/** The chosen hops, each kept only with the hop it copies from, renumbered for the shorter route. */
export function chosenRoute(route: BackportHop[], chosen: ReadonlySet<string>): BackportHop[] {
  const kept: BackportHop[] = [];
  for (const hop of route) {
    const from = hop.sourceHop < 0 ? null : route[hop.sourceHop].repo.workspaceId;
    const sourceHop = from === null ? -1 : kept.findIndex(item => item.repo.workspaceId === from);
    if (chosen.has(hop.repo.workspaceId) && (from === null || sourceHop >= 0)) kept.push({ repo: hop.repo, sourceHop });
  }
  return kept;
}

/**
 * Selection is always one contiguous span: the checked SHAs only anchor it,
 * and everything between the outermost anchors is effectively selected.
 * `commits` is newest-first, exactly as `git log` returns it.
 */
export function selectedSpan(commits: GitCommitDto[], checked: ReadonlySet<string>): {
  lo: number;
  hi: number;
} | null {
  let lo = -1;
  let hi = -1;
  commits.forEach((commit, index) => {
    if (!checked.has(commit.sha)) return;
    if (lo < 0 || index < lo) lo = index;
    if (index > hi) hi = index;
  });
  return lo < 0 ? null : { lo, hi };
}

/**
 * Typed from/to SHA prefixes select the same span checkboxes would.
 * Order does not matter; either input may name the newer commit.
 */
export function spanFromShas(
  commits: GitCommitDto[],
  from: string,
  to: string,
): { anchors: [string, string] } | { error: true } {
  const find = (prefix: string) => {
    const needle = prefix.trim().toLowerCase();
    if (needle === '') return -1;
    return commits.findIndex(commit => commit.sha.toLowerCase().startsWith(needle));
  };
  const first = find(from);
  const second = find(to);
  if (first < 0 || second < 0) return { error: true };
  return { anchors: [commits[first].sha, commits[second].sha] };
}

/**
 * A pasted `8fd3d32…741339a` / `8fd3d32..741339a` pair, resolved to both ends.
 * Anything else — including a prefix that names no commit — is ordinary search
 * text, so the caller keeps treating it as a query.
 */
export function pastedRangePair(
  commits: GitCommitDto[],
  value: string,
): { anchors: [string, string] } | null {
  const parts = value.split(/\.\.\.?|…/).map(part => part.trim()).filter(part => part !== '');
  if (parts.length !== 2) return null;
  const result = spanFromShas(commits, parts[0], parts[1]);
  return 'anchors' in result ? result : null;
}

/** A group's select-all, such as a day's: every item already checked clears the group, anything else fills it. */
export function toggledGroup<T>(checked: ReadonlySet<T>, items: T[]): Set<T> {
  const next = new Set(checked);
  if (items.length > 0 && items.every(item => next.has(item))) items.forEach(item => next.delete(item));
  else items.forEach(item => next.add(item));
  return next;
}

type DayGroup = { label: string; commits: Array<{ commit: GitCommitDto; index: number }> };

type LimitedDayGroup = DayGroup & { totalCommits: number };

const DAY_FORMAT = new Intl.DateTimeFormat('en-US', {
  year: 'numeric',
  month: 'short',
  day: 'numeric',
});

export function groupByDay(commits: GitCommitDto[]): DayGroup[] {
  const groups: DayGroup[] = [];
  let currentKey = '';
  commits.forEach((commit, index) => {
    const date = new Date(commit.committedAt);
    const key = Number.isNaN(date.getTime()) ? 'Undated' : DAY_FORMAT.format(date);
    if (key !== currentKey) {
      currentKey = key;
      groups.push({ label: `Commits on ${key}`, commits: [] });
    }
    groups[groups.length - 1].commits.push({ commit, index });
  });
  return groups;
}

export function limitDayGroups(groups: DayGroup[], limit: number): LimitedDayGroup[] {
  let remaining = limit;
  const visible: LimitedDayGroup[] = [];
  for (const group of groups) {
    if (remaining <= 0) break;
    const commits = group.commits.slice(0, remaining);
    visible.push({ ...group, commits, totalCommits: group.commits.length });
    remaining -= commits.length;
  }
  return visible;
}

/**
 * The branch a browsed remote source should land on, or null to stay put.
 * Until the user picks a branch themselves, the source's detected default
 * branch wins; after a pick (or with no default known), the current choice
 * only changes when the source does not have it — then default, then
 * main/master, then the first branch. Returns null while branches are
 * unknown.
 */
export function remoteBranchCorrection(
  branches: string[],
  current: string,
  defaultBranch: string | null,
  userPicked: boolean,
): string | null {
  if (branches.length === 0) return null;
  if (!userPicked && defaultBranch !== null && branches.includes(defaultBranch)) {
    return defaultBranch === current ? null : defaultBranch;
  }
  if (branches.includes(current)) return null;
  if (defaultBranch !== null && branches.includes(defaultBranch)) return defaultBranch;
  return ['main', 'master'].find(name => branches.includes(name)) ?? branches[0];
}

/** Case-insensitive commit search: title, PR title, PR #number, or SHA prefix. */
export function matchesCommitQuery(commit: GitCommitDto, query: string): boolean {
  const needle = query.trim().toLowerCase();
  if (needle === '') return true;
  const bare = needle.startsWith('#') ? needle.slice(1) : needle;
  const pullNumber = commit.pullRequestNumber ?? null;
  return commit.title.toLowerCase().includes(needle)
    || (commit.pullRequestTitle ?? '').toLowerCase().includes(needle)
    || (bare !== '' && commit.sha.toLowerCase().startsWith(bare))
    || (bare !== '' && pullNumber !== null && String(pullNumber).startsWith(bare));
}

/** Default combined message for squashing a span (oldest → newest). */
export function squashMessage(spanNewestFirst: GitCommitDto[]): string {
  if (spanNewestFirst.length === 0) return '';
  const newest = spanNewestFirst[0];
  const rest = spanNewestFirst.slice(1);
  return `${newest.title}\n\n${rest.map(commit => `* ${commit.title}`).join('\n')}`
    + `\n\nSquashed: ${[...spanNewestFirst].reverse().map(commit => commit.sha.slice(0, 7)).join(', ')}`;
}

export type TaskState = 'running' | 'paused' | 'done' | 'canceled';

export function taskState(execution: CherryPickExecution): TaskState {
  if (execution.status === 'RUNNING') return 'running';
  if (execution.status === 'PAUSED') return 'paused';
  return execution.finishReason === 'CANCELLED' ? 'canceled' : 'done';
}

export function isOpenTask(execution: CherryPickExecution): boolean {
  const state = taskState(execution);
  return state === 'running' || state === 'paused';
}

/** Running and paused tasks always sort first; order is otherwise preserved. */
export function orderTasks(executions: CherryPickExecution[]): CherryPickExecution[] {
  return [...executions.filter(isOpenTask), ...executions.filter(task => !isOpenTask(task))];
}

export type TaskFilter = 'all' | 'open' | 'done';

export function filterTasks(executions: CherryPickExecution[], filter: TaskFilter): CherryPickExecution[] {
  if (filter === 'all') return executions;
  return executions.filter(task => (filter === 'open' ? isOpenTask(task) : !isOpenTask(task)));
}
export function completedExecutionItems(items: CherryPickExecutionItem[]): number {
  return items.filter(item => item.result !== 'PENDING').length;
}

export { shortSha };

/** The stages a delivering cherry-pick walks through, in order. */
export const CHERRY_PICK_STAGES: ReadonlyArray<{ stage: CherryPickStage; name: string }> = [
  { stage: 'APPLY', name: 'Local apply & conflicts' },
  { stage: 'BUILD', name: 'Local build & tests' },
  { stage: 'PUBLISH', name: 'Push work branch' },
  { stage: 'CI_FIX', name: 'Remote CI fix' },
  { stage: 'SQUASH', name: 'Squash fixups' },
  { stage: 'MERGE', name: 'CI green → merge' },
];

export type StageState = 'done' | 'running' | 'paused' | 'needs-you' | 'pending';

export type StageView = { stage: CherryPickStage; name: string; state: StageState; detail: string; at: string | null };

function count(amount: number, singular: string, plural = `${singular}s`): string {
  return `${amount} ${amount === 1 ? singular : plural}`;
}

export function greenRound(run: CiAutofixRunDto | undefined): boolean {
  return run?.status === 'FINISHED' && run.finishReason === 'GREEN';
}

/** Fixups still on the branch, then the ones a squash already folded. */
function everyFixup(execution: CherryPickExecution): CherryPickBranchCommit[] {
  const lifecycle = execution.lifecycle;
  return [...lifecycle.commits.filter(commit => commit.fixupOf), ...(lifecycle.squash?.resultHead ? lifecycle.squash.fixups : [])];
}

/** What the task waits on the user for now, if anything. */
export function cherryPickWaitsFor(execution: CherryPickExecution, rounds: CiAutofixRunDto[]): 'resume' | 'squash' | 'merge' | null {
  const lifecycle = execution.lifecycle;
  if (execution.status === 'FINISHED') return null;
  if (execution.status === 'PAUSED' || lifecycle.hold) return 'resume';
  if (lifecycle.stage === 'SQUASH' && !lifecycle.squash && lifecycle.commits.some(commit => commit.fixupOf)) return 'squash';
  if (lifecycle.stage === 'MERGE' && greenRound(rounds.at(-1)) && !lifecycle.commits.some(commit => commit.fixupOf)) return 'merge';
  return null;
}

function stoppedDetail(execution: CherryPickExecution): string {
  const hold = execution.lifecycle.hold;
  if (hold) return hold.reason === 'USER' ? 'Paused by you' : hold.summary;
  return execution.summary ?? 'Paused — needs your input';
}

function runningDetail(execution: CherryPickExecution, rounds: CiAutofixRunDto[]): string {
  const lifecycle = execution.lifecycle;
  const fixups = lifecycle.commits.filter(commit => commit.fixupOf).length;
  const live = lifecycle.rebases.find(rebase => rebase.state === 'RUNNING');
  const round = rounds.at(-1);
  switch (lifecycle.stage) {
    case 'APPLY': {
      const position = execution.currentPosition ?? null;
      if (position === null) return 'Applying commits oldest first';
      return `Applying ${position} of ${execution.items.length}${execution.currentStep === 'RESOLVING' ? ' · resolving a conflict' : ''}`;
    }
    case 'BUILD': return 'Building and testing the touched modules';
    case 'PUBLISH': return 'Pushing the work branch and opening the draft pull request';
    case 'CI_FIX':
      if (live) return `Held for auto-rebase #${live.number} · ${execution.targetBranch} moved +${live.targetMoved}`;
      return round?.status === 'ACTIVE' ? `Round ${rounds.length} · attempt ${Math.max(1, round.attemptsUsed)} of ${round.authorization.maxAttempts}` : 'Starting CI Autofix';
    case 'SQUASH':
      return lifecycle.squash ? `Folding ${count(fixups, 'fixup')}` : `Squash ${count(fixups, 'fixup')} into their source commits — waiting for your approval`;
    case 'MERGE':
      return greenRound(round) ? 'CI green on the squashed head — ready for you to merge' : 'CI running on the squashed head';
  }
}

function doneDetail(stage: CherryPickStage, execution: CherryPickExecution, rounds: CiAutofixRunDto[]): string {
  const lifecycle = execution.lifecycle;
  const fixups = everyFixup(execution);
  switch (stage) {
    case 'APPLY': {
      const applied = execution.items.filter(item => item.result === 'APPLIED').length;
      const conflicts = fixups.filter(commit => commit.origin === 'CONFLICT').length;
      return `${count(applied, 'commit')}${conflicts > 0 ? ` · ${count(conflicts, 'conflict')} resolved` : ''}`;
    }
    case 'BUILD': {
      const repairs = fixups.filter(commit => commit.origin === 'BUILD').length;
      return repairs > 0 ? `${count(repairs, 'fixup')} from the build` : 'Built and tested · no repairs needed';
    }
    case 'PUBLISH': return execution.delivery?.pullRequestNumber ? `Draft PR #${execution.delivery.pullRequestNumber}` : 'Work branch pushed';
    case 'CI_FIX': {
      const repairs = fixups.filter(commit => commit.origin === 'CI').length;
      const rebases = lifecycle.rebases.filter(rebase => rebase.state === 'DONE').length;
      return [`Green after ${count(rounds.length, 'round')}`, repairs > 0 ? count(repairs, 'fixup') : null,
        rebases > 0 ? count(rebases, 'auto-rebase') : null].filter(part => part !== null).join(' · ');
    }
    case 'SQUASH': {
      const folded = lifecycle.squash?.resultHead ? lifecycle.squash.fixups.length : 0;
      return folded > 0 ? `${count(folded, 'fixup')} squashed into ${count(lifecycle.commits.length, 'commit')}` : 'No fixups to squash';
    }
    case 'MERGE': return `Merged into ${execution.targetBranch}`;
  }
}

function doneAt(stage: CherryPickStage, execution: CherryPickExecution, rounds: CiAutofixRunDto[]): string | null {
  switch (stage) {
    case 'APPLY': return execution.createdAt;
    case 'CI_FIX': return rounds.find(greenRound)?.updatedAt ?? null;
    case 'SQUASH': return execution.lifecycle.squash?.approvedAt ?? null;
    case 'MERGE': return execution.terminalAt;
    default: return null;
  }
}

const PENDING_DETAIL: Partial<Record<CherryPickStage, string>> = {
  CI_FIX: 'Fix and push until green',
  SQUASH: 'Needs your approval',
  MERGE: 'You merge',
};

/** Each stage as the task shows it; an execution a backport chain started only applies. */
export function cherryPickStages(execution: CherryPickExecution, rounds: CiAutofixRunDto[]): StageView[] {
  const lifecycle = execution.lifecycle;
  const stages = lifecycle.delivers ? CHERRY_PICK_STAGES : CHERRY_PICK_STAGES.slice(0, 1);
  const current = CHERRY_PICK_STAGES.findIndex(entry => entry.stage === lifecycle.stage);
  const merged = execution.status === 'FINISHED' && execution.finishReason !== 'CANCELLED';
  const waits = cherryPickWaitsFor(execution, rounds);
  return stages.map(({ stage, name }, index): StageView => {
    if (merged || index < current) return { stage, name, state: 'done', detail: doneDetail(stage, execution, rounds), at: doneAt(stage, execution, rounds) };
    if (index > current) return { stage, name, state: 'pending', detail: PENDING_DETAIL[stage] ?? '', at: null };
    if (execution.status === 'FINISHED') return { stage, name, state: 'pending', detail: 'Aborted', at: execution.terminalAt };
    if (waits === 'resume') {
      return { stage, name, state: lifecycle.hold?.reason === 'USER' ? 'paused' : 'needs-you', detail: stoppedDetail(execution), at: null };
    }
    return { stage, name, state: waits === null ? 'running' : 'needs-you', detail: runningDetail(execution, rounds), at: null };
  });
}

/** The pill a task shows in its header and on the task list. */
export function cherryPickStatus(execution: CherryPickExecution, rounds: CiAutofixRunDto[]): { label: string; tone: CardTone } {
  const lifecycle = execution.lifecycle;
  if (execution.status === 'FINISHED') {
    if (execution.finishReason === 'CANCELLED') return { label: 'Aborted', tone: 'red' };
    return { label: lifecycle.delivers ? 'Merged' : 'Applied', tone: 'green' };
  }
  if (lifecycle.hold?.reason === 'USER') return { label: 'Paused', tone: 'neutral' };
  const waits = cherryPickWaitsFor(execution, rounds);
  if (waits === 'resume') return { label: 'Needs you', tone: 'amber' };
  if (waits === 'squash') return { label: 'Needs your approval', tone: 'amber' };
  if (waits === 'merge') return { label: 'Ready to merge', tone: 'green' };
  if (lifecycle.rebases.some(rebase => rebase.state === 'RUNNING')) return { label: 'Auto-rebasing', tone: 'purple' };
  switch (lifecycle.stage) {
    case 'APPLY': return { label: 'Local apply', tone: 'blue' };
    case 'BUILD': return { label: 'Local build', tone: 'blue' };
    case 'PUBLISH': return { label: 'Publishing', tone: 'blue' };
    case 'CI_FIX': return { label: `CI fix · round ${Math.max(1, rounds.length)}`, tone: 'blue' };
    case 'SQUASH': return { label: 'Squashing', tone: 'blue' };
    case 'MERGE': return { label: 'CI on squashed head', tone: 'blue' };
  }
}

/** The header badge: how far the target moved and how often the branch followed it. */
export function driftBadge(execution: CherryPickExecution): string | null {
  const rebases = execution.lifecycle.rebases;
  const live = rebases.find(rebase => rebase.state === 'RUNNING');
  if (live) return `rebasing · target +${live.targetMoved}`;
  const done = rebases.filter(rebase => rebase.state === 'DONE');
  if (done.length === 0) return null;
  return `target +${done.reduce((total, rebase) => total + rebase.targetMoved, 0)} · auto-rebased ${done.length}×`;
}

export type CommitRowState = 'applied' | 'resolving' | 'queued' | 'skipped' | 'fixup' | 'squashed';

export type CommitRow = { key: string; kind: 'source' | 'fixup'; state: CommitRowState; title: string; shas: string; tag: string };

const ORIGIN_LABEL: Record<NonNullable<CherryPickBranchCommit['origin']>, string> = {
  CONFLICT: 'conflict resolution', BUILD: 'local build', CI: 'CI fix',
};

/** The plan in order, each copied commit with the fixups that belong to it. */
export function commitRows(execution: CherryPickExecution): { interleaved: CommitRow[]; sources: CommitRow[]; fixups: CommitRow[] } {
  const lifecycle = execution.lifecycle;
  const squashed = lifecycle.squash?.resultHead ? lifecycle.squash.fixups : [];
  const current = lifecycle.commits.filter(commit => commit.fixupOf);
  const shaOf = (item: CherryPickExecutionItem) => lifecycle.commits.find(commit => commit.sourceSha === item.sourceSha)?.sha ?? item.targetSha ?? null;
  const fixupRows = (sourceSha: string): CommitRow[] => [
    ...current.filter(commit => commit.fixupOf === sourceSha).map((commit): CommitRow => ({
      key: commit.sha, kind: 'fixup', state: 'fixup', title: commit.subject,
      shas: `${shortSha(commit.sha)} · from ${commit.origin ? ORIGIN_LABEL[commit.origin] : 'the agent'}`, tag: 'local',
    })),
    ...squashed.filter(commit => commit.fixupOf === sourceSha).map((commit): CommitRow => {
      const into = lifecycle.commits.find(entry => entry.sourceSha === sourceSha)?.sha;
      return { key: commit.sha, kind: 'fixup', state: 'squashed', title: commit.subject,
        shas: `${shortSha(commit.sha)} · squashed${into ? ` into ${shortSha(into)}` : ''}`, tag: 'squashed' };
    }),
  ];
  const sources = execution.items.map((item): CommitRow => {
    const active = item.result === 'PENDING' && execution.currentPosition === item.position && execution.status !== 'FINISHED';
    const conflicted = [...current, ...squashed].some(commit => commit.fixupOf === item.sourceSha && commit.origin === 'CONFLICT');
    const target = shaOf(item);
    const base = { key: item.sourceSha, kind: 'source' as const, title: `${item.position}. ${item.title}` };
    switch (item.result) {
      case 'APPLIED': return { ...base, state: 'applied', shas: target ? `${shortSha(item.sourceSha)} → ${shortSha(target)}` : shortSha(item.sourceSha),
        tag: conflicted ? 'conflict resolved' : 'clean' };
      case 'USER_SKIPPED': return { ...base, state: 'skipped', shas: shortSha(item.sourceSha), tag: 'skipped by you' };
      case 'ALREADY_ON_TARGET': return { ...base, state: 'skipped', shas: shortSha(item.sourceSha), tag: 'already on target' };
      case 'EMPTY_SKIPPED': return { ...base, state: 'skipped', shas: shortSha(item.sourceSha), tag: 'empty' };
      default: return { ...base, state: active ? 'resolving' : 'queued', shas: shortSha(item.sourceSha),
        tag: active ? execution.currentStep === 'RESOLVING' ? 'resolving conflict' : 'applying' : 'queued' };
    }
  });
  return {
    interleaved: execution.items.flatMap((item, index) => [sources[index], ...fixupRows(item.sourceSha)]),
    sources,
    fixups: execution.items.flatMap(item => fixupRows(item.sourceSha)),
  };
}

export function commitSummary(execution: CherryPickExecution): string {
  const lifecycle = execution.lifecycle;
  const applied = execution.items.filter(item => item.result === 'APPLIED').length;
  if (lifecycle.stage === 'APPLY' && execution.status !== 'FINISHED') return `${applied} of ${execution.items.length} applied · oldest first`;
  if (lifecycle.squash?.resultHead) return `${count(applied, 'source commit')} · ${count(lifecycle.squash.fixups.length, 'fixup')} squashed`;
  return `${count(applied, 'source commit')} · ${count(lifecycle.commits.filter(commit => commit.fixupOf).length, 'local fixup')}`;
}

export type AttemptRow = { key: string; state: 'failed' | 'passed' | 'running' | 'stopped'; title: string; detail: string; at: string | null };

/** Every CI attempt across the task's CI rounds, oldest first; a round without attempts is one row. */
export function ciAttemptRows(rounds: CiAutofixRunDto[]): AttemptRow[] {
  return rounds.flatMap((round, index): AttemptRow[] => {
    const label = `Round ${index + 1}`;
    const ended = round.status !== 'ACTIVE';
    const rows = round.attempts.map((attempt): AttemptRow => ({
      key: `${round.id}:${attempt.number}`,
      state: attempt.observedCiResult === 'FAILED' ? 'failed' : attempt.observedCiResult === 'PASSED' ? 'passed' : ended ? 'stopped' : 'running',
      title: `${label} · attempt ${attempt.number}`,
      detail: [attempt.failures.slice(0, 2).join(', '), attempt.pushedSha ? `fixup ${shortSha(attempt.pushedSha)}` : null]
        .filter(part => part !== null && part !== '').join(' → '),
      at: attempt.updatedAt,
    }));
    if (rows.length > 0 && !greenRound(round)) return rows;
    const outcome: AttemptRow = greenRound(round)
      ? { key: round.id, state: 'passed', title: `${label} · green`, detail: round.summary ?? 'Every check passed', at: round.updatedAt }
      : round.status === 'ACTIVE'
        ? { key: round.id, state: 'running', title: `${label} · watching checks`, detail: round.summary ?? '', at: null }
        : { key: round.id, state: 'stopped', title: `${label} · ${round.finishReason === 'CANCELLED' ? 'cancelled' : 'stopped'}`, detail: round.summary ?? '', at: round.updatedAt };
    return [...rows, outcome];
  });
}
