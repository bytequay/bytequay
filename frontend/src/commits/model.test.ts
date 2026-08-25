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
import type { CherryPickExecution, GitCommitDto } from '../types';
import {
  filterTasks,
  foldNav,
  groupByDay,
  isLikelyGitHubLogin,
  matchesCommitQuery,
  orderTasks,
  remoteBranchCorrection,
  selectedSpan,
  spanFromShas,
  squashMessage,
  taskState,
} from './model';

function commit(sha: string, committedAt = '2026-08-20T10:00:00Z'): GitCommitDto {
  return { sha, title: `Commit ${sha}`, body: '', author: 'dev', committedAt };
}

const COMMITS = [commit('aaaa111'), commit('bbbb222'), commit('cccc333'), commit('dddd444')];

function execution(status: string, finishReason?: string): CherryPickExecution {
  return {
    executionId: `exec-${status}-${finishReason ?? ''}`,
    repository: 'bytequay/trino-fork',
    workBranch: 'bytequay/cp-1',
    localTargetBranch: 'master',
    targetBranch: 'master',
    status,
    finishReason: finishReason ?? null,
    initialTargetSha: '41ab9e2aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
    currentTargetSha: '41ab9e2aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
    items: [],
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

describe('groupByDay', () => {
  it('groups consecutive commits by committed date', () => {
    const commits = [
      commit('a1', '2026-08-20T10:00:00Z'),
      commit('a2', '2026-08-20T08:00:00Z'),
      commit('b1', '2026-08-15T10:00:00Z'),
    ];
    const groups = groupByDay(commits);
    expect(groups).toHaveLength(2);
    expect(groups[0].label).toMatch(/^Commits on /);
    expect(groups[0].commits.map(entry => entry.commit.sha)).toEqual(['a1', 'a2']);
    expect(groups[1].commits.map(entry => entry.index)).toEqual([2]);
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

describe('isLikelyGitHubLogin', () => {
  it('accepts login-shaped names and rejects display names', () => {
    expect(isLikelyGitHubLogin('chenjian2664')).toBe(true);
    expect(isLikelyGitHubLogin('example-author')).toBe(true);
    expect(isLikelyGitHubLogin('Pia Fenwick')).toBe(false);
    expect(isLikelyGitHubLogin('张万义')).toBe(false);
    expect(isLikelyGitHubLogin('')).toBe(false);
  });
});

describe('matchesCommitQuery', () => {
  const subject: GitCommitDto = {
    sha: 'f4627c7abcdef',
    title: 'Update MongoDB image versions',
    body: '',
    author: 'dev',
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

describe('foldNav', () => {
  const items = Array.from({ length: 20 }, (_, index) => index);

  it('shows previous, current, and the next seven around the active item', () => {
    const rows = foldNav(items, 10, false);
    expect(rows[0]).toEqual({ kind: 'fold', label: '9 earlier items — settled', expand: true });
    expect(rows.slice(1, -1).map(row => row.kind === 'item' && row.value))
      .toEqual([9, 10, 11, 12, 13, 14, 15, 16, 17]);
    expect(rows.at(-1)).toEqual({ kind: 'fold', label: '2 more pending', expand: true });
  });

  it('expands fully with a collapse row', () => {
    const rows = foldNav(items, 10, true);
    expect(rows[0]).toEqual({ kind: 'fold', label: 'Collapse to the active window', expand: false });
    expect(rows).toHaveLength(21);
  });

  it('never folds a short list', () => {
    expect(foldNav([1, 2, 3], 1, false).every(row => row.kind === 'item')).toBe(true);
  });

  it('anchors to the end when nothing is active', () => {
    const rows = foldNav(items, -1, false);
    expect(rows.at(-1)).toEqual({ kind: 'item', value: 19 });
    expect(rows[0]).toEqual({ kind: 'fold', label: '18 earlier items — settled', expand: true });
  });
});
