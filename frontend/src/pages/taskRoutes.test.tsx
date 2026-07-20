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
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { TaskBrainRoute } from './TaskBrainRoute';
import { StageDetailRoute } from './StageDetailRoute';
import { buildMockBrainView } from '../threads/brain/brainViewFixture';
import type { PlanCardDto } from '../types/brainView';
import type { DiffFileDto } from '../types';
import type { LocalPRBundle } from '../types/localPr';

// jsdom lacks scrollIntoView; the shared conversation may call it.
beforeAll(() => { Element.prototype.scrollIntoView = vi.fn(); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); Reflect.deleteProperty(window, 'bridge'); localStorage.clear(); });

describe('TaskBrainRoute', () => {
  it('inherits the per-thread auto-approve default for a task whose backend value is off', async () => {
    localStorage.setItem('bq.autoApprove.thread.t1', 'true');
    const getTaskAutoApprove = vi.fn().mockResolvedValue({ enabled: false });
    const setTaskAutoApprove = vi.fn().mockResolvedValue({ enabled: true });
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn(() => new Promise(() => {})),
      sendBrainMessage: vi.fn(),
      getTaskAutoApprove,
      setTaskAutoApprove,
    };
    render(<TaskBrainRoute threadId="t1" taskId="task-1" onOpenStage={() => {}} onOpenCode={() => {}} onClosed={() => {}} />);
    // The new task inherits the thread default (on) and persists it to the backend.
    await waitFor(() => expect(setTaskAutoApprove).toHaveBeenCalledWith('t1', 'task-1', true));
  });

  it('mounts the V3 brain page on the fixture data and steers the brain agent', async () => {
    const sendBrainMessage = vi.fn().mockResolvedValue({ messageId: 'm1' });
    // getBrainView never resolves → the hook keeps its initial fixture data.
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn(() => new Promise(() => {})),
      sendBrainMessage,
    };
    render(<TaskBrainRoute threadId="t1" taskId="task-1" onOpenStage={() => {}} onOpenCode={() => {}} onClosed={() => {}} />);

    // The locked shell + BRAIN badge render from the fixture.
    expect(document.querySelector('.shell')).toBeTruthy();
    expect(document.querySelector('.workspace-task-header__badge')?.textContent).toBe('BRAIN');

    fireEvent.click(screen.getByTitle('Usage'));
    expect(screen.getByText(/tokens$/)).toBeTruthy();

    const box = screen.getByRole('textbox');
    fireEvent.change(box, { target: { value: 'what next?' } });
    fireEvent.keyDown(box, { key: 'Enter' });
    await waitFor(() => expect(sendBrainMessage).toHaveBeenCalledWith('task-1', 'what next?', []));
    // The working indicator appears while awaiting the brain's reply.
    expect(await screen.findByText('Brain is thinking…')).toBeTruthy();
  });

  it('opens the embedded PR Changes tab from the changed-files controls', async () => {
    const file: DiffFileDto = {
      filename: 'frontend/src/App.tsx', status: 'modified', additions: 3, deletions: 1, patch: null,
    };
    const bundle = {
      pr: {
        id: 'task-pr', taskId: 'task-pr-changes', branchName: 'jack/cost-meter', baseBranch: 'master',
        title: 'Cost-meter widget', description: 'Add task usage to the workspace.', status: 'remote-open',
        createdAt: 0, pushedAt: 0, remotePrNumber: 5680, remotePrUrl: 'https://example.test/5680',
        mergedAt: null, closedAt: null, origin: 'task', repo: 'trinodb/trino', author: 'octocat',
        syncedAt: null, syncedAdditions: 3, syncedDeletions: 1, syncedMergeable: null,
        syncedMergeableState: null, syncedMergeQueueEnabled: false, syncedMergeQueueState: null,
        branchDeletedAt: null,
      },
      commits: [], timeline: [], checks: [], comments: [],
    } as LocalPRBundle;
    const onOpenCode = vi.fn();
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn().mockResolvedValue(buildMockBrainView(0)),
      getPrForTask: vi.fn().mockResolvedValue(bundle.pr),
      getLocalPrBundle: vi.fn().mockResolvedValue(bundle),
      getTaskCumulativeDiff: vi.fn().mockResolvedValue([file]),
      getAgentReview: vi.fn().mockResolvedValue(null),
      fetchPullRequestDetail: vi.fn().mockResolvedValue({ recentActivity: [], reviewThreads: [] }),
      fetchPrDiffFiles: vi.fn().mockResolvedValue([file]),
    };

    render(
      <TaskBrainRoute
        threadId="t1"
        taskId="task-pr-changes"
        onOpenStage={() => {}}
        onOpenCode={onOpenCode}
        onClosed={() => {}}
      />,
    );

    fireEvent.click(await screen.findByRole('button', { name: 'Review' }));
    const changesTab = screen.getAllByRole('button', { name: /Changes/ })
      .find(button => button.closest('.workspace-task-v2__pr') !== null) as HTMLButtonElement;
    await waitFor(() => expect(changesTab.style.fontWeight).toBe('600'));
    expect(onOpenCode).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: /Overview/ }));
    const changesPill = screen.getAllByRole('button', { name: /Changes/ })
      .find(button => button.classList.contains('workspace-task-artifact-pill')) as HTMLButtonElement;
    fireEvent.click(changesPill);
    await waitFor(() => expect(changesTab.style.fontWeight).toBe('600'));
    expect(onOpenCode).not.toHaveBeenCalled();
  });

  it('shows the root-node plan with the review bar when the plan awaits the user', async () => {
    const approvePlan = vi.fn().mockResolvedValue({ devStageId: 'dev-9', redirectUrl: '' });
    const base = buildMockBrainView(0);
    const plan: PlanCardDto = {
      planStageId: 'plan-1', state: 'awaiting', status: 'finalized', source: 'brain',
      understandingSummary: 'Add a cost meter to the rail', intentSummary: 'wire it',
      steps: [{ ordinal: 1, action: 'Build the meter' }], validationStrategy: 'tests',
      pushStrategy: 'await_approval',
      signals: { riskLevel: 'low', estimatedComplexity: 'small', componentsCount: 2, expectedGain: 'x' },
      revisionCount: 0, followups: [],
    };
    const view = { ...base, rightRail: { ...base.rightRail, plan } };
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn().mockResolvedValue(view),
      sendBrainMessage: vi.fn().mockResolvedValue({}),
      approvePlan,
    };
    const onOpenStage = vi.fn();
    render(<TaskBrainRoute threadId="t1" taskId="task-1" onOpenStage={onOpenStage} onOpenCode={() => {}} onClosed={() => {}} />);

    // The plan renders inline as the root node → its step + the review bar's
    // "Approve & start dev" action show.
    expect(await screen.findByText('Build the meter')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /Approve & start dev/ }));
    await waitFor(() => expect(approvePlan).toHaveBeenCalledWith('plan-1'));
    await waitFor(() => expect(onOpenStage).toHaveBeenCalledWith('dev-9'));
  });

  it('renders the auto-approve toggle only in the plan card, not as a top-bar button', async () => {
    const base = buildMockBrainView(0);
    const plan: PlanCardDto = {
      planStageId: 'plan-1', state: 'awaiting', status: 'finalized', source: 'brain',
      understandingSummary: 'Add a cost meter to the rail', intentSummary: 'wire it',
      steps: [{ ordinal: 1, action: 'Build the meter' }], validationStrategy: 'tests',
      pushStrategy: 'await_approval',
      signals: { riskLevel: 'low', estimatedComplexity: 'small', componentsCount: 2, expectedGain: 'x' },
      revisionCount: 0, followups: [],
    };
    const view = { ...base, rightRail: { ...base.rightRail, plan } };
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn().mockResolvedValue(view),
      sendBrainMessage: vi.fn().mockResolvedValue({}),
      getTaskAutoApprove: vi.fn().mockResolvedValue({ enabled: false }),
      setTaskAutoApprove: vi.fn().mockResolvedValue({ enabled: true }),
    };
    render(<TaskBrainRoute threadId="t1" taskId="task-1" onOpenStage={() => {}} onOpenCode={() => {}} onClosed={() => {}} />);

    await screen.findByText('Build the meter');
    // Auto-approve lives in the plan card's policy toolbar, not the top bar.
    expect(document.querySelector('.plan-pipeline-card')).toBeTruthy();
    expect(screen.getByText('Auto-approve')).toBeTruthy();
    // The old top-bar button (with its dynamic "Auto-approve on/off" label) is gone.
    expect(screen.queryByText(/^Auto-approve (on|off)$/)).toBeNull();
  });
});

describe('StageDetailRoute', () => {
  it('mounts the V3 stage page and steers the stage agent', async () => {
    const steerStage = vi.fn().mockResolvedValue({ turnId: 'x' });
    // getStageDetail never resolves → renders the loading defaults.
    (window as unknown as { bridge: unknown }).bridge = {
      getStageDetail: vi.fn(() => new Promise(() => {})),
      steerStage,
    };
    render(<StageDetailRoute threadId="t1" taskId="task-1" stageId="stage-1" onOpenCode={() => {}} />);

    expect(document.querySelector('.shell')).toBeTruthy();
    expect(document.querySelector('.workspace-task-header__badge')?.textContent).toBe('DEV STAGE');

    const box = screen.getByRole('textbox');
    fireEvent.change(box, { target: { value: 'fix the import' } });
    fireEvent.keyDown(box, { key: 'Enter' });
    await waitFor(() => expect(steerStage).toHaveBeenCalledWith('stage-1', 'fix the import', []));
  });
});
