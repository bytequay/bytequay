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
import { describe, expect, it } from 'vitest';
import type { CherryPickExecution, CiAutofixRunDto, GitCommitDto } from '../types';
import { CI_RUN } from '../agents/fixtures/ciAutofix';
import { workspaceFixture } from '../workspaces/fixtures/workspace';
import { lifecycle } from './fixtures/lifecycle';
import {
  backportRoute,
  ciAttemptRows,
  cherryPickStages,
  cherryPickStatus,
  cherryPickWaitsFor,
  commitRows,
  driftBadge,
  chosenRoute,
  filterTasks,
  lineRepos,
  groupByDay,
  limitDayGroups,
  matchesCommitQuery,
  orderTasks,
  remoteBranchCorrection,
  selectedSpan,
  pastedRangePair,
  spanFromShas,
  toggledGroup,
  squashMessage,
  taskState,
} from './model';

function commit(
  sha: string,
  authoredAt = '2026-08-20T10:00:00Z',
  committedAt = authoredAt,
): GitCommitDto {
  return {
    sha,
    title: `Commit ${sha}`,
    body: '',
    author: 'dev',
    committer: 'dev',
    authoredAt,
    committedAt,
  };
}

const COMMITS = [commit('aaaa111'), commit('bbbb222'), commit('cccc333'), commit('dddd444')];

function base(status: string, finishReason?: string): CherryPickExecution {
  return {
    executionId: `exec-${status}-${finishReason ?? ''}`,
    workspaceId: 'workspace-1',
    repository: 'bytequay/fork',
    workBranch: 'bytequay/cp-1',
    localTargetBranch: 'master',
    targetBranch: 'master',
    status,
    finishReason: finishReason ?? null,
    initialTargetSha: '41ab9e2aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
    currentTargetSha: '41ab9e2aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
    items: [],
    createdAt: '2026-09-01T10:00:00Z',
    updatedAt: '2026-09-01T10:00:00Z',
    terminalAt: null,
    lifecycle: lifecycle(),
  };
}

describe('selectedSpan', () => {
  it('spans everything between the outermost checked commits', () => {
    expect(selectedSpan(COMMITS, new Set(['aaaa111', 'dddd444']))).toEqual({ lo: 0, hi: 3 });
    expect(selectedSpan(COMMITS, new Set(['bbbb222']))).toEqual({ lo: 1, hi: 1 });
    expect(selectedSpan(COMMITS, new Set())).toBeNull();
  });

  it('ignores checked SHAs no longer in the list', () => {
    expect(selectedSpan(COMMITS, new Set(['gone000']))).toBeNull();
  });
});

describe('spanFromShas', () => {
  it('accepts prefixes in either order', () => {
    expect(spanFromShas(COMMITS, 'dddd', 'aaaa')).toEqual({ anchors: ['dddd444', 'aaaa111'] });
    expect(spanFromShas(COMMITS, 'aaaa', 'dddd')).toEqual({ anchors: ['aaaa111', 'dddd444'] });
  });

  it('flags SHAs not on the branch', () => {
    expect(spanFromShas(COMMITS, 'aaaa', 'ffff')).toEqual({ error: true });
    expect(spanFromShas(COMMITS, '', 'aaaa')).toEqual({ error: true });
  });
});

describe('toggledGroup', () => {
  it('fills a day that is not fully checked, and clears one that is', () => {
    expect([...toggledGroup(new Set(), ['a', 'b'])]).toEqual(['a', 'b']);
    // A partly checked day fills rather than clears, so one stray tick cannot empty it.
    expect([...toggledGroup(new Set(['a']), ['a', 'b'])]).toEqual(['a', 'b']);
    expect([...toggledGroup(new Set(['a', 'b']), ['a', 'b'])]).toEqual([]);
  });

  it('leaves commits outside the day alone', () => {
    expect([...toggledGroup(new Set(['z', 'a', 'b']), ['a', 'b'])]).toEqual(['z']);
    expect([...toggledGroup(new Set(['z']), [])]).toEqual(['z']);
  });
});

describe('pastedRangePair', () => {
  it.each(['aaaa…dddd', 'aaaa..dddd', 'aaaa...dddd', '  aaaa … dddd  '])('reads a pasted pair: %s', value => {
    expect(pastedRangePair(COMMITS, value)).toEqual({ anchors: ['aaaa111', 'dddd444'] });
  });

  it('leaves ordinary search text alone', () => {
    // One term, three terms, and a pair naming a commit that is not here.
    expect(pastedRangePair(COMMITS, 'aaaa')).toBeNull();
    expect(pastedRangePair(COMMITS, 'aaaa..bbbb..cccc')).toBeNull();
    expect(pastedRangePair(COMMITS, 'aaaa…ffff')).toBeNull();
    expect(pastedRangePair(COMMITS, '')).toBeNull();
  });
});

describe('groupByDay', () => {
  it('groups consecutive commits by committed date when author dates differ', () => {
    const commits = [
      commit('a1', '2026-08-20T10:00:00Z', '2026-08-24T10:00:00Z'),
      commit('a2', '2026-08-15T08:00:00Z', '2026-08-24T08:00:00Z'),
      commit('b1', '2026-08-20T06:00:00Z', '2026-08-23T10:00:00Z'),
    ];
    const groups = groupByDay(commits);
    expect(groups).toHaveLength(2);
    expect(groups[0].label).toMatch(/^Commits on /);
    expect(groups[0].commits.map(entry => entry.commit.sha)).toEqual(['a1', 'a2']);
    expect(groups[1].commits.map(entry => entry.index)).toEqual([2]);
  });

  it('limits rows across day groups without changing each day total', () => {
    const groups = groupByDay([
      commit('a1', '2026-08-24T10:00:00Z'),
      commit('a2', '2026-08-24T08:00:00Z'),
      commit('b1', '2026-08-23T10:00:00Z'),
      commit('b2', '2026-08-23T08:00:00Z'),
    ]);

    expect(limitDayGroups(groups, 3).map(group => ({
      shas: group.commits.map(entry => entry.commit.sha),
      totalCommits: group.totalCommits,
    }))).toEqual([
      { shas: ['a1', 'a2'], totalCommits: 2 },
      { shas: ['b1'], totalCommits: 2 },
    ]);
  });
});

describe('remoteBranchCorrection', () => {
  it('stays put while branches are unknown', () => {
    expect(remoteBranchCorrection([], 'master', 'main', false)).toBeNull();
  });

  it('steers to the detected default branch until the user picks one', () => {
    expect(remoteBranchCorrection(['main', 'master'], 'master', 'main', false)).toBe('main');
    expect(remoteBranchCorrection(['main', 'master'], 'main', 'main', false)).toBeNull();
    expect(remoteBranchCorrection(['main', 'master'], 'master', 'main', true)).toBeNull();
  });

  it('replaces a branch the source does not have: default, then main/master, then first', () => {
    expect(remoteBranchCorrection(['dev', 'release'], 'master', 'release', true)).toBe('release');
    expect(remoteBranchCorrection(['dev', 'main'], 'master', null, false)).toBe('main');
    expect(remoteBranchCorrection(['dev', 'master'], 'main', null, true)).toBe('master');
    expect(remoteBranchCorrection(['dev', 'release'], 'master', null, false)).toBe('dev');
  });
});

describe('matchesCommitQuery', () => {
  const subject: GitCommitDto = {
    sha: 'f4627c7abcdef',
    title: 'Update MongoDB image versions',
    body: '',
    author: 'dev',
    committer: 'dev',
    authoredAt: '2026-08-21T10:00:00Z',
    committedAt: '2026-08-21T10:00:00Z',
    pullRequestNumber: 30825,
    pullRequestTitle: 'Update MongoDB image versions in tests',
  };

  it('matches title, PR title, PR #number, and SHA prefix', () => {
    expect(matchesCommitQuery(subject, '')).toBe(true);
    expect(matchesCommitQuery(subject, 'mongodb IMAGE')).toBe(true);
    expect(matchesCommitQuery(subject, 'in tests')).toBe(true);
    expect(matchesCommitQuery(subject, '#30825')).toBe(true);
    expect(matchesCommitQuery(subject, '308')).toBe(true);
    expect(matchesCommitQuery(subject, 'F4627')).toBe(true);
  });

  it('rejects non-matches, including a lone #', () => {
    expect(matchesCommitQuery(subject, 'kafka')).toBe(false);
    expect(matchesCommitQuery(subject, '#99999')).toBe(false);
    expect(matchesCommitQuery(subject, '627c7')).toBe(false);
    expect(matchesCommitQuery(commit('aaaa111'), '#')).toBe(false);
  });
});

describe('squashMessage', () => {
  it('leads with the newest title and keeps the original SHAs', () => {
    const message = squashMessage([commit('bbbb222'), commit('aaaa111')]);
    expect(message.startsWith('Commit bbbb222\n')).toBe(true);
    expect(message).toContain('* Commit aaaa111');
    expect(message).toContain('Squashed: aaaa111, bbbb222');
  });
});

const execution = base;

describe('task ordering and filtering', () => {
  const done = execution('FINISHED');
  const canceled = execution('FINISHED', 'CANCELLED');
  const running = execution('RUNNING');
  const paused = execution('PAUSED');

  it('maps execution status to display state', () => {
    expect(taskState(running)).toBe('running');
    expect(taskState(paused)).toBe('paused');
    expect(taskState(done)).toBe('done');
    expect(taskState(canceled)).toBe('canceled');
  });

  it('keeps running and paused tasks first', () => {
    expect(orderTasks([done, running, canceled, paused]).map(taskState))
      .toEqual(['running', 'paused', 'done', 'canceled']);
  });

  it('filters open versus done', () => {
    const all = [done, running, canceled, paused];
    expect(filterTasks(all, 'open').map(taskState)).toEqual(['running', 'paused']);
    expect(filterTasks(all, 'done').map(taskState)).toEqual(['done', 'canceled']);
    expect(filterTasks(all, 'all')).toHaveLength(4);
  });
});

describe('backport routes', () => {
  // oss → internal → here → product, and oss → sibling; `extra` is another Fork source outside the line.
  const here = workspaceFixture({ id: 'here' });
  const repos = lineRepos(here, {
    links: [],
    related: [
      { workspaceId: 'internal', position: 'UPSTREAM', remote: 'backport-2', unavailableReason: null },
      { workspaceId: 'oss', position: 'UPSTREAM', remote: 'backport-1', unavailableReason: null },
      { workspaceId: 'extra', position: 'UPSTREAM', remote: 'backport-9', unavailableReason: null },
    ],
    line: [
      { workspaceId: 'oss', parentWorkspaceId: null },
      { workspaceId: 'internal', parentWorkspaceId: 'oss' },
      { workspaceId: 'here', parentWorkspaceId: 'internal' },
      { workspaceId: 'product', parentWorkspaceId: 'here' },
      { workspaceId: 'tools', parentWorkspaceId: 'here' },
    ],
  }, []);
  const ids = (route: ReturnType<typeof backportRoute>) => route.map(hop => `${hop.repo.workspaceId}<${hop.sourceHop}`);

  it('runs from the source down the line, each hop copying from the one it forks', () => {
    expect(ids(backportRoute(repos, 'oss'))).toEqual(['internal<-1', 'here<0', 'product<1', 'tools<1']);
    expect(ids(backportRoute(repos, 'here'))).toEqual(['product<-1', 'tools<-1']);
    expect(backportRoute(repos, 'product')).toEqual([]);
    expect(backportRoute(repos, 'extra')).toEqual([]);
  });

  it('drops a hop whose source is left out and renumbers the rest', () => {
    const route = backportRoute(repos, 'oss');
    expect(ids(chosenRoute(route, new Set(['internal', 'here'])))).toEqual(['internal<-1', 'here<0']);
    expect(ids(chosenRoute(route, new Set(['internal', 'product', 'tools'])))).toEqual(['internal<-1']);
    expect(ids(chosenRoute(route, new Set(['internal', 'here', 'tools'])))).toEqual(['internal<-1', 'here<0', 'tools<1']);
  });
});

describe('cherry-pick lifecycle', () => {
  const sha = (digit: string) => digit.repeat(40);
  const delivering = (overrides: Partial<CherryPickExecution['lifecycle']>, execution: Partial<CherryPickExecution> = {}): CherryPickExecution => ({
    ...base('RUNNING'),
    items: [
      { position: 1, sourceSha: sha('1'), title: 'Port change', result: 'APPLIED', targetSha: sha('5') },
      { position: 2, sourceSha: sha('2'), title: 'Skip me', result: 'USER_SKIPPED' },
    ],
    delivery: { pushRemote: 'origin', head: 'cp-1', sha: sha('6'), pullRequestNumber: 31 },
    ...execution,
    lifecycle: lifecycle({ delivers: true, ...overrides }),
  });
  const copy = { sha: sha('6'), subject: 'Port change', sourceSha: sha('1') };
  const fixup = { sha: sha('7'), subject: 'fixup! Port change', fixupOf: sha('1'), origin: 'CI' as const };
  const round = (overrides: Partial<CiAutofixRunDto>): CiAutofixRunDto => ({ ...CI_RUN, parentTaskId: null, ...overrides });

  it('marks earlier stages done, the current one running, and later ones pending', () => {
    const stages = cherryPickStages(delivering({ stage: 'CI_FIX', ciRunIds: ['run-1'] }), [round({ status: 'ACTIVE', attemptsUsed: 2 })]);
    expect(stages.map(stage => stage.state)).toEqual(['done', 'done', 'done', 'running', 'pending', 'pending']);
    expect(stages[2].detail).toBe('Draft PR #31');
    expect(stages[3].detail).toBe('Round 1 · attempt 2 of 3');
    expect(stages[5].detail).toBe('You merge');
  });

  it('waits on the user to approve the squash, then to merge a green squashed head', () => {
    const squash = delivering({ stage: 'SQUASH', commits: [copy, fixup] });
    expect(cherryPickWaitsFor(squash, [])).toBe('squash');
    expect(cherryPickStatus(squash, [])).toEqual({ label: 'Needs your approval', tone: 'amber' });
    expect(cherryPickStages(squash, [])[4]).toMatchObject({ state: 'needs-you' });

    const green = round({ status: 'FINISHED', finishReason: 'GREEN' });
    const merge = delivering({ stage: 'MERGE', commits: [copy], squash: { approvedHead: sha('7'), approvedAt: '2026-09-01T11:00:00Z', resultHead: sha('6'), fixups: [fixup] } });
    expect(cherryPickWaitsFor(merge, [green])).toBe('merge');
    expect(cherryPickStatus(merge, [green])).toEqual({ label: 'Ready to merge', tone: 'green' });
    expect(cherryPickStages(merge, [green])[4].detail).toBe('1 fixup squashed into 1 commit');
  });

  it('reads a pause by the user apart from a stop that needs the user', () => {
    const paused = delivering({ stage: 'CI_FIX', hold: { reason: 'USER', summary: 'Paused by you', at: '2026-09-01T11:00:00Z' } });
    expect(cherryPickStatus(paused, [])).toEqual({ label: 'Paused', tone: 'neutral' });
    expect(cherryPickStages(paused, [])[3]).toMatchObject({ state: 'paused', detail: 'Paused by you' });
    const stopped = delivering({ stage: 'CI_FIX', hold: { reason: 'CI_FAILED', summary: 'Out of attempts', at: '2026-09-01T11:00:00Z' } });
    expect(cherryPickStages(stopped, [])[3]).toMatchObject({ state: 'needs-you', detail: 'Out of attempts' });
  });

  it('shows only the apply stage for a backport chain’s execution', () => {
    expect(cherryPickStages({ ...base('FINISHED', 'COMPLETED'), lifecycle: lifecycle() }, []).map(stage => stage.state)).toEqual(['done']);
  });

  it('sums target drift across finished rebases and names a live one', () => {
    const rebase = (number: number, state: 'RUNNING' | 'DONE', targetMoved: number) => ({ number, baseSha: sha('8'), targetMoved, state, startedAt: '2026-09-01T11:00:00Z' });
    expect(driftBadge(delivering({ rebases: [rebase(1, 'DONE', 2), rebase(2, 'DONE', 3)] }))).toBe('target +5 · auto-rebased 2×');
    expect(driftBadge(delivering({ rebases: [rebase(1, 'DONE', 2), rebase(2, 'RUNNING', 4)] }))).toBe('rebasing · target +4');
    expect(driftBadge(delivering({}))).toBeNull();
  });

  it('nests each fixup under its commit and strikes squashed ones', () => {
    const rows = commitRows(delivering({ stage: 'SQUASH', commits: [copy, { ...fixup, origin: 'CONFLICT' }] }));
    expect(rows.interleaved.map(row => [row.kind, row.state, row.tag])).toEqual([
      ['source', 'applied', 'conflict resolved'], ['fixup', 'fixup', 'local'], ['source', 'skipped', 'skipped by you']]);
    expect(rows.interleaved[0].shas).toBe('1111111 → 6666666');
    const squashed = commitRows(delivering({ stage: 'MERGE', commits: [copy],
      squash: { approvedHead: sha('7'), approvedAt: '2026-09-01T11:00:00Z', resultHead: sha('6'), fixups: [fixup] } }));
    expect(squashed.fixups).toEqual([expect.objectContaining({ state: 'squashed', shas: '7777777 · squashed into 6666666' })]);
  });

  it('lists every CI attempt across rounds, and a green round once', () => {
    const rows = ciAttemptRows([
      round({ id: 'run-1', status: 'FINISHED', finishReason: 'CANCELLED', attempts: [
        { number: 1, observedCiResult: 'FAILED', failures: ['test (core)'], pushedSha: sha('9'), updatedAt: '2026-09-01T11:00:00Z' }] }),
      round({ id: 'run-2', status: 'FINISHED', finishReason: 'GREEN', summary: 'Checks passed', attempts: [] }),
    ]);
    expect(rows.map(row => [row.title, row.state, row.detail])).toEqual([
      ['Round 1 · attempt 1', 'failed', 'test (core) → fixup 9999999'],
      ['Round 2 · green', 'passed', 'Checks passed'],
    ]);
  });
});
