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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TrunkFeed } from './TrunkFeed';
import type { DiffFileDto, ThreadCommitDto, ThreadMessageDto, WorkUnitTaskDto } from '../types';

afterEach(cleanup);

function msg(id: string, role: string, type: string, body: unknown, ts: string): ThreadMessageDto {
  return {
    id, threadId: 't', taskId: null, seq: 0, role, type,
    contentJson: JSON.stringify(body), durationMs: null, tokensIn: null, tokensOut: null, costUsdMilli: null, ts,
  };
}

function task(
  id: string, seq: number, name: string, status: string, createdAt: string,
  prState: string | null = null,
): WorkUnitTaskDto {
  return { id, seq, name, status, createdAt, prState, prNumber: 42 } as unknown as WorkUnitTaskDto;
}

describe('TrunkFeed', () => {
  it('shows managed skills only behind a runtime disclosure', () => {
    const { container } = render(
      <TrunkFeed
        messages={[
          msg('u', 'user', 'text',
            { text: 'go ahead', managedSkills: ['trunk-planner'] },
            '2026-01-01T00:00:00Z'),
        ]}
        tasks={[]}
        density="focused"
        onOpenTask={() => {}}
      />,
    );

    const details = container.querySelector('details');
    expect(details?.open).toBe(false);
    fireEvent.click(screen.getByText('runtime'));
    expect(details?.open).toBe(true);
    expect(screen.getByText('Managed skills: trunk-planner')).toBeTruthy();
  });

  it('renders a pending permission_request as a clickable card and answers it', () => {
    const onDecidePermission = vi.fn();
    const messages = [
      msg('u', 'user', 'text', { text: 'check the branch' }, '2026-01-01T00:00:00Z'),
      msg('pr', 'system', 'permission_request',
        { callId: 'c1', toolName: 'Bash', summary: '{"command":"git fetch origin main"}' },
        '2026-01-01T00:00:05Z'),
    ];
    render(
      <TrunkFeed
        messages={messages}
        tasks={[]}
        density="focused"
        onOpenTask={() => {}}
        onDecidePermission={onDecidePermission}
      />,
    );

    expect(screen.getByText(/Approval needed/)).toBeTruthy();
    fireEvent.click(screen.getByText('Approve once'));
    expect(onDecidePermission).toHaveBeenCalledWith('c1', 'ALLOW');
  });

  it('does not render a card for an already-decided permission_request', () => {
    const messages = [
      msg('u', 'user', 'text', { text: 'check the branch' }, '2026-01-01T00:00:00Z'),
      msg('pr', 'system', 'permission_request', { callId: 'c1', toolName: 'Bash', summary: 'git fetch' },
        '2026-01-01T00:00:05Z'),
      msg('dec', 'system', 'permission_decision', { callId: 'c1', decision: 'ALLOW' },
        '2026-01-01T00:00:10Z'),
    ];
    render(<TrunkFeed messages={messages} tasks={[]} density="focused" onOpenTask={() => {}} />);
    expect(screen.queryByText(/Approval needed/)).toBeNull();
  });

  it('keeps a terminal process error visible in focused density', () => {
    const { container } = render(
      <TrunkFeed
        messages={[
          msg('u', 'user', 'text', { text: 'inspect this' }, '2026-01-01T00:00:00Z'),
          msg('err', 'system', 'error', { message: 'permission MCP unavailable' }, '2026-01-01T00:00:05Z'),
        ]}
        tasks={[]}
        density="focused"
        onOpenTask={() => {}}
      />,
    );

    expect(screen.getByRole('alert').textContent).toContain('permission MCP unavailable');
    expect(container.querySelector('.sp-badge--fail')?.textContent).toContain('1 failed');
  });

  it('keeps the newest cut expanded without losing an older concurrent task', () => {
    // Regression: task2 and task3 both start (and task3's own cut lands)
    // BEFORE task2's own completion summary is finally recorded — tasks run
    // concurrently, so a later task routinely starts before an earlier one
    // finishes. The old grouping ("everything before the next summary
    // encountered belongs to it") swallowed task3's cut into task2's fold,
    // making it disappear entirely.
    const messages: ThreadMessageDto[] = [
      msg('sum1', 'assistant', 'task_summary', { text: 'Shipped task 1.', taskId: 't1', taskSeq: 1 },
        '2026-01-01T01:00:00Z'),
      // task3 (created below) starts before task2's summary lands:
      msg('sum2', 'assistant', 'task_summary', { text: 'Shipped task 2.', taskId: 't2', taskSeq: 2 },
        '2026-01-03T00:00:00Z'),
    ];
    const tasks = [
      task('t1', 1, 'Task one', 'COMPLETED', '2026-01-01T00:00:00Z'),
      task('t2', 2, 'Task two', 'COMPLETED', '2026-01-01T00:30:00Z'),
      task('t3', 3, 'Task three', 'IN_REVIEW', '2026-01-02T00:00:00Z'),
      task('t4', 4, 'Task four', 'IDLE', '2026-01-02T01:00:00Z'),
    ];
    const { container } = render(
      <TrunkFeed messages={messages} tasks={tasks} density="focused" onOpenTask={() => {}} />,
    );

    // Completed predecessors live in TrunkPage's compact history, not in a
    // second set of feed folds.
    expect(container.querySelectorAll('.sp-taskrow--done').length).toBe(0);
    // The older still-running cut remains a compact row, so concurrency
    // cannot make it disappear behind an unrelated completion summary.
    const runningFolds = container.querySelectorAll('.sp-taskrow--running');
    expect(runningFolds.length).toBe(1);
    expect(runningFolds[0].textContent).toContain('Task three');
    // Locked frame 1b keeps the newest task detail visible on the branch.
    const latestCut = container.querySelector('.trunk-page-v2__branch-row--cut');
    expect(latestCut?.textContent).toContain('Task four');
  });

  it('folds a finished task out of the feed instead of expanding it', () => {
    // Once a task finishes it collapses into TrunkPage's compact top history —
    // it no longer lingers expanded on the branch rail. With every task done,
    // the feed keeps nothing open.
    const messages = [
      msg('sum1', 'assistant', 'task_summary', { text: 'First shipped.', taskId: 't1', taskSeq: 1 },
        '2026-01-01T01:00:00Z'),
      msg('sum2', 'assistant', 'task_summary', { text: 'Second shipped.', taskId: 't2', taskSeq: 2 },
        '2026-01-02T01:00:00Z'),
    ];
    const tasks = [
      task('t1', 1, 'Task one', 'COMPLETED', '2026-01-01T00:00:00Z'),
      task('t2', 2, 'Task two', 'COMPLETED', '2026-01-02T00:00:00Z', 'MERGED'),
    ];

    const { container } = render(
      <TrunkFeed messages={messages} tasks={tasks} density="focused" onOpenTask={() => {}} />,
    );

    expect(container.querySelector('.trunk-page-v2__branch-row')).toBeNull();
    expect(container.textContent).not.toContain('Task one');
    expect(container.textContent).not.toContain('Task two');
  });

  it('keeps the newest active task artifacts on its branch in the locked order', () => {
    const onReview = vi.fn();
    const onUndo = vi.fn();
    const files: DiffFileDto[] = Array.from({ length: 5 }, (_, index): DiffFileDto => ({
      filename: `frontend/src/file-${index + 1}.tsx`, status: 'modified', additions: index + 1,
      deletions: index, patch: null,
    }));
    const commits: ThreadCommitDto[] = [{
      sha: 'd4aae82f1234', shortSha: 'd4aae82f', authorName: 'Jack', authorEmail: 'jack@example.com',
      authoredAt: '2026-01-01T00:03:00Z', subject: 'Remove stale repository pages',
    }];
    const artifactsByTaskId = new Map([['t1', { files, commits, onReview, onUndo }]]);
    const { container } = render(
      <TrunkFeed
        messages={[]}
        tasks={[task('t1', 1, 'Task one', 'IN_REVIEW', '2026-01-01T00:00:00Z')]}
        density="focused"
        onOpenTask={() => {}}
        artifactsByTaskId={artifactsByTaskId}
      />,
    );

    const branchText = Array.from(container.querySelectorAll('.trunk-page-v2__branch-row'))
      .map(row => row.textContent ?? '');
    expect(branchText[0]).toContain('Task one');
    expect(branchText[1]).toContain('Worked for');
    expect(branchText[2]).toContain('Edited 5 files');
    expect(branchText[3]).toContain('d4aae82f');
    expect(container.querySelector('.sp-work__inner')).toBeNull();
    expect(container.querySelectorAll('.workspace-task-files-card__file')).toHaveLength(3);
    fireEvent.click(screen.getByRole('button', { name: 'Show 2 more files' }));
    expect(container.querySelectorAll('.workspace-task-files-card__file')).toHaveLength(5);
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));
    fireEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(onReview).toHaveBeenCalledOnce();
    expect(onUndo).toHaveBeenCalledOnce();
  });
});
