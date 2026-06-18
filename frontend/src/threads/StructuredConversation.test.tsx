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
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { ThreadMessageDto, WorkUnitTaskDto } from '../types';
import { StructuredConversation } from './StructuredConversation';

afterEach(() => {
  cleanup();
});

describe('StructuredConversation card fold', () => {
  it('collapses an assistant card body when its fold toggle is clicked', () => {
    const messages: ThreadMessageDto[] = [
      userMessage({ seq: 1, ts: '2026-05-15T12:00:00Z', text: 'a prompt' }),
      assistantMessage({ seq: 2, ts: '2026-05-15T12:00:01Z', text: 'a long reply body' }),
    ];

    render(
      <StructuredConversation
        messages={messages}
        pendingPermission={null}
        onDecide={() => {}}
        modelName="claude-sonnet-4.6"
        tasks={[]}
      />,
    );

    expect(screen.getByText('a long reply body')).toBeTruthy();
    const card = screen.getByText('a long reply body').closest('article');
    expect(card).toBeTruthy();
    fireEvent.click(within(card as HTMLElement).getByLabelText('Collapse message'));
    // Body hidden; header (and now an "Expand" toggle) remains.
    expect(screen.queryByText('a long reply body')).toBeNull();
    expect(within(card as HTMLElement).getByLabelText('Expand message')).toBeTruthy();
  });
});

describe('StructuredConversation task-boundary marker', () => {
  it('inserts a divider where ship-and-continue rolled into a new task', () => {
    const messages: ThreadMessageDto[] = [
      userMessage({ seq: 1, ts: '2026-05-15T12:00:00Z', text: 'first task prompt' }),
      assistantMessage({ seq: 2, ts: '2026-05-15T12:00:01Z', text: 'first task reply' }),
      // Boundary lands here — task 2 starts at seq 3.
      userMessage({ seq: 3, ts: '2026-05-15T12:05:00Z', text: 'second task prompt' }),
      assistantMessage({ seq: 4, ts: '2026-05-15T12:05:01Z', text: 'second task reply' }),
    ];
    const tasks: WorkUnitTaskDto[] = [
      task({ seq: 1, branchName: 'auto/first', prNumber: 5677, prState: 'merged',
        firstMsgSeq: 1, lastMsgSeq: 2 }),
      task({ seq: 2, branchName: 'jack/cost-pipeline', prNumber: null, prState: null,
        firstMsgSeq: 3, lastMsgSeq: 4 }),
    ];

    render(
      <StructuredConversation
        messages={messages}
        pendingPermission={null}
        onDecide={() => {}}
        modelName="claude-sonnet-4.6"
        tasks={tasks}
      />,
    );

    expect(screen.getByText(/Shipped Task 1.*PR #5677.*started Task 2 on jack\/cost-pipeline/))
      .toBeTruthy();
  });

  it('omits the divider when no tasks have a known firstMsgSeq', () => {
    const messages: ThreadMessageDto[] = [
      userMessage({ seq: 1, ts: '2026-05-15T12:00:00Z', text: 'only prompt' }),
      assistantMessage({ seq: 2, ts: '2026-05-15T12:00:01Z', text: 'only reply' }),
    ];
    // Tasks without a known firstMsgSeq — the row was backfilled
    // pre-V72 or hasn't started accumulating messages yet.
    const tasks: WorkUnitTaskDto[] = [
      task({ seq: 1, branchName: 'auto/first', prNumber: null, prState: null }),
      task({ seq: 2, branchName: 'auto/second', prNumber: null, prState: null }),
    ];

    const { container } = render(
      <StructuredConversation
        messages={messages}
        pendingPermission={null}
        onDecide={() => {}}
        modelName="claude-sonnet-4.6"
        tasks={tasks}
      />,
    );

    expect(container.textContent ?? '').not.toContain('Shipped Task');
  });
});

function userMessage(args: { seq: number; ts: string; text: string }): ThreadMessageDto {
  return {
    id: `m-${args.seq}`,
    threadId: 'thread-1',
    taskId: null,
    seq: args.seq,
    role: 'user',
    type: 'text',
    contentJson: JSON.stringify({ text: args.text }),
    durationMs: null,
    tokensIn: 0,
    tokensOut: 0,
    costUsdMilli: 0,
    ts: args.ts,
  };
}

function assistantMessage(args: { seq: number; ts: string; text: string }): ThreadMessageDto {
  return {
    ...userMessage(args),
    role: 'assistant',
  };
}

type TaskOverrides = Partial<WorkUnitTaskDto> & {
  firstMsgSeq?: number | null;
  lastMsgSeq?: number | null;
};

function task(overrides: TaskOverrides): WorkUnitTaskDto {
  // taskBoundaries reads firstMsgSeq off the runtime object even
  // though the DTO type doesn't surface it; cast through unknown so
  // the test can seed the value the live API will eventually return.
  return ({
    id: `task-${overrides.seq ?? 1}`,
    threadId: 'thread-1',
    seq: 1,
    status: 'IDLE',
    branchName: null,
    worktreePath: null,
    baseBranch: 'main',
    workingDir: null,
    prNumber: null,
    prState: null,
    ciState: null,
    taskType: 'DEVELOP',
    linkedPrNumber: null,
    linkedIssueNumber: null,
    ...overrides,
  } as unknown) as WorkUnitTaskDto;
}
