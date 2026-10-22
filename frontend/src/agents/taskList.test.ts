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
import { reviewFixture, runningReviewFixture } from '../pulls/agent-review/fixtures/review';
import type { AgentTaskActivityDto, CherryPickExecution } from '../types';
import { lifecycle } from '../commits/fixtures/lifecycle';
import { CI_RUN } from './fixtures/ciAutofix';
import { previewTask } from './fixtures/previewBridge';
import { viewerInitials } from './TaskListCards';
import { cherryPickItem, ciFixItem, developmentItem, groupTaskList, reviewItem, type TaskListItem } from './taskList';

function item(key: string, at: string, label: TaskListItem['state']['label'] = 'Idle'): TaskListItem {
  return {
    key, type: 'dev', title: key, at, message: null, links: [], change: null, stages: null,
    state: { label, tone: 'green', spinning: false, reason: null },
    task: null, review: null, execution: null, run: null,
  };
}

function execution(overrides: Partial<CherryPickExecution>): CherryPickExecution {
  return {
    executionId: 'exec-1', workspaceId: 'workspace-1', repository: 'trinodb/trino', workBranch: 'cp-1',
    localTargetBranch: '478-lts', targetBranch: '478-lts', status: 'RUNNING',
    initialTargetSha: 'a'.repeat(40), currentTargetSha: 'a'.repeat(40),
    items: [
      { position: 1, sourceSha: 'b'.repeat(40), title: 'one', result: 'APPLIED' },
      { position: 2, sourceSha: 'c'.repeat(40), title: 'two', result: 'PENDING' },
    ],
    createdAt: '2026-09-27T08:00:00Z', updatedAt: '2026-09-27T09:00:00Z', terminalAt: null, lifecycle: lifecycle(),
    ...overrides,
  };
}

describe('task list days', () => {
  // A Friday: this week began on Monday the 21st.
  const now = new Date(2026, 8, 25, 12).getTime();
  const local = (day: number, hour = 9) => new Date(2026, 8, day, hour).toISOString();

  it('puts today first by what it needs, then yesterday, the rest of the week and older work, newest first', () => {
    const grouped = groupTaskList([
      item('today-idle', local(25, 11)),
      item('today-needs', local(25, 8), 'Needs you'),
      item('today-running', local(25, 10), 'Running'),
      item('today-done', local(25, 11), 'Completed'),
      item('yesterday', local(24)),
      item('wednesday', local(23)),
      item('monday', local(21, 18)),
      item('last-sunday', local(20)),
      item('last-friday', local(18)),
      item('unreadable', '<timestamp>'),
    ], now);

    expect(grouped.today.map((entry) => entry.key)).toEqual(['today-needs', 'today-running', 'today-idle', 'today-done']);
    expect(grouped.todayDate).toBe('Fri, Sep 25');
    expect(grouped.groups.map((group) => [group.label, group.date, group.items.map((entry) => entry.key)])).toEqual([
      ['Yesterday', 'Thu, Sep 24', ['yesterday']],
      ['Earlier this week', 'Sep 21\u2009–\u200923', ['wednesday', 'monday']],
      // A time that does not read still lists the item rather than losing it.
      ['Older', 'Sep 18\u2009–\u200920', ['last-sunday', 'last-friday', 'unreadable']],
    ]);
  });

  it('starts the week on Monday, so on a Monday yesterday is the only earlier group this week', () => {
    const monday = new Date(2026, 8, 28, 12).getTime();
    const grouped = groupTaskList([item('sunday', local(27)), item('saturday', local(26))], monday);

    expect(grouped.groups.map((group) => [group.label, group.items.map((entry) => entry.key)])).toEqual([
      ['Yesterday', ['sunday']],
      ['Older', ['saturday']],
    ]);
  });

  it('keeps yesterday a calendar day across a daylight-saving change', () => {
    // Europe and the US leave daylight saving on these Sundays; either way the Saturday stays yesterday's.
    for (const [sunday, saturday] of [[new Date(2026, 9, 26, 0, 30), new Date(2026, 9, 25, 0, 10)], [new Date(2026, 10, 2, 0, 30), new Date(2026, 10, 1, 0, 10)]]) {
      const grouped = groupTaskList([item('late-night', saturday.toISOString())], sunday.getTime());
      expect(grouped.groups[0]?.label).toBe('Yesterday');
    }
  });
});

describe('task list items', () => {
  it('names a development task by its lane, or You, and leaves a pull request to the links', () => {
    const task = { ...previewTask('workspace-1', 'task-1'), laneId: 'claude' };
    const activity: AgentTaskActivityDto = {
      pendingApproval: null, latestTurn: null,
      lastMessage: { author: 'AGENT' as const, text: 'Ready for review.', at: '2026-09-27T10:00:00Z', turnId: null },
    };
    const item = developmentItem(task, activity, { uncommitted: false, commits: 2 }, [{ id: 'claude', label: 'Claude Code' } as never]);

    expect(item.message).toEqual({ who: 'Claude Code', text: 'Ready for review.', at: '2026-09-27T10:00:00Z' });
    expect(item.change).toBe('2 commits');
    const mine = developmentItem(task, { ...activity, lastMessage: { ...activity.lastMessage, author: 'USER' } }, null);
    expect(mine.message?.who).toBe('You');
    const delivered = developmentItem({ ...task, pullRequest: { number: 7, title: 'Fix', state: 'open', draft: false } as never }, activity, { uncommitted: false, commits: 2 });
    expect(delivered.change).toBeNull();
    expect(delivered.links.map((link) => link.key)).toEqual(['#7']);
  });

  it('reads a review as running, waiting on an approval, or finished with what it found', () => {
    expect(reviewItem(runningReviewFixture()).state).toMatchObject({ label: 'Running', spinning: true });
    expect(reviewItem({ ...runningReviewFixture(), pendingApprovals: 1 }).state).toMatchObject({ label: 'Needs you', reason: 'Approval needed' });
    const finished = reviewItem(reviewFixture());
    expect(finished.state.label).toBe('Finished');
    expect(finished.change).toMatch(/finding|open/);
    expect(finished.links[0]).toMatchObject({ source: 'pr', key: '#30304' });
    expect(reviewItem(reviewFixture({ number: null })).title).toBe('trinodb/trino · Local changes');
    // The last word may be a development agent's reply on a finding, which is not the reviewer speaking.
    const [last] = reviewFixture().messages.slice(-1);
    expect(finished.message?.who).toBe(reviewFixture().task.laneLabel);
    expect(reviewItem(reviewFixture({ messages: [{ ...last, findingId: 'finding-1', developerTaskId: 'developer-task' }] })).message?.who).toBe('Developer');
  });

  it('reads a cherry-pick by the stages it came through, and a canceled one as finished, not completed', () => {
    const running = cherryPickItem(execution({ pullRequestDraft: { title: 'Backport conflict classification', body: '' } }));
    expect(running).toMatchObject({ title: 'Backport conflict classification', at: '2026-09-27T09:00:00Z' });
    expect(running.stages?.map(stage => stage.state)).toEqual(['running']);
    expect(running.state.label).toBe('Running');
    const delivering = cherryPickItem(execution({ lifecycle: lifecycle({ delivers: true, stage: 'CI_FIX' }) }));
    expect(delivering.stages?.map(stage => stage.state)).toEqual(['done', 'done', 'done', 'running', 'pending', 'pending']);
    expect(cherryPickItem(execution({ lifecycle: lifecycle({ delivers: true, stage: 'SQUASH',
      commits: [{ sha: 'd'.repeat(40), subject: 'one', sourceSha: 'b'.repeat(40) }, { sha: 'e'.repeat(40), subject: 'fixup! one', fixupOf: 'b'.repeat(40), origin: 'CI' }] }) })).state)
      .toMatchObject({ label: 'Needs you', reason: 'Approve the squash' });
    expect(cherryPickItem(execution({ lifecycle: lifecycle({ delivers: true, stage: 'CI_FIX', hold: { reason: 'USER', summary: 'Paused by you', at: '2026-09-27T09:00:00Z' } }) })).state.label)
      .toBe('Idle');
    expect(cherryPickItem(execution({ status: 'PAUSED', pauseReason: 'MERGE_CONFLICT' })).state)
      .toMatchObject({ label: 'Needs you', reason: 'Paused: Merge conflict' });
    expect(cherryPickItem(execution({ status: 'FINISHED', finishReason: 'COMPLETED' })).state.label).toBe('Completed');
    expect(cherryPickItem(execution({ status: 'FINISHED', finishReason: 'CANCELLED' })).state.label).toBe('Finished');
    expect(cherryPickItem(execution({ delivery: { pushRemote: 'origin', head: 'cp-1', sha: 'd'.repeat(40), pullRequestNumber: 31 } })).links[0])
      .toMatchObject({ source: 'pr', key: '#31' });
    expect(cherryPickItem(execution({})).title).toBe('Cherry-pick 2 commits into 478-lts');
  });

  it('reads a CI fix by its attempts, and a run that ran out of them as failed', () => {
    const paused = ciFixItem(CI_RUN);
    expect(paused).toMatchObject({ title: 'Get CI green on #17', change: 'attempt 1 of 3' });
    expect(paused.state).toMatchObject({ label: 'Needs you', reason: 'Paused: Invalid git result' });
    expect(ciFixItem({ ...CI_RUN, status: 'ACTIVE' }).state.label).toBe('Running');
    expect(ciFixItem({ ...CI_RUN, status: 'FINISHED', finishReason: 'GREEN' }).state.label).toBe('Completed');
    expect(ciFixItem({ ...CI_RUN, status: 'FINISHED', finishReason: 'MAX_ATTEMPTS' }).state.label).toBe('Failed');
    expect(ciFixItem({ ...CI_RUN, status: 'FINISHED', finishReason: 'CANCELLED' }).state.label).toBe('Finished');
  });

  it('gives the viewer initials from their name, else their login', () => {
    expect(viewerInitials({ login: 'chenjian2664', name: 'Jack Chen' })).toBe('JC');
    expect(viewerInitials({ login: 'chenjian2664', name: null })).toBe('CH');
    expect(viewerInitials(undefined)).toBe('Y');
  });
});
