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
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { StageDetailData } from '../../types/brainView';
import TaskStageDetailView from './TaskStageDetailView';

afterEach(() => { cleanup(); vi.restoreAllMocks(); Reflect.deleteProperty(window, 'bridge'); });

function fixture(): StageDetailData {
  return {
    task: {
      id: 'task-2', taskNumber: 2, title: 'Cost meter', branch: 'jack/cost-meter',
      repoFullName: 'trinodb/trino', prNumber: 5680, prDraft: false,
      currentPhase: 'CI_FIXING', agentRuntime: 'CLI', agentModel: 'claude-sonnet-4-6',
    },
    stage: {
      id: 'stage-ci', type: 'CI_FIXING_STAGE', state: 'OPEN',
      openedAt: '2026-06-21T10:00:00Z', closedAt: null, callerStageId: null,
      iterationCount: 1, currentIterationNumber: 1,
      config: { autoPushBudget: { used: 2, limit: 5 }, internalReviewEnabled: false },
      metrics: {
        wallTimeSec: 120, loopIterations: 1, toolCallsCount: 3, turnsCount: 2,
        messagesCount: 9, tokensCount: 4200, costCents: 7,
        panelInvocationsCount: 0,
      },
    },
    allStages: [
      { id: 'stage-dev', taskId: 'task-2', type: 'DEVELOPMENT_STAGE', state: 'CLOSED', openedAt: '', closedAt: '', callerStageId: null, summary: 'Development', loopIteration: 0 },
      { id: 'stage-ci', taskId: 'task-2', type: 'CI_FIXING_STAGE', state: 'OPEN', openedAt: '', closedAt: null, callerStageId: null, summary: 'CiFixing', loopIteration: 0 },
    ],
    subStages: [],
    iterations: [{
      id: 'iter-1', iterationNumber: 1, trigger: 'red_ci',
      startedAt: '2026-06-21T10:00:00Z', endedAt: null, endedReason: null,
      summaryText: 'fix #1: bumped retry default', recordedBy: 'agent',
      log: [
        { id: 'r1', ts: '2026-06-21T10:00:05Z', kind: 'tool_call', toolCall: { tag: 'Read', label: 'read_file', detail: 'RetryConfig.java' } },
        { id: 'r2', ts: '2026-06-21T10:00:50Z', kind: 'iteration_summary', iterationSummary: { text: 'fix #1: bumped retry default', recordedBy: 'agent', recordedAt: '2026-06-21T10:00:50Z' } },
      ],
    }],
    realtimeCi: null,
    ciFixHistory: [{ iterationNumber: 1, endedReason: null, summaryText: 'fix #1: bumped retry default' }],
    context: { tokensUsed: 0, tokensLimit: 200000, safeBand: 'safe' },
    scrubber: { userMessages: [] },
  };
}

function mockBridge(getStageDetail = vi.fn().mockResolvedValue(fixture())) {
  (window as unknown as { bridge: unknown }).bridge = { getStageDetail };
  return getStageDetail;
}

describe('TaskStageDetailView', () => {
  it('renders breadcrumb, iteration band, log rows, metrics, and disabled composer', async () => {
    mockBridge();
    render(<TaskStageDetailView taskId="task-2" stageId="stage-ci" onBack={() => {}} onOpenStage={() => {}} />);

    // Breadcrumb / identity carry the stage label.
    expect(await screen.findAllByText('CiFixingStage')).not.toHaveLength(0);
    // Iteration band + its log.
    expect(screen.getByLabelText('Jump to iteration 1')).toBeTruthy();
    expect(screen.getByText('read_file')).toBeTruthy();
    expect(screen.getAllByText('fix #1: bumped retry default').length).toBeGreaterThan(0);
    // Derivable metric is shown.
    expect(screen.getByText('Tool calls')).toBeTruthy();
    // Steering composer is live (no longer a disabled placeholder).
    expect(screen.getByLabelText('Steering message')).toBeTruthy();
  });

  it('submits a steering message through the bridge', async () => {
    const getStageDetail = vi.fn().mockResolvedValue(fixture());
    const steerStage = vi.fn().mockResolvedValue({ turnId: 'turn-1' });
    (window as unknown as { bridge: unknown }).bridge = { getStageDetail, steerStage };

    render(<TaskStageDetailView taskId="task-2" stageId="stage-ci" onBack={() => {}} onOpenStage={() => {}} />);
    const box = await screen.findByLabelText('Steering message');
    fireEvent.change(box, { target: { value: 'bump the retry default' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send steering message' }));

    await waitFor(() => expect(steerStage).toHaveBeenCalledWith('stage-ci', 'bump the retry default'));
  });

  it('disables the composer on a closed stage', async () => {
    const closed = fixture();
    closed.stage.state = 'CLOSED';
    mockBridge(vi.fn().mockResolvedValue(closed));

    render(<TaskStageDetailView taskId="task-2" stageId="stage-ci" onBack={() => {}} onOpenStage={() => {}} />);
    const box = await screen.findByLabelText('Steering message');
    expect((box as HTMLTextAreaElement).disabled).toBe(true);
  });

  it('renders an operation card with its nested tool calls', async () => {
    const withOp = fixture();
    withOp.iterations[0].log = [{
      id: 'op:r1', ts: '2026-06-21T10:00:05Z', kind: 'operation',
      operation: {
        operation: 'code', startedAt: '2026-06-21T10:00:05Z', completedAt: '2026-06-21T10:00:09Z',
        durationSec: 4, toolCallCount: 2, status: 'ok',
        toolCalls: [
          { id: 't1', ts: '2026-06-21T10:00:05Z', kind: 'tool_call', toolCall: { tag: 'Read', label: 'read_file', detail: 'A.java' } },
          { id: 't2', ts: '2026-06-21T10:00:09Z', kind: 'tool_call', toolCall: { tag: 'Write', label: 'edit_file', detail: 'A.java' } },
        ],
      },
    }];
    mockBridge(vi.fn().mockResolvedValue(withOp));

    render(<TaskStageDetailView taskId="task-2" stageId="stage-ci" onBack={() => {}} onOpenStage={() => {}} />);

    expect(await screen.findByText('code')).toBeTruthy();
    expect(screen.getByText('2 tool calls · 4s')).toBeTruthy();
    // Nested tool calls render inside the card.
    expect(screen.getByText('read_file')).toBeTruthy();
    expect(screen.getByText('edit_file')).toBeTruthy();
  });

  it('renders the enriched failing-check detail on the CI fix history', async () => {
    const enriched = fixture();
    enriched.ciFixHistory = [{
      iterationNumber: 1, endedReason: 'fixing', summaryText: 'retry bump',
      failedCheck: 'frontend / lint', errorMessage: 'ESLint: 3 problems',
      actionsRunUrl: 'https://github.com/acme/widget/actions/runs/42',
    }];
    mockBridge(vi.fn().mockResolvedValue(enriched));

    render(<TaskStageDetailView taskId="task-2" stageId="stage-ci" onBack={() => {}} onOpenStage={() => {}} />);

    expect(await screen.findByText('frontend / lint')).toBeTruthy();
    const link = screen.getByLabelText('Open the Actions run');
    expect(link.getAttribute('href')).toContain('/actions/runs/42');
  });

  it('navigates between stages from the left-rail navigator', async () => {
    mockBridge();
    const onOpenStage = vi.fn();
    render(<TaskStageDetailView taskId="task-2" stageId="stage-ci" onBack={() => {}} onOpenStage={onOpenStage} />);

    const devChip = await screen.findByText('DevelopmentStage · CLOSED');
    fireEvent.click(devChip);
    expect(onOpenStage).toHaveBeenCalledWith('stage-dev');
  });

  it('returns to the brain view via the back button', async () => {
    mockBridge();
    const onBack = vi.fn();
    render(<TaskStageDetailView taskId="task-2" stageId="stage-ci" onBack={onBack} onOpenStage={() => {}} />);

    // Wait for the loaded view (the loading state shares the back-button label).
    await screen.findByText('read_file');
    fireEvent.click(screen.getByLabelText('Back to brain view'));
    expect(onBack).toHaveBeenCalledTimes(1);
  });

  it('shows a loading state before data arrives (no bridge)', () => {
    render(<TaskStageDetailView taskId="task-2" stageId="stage-ci" onBack={() => {}} onOpenStage={() => {}} />);
    expect(screen.getByText('Loading stage…')).toBeTruthy();
  });
});
