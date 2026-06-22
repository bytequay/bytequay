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
      log: [],
    }],
    conversation: [
      { id: 'm1', kind: 'iteration_marker', text: 'red_ci', toolTag: null, toolLabel: null, toolDetail: null, iterationNumber: 1, ts: '2026-06-21T10:00:00Z' },
      { id: 'm2', kind: 'agent', text: 'Lint failed on an unused import. Removing it.', toolTag: null, toolLabel: null, toolDetail: null, iterationNumber: null, ts: '2026-06-21T10:00:03Z' },
      { id: 'm3', kind: 'tool_call', text: null, toolTag: 'Read', toolLabel: 'read_file', toolDetail: 'CostMeter.tsx', iterationNumber: null, ts: '2026-06-21T10:00:05Z' },
      { id: 'm4', kind: 'user', text: 'try a smaller diff', toolTag: null, toolLabel: null, toolDetail: null, iterationNumber: null, ts: '2026-06-21T10:00:10Z' },
    ],
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
  it('renders breadcrumb, conversation transcript, metrics, and the composer', async () => {
    mockBridge();
    render(<TaskStageDetailView taskId="task-2" stageId="stage-ci" onBack={() => {}} onOpenStage={() => {}} />);

    // Breadcrumb / identity carry the stage label.
    expect(await screen.findAllByText('CiFixingStage')).not.toHaveLength(0);
    // Iteration strip jump + the transcript: agent turn + tool-call row.
    expect(screen.getByLabelText('Jump to iteration 1')).toBeTruthy();
    expect(screen.getByText('Lint failed on an unused import. Removing it.')).toBeTruthy();
    expect(screen.getByText('read_file')).toBeTruthy();
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

  it('shows tool detail + time and renders the user message without a YOU label', async () => {
    mockBridge();
    render(<TaskStageDetailView taskId="task-2" stageId="stage-ci" onBack={() => {}} onOpenStage={() => {}} />);

    // Tool call surfaces its command detail and a relative time.
    expect(await screen.findByText('CostMeter.tsx')).toBeTruthy();
    expect(screen.getAllByText(/ago|now/).length).toBeGreaterThan(0);
    // User message renders, but the "YOU" avatar label is gone.
    expect(screen.getByText('try a smaller diff')).toBeTruthy();
    expect(screen.queryByText('YOU')).toBeNull();
  });

  it('layers an iteration marker over the transcript for a looping stage', async () => {
    mockBridge();
    render(<TaskStageDetailView taskId="task-2" stageId="stage-ci" onBack={() => {}} onOpenStage={() => {}} />);

    // The marker (its loop trigger) delineates the iteration over the
    // transcript; the tool tag + label render as a compact row.
    expect(await screen.findByText('red_ci')).toBeTruthy();
    expect(screen.getByText('Read')).toBeTruthy();
    expect(screen.getByText('read_file')).toBeTruthy();
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

    const devChip = await screen.findByRole('button', { name: /DevelopmentStage · CLOSED/ });
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

  it('opens the code diff from the breadcrumb', async () => {
    mockBridge();
    const onOpenCode = vi.fn();
    render(<TaskStageDetailView taskId="task-2" stageId="stage-ci" onBack={() => {}}
      onOpenStage={() => {}} onOpenCode={onOpenCode} />);

    await screen.findByText('read_file');
    fireEvent.click(screen.getByRole('button', { name: /View code diff/ }));
    expect(onOpenCode).toHaveBeenCalledTimes(1);
  });

  it('renders a stage with no auto-push budget without crashing', async () => {
    // The development stage carries autoPushBudget: null over the wire (JSON
    // null, not undefined). The budget card must be skipped, not throw on
    // null.used.
    const noBudget = fixture();
    noBudget.stage.type = 'DEVELOPMENT_STAGE';
    noBudget.stage.config.autoPushBudget = null;
    mockBridge(vi.fn().mockResolvedValue(noBudget));

    render(<TaskStageDetailView taskId="task-2" stageId="stage-ci" onBack={() => {}} onOpenStage={() => {}} />);

    // The metrics card still renders; the budget card is absent.
    expect(await screen.findByText('Metrics')).toBeTruthy();
    expect(screen.queryByLabelText('Auto-push budget')).toBeNull();
  });

  it('shows a loading state before data arrives (no bridge)', () => {
    render(<TaskStageDetailView taskId="task-2" stageId="stage-ci" onBack={() => {}} onOpenStage={() => {}} />);
    expect(screen.getByText('Loading stage…')).toBeTruthy();
  });
});
