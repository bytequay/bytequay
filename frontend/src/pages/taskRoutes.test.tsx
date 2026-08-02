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
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { TaskBrainRoute } from './TaskBrainRoute';
import { StageDetailRoute } from './StageDetailRoute';
import { buildMockBrainView } from '../threads/brain/brainViewFixture';
import type { AgentRunDto, PlanCardDto, StageDetailData, TaskBrainViewData } from '../types/brainView';
import type { DiffFileDto, ThreadCommitDto } from '../types';
import type { LocalPRBundle, LocalPRCommit } from '../types/localPr';

// jsdom lacks scrollIntoView; the shared conversation may call it.
beforeAll(() => { Element.prototype.scrollIntoView = vi.fn(); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); Reflect.deleteProperty(window, 'bridge'); localStorage.clear(); });

describe('TaskBrainRoute', () => {
  it('renders a budget pause as an amber ask and opens workspace Agents settings', async () => {
    const taskId = 'task-budget-action';
    const base = buildMockBrainView(0);
    const view: TaskBrainViewData = {
      ...base,
      task: { ...base.task, id: taskId, paused: true },
      rightRail: {
        ...base.rightRail,
        approval: {
          tone: 'ask',
          stageId: 'dev-1',
          stageTitle: 'Task paused at budget cap',
          reasonShort: 'daily workspace budget cap reached ($10.00)',
          pendingArtifact: 'Increase the workspace agent budget, then resume this task.',
          primaryAction: {
            label: 'Increase budget',
            href: '#/workspace/ws-default/settings/agents',
          },
        },
      },
    };
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn().mockResolvedValue(view),
      getPrForTask: vi.fn().mockResolvedValue(null),
      getLocalPrBundle: vi.fn().mockResolvedValue(null),
      listNotificationsForThread: vi.fn().mockResolvedValue([]),
      getTaskCumulativeDiff: vi.fn().mockResolvedValue([]),
      listTaskCommits: vi.fn().mockResolvedValue([]),
      getAgentReview: vi.fn().mockResolvedValue(null),
    };
    window.location.hash = '#/workspace/ws-default/trunks/t1';

    render(<TaskBrainRoute threadId="t1" taskId={taskId} onOpenStage={() => {}} onClosed={() => {}} />);

    const action = await screen.findByRole('button', { name: 'Increase budget' });
    expect(action.closest('.sp-gate--ask')).toBeTruthy();
    fireEvent.click(action);
    expect(window.location.hash).toBe('#/workspace/ws-default/settings/agents');
  });

  it('renders a V2 blocker without a primary action', async () => {
    const taskId = 'task-actionless-blocker';
    const base = buildMockBrainView(0);
    const view: TaskBrainViewData = {
      ...base,
      task: {
        ...base.task, id: taskId, paused: true,
        currentPhase: 'NEEDS_ATTENTION', statusLabel: 'NEEDS ATTENTION',
      },
      rightRail: {
        ...base.rightRail,
        approval: {
          tone: 'ask',
          stageId: 'dev-1',
          stageTitle: 'Task needs attention',
          reasonShort: 'operation failed',
          pendingArtifact: 'blocker-1',
          primaryAction: null,
        },
      },
    };
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn().mockResolvedValue(view),
      getPrForTask: vi.fn().mockResolvedValue(null),
      getLocalPrBundle: vi.fn().mockResolvedValue(null),
      listNotificationsForThread: vi.fn().mockResolvedValue([]),
      getTaskCumulativeDiff: vi.fn().mockResolvedValue([]),
      listTaskCommits: vi.fn().mockResolvedValue([]),
      getAgentReview: vi.fn().mockResolvedValue(null),
    };

    render(<TaskBrainRoute threadId="t1" taskId={taskId} onOpenStage={() => {}} onClosed={() => {}} />);

    expect(await screen.findByText('Task needs attention')).toBeTruthy();
    await waitFor(() => expect(screen.getByText('operation failed — blocker-1')).toBeTruthy());
    expect(document.querySelector('.sp-appr__actions')).toBeNull();
    expect(screen.queryByRole('button', { name: /Resume/ })).toBeNull();
  });

  it('retries the exact failed Plan draft without resuming the Task', async () => {
    const taskId = 'task-plan-retry';
    const base = buildMockBrainView(0);
    const view: TaskBrainViewData = {
      ...base,
      task: {
        ...base.task, id: taskId, paused: true,
        currentPhase: 'NEEDS_ATTENTION', statusLabel: 'NEEDS ATTENTION',
      },
      recovery: {
        kind: 'RETRY_PLAN_DRAFT', stageId: 'plan-1',
        blockerId: 'blocker-1', failedTurnId: 'turn-1',
      },
      rightRail: {
        ...base.rightRail,
        approval: {
          tone: 'ask', stageId: 'plan-1', stageTitle: 'Task needs attention',
          reasonShort: 'operation failed', pendingArtifact: 'blocker-1',
          primaryAction: null,
        },
      },
    };
    const recoverV2Plan = vi.fn().mockResolvedValue({});
    const resumePausedTask = vi.fn().mockResolvedValue({});
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn().mockResolvedValue(view),
      getPrForTask: vi.fn().mockResolvedValue(null),
      getLocalPrBundle: vi.fn().mockResolvedValue(null),
      listNotificationsForThread: vi.fn().mockResolvedValue([]),
      getTaskCumulativeDiff: vi.fn().mockResolvedValue([]),
      listTaskCommits: vi.fn().mockResolvedValue([]),
      getAgentReview: vi.fn().mockResolvedValue(null),
      recoverV2Plan,
      resumePausedTask,
    };

    render(<TaskBrainRoute threadId="t1" taskId={taskId} onOpenStage={() => {}} onClosed={() => {}} />);

    fireEvent.click(await screen.findByRole('button', {
      name: 'Retry Plan · NEEDS ATTENTION',
    }));
    await waitFor(() => expect(recoverV2Plan).toHaveBeenCalledWith(
      taskId,
      'turn-1',
      expect.objectContaining({
        blockerId: 'blocker-1',
        reason: 'Explicit Retry Plan action from the Task run control',
      }),
    ));
    expect(resumePausedTask).not.toHaveBeenCalled();
  });

  it('retries the exact malformed Development Brain review and disables ordinary Brain messages', async () => {
    const taskId = 'task-brain-review-retry';
    const base = buildMockBrainView(0);
    const view: TaskBrainViewData = {
      ...base,
      task: {
        ...base.task, id: taskId, paused: true,
        currentPhase: 'NEEDS_ATTENTION', statusLabel: 'NEEDS ATTENTION',
      },
      recovery: {
        kind: 'RETRY_DEVELOPMENT_BRAIN_REVIEW', stageId: 'local-stage-1',
        blockerId: 'brain-blocker-1', failedTurnId: 'brain-turn-1',
      },
    };
    const recoverV2DevelopmentBrainReview = vi.fn().mockResolvedValue({});
    const resumePausedTask = vi.fn().mockResolvedValue({});
    const sendBrainMessage = vi.fn().mockResolvedValue({});
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn().mockResolvedValue(view),
      getPrForTask: vi.fn().mockResolvedValue(null),
      getLocalPrBundle: vi.fn().mockResolvedValue(null),
      listNotificationsForThread: vi.fn().mockResolvedValue([]),
      getTaskCumulativeDiff: vi.fn().mockResolvedValue([]),
      listTaskCommits: vi.fn().mockResolvedValue([]),
      getAgentReview: vi.fn().mockResolvedValue(null),
      recoverV2DevelopmentBrainReview,
      resumePausedTask,
      sendBrainMessage,
    };

    render(<TaskBrainRoute threadId="t1" taskId={taskId} onOpenStage={() => {}} onClosed={() => {}} />);

    const composer = await screen.findByRole('textbox', { name: 'Message' });
    await waitFor(() => expect((composer as HTMLTextAreaElement).disabled).toBe(true));
    fireEvent.change(composer, { target: { value: 'start another Brain turn' } });
    fireEvent.keyDown(composer, { key: 'Enter' });
    expect(sendBrainMessage).not.toHaveBeenCalled();

    const retry = await screen.findByRole('button', {
      name: 'Retry Brain review · NEEDS ATTENTION',
    });
    fireEvent.click(retry);
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', {
      name: 'Retry Brain review',
    }));
    await waitFor(() => expect(recoverV2DevelopmentBrainReview).toHaveBeenCalledTimes(1));
    const firstCommandId = recoverV2DevelopmentBrainReview.mock.calls[0][2].commandId;

    fireEvent.click(await screen.findByRole('button', {
      name: 'Retry Brain review · NEEDS ATTENTION',
    }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', {
      name: 'Retry Brain review',
    }));
    await waitFor(() => expect(recoverV2DevelopmentBrainReview).toHaveBeenCalledTimes(2));
    expect(recoverV2DevelopmentBrainReview).toHaveBeenLastCalledWith(
      taskId,
      'brain-turn-1',
      {
        blockerId: 'brain-blocker-1',
        commandId: firstCommandId,
        reason: 'Explicit Retry Development Brain review action from the Task run control',
      },
    );
    expect(resumePausedTask).not.toHaveBeenCalled();
  });

  it('retries the exact failed Remote repair Brain review without resuming the Task', async () => {
    const taskId = 'task-remote-brain-retry';
    const base = buildMockBrainView(0);
    const view: TaskBrainViewData = {
      ...base,
      task: {
        ...base.task, id: taskId, paused: true,
        currentPhase: 'NEEDS_ATTENTION', statusLabel: 'NEEDS ATTENTION',
      },
      recovery: {
        kind: 'RETRY_REMOTE_REPAIR_BRAIN_REVIEW', stageId: 'remote-stage-1',
        blockerId: 'remote-brain-blocker-1', failedTurnId: 'remote-brain-turn-1',
      },
    };
    const recoverV2RemoteRepairBrainReview = vi.fn().mockResolvedValue({});
    const resumePausedTask = vi.fn().mockResolvedValue({});
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn().mockResolvedValue(view),
      getPrForTask: vi.fn().mockResolvedValue(null),
      getLocalPrBundle: vi.fn().mockResolvedValue(null),
      listNotificationsForThread: vi.fn().mockResolvedValue([]),
      getTaskCumulativeDiff: vi.fn().mockResolvedValue([]),
      listTaskCommits: vi.fn().mockResolvedValue([]),
      getAgentReview: vi.fn().mockResolvedValue(null),
      recoverV2RemoteRepairBrainReview,
      resumePausedTask,
    };

    render(<TaskBrainRoute threadId="t1" taskId={taskId} onOpenStage={() => {}} onClosed={() => {}} />);

    fireEvent.click(await screen.findByRole('button', {
      name: 'Retry Brain review · NEEDS ATTENTION',
    }));
    await waitFor(() => expect(screen.getByText('Retry Remote repair Brain review?')).toBeTruthy());
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', {
      name: 'Retry Brain review',
    }));
    await waitFor(() => expect(recoverV2RemoteRepairBrainReview).toHaveBeenCalledWith(
      taskId,
      'remote-brain-turn-1',
      expect.objectContaining({
        blockerId: 'remote-brain-blocker-1',
        reason: 'Explicit Retry Remote repair Brain review action from the Task run control',
      }),
    ));
    expect(resumePausedTask).not.toHaveBeenCalled();
  });

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
    render(<TaskBrainRoute threadId="t1" taskId="task-1" onOpenStage={() => {}} onClosed={() => {}} />);
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
    render(<TaskBrainRoute threadId="t1" taskId="task-1" onOpenStage={() => {}} onClosed={() => {}} />);

    // The locked shell + BRAIN badge render from the fixture.
    expect(document.querySelector('.shell')).toBeTruthy();
    expect(document.querySelector('.shell.full-width')).toBeNull();
    expect(document.querySelector('.workspace-task-sidebar-v2')).toBeTruthy();
    expect(screen.getByText('STAGES')).toBeTruthy();
    expect(document.querySelector('.workspace-task-header__badge')?.textContent).toBe('BRAIN');

    expect(screen.queryByTitle('Usage')).toBeNull();
    expect(screen.queryByTitle('Voice input')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Add context' })).toBeNull();

    const box = screen.getByRole('textbox');
    fireEvent.change(box, { target: { value: 'what next?' } });
    fireEvent.keyDown(box, { key: 'Enter' });
    await waitFor(() => expect(sendBrainMessage).toHaveBeenCalledWith('task-1', 'what next?', []));
    // The working indicator appears while awaiting the brain's reply.
    expect(await screen.findByText('Brain is thinking…')).toBeTruthy();
  });

  it('removes the task-specific sidebar when the shared app rail is collapsed', () => {
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn(() => new Promise(() => {})),
    };
    render(
      <TaskBrainRoute
        threadId="t1"
        taskId="task-1"
        collapsed
        onOpenStage={() => {}}
        onClosed={() => {}}
      />,
    );

    expect(document.querySelector('.shell.full-width')).toBeTruthy();
    expect(document.querySelector('.workspace-task-sidebar-v2')).toBeNull();
  });

  it('opens the embedded PR Changes tab and zooms the same detail in place', async () => {
    const file: DiffFileDto = {
      filename: 'frontend/src/App.tsx', status: 'modified', additions: 3, deletions: 1, patch: null,
    };
    const bundle = {
      pr: {
        id: 'task-pr', taskId: 'task-pr-changes', branchName: 'jack/cost-meter', baseBranch: 'master',
        title: 'Cost-meter widget', description: 'Add task usage to the workspace.', status: 'local-drafted',
        createdAt: 0, pushedAt: null, remotePrNumber: null, remotePrUrl: null,
        mergedAt: null, closedAt: null, origin: 'task', repo: 'trinodb/trino', author: 'octocat',
        syncedAt: null, syncedAdditions: 3, syncedDeletions: 1, syncedMergeable: null,
        syncedMergeableState: null, syncedMergeQueueEnabled: false, syncedMergeQueueState: null,
        branchDeletedAt: null,
      },
      commits: Array.from({ length: 4 }, (_, index): LocalPRCommit => ({
        id: `stale-${index}`, localPrId: 'task-pr', sha: `stale-${index}`, message: 'superseded',
        additions: 0, deletions: 0, authoredAt: 0, pushedAt: null,
      })),
      timeline: [], checks: [], comments: [],
    } as LocalPRBundle;
    const branchCommits: ThreadCommitDto[] = Array.from({ length: 2 }, (_, index) => ({
      sha: `current-${index}`, shortSha: `current-${index}`, authorName: 'Jack', authorEmail: 'jack@example.com',
      authoredAt: '2026-01-01T00:00:00Z', subject: `Current commit ${index + 1}`,
    }));
    const getLocalPrBundle = vi.fn().mockResolvedValue(bundle);
    const fetchPrDiffFiles = vi.fn().mockResolvedValue([file]);
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn().mockResolvedValue(buildMockBrainView(0)),
      getPrForTask: vi.fn().mockResolvedValue(bundle.pr),
      getLocalPrBundle,
      getTaskCumulativeDiff: vi.fn().mockResolvedValue([file]),
      listTaskCommits: vi.fn().mockResolvedValue(branchCommits),
      getAgentReview: vi.fn().mockResolvedValue(null),
      fetchPullRequestDetail: vi.fn().mockResolvedValue({ recentActivity: [], reviewThreads: [] }),
      fetchPrDiffFiles,
      fetchTaskFileBlob: vi.fn().mockResolvedValue({ lines: [] }),
    };

    render(
      <TaskBrainRoute
        threadId="t1"
        taskId="task-pr-changes"
        initialPrSubTab="changes"
        onOpenStage={() => {}}
        onClosed={() => {}}
      />,
    );

    const changedFilesCard = (await screen.findByText('Changed 1 file'))
      .closest('.workspace-task-files-card') as HTMLElement;
    expect(await within(changedFilesCard).findByText('2 commits')).toBeTruthy();
    expect(within(changedFilesCard).queryByText('4 commits')).toBeNull();
    const reviewButton = within(changedFilesCard).getByRole('button', { name: 'Review' });
    const changesTab = (await screen.findAllByRole('button', { name: /Changes/ }))
      .find(button => button.closest('.workspace-task-v2__pr') !== null) as HTMLButtonElement;
    await waitFor(() => expect(changesTab.style.fontWeight).toBe('600'));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Toggle PR panel' }).getAttribute('aria-pressed')).toBe('true'));
    expect(fetchPrDiffFiles).not.toHaveBeenCalled();

    const fetchesBeforeReview = getLocalPrBundle.mock.calls.length;
    fireEvent.click(reviewButton);
    await waitFor(() => expect(changesTab.style.fontWeight).toBe('600'));
    await waitFor(() => expect(getLocalPrBundle.mock.calls.length).toBeGreaterThan(fetchesBeforeReview));

    const overviewTab = screen.getByRole('button', { name: /Overview/ });
    fireEvent.click(overviewTab);
    const changesPill = screen.getAllByRole('button', { name: /Changes/ })
      .find(button => button.classList.contains('workspace-task-artifact-pill')) as HTMLButtonElement;
    const fetchesBeforePill = getLocalPrBundle.mock.calls.length;
    fireEvent.click(changesPill);
    await waitFor(() => expect(changesTab.style.fontWeight).toBe('600'));
    await waitFor(() => expect(getLocalPrBundle.mock.calls.length).toBeGreaterThan(fetchesBeforePill));

    const prPill = screen.getAllByRole('button', { name: /PR #5680/ })
      .find(button => button.classList.contains('workspace-task-artifact-pill')) as HTMLButtonElement;
    fireEvent.click(prPill);
    await waitFor(() => expect(overviewTab.style.fontWeight).toBe('600'));

    fireEvent.click(screen.getByRole('button', { name: 'Open in workspace' }));
    await waitFor(() => expect(changesTab.style.fontWeight).toBe('600'));

    const taskPage = document.querySelector('.workspace-task-v2');
    const hash = window.location.hash;
    fireEvent.click(screen.getByRole('button', { name: 'Maximize pull request details' }));
    const dialog = screen.getByRole('dialog', { name: 'Pull request details' });
    await waitFor(() => expect(document.querySelector('.workspace-task-v2')).toBe(taskPage));
    await waitFor(() => expect(within(dialog).getByRole('button', { name: /Changes/ })).toBe(changesTab));
    expect(changesTab.style.fontWeight).toBe('600');
    expect(window.location.hash).toBe(hash);

    fireEvent.click(within(dialog).getByRole('button', { name: 'Close pull request details' }));
    expect(screen.queryByRole('dialog', { name: 'Pull request details' })).toBeNull();
    await waitFor(() => expect(document.querySelector('.workspace-task-v2')).toBe(taskPage));
  });

  it('owns Local Review approval in the conversation, not the right Overview panel', async () => {
    const taskId = 'task-local-review-gate';
    const bundle: LocalPRBundle = {
      pr: {
        id: 'local-review-pr', taskId, branchName: 'dev/local-review', baseBranch: 'main',
        title: 'Local review change', description: 'Ready for review', status: 'local-open',
        createdAt: 0, pushedAt: null, remotePrNumber: null, remotePrUrl: null,
        mergedAt: null, closedAt: null, origin: 'task', repo: 'bytequay/app', author: 'agent',
        syncedAt: null, syncedAdditions: 2, syncedDeletions: 1, syncedMergeable: null,
        syncedMergeableState: null, syncedMergeQueueEnabled: false,
        syncedMergeQueueState: null, branchDeletedAt: null,
      },
      commits: [], timeline: [], checks: [{
        id: 'local-check', localPrId: 'local-review-pr', kind: 'local', name: 'Local tests',
        status: 'passed', durationMs: 10, startedAt: 1, finishedAt: 11, runId: 'run-1',
      }], comments: [],
    };
    const base = buildMockBrainView(0);
    const view: TaskBrainViewData = {
      ...base,
      task: {
        ...base.task, id: taskId, prNumber: null, prDraft: false, currentPhase: 'AWAITING_PUSH',
      },
      rightRail: { ...base.rightRail, linkedPr: null },
      devPhases: [
        { key: 'implementing', status: 'done', meta: null, badgeRunId: null },
        { key: 'validation', status: 'done', meta: null, badgeRunId: null },
        { key: 'brainReview', status: 'done', meta: 'brain approved', badgeRunId: null },
      ],
    };
    const approveNotification = vi.fn();
    const submitReview = vi.fn().mockResolvedValue({ submitted: 0, turnId: null });
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn().mockResolvedValue(view),
      getPrForTask: vi.fn().mockResolvedValue(bundle.pr),
      getLocalPrBundle: vi.fn().mockResolvedValue(bundle),
      listNotificationsForThread: vi.fn().mockResolvedValue([]),
      getTaskCumulativeDiff: vi.fn().mockResolvedValue([]),
      listTaskCommits: vi.fn().mockResolvedValue([]),
      getAgentReview: vi.fn().mockResolvedValue(null),
      approveNotification,
      submitReview,
    };

    render(<TaskBrainRoute threadId="t1" taskId={taskId} onOpenStage={() => {}} onClosed={() => {}} />);

    const approve = await screen.findByRole('button', { name: 'Approve & ship' });
    expect(approve.closest('.workspace-task-v2__conversation')).toBeTruthy();
    const prPane = document.querySelector('.workspace-task-v2__pr') as HTMLElement;
    await waitFor(() => expect(within(prPane).getByText('All checks have passed')).toBeTruthy());
    expect(prPane.querySelector('.merge-box')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Submit review • 0' }));
    const reviewDialog = screen.getByRole('dialog', { name: 'Submit review' });
    fireEvent.click(within(reviewDialog).getByRole('button', { name: 'Submit review' }));
    await waitFor(() => expect(submitReview).toHaveBeenCalledWith(
      taskId, { body: '', verdict: 'COMMENT', commentIds: [] },
    ));
    fireEvent.click(approve);
    expect(await screen.findByRole('dialog')).toBeTruthy();
    await waitFor(() => expect(screen.getByText('Push to GitHub')).toBeTruthy());
    expect(approveNotification).not.toHaveBeenCalled();
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
    render(<TaskBrainRoute threadId="t1" taskId="task-1" onOpenStage={onOpenStage} onClosed={() => {}} />);

    // The plan renders inline as the root node → its step + the review bar's
    // "Approve & start dev" action show.
    expect(await screen.findByText('Build the meter')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /Approve & start dev/ }));
    await waitFor(() => expect(approvePlan).toHaveBeenCalledWith('plan-1'));
    await waitFor(() => expect(onOpenStage).toHaveBeenCalledWith('dev-9'));
  });

  it('surfaces plan approval failures instead of making the button look inert', async () => {
    const approvePlan = vi.fn().mockRejectedValue(new Error('backend approve returned 500'));
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

    render(<TaskBrainRoute threadId="t1" taskId="task-1" onOpenStage={() => {}} onClosed={() => {}} />);

    fireEvent.click(await screen.findByRole('button', { name: /Approve & start dev/ }));
    expect((await screen.findByRole('alert')).textContent).toContain('backend approve returned 500');
  });

  it('labels an unfinished root-node plan as drafting', async () => {
    const base = buildMockBrainView(0);
    const plan: PlanCardDto = {
      planStageId: 'plan-1', state: 'draft', status: 'suggested', source: 'brain',
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
    };

    render(<TaskBrainRoute threadId="t1" taskId="task-1" onOpenStage={() => {}} onClosed={() => {}} />);

    await screen.findByText('Build the meter');
    // The steps render before the card's own copy settles, so reading the
    // label synchronously off the first match races the last update.
    await waitFor(() => expect(
      document.querySelector('.plan-feed-event__copy strong')?.textContent)
      .toBe('Plan drafting'));
  });

  it('labels a finalized plan as under Brain review until approval unlocks', async () => {
    const base = buildMockBrainView(0);
    const plan: PlanCardDto = {
      planStageId: 'plan-1', state: 'draft', status: 'finalized', source: 'brain',
      understandingSummary: 'Add a cost meter to the rail', intentSummary: 'wire it',
      steps: [{ ordinal: 1, action: 'Build the meter' }], validationStrategy: 'tests',
      pushStrategy: 'await_approval',
      signals: { riskLevel: 'low', estimatedComplexity: 'small', componentsCount: 2, expectedGain: 'x' },
      revisionCount: 0, followups: [],
    };
    const view: TaskBrainViewData = {
      ...base,
      liveRuns: [{
        id: 'plan-review-1', taskId: 'task-1', kind: 'plan' as const, source: 'scheduled' as const,
        parentStageId: 'plan-1', reviewRoundId: null, stageId: 'plan-1', status: 'running' as const,
        iterations: 0, budget: null, headline: null,
        startedAt: '2026-07-27T03:06:12Z', finishedAt: null,
      }],
      rightRail: { ...base.rightRail, plan },
    };
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn().mockResolvedValue(view),
      sendBrainMessage: vi.fn().mockResolvedValue({}),
    };

    const onOpenRun = vi.fn();
    render(<TaskBrainRoute
      threadId="t1" taskId="task-1" onOpenStage={() => {}} onOpenRun={onOpenRun} onClosed={() => {}}
    />);

    await screen.findByText('Build the meter');
    // The label also depends on the Task runs load, which lands after the
    // plan steps render.
    await waitFor(() => expect(
      document.querySelector('.plan-feed-event__copy strong')?.textContent)
      .toBe('Brain reviewing plan'));
    const approveButton = screen.getByRole('button', { name: /Approve & start dev/ }) as HTMLButtonElement;
    expect(approveButton.disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'View review log' }));
    expect(onOpenRun).toHaveBeenCalledWith('plan-review-1');
  });

  it('keeps the completed plan review log available', async () => {
    const base = buildMockBrainView(0);
    const plan: PlanCardDto = {
      planStageId: 'plan-1', state: 'awaiting', status: 'finalized', source: 'brain',
      understandingSummary: 'Add a cost meter to the rail', intentSummary: 'wire it',
      steps: [{ ordinal: 1, action: 'Build the meter' }], validationStrategy: 'tests',
      pushStrategy: 'await_approval',
      signals: { riskLevel: 'low', estimatedComplexity: 'small', componentsCount: 2, expectedGain: 'x' },
      revisionCount: 0, followups: [],
    };
    const view: TaskBrainViewData = { ...base, liveRuns: [], rightRail: { ...base.rightRail, plan } };
    const completedRun: AgentRunDto = {
      id: 'plan-review-done', taskId: 'task-1', kind: 'plan' as const, source: 'scheduled' as const,
      parentStageId: 'plan-1', reviewRoundId: null, stageId: 'plan-1', status: 'succeeded' as const,
      iterations: 0, budget: null, headline: null,
      startedAt: '2026-07-27T03:06:12Z', finishedAt: '2026-07-27T03:09:14Z',
    };
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn().mockResolvedValue(view),
      getTaskRuns: vi.fn().mockResolvedValue([completedRun]),
      sendBrainMessage: vi.fn().mockResolvedValue({}),
    };
    const onOpenRun = vi.fn();

    render(<TaskBrainRoute
      threadId="t1" taskId="task-1" onOpenStage={() => {}} onOpenRun={onOpenRun} onClosed={() => {}}
    />);

    fireEvent.click(await screen.findByRole('button', { name: 'View review log' }));
    expect(onOpenRun).toHaveBeenCalledWith('plan-review-done');
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
    render(<TaskBrainRoute threadId="t1" taskId="task-1" onOpenStage={() => {}} onClosed={() => {}} />);

    await screen.findByText('Build the meter');
    // Auto-approve lives in the plan card's policy toolbar, not the top bar.
    await waitFor(() => expect(document.querySelector('.plan-pipeline-card')).toBeTruthy());
    await waitFor(() => expect(screen.getByText('Auto-approve')).toBeTruthy());
    // The old top-bar button (with its dynamic "Auto-approve on/off" label) is gone.
    expect(screen.queryByText(/^Auto-approve (on|off)$/)).toBeNull();
  });

  it('keeps selected policy values and saves them before approving the plan', async () => {
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
    let resolveAutoApproveRead!: (value: { enabled: boolean }) => void;
    let resolveAutoMergeRead!: (value: { enabled: boolean }) => void;
    let resolveAutoApproveWrite!: (value: { enabled: boolean }) => void;
    let resolveAutoMergeWrite!: (value: { enabled: boolean }) => void;
    const getTaskAutoApprove = vi.fn(() => new Promise(resolve => { resolveAutoApproveRead = resolve; }));
    const getTaskAutoMerge = vi.fn(() => new Promise(resolve => { resolveAutoMergeRead = resolve; }));
    const setTaskAutoApprove = vi.fn(() => new Promise(resolve => { resolveAutoApproveWrite = resolve; }));
    const setTaskAutoMerge = vi.fn(() => new Promise(resolve => { resolveAutoMergeWrite = resolve; }));
    const approvePlan = vi.fn().mockResolvedValue({ devStageId: 'dev-1' });
    const onOpenStage = vi.fn();
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn().mockResolvedValue(view),
      sendBrainMessage: vi.fn().mockResolvedValue({}),
      getTaskAutoApprove,
      setTaskAutoApprove,
      getTaskAutoMerge,
      setTaskAutoMerge,
      approvePlan,
    };

    render(<TaskBrainRoute threadId="t1" taskId="task-1" onOpenStage={onOpenStage} onClosed={() => {}} />);

    await screen.findByText('Build the meter');
    const onButtons = screen.getAllByText('On', { selector: '.ppc-seg-cell' });
    fireEvent.click(onButtons[0]);
    fireEvent.click(onButtons[1]);
    resolveAutoApproveRead({ enabled: false });
    resolveAutoMergeRead({ enabled: false });
    await waitFor(() => onButtons.forEach(button => expect(button.getAttribute('aria-pressed')).toBe('true')));

    fireEvent.click(screen.getByText('Approve & start dev'));
    expect(approvePlan).not.toHaveBeenCalled();
    resolveAutoApproveWrite({ enabled: true });
    resolveAutoMergeWrite({ enabled: true });
    await waitFor(() => expect(approvePlan).toHaveBeenCalledWith('plan-1'));
    await waitFor(() => expect(onOpenStage).toHaveBeenCalledWith('dev-1'));
  });
});

describe('StageDetailRoute', () => {
  it('mounts quarantine-only recovery and disables ordinary Stage controls', async () => {
    const now = '2026-08-02T00:00:00Z';
    const base = buildMockBrainView(0);
    const view: TaskBrainViewData = {
      ...base,
      task: {
        ...base.task,
        id: 'task-quarantined',
        paused: true,
        terminal: false,
        currentPhase: 'IMPLEMENTING',
        statusLabel: 'Development',
      },
    };
    const detail: StageDetailData = {
      task: {
        id: 'task-quarantined', taskNumber: 1, title: 'Quarantined task',
        branch: 'dev/task-quarantined', repoFullName: 'bytequay/app',
        prNumber: null, prDraft: false, currentPhase: 'IMPLEMENTING',
        agentRuntime: 'CLI', agentModel: 'codex',
      },
      stage: {
        id: 'stage-current', type: 'DEVELOPMENT_STAGE', state: 'OPEN',
        openedAt: now, closedAt: null, callerStageId: null,
        iterationCount: 0, currentIterationNumber: null, agentActive: false,
        config: { internalReviewEnabled: false },
        metrics: { panelInvocationsCount: 0 },
      },
      allStages: [], subStages: [], conversationThreadId: 'stage-thread',
      iterations: [], conversation: [], realtimeCi: null, ciFixHistory: [],
      pr: null,
      context: { tokensUsed: 0, tokensLimit: 200_000, safeBand: 'safe' },
      scrubber: { userMessages: [] }, liveRuns: [], guard: null,
      liveRound: null, devPhases: [],
      recovery: {
        replacement: null,
        failure: null,
        ci: null,
        cleanup: null,
        localPublishBaseSync: null,
        branchSync: null,
        worktreeQuarantine: {
          quarantineId: 'quarantine-1',
          blockerId: 'worktree-blocker',
          sourceOperationId: 'source-operation',
          taskEpoch: 3,
          stageId: 'stage-current',
          stageGeneration: 5,
          worktreePath: '/worktrees/task-quarantined',
          expectedBranchName: 'dev/task-quarantined',
          expectedCodeFingerprint: 'fingerprint-1',
          expectedHeadSha: 'head-1',
          expectedBaseSha: 'base-1',
          repairOperationId: null,
          repairStatus: null,
          message: 'The exact worktree could not be restored',
          actions: ['REPAIR_WORKTREE'],
        },
      },
    };
    const steerStage = vi.fn().mockResolvedValue({});
    const pauseTask = vi.fn().mockResolvedValue({});
    const resumePausedTask = vi.fn().mockResolvedValue({});
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn().mockResolvedValue(view),
      getStageDetail: vi.fn().mockResolvedValue(detail),
      getTaskRuns: vi.fn().mockResolvedValue([]),
      getTaskRounds: vi.fn().mockResolvedValue([]),
      getTaskCumulativeDiff: vi.fn().mockResolvedValue([]),
      listTaskCommits: vi.fn().mockResolvedValue([]),
      steerStage,
      pauseTask,
      resumePausedTask,
    };

    render(<StageDetailRoute
      threadId="t1" taskId="task-quarantined" stageId="stage-current" />);

    expect(await screen.findByText('Task worktree is quarantined')).toBeTruthy();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Repair worktree' })).toBeTruthy());
    const composer = screen.getByRole('textbox', { name: 'Message' });
    expect((composer as HTMLTextAreaElement).disabled).toBe(true);
    fireEvent.change(composer, { target: { value: 'ordinary stage turn' } });
    fireEvent.keyDown(composer, { key: 'Enter' });
    expect(steerStage).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: /^Resume ·/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Pause' })).toBeNull();
    expect(screen.getByRole('button', { name: /^development$/i })
      .getAttribute('aria-haspopup')).toBeNull();
    expect(pauseTask).not.toHaveBeenCalled();
    expect(resumePausedTask).not.toHaveBeenCalled();
  });

  it('edits task policy from the plan overlay and saves it before approval', async () => {
    const now = '2026-07-21T00:00:00Z';
    const base = buildMockBrainView(0);
    const plan: PlanCardDto = {
      planStageId: 'stage-plan', state: 'awaiting', status: 'finalized', source: 'brain',
      understandingSummary: 'Keep the policy editable from the Plan stage', intentSummary: 'wire the overlay',
      steps: [{ ordinal: 1, action: 'Persist the selected task policy' }],
      validationStrategy: 'route test', pushStrategy: 'await_approval',
      signals: { riskLevel: 'low', estimatedComplexity: 'small', componentsCount: 1, expectedGain: 'x' },
      revisionCount: 1, followups: [],
    };
    const view: TaskBrainViewData = {
      ...base,
      task: { ...base.task, id: 'task-plan-policy' },
      rightRail: { ...base.rightRail, plan },
    };
    const detail: StageDetailData = {
      task: {
        id: 'task-plan-policy', taskNumber: 1, title: 'Plan policy task',
        branch: 'dev/plan-policy', repoFullName: 'bytequay/app', prNumber: null,
        prDraft: false, currentPhase: 'PLANNING', agentRuntime: 'CLI', agentModel: 'codex',
      },
      stage: {
        id: 'stage-plan', type: 'PLAN_STAGE', state: 'OPEN', openedAt: now,
        closedAt: null, callerStageId: null, iterationCount: 0,
        currentIterationNumber: null, agentActive: false,
        config: { internalReviewEnabled: false }, metrics: { panelInvocationsCount: 0 },
      },
      allStages: [], subStages: [], conversationThreadId: 't1', iterations: [],
      conversation: [], realtimeCi: null, ciFixHistory: [], pr: null,
      context: { tokensUsed: 0, tokensLimit: 200_000, safeBand: 'safe' },
      scrubber: { userMessages: [] }, liveRuns: [], guard: null, liveRound: null,
      devPhases: [],
    };
    let resolveAutoMerge!: (value: { enabled: boolean }) => void;
    let resolveMinApprovals!: (value: { minApprovals: number }) => void;
    const setTaskAutoApprove = vi.fn().mockResolvedValue({ enabled: true });
    const setTaskAutoMerge = vi.fn(() => new Promise(resolve => { resolveAutoMerge = resolve; }));
    const setTaskMinApprovals = vi.fn(() => new Promise(resolve => { resolveMinApprovals = resolve; }));
    const approvePlan = vi.fn().mockResolvedValue({ devStageId: 'stage-dev' });
    const onOpenStage = vi.fn();
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn().mockResolvedValue(view),
      getStageDetail: vi.fn().mockResolvedValue(detail),
      getTaskRuns: vi.fn().mockResolvedValue([]),
      getTaskRounds: vi.fn().mockResolvedValue([]),
      getTaskAutoApprove: vi.fn().mockResolvedValue({ enabled: false }),
      getTaskAutoMerge: vi.fn().mockResolvedValue({ enabled: false }),
      getTaskMinApprovals: vi.fn().mockResolvedValue({ minApprovals: 0 }),
      setTaskAutoApprove,
      setTaskAutoMerge,
      setTaskMinApprovals,
      approvePlan,
    };

    render(<StageDetailRoute
      threadId="t1" taskId="task-plan-policy" stageId="stage-plan" onOpenStage={onOpenStage}
    />);

    fireEvent.click(await screen.findByRole('button', { name: /Plan awaiting your review/ }));
    const onButtons = screen.getAllByText('On', { selector: '.ppc-seg-cell' });
    expect(onButtons).toHaveLength(2);
    onButtons.forEach(button => expect((button as HTMLButtonElement).disabled).toBe(false));

    fireEvent.click(screen.getByText('2', { selector: '.ppc-seg-cell' }));
    fireEvent.click(onButtons[1]);
    await waitFor(() => onButtons.forEach(button => expect(button.getAttribute('aria-pressed')).toBe('true')));
    expect(setTaskAutoMerge).toHaveBeenCalledWith('t1', 'task-plan-policy', true);
    expect(setTaskAutoApprove).not.toHaveBeenCalled();

    fireEvent.click(screen.getByText('Approve & start dev'));
    expect(approvePlan).not.toHaveBeenCalled();
    resolveMinApprovals({ minApprovals: 2 });
    resolveAutoMerge({ enabled: true });
    await waitFor(() => expect(approvePlan).toHaveBeenCalledWith('stage-plan'));
    expect(onOpenStage).toHaveBeenCalledWith('stage-dev');
  });

  it('requires confirmation and uses the explicit retry action for exhausted CI', async () => {
    const now = '2026-07-21T00:00:00Z';
    const base = buildMockBrainView(0);
    const view: TaskBrainViewData = {
      ...base,
      task: {
        ...base.task, id: 'task-ci', paused: true, terminal: false,
        currentPhase: 'NEEDS_ATTENTION', statusLabel: 'ci fix attempts exhausted (5/5)',
        prNumber: 45,
      },
    };
    const detail: StageDetailData = {
      task: {
        id: 'task-ci', taskNumber: 1, title: 'CI task', branch: 'dev/ci-task',
        repoFullName: 'bytequay/app', prNumber: 45, prDraft: true,
        currentPhase: 'NEEDS_ATTENTION', agentRuntime: 'CLI', agentModel: 'codex',
      },
      stage: {
        id: 'stage-remote', type: 'REMOTE_DEVELOPMENT_STAGE', state: 'OPEN', openedAt: now,
        closedAt: null, callerStageId: null, iterationCount: 0, currentIterationNumber: null,
        agentActive: false,
        config: { internalReviewEnabled: false }, metrics: { panelInvocationsCount: 0 },
      },
      allStages: [], subStages: [], conversationThreadId: 't1', iterations: [], conversation: [],
      realtimeCi: null, ciFixHistory: [], pr: null,
      context: { tokensUsed: 0, tokensLimit: 200_000, safeBand: 'safe' },
      scrubber: { userMessages: [] }, liveRuns: [], guard: null, liveRound: null, devPhases: [],
    };
    const retryFailedCi = vi.fn().mockResolvedValue(undefined);
    const resumePausedTask = vi.fn().mockResolvedValue(undefined);
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn().mockResolvedValue(view),
      getStageDetail: vi.fn().mockResolvedValue(detail),
      getTaskRuns: vi.fn().mockResolvedValue([]),
      getTaskRounds: vi.fn().mockResolvedValue([]),
      getTaskCumulativeDiff: vi.fn().mockResolvedValue([]),
      listTaskCommits: vi.fn().mockResolvedValue([]),
      retryFailedCi,
      resumePausedTask,
    };

    render(<StageDetailRoute threadId="t1" taskId="task-ci" stageId="stage-remote" />);

    fireEvent.click(await screen.findByRole('button', {
      name: 'Retry CI · CI FIX ATTEMPTS EXHAUSTED (5/5)',
    }));
    expect(retryFailedCi).not.toHaveBeenCalled();
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Retry CI' }));
    await waitFor(() => expect(retryFailedCi).toHaveBeenCalledWith('t1', 'task-ci'));
    expect(resumePausedTask).not.toHaveBeenCalled();
  });

  it('retries the exact failed Plan draft from the Stage route', async () => {
    const now = '2026-07-21T00:00:00Z';
    const base = buildMockBrainView(0);
    const view: TaskBrainViewData = {
      ...base,
      task: {
        ...base.task, id: 'task-plan', paused: true, terminal: false,
        currentPhase: 'NEEDS_ATTENTION', statusLabel: 'NEEDS ATTENTION',
      },
      recovery: {
        kind: 'RETRY_PLAN_DRAFT', stageId: 'stage-plan',
        blockerId: 'blocker-plan', failedTurnId: 'turn-plan',
      },
    };
    const detail: StageDetailData = {
      task: {
        id: 'task-plan', taskNumber: 1, title: 'Plan task', branch: 'dev/plan-task',
        repoFullName: 'bytequay/app', prNumber: null, prDraft: false,
        currentPhase: 'NEEDS_ATTENTION', agentRuntime: 'CLI', agentModel: 'codex',
      },
      stage: {
        id: 'stage-plan', type: 'PLAN_STAGE', state: 'OPEN', openedAt: now,
        closedAt: null, callerStageId: null, iterationCount: 0,
        currentIterationNumber: null, agentActive: false,
        config: { internalReviewEnabled: false }, metrics: { panelInvocationsCount: 0 },
      },
      allStages: [], subStages: [], conversationThreadId: 't1', iterations: [],
      conversation: [], realtimeCi: null, ciFixHistory: [], pr: null,
      context: { tokensUsed: 0, tokensLimit: 200_000, safeBand: 'safe' },
      scrubber: { userMessages: [] }, liveRuns: [], guard: null, liveRound: null,
      devPhases: [],
    };
    const recoverV2Plan = vi.fn().mockResolvedValue({});
    const resumePausedTask = vi.fn().mockResolvedValue({});
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn().mockResolvedValue(view),
      getStageDetail: vi.fn().mockResolvedValue(detail),
      getTaskRuns: vi.fn().mockResolvedValue([]),
      getTaskRounds: vi.fn().mockResolvedValue([]),
      getTaskCumulativeDiff: vi.fn().mockResolvedValue([]),
      listTaskCommits: vi.fn().mockResolvedValue([]),
      recoverV2Plan,
      resumePausedTask,
    };

    render(<StageDetailRoute threadId="t1" taskId="task-plan" stageId="stage-plan" />);

    fireEvent.click(await screen.findByRole('button', {
      name: 'Retry Plan · NEEDS ATTENTION',
    }));
    await waitFor(() => expect(recoverV2Plan).toHaveBeenCalledWith(
      'task-plan',
      'turn-plan',
      expect.objectContaining({
        blockerId: 'blocker-plan',
        reason: 'Explicit Retry Plan action from the Stage run control',
      }),
    ));
    expect(resumePausedTask).not.toHaveBeenCalled();
  });

  it('retries the exact malformed Development Brain review from its active Local Stage', async () => {
    const now = '2026-07-21T00:00:00Z';
    const base = buildMockBrainView(0);
    const view: TaskBrainViewData = {
      ...base,
      task: {
        ...base.task, id: 'task-brain-stage', paused: true, terminal: false,
        currentPhase: 'NEEDS_ATTENTION', statusLabel: 'NEEDS ATTENTION',
      },
      recovery: {
        kind: 'RETRY_DEVELOPMENT_BRAIN_REVIEW', stageId: 'stage-brain-review',
        blockerId: 'brain-blocker-stage', failedTurnId: 'brain-turn-stage',
      },
    };
    const detail: StageDetailData = {
      task: {
        id: 'task-brain-stage', taskNumber: 1, title: 'Development task',
        branch: 'dev/brain-stage', repoFullName: 'bytequay/app', prNumber: null,
        prDraft: false, currentPhase: 'NEEDS_ATTENTION', agentRuntime: 'CLI', agentModel: 'codex',
      },
      stage: {
        id: 'stage-brain-review', type: 'DEVELOPMENT_STAGE', state: 'OPEN', openedAt: now,
        closedAt: null, callerStageId: null, iterationCount: 0,
        currentIterationNumber: null, agentActive: false,
        config: { internalReviewEnabled: false }, metrics: { panelInvocationsCount: 0 },
      },
      allStages: [], subStages: [], conversationThreadId: 't1', iterations: [],
      conversation: [], realtimeCi: null, ciFixHistory: [], pr: null,
      context: { tokensUsed: 0, tokensLimit: 200_000, safeBand: 'safe' },
      scrubber: { userMessages: [] }, liveRuns: [], guard: null, liveRound: null,
      devPhases: [],
      recovery: { replacement: null, failure: null, ci: null, cleanup: null },
    };
    const recoverV2DevelopmentBrainReview = vi.fn().mockResolvedValue({});
    const steerStage = vi.fn().mockResolvedValue({ turnId: 'unexpected' });
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn().mockResolvedValue(view),
      getStageDetail: vi.fn().mockResolvedValue(detail),
      getTaskRuns: vi.fn().mockResolvedValue([]),
      getTaskRounds: vi.fn().mockResolvedValue([]),
      getTaskCumulativeDiff: vi.fn().mockResolvedValue([]),
      listTaskCommits: vi.fn().mockResolvedValue([]),
      recoverV2DevelopmentBrainReview,
      steerStage,
    };

    render(<StageDetailRoute
      threadId="t1" taskId="task-brain-stage" stageId="stage-brain-review" />);

    const composer = await screen.findByRole('textbox', { name: 'Message' });
    await waitFor(() => expect((composer as HTMLTextAreaElement).disabled).toBe(true));
    fireEvent.change(composer, { target: { value: 'steer around the failed review' } });
    fireEvent.keyDown(composer, { key: 'Enter' });
    expect(steerStage).not.toHaveBeenCalled();

    fireEvent.click(await screen.findByRole('button', {
      name: 'Retry Brain review · NEEDS ATTENTION',
    }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', {
      name: 'Retry Brain review',
    }));
    await waitFor(() => expect(recoverV2DevelopmentBrainReview).toHaveBeenCalledWith(
      'task-brain-stage',
      'brain-turn-stage',
      expect.objectContaining({
        blockerId: 'brain-blocker-stage',
        reason: 'Explicit Retry Development Brain review action from the Stage run control',
      }),
    ));
    expect(steerStage).not.toHaveBeenCalled();
  });

  it('replaces the exact stalled stage directly even while it is projected as active', async () => {
    const now = '2026-07-21T00:00:00Z';
    const base = buildMockBrainView(0);
    const view: TaskBrainViewData = {
      ...base,
      task: {
        ...base.task, id: 'task-retry-stage', paused: false, terminal: false,
        currentPhase: 'IMPLEMENTING', statusLabel: 'Development',
      },
    };
    const detail: StageDetailData = {
      task: {
        id: 'task-retry-stage', taskNumber: 1, title: 'Development task',
        branch: 'dev/retry-stage', repoFullName: 'bytequay/app', prNumber: null,
        prDraft: false, currentPhase: 'IMPLEMENTING', agentRuntime: 'CLI', agentModel: 'codex',
      },
      stage: {
        id: 'stage-retry', type: 'DEVELOPMENT_STAGE', state: 'OPEN', openedAt: now,
        closedAt: null, callerStageId: null, iterationCount: 0,
        currentIterationNumber: null, agentActive: true,
        config: { internalReviewEnabled: false }, metrics: { panelInvocationsCount: 0 },
      },
      allStages: [], subStages: [], conversationThreadId: 't1', iterations: [],
      conversation: [], realtimeCi: null, ciFixHistory: [], pr: null,
      context: { tokensUsed: 0, tokensLimit: 200_000, safeBand: 'safe' },
      scrubber: { userMessages: [] }, liveRuns: [], guard: null, liveRound: null,
      devPhases: [],
      recovery: {
        replacement: { stageTurnId: 'turn-stalled', reason: 'Strict stage result could not be delivered' },
        ci: null,
        cleanup: null,
      },
    };
    const steerStage = vi.fn().mockResolvedValue({ turnId: 'turn-replacement' });
    const pauseTask = vi.fn().mockResolvedValue(undefined);
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn().mockResolvedValue(view),
      getStageDetail: vi.fn().mockResolvedValue(detail),
      getTaskRuns: vi.fn().mockResolvedValue([]),
      getTaskRounds: vi.fn().mockResolvedValue([]),
      getTaskCumulativeDiff: vi.fn().mockResolvedValue([]),
      listTaskCommits: vi.fn().mockResolvedValue([]),
      steerStage,
      pauseTask,
    };

    render(<StageDetailRoute threadId="t1" taskId="task-retry-stage" stageId="stage-retry" />);

    const composer = await screen.findByRole('textbox', { name: 'Message' });
    // The composer renders before the recovery projection lands, so it is
    // briefly enabled.
    await waitFor(() => expect((composer as HTMLTextAreaElement).disabled).toBe(true));
    fireEvent.change(composer, { target: { value: 'start another turn' } });
    fireEvent.keyDown(composer, { key: 'Enter' });
    expect(steerStage).not.toHaveBeenCalled();

    fireEvent.click(await screen.findByRole('button', { name: /Retry stage/ }));
    expect(steerStage).not.toHaveBeenCalled();
    expect(within(screen.getByRole('dialog')).getByText('Strict stage result could not be delivered'))
      .toBeTruthy();
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Retry stage' }));

    await waitFor(() => expect(steerStage).toHaveBeenCalledWith(
      'stage-retry',
      'Retry this stage from its durable context; complete the assigned work, run required validation, and return the strict stage result.',
      [],
      'CANCEL_AND_REPLACE',
      'turn-stalled',
    ));
    expect(pauseTask).not.toHaveBeenCalled();
  });

  it('retries an accepted failed stage turn through its exact recovery command', async () => {
    const now = '2026-07-21T00:00:00Z';
    const base = buildMockBrainView(0);
    const view: TaskBrainViewData = {
      ...base,
      task: {
        ...base.task, id: 'task-failed-stage', paused: false, terminal: false,
        currentPhase: 'IMPLEMENTING', statusLabel: 'Development',
      },
    };
    const failedDetail: StageDetailData = {
      task: {
        id: 'task-failed-stage', taskNumber: 1, title: 'Development task',
        branch: 'dev/failed-stage', repoFullName: 'bytequay/app', prNumber: null,
        prDraft: false, currentPhase: 'IMPLEMENTING', agentRuntime: 'CLI', agentModel: 'claude',
      },
      stage: {
        id: 'stage-failed', type: 'DEVELOPMENT_STAGE', state: 'OPEN', openedAt: now,
        closedAt: null, callerStageId: null, iterationCount: 0,
        currentIterationNumber: null, agentActive: false,
        config: { internalReviewEnabled: false }, metrics: { panelInvocationsCount: 0 },
      },
      allStages: [], subStages: [], conversationThreadId: 't1', iterations: [],
      conversation: [], realtimeCi: null, ciFixHistory: [], pr: null,
      context: { tokensUsed: 0, tokensLimit: 200_000, safeBand: 'safe' },
      scrubber: { userMessages: [] }, liveRuns: [], guard: null, liveRound: null,
      devPhases: [],
      recovery: {
        replacement: null,
        failure: {
          stageTurnId: 'turn-failed', blockerId: 'blocker-failed',
          reason: "You've hit your session limit · resets 12:40am (Asia/Singapore)",
        },
        ci: null,
        cleanup: null,
      },
    };
    const activeDetail: StageDetailData = {
      ...failedDetail,
      stage: { ...failedDetail.stage, agentActive: true },
      recovery: {
        replacement: null,
        failure: null,
        ci: null,
        cleanup: null,
      },
    };
    let currentDetail = activeDetail;
    let onStageEvent: ((event: { name: string; data: Record<string, unknown> }) => void) | undefined;
    const recoverV2Stage = vi.fn().mockResolvedValue({});
    const steerStage = vi.fn().mockResolvedValue({ turnId: 'unexpected' });
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn().mockResolvedValue(view),
      getStageDetail: vi.fn().mockImplementation(() => Promise.resolve(currentDetail)),
      getTaskRuns: vi.fn().mockResolvedValue([]),
      getTaskRounds: vi.fn().mockResolvedValue([]),
      getTaskCumulativeDiff: vi.fn().mockResolvedValue([]),
      listTaskCommits: vi.fn().mockResolvedValue([]),
      subscribeStageStream: vi.fn((
        _stageId: string,
        listener: (event: { name: string; data: Record<string, unknown> }) => void,
      ) => {
        onStageEvent = listener;
        return () => {};
      }),
      recoverV2Stage,
      steerStage,
    };

    render(<StageDetailRoute threadId="t1" taskId="task-failed-stage" stageId="stage-failed" />);

    const composer = await screen.findByRole('textbox', { name: 'Message' });
    await waitFor(() => expect((composer as HTMLTextAreaElement).disabled).toBe(false));
    fireEvent.change(composer, { target: { value: 'queued before the failure' } });
    fireEvent.keyDown(composer, { key: 'Enter' });
    expect(await screen.findByText('queued before the failure')).toBeTruthy();
    expect(steerStage).not.toHaveBeenCalled();

    currentDetail = failedDetail;
    onStageEvent?.({ name: 'TurnDone', data: {} });
    await waitFor(() => expect((composer as HTMLTextAreaElement).disabled).toBe(true));
    await waitFor(() => expect(screen.getByText('queued before the failure')).toBeTruthy());
    expect(steerStage).not.toHaveBeenCalled();

    fireEvent.click(await screen.findByRole('button', { name: /Retry stage/ }));
    expect(recoverV2Stage).not.toHaveBeenCalled();
    expect(within(screen.getByRole('dialog')).getByText(
      /The failed provider session will not be resumed/,
    )).toBeTruthy();
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Retry stage' }));

    await waitFor(() => expect(recoverV2Stage).toHaveBeenCalledWith(
      'task-failed-stage',
      'turn-failed',
      {
        blockerId: 'blocker-failed',
        commandId: expect.stringMatching(/:blocker-failed$/),
        reason: 'Explicit Retry action from the Local Stage run control',
      },
    ));
    expect(steerStage).not.toHaveBeenCalled();
  });

  it('keeps the changed-files card before later stage turns', async () => {
    const now = '2026-07-21T00:00:00Z';
    const detail: StageDetailData = {
      task: {
        id: 'task-1', taskNumber: 1, title: 'Development task', branch: 'dev/task-1',
        repoFullName: 'bytequay/app', prNumber: null, prDraft: false,
        currentPhase: 'DEVELOPMENT', agentRuntime: 'CLI', agentModel: 'codex',
      },
      stage: {
        id: 'stage-1', type: 'DEVELOPMENT_STAGE', state: 'OPEN', openedAt: now, closedAt: null,
        callerStageId: null, iterationCount: 0, currentIterationNumber: null,
        agentActive: false,
        config: { internalReviewEnabled: false }, metrics: { panelInvocationsCount: 0 },
      },
      allStages: [], subStages: [], conversationThreadId: 'stage-thread', iterations: [],
      conversation: [
        {
          id: 'user-1', messageSeq: 1, kind: 'user', text: 'Initial instructions', ts: now,
          toolTag: null, toolLabel: null, toolDetail: null, toolResult: null, toolError: null,
          toolDiff: null, iterationNumber: null, callId: null, images: [], managedSkills: [],
        },
        {
          id: 'agent-1', messageSeq: 2, kind: 'agent', text: 'Initial work complete', ts: now,
          toolTag: null, toolLabel: null, toolDetail: null, toolResult: null, toolError: null,
          toolDiff: null, iterationNumber: null, callId: null, images: [], managedSkills: [],
        },
        {
          id: 'user-2', messageSeq: 3, kind: 'user', text: 'One more adjustment', ts: now,
          toolTag: null, toolLabel: null, toolDetail: null, toolResult: null, toolError: null,
          toolDiff: null, iterationNumber: null, callId: null, images: [], managedSkills: [],
        },
        {
          id: 'agent-2', messageSeq: 4, kind: 'agent', text: 'Follow-up complete', ts: now,
          toolTag: null, toolLabel: null, toolDetail: null, toolResult: null, toolError: null,
          toolDiff: null, iterationNumber: null, callId: null, images: [], managedSkills: [],
        },
      ],
      realtimeCi: null, ciFixHistory: [], pr: null,
      context: { tokensUsed: 0, tokensLimit: 200_000, safeBand: 'safe' },
      scrubber: { userMessages: [] }, liveRuns: [], guard: null, liveRound: null, devPhases: [],
    };
    (window as unknown as { bridge: unknown }).bridge = {
      getStageDetail: vi.fn().mockResolvedValue(detail),
      getTaskCumulativeDiff: vi.fn().mockResolvedValue([{
        filename: 'frontend/src/App.tsx', status: 'modified', additions: 3, deletions: 1, patch: null,
      }]),
      listTaskCommits: vi.fn().mockResolvedValue([]),
      getTaskRuns: vi.fn().mockResolvedValue([]),
      getTaskRounds: vi.fn().mockResolvedValue([]),
    };

    render(<StageDetailRoute threadId="t1" taskId="task-1" stageId="stage-1" />);

    const card = (await screen.findByText('Changed 1 file')).closest('.workspace-task-files-card');
    const followUp = screen.getByText('One more adjustment');
    expect(card?.compareDocumentPosition(followUp) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('keeps the changed-files card before remote-stage steering starts', async () => {
    const now = '2026-07-21T00:00:00Z';
    const detail: StageDetailData = {
      task: {
        id: 'task-1', taskNumber: 1, title: 'Remote development task', branch: 'dev/task-1',
        repoFullName: 'bytequay/app', prNumber: 46, prDraft: false,
        currentPhase: 'REMOTE_DEVELOPMENT', agentRuntime: 'CLI', agentModel: 'codex',
      },
      stage: {
        id: 'stage-remote', type: 'REMOTE_DEVELOPMENT_STAGE', state: 'OPEN', openedAt: now,
        closedAt: null, callerStageId: null, iterationCount: 1, currentIterationNumber: 1,
        agentActive: false,
        config: { internalReviewEnabled: false }, metrics: { panelInvocationsCount: 0 },
      },
      allStages: [], subStages: [], conversationThreadId: 'stage-thread', iterations: [],
      conversation: [
        {
          id: 'iteration-1', messageSeq: null, kind: 'iteration_marker', text: 'user_steering', ts: now,
          toolTag: null, toolLabel: null, toolDetail: null, toolResult: null, toolError: null,
          toolDiff: null, iterationNumber: 1, callId: null, images: [], managedSkills: [],
        },
        {
          id: 'user-1', messageSeq: 1, kind: 'user', text: 'Why is the stage still working?',
          ts: '2026-07-21T00:00:01Z',
          toolTag: null, toolLabel: null, toolDetail: null, toolResult: null, toolError: null,
          toolDiff: null, iterationNumber: null, callId: null, images: [], managedSkills: [],
        },
      ],
      realtimeCi: null, ciFixHistory: [], pr: null,
      context: { tokensUsed: 0, tokensLimit: 200_000, safeBand: 'safe' },
      scrubber: { userMessages: [] }, liveRuns: [], guard: null, liveRound: null, devPhases: [],
    };
    (window as unknown as { bridge: unknown }).bridge = {
      getStageDetail: vi.fn().mockResolvedValue(detail),
      getTaskCumulativeDiff: vi.fn().mockResolvedValue([{
        filename: 'frontend/src/App.tsx', status: 'modified', additions: 3, deletions: 1, patch: null,
      }]),
      listTaskCommits: vi.fn().mockResolvedValue([]),
      getTaskRuns: vi.fn().mockResolvedValue([]),
      getTaskRounds: vi.fn().mockResolvedValue([]),
    };

    render(<StageDetailRoute threadId="t1" taskId="task-1" stageId="stage-remote" />);

    const card = (await screen.findByText('Changed 1 file')).closest('.workspace-task-files-card');
    const marker = await screen.findByText('Steered by you');
    const steeringMessage = screen.getByText('Why is the stage still working?');
    expect(card?.compareDocumentPosition(marker) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(card?.compareDocumentPosition(steeringMessage) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('surfaces supporting run and round poll failures', async () => {
    (window as unknown as { bridge: unknown }).bridge = {
      getStageDetail: vi.fn(() => new Promise(() => {})),
      getTaskRuns: vi.fn().mockResolvedValue([]),
      getTaskRounds: vi.fn().mockRejectedValue(new Error('Review rounds unavailable')),
    };

    render(<StageDetailRoute threadId="t1" taskId="task-1" stageId="stage-1" />);

    expect((await screen.findByRole('alert')).textContent).toContain('Review rounds unavailable');
  });

  it('surfaces round approval failures', async () => {
    const now = '2026-07-21T00:00:00Z';
    const detail: StageDetailData = {
      task: {
        id: 'task-1', taskNumber: 1, title: 'Review task', branch: 'dev/review-task',
        repoFullName: 'bytequay/app', prNumber: 1, prDraft: false,
        currentPhase: 'AWAITING_REMOTE_REVIEW', agentRuntime: 'CLI', agentModel: 'claude',
      },
      stage: {
        id: 'stage-approve', type: 'REVIEW_MONITOR_STAGE', state: 'OPEN', openedAt: now, closedAt: null,
        callerStageId: null, iterationCount: 0, currentIterationNumber: null,
        agentActive: false,
        config: { internalReviewEnabled: false }, metrics: { panelInvocationsCount: 0 },
      },
      allStages: [], subStages: [], conversationThreadId: 't1', iterations: [], conversation: [],
      realtimeCi: null, ciFixHistory: [], pr: null,
      context: { tokensUsed: 0, tokensLimit: 200_000, safeBand: 'safe' },
      scrubber: { userMessages: [] }, liveRuns: [], guard: null, liveRound: null, devPhases: [],
    };
    const approveRound = vi.fn().mockRejectedValue(new Error('Could not post this round'));
    (window as unknown as { bridge: unknown }).bridge = {
      getStageDetail: vi.fn().mockResolvedValue(detail),
      getTaskRuns: vi.fn().mockResolvedValue([]),
      getTaskRounds: vi.fn().mockResolvedValue([{
        id: 'round-1', taskId: 'task-1', idx: 1, reviewers: ['@alice'], status: 'awaiting_gate',
        stats: { fixed: 1, replied: 0, pushedBack: 0, open: 0 }, runId: null,
        openedAt: now, gatedAt: now, postedAt: null, origin: 'external', brainVerdict: null,
        iteration: 1, budget: 3,
      }]),
      approveRound,
    };

    render(<StageDetailRoute threadId="t1" taskId="task-1" stageId="stage-approve" />);
    fireEvent.click(await screen.findByRole('button', { name: 'Approve & post' }));

    await waitFor(() => expect(approveRound).toHaveBeenCalledWith('round-1'));
    expect((await screen.findByRole('alert')).textContent).toContain('Could not post this round');
  });

  it('mounts the V3 stage page and steers the stage agent', async () => {
    let acceptSteer!: (result: { turnId: string }) => void;
    const steerStage = vi.fn(() => new Promise<{ turnId: string }>(resolve => {
      acceptSteer = resolve;
    }));
    // getStageDetail never resolves → renders the loading defaults.
    (window as unknown as { bridge: unknown }).bridge = {
      getStageDetail: vi.fn(() => new Promise(() => {})),
      steerStage,
    };
    render(<StageDetailRoute threadId="t1" taskId="task-1" stageId="stage-1" />);

    expect(document.querySelector('.shell')).toBeTruthy();
    expect(document.querySelector('.shell.full-width')).toBeNull();
    expect(document.querySelector('.workspace-task-sidebar-v2')).toBeTruthy();
    expect(screen.getByText('STAGES')).toBeTruthy();
    expect(document.querySelector('.workspace-task-header__badge')?.textContent).toBe('DEV STAGE');

    const box = screen.getByRole('textbox');
    fireEvent.change(box, { target: { value: 'fix the import' } });
    fireEvent.keyDown(box, { key: 'Enter' });
    await waitFor(() => expect(steerStage).toHaveBeenCalledWith('stage-1', 'fix the import', []));
    await waitFor(() => expect(screen.getByText('fix the import')).toBeTruthy());
    await waitFor(() => expect(screen.getByText('You · sending')).toBeTruthy());

    acceptSteer({ turnId: 'x' });
    await waitFor(() => expect(screen.getByText('You · queued')).toBeTruthy());
  });

  it('restores a stage steer and shows the backend error when submission fails', async () => {
    const steerStage = vi.fn().mockRejectedValue(new Error(
      'task command cannot start inside an ambient transaction'));
    (window as unknown as { bridge: unknown }).bridge = {
      getStageDetail: vi.fn(() => new Promise(() => {})),
      steerStage,
    };
    render(<StageDetailRoute threadId="t1" taskId="task-1" stageId="stage-1" />);

    const box = screen.getByRole('textbox');
    fireEvent.change(box, { target: { value: 'check the failing CI job' } });
    fireEvent.keyDown(box, { key: 'Enter' });

    await waitFor(() => expect(box).toHaveProperty('value', 'check the failing CI job'));
    expect((await screen.findByRole('alert')).textContent)
      .toContain('task command cannot start inside an ambient transaction');
    expect(screen.queryByText('You · sending')).toBeNull();
  });

  it('removes the stage-specific sidebar when the shared app rail is collapsed', () => {
    (window as unknown as { bridge: unknown }).bridge = {
      getStageDetail: vi.fn(() => new Promise(() => {})),
    };
    render(<StageDetailRoute threadId="t1" taskId="task-1" stageId="stage-1" collapsed />);

    expect(document.querySelector('.shell.full-width')).toBeTruthy();
    expect(document.querySelector('.workspace-task-sidebar-v2')).toBeNull();
  });

  it('routes the stage feed and permission decision through its conversation thread', async () => {
    const attachment = '/tmp/attachments/brain-thread/permission-route.png';
    const now = '2026-07-21T00:00:00Z';
    const detail: StageDetailData = {
      task: {
        id: 'task-plan', taskNumber: 1, title: 'Plan task', branch: 'dev/plan-task',
        repoFullName: 'bytequay/app', prNumber: null, prDraft: false,
        currentPhase: 'PLANNING', agentRuntime: 'CLI', agentModel: 'claude',
      },
      stage: {
        id: 'stage-plan', type: 'PLAN_STAGE', state: 'CLOSED', openedAt: now, closedAt: now,
        callerStageId: null, iterationCount: 0, currentIterationNumber: null,
        agentActive: false,
        config: { internalReviewEnabled: false }, metrics: { panelInvocationsCount: 0 },
      },
      allStages: [{
        id: 'stage-plan', taskId: 'task-plan', type: 'PLAN_STAGE', state: 'CLOSED',
        openedAt: now, closedAt: now, callerStageId: null, summary: '', loopIteration: 0,
      }],
      subStages: [],
      conversationThreadId: 'brain-thread',
      iterations: [],
      conversation: [
        {
          id: 'user-1', messageSeq: 1, kind: 'user', text: 'See the attached context',
          toolTag: null, toolLabel: null, toolDetail: null, toolResult: null, toolError: null,
          toolDiff: null, iterationNumber: null, ts: now, callId: null,
          images: [attachment], managedSkills: [],
        },
        {
          id: 'permission-1', messageSeq: null, kind: 'permission', text: '{"command":"git status"}',
          toolTag: null, toolLabel: 'run_shell', toolDetail: null, toolResult: null, toolError: null,
          toolDiff: null, iterationNumber: null, ts: now, callId: 'call-1', images: [], managedSkills: [],
        },
      ],
      realtimeCi: null, ciFixHistory: [], pr: null,
      context: { tokensUsed: 0, tokensLimit: 200_000, safeBand: 'safe' },
      scrubber: { userMessages: [] }, liveRuns: [],
      guard: {
        taskId: 'task-plan', enabled: false, schedule: 'nightly', state: 'healthy',
        health: { behindBy: 0, mergeable: true, checksGreen: true },
        lastRunId: null, lastCheckedAt: null,
      },
      liveRound: null, devPhases: [],
    };
    const decideTaskPermission = vi.fn().mockResolvedValue({ status: 'recorded' });
    const readAttachment = vi.fn().mockResolvedValue('data:image/png;base64,aaa');
    (window as unknown as { bridge: unknown }).bridge = {
      getStageDetail: vi.fn().mockResolvedValue(detail),
      decideTaskPermission,
      readAttachment,
    };

    render(<StageDetailRoute threadId="development-thread" taskId="task-plan" stageId="stage-plan" />);

    await waitFor(() => expect(readAttachment).toHaveBeenCalledWith('brain-thread', attachment));
    fireEvent.click(await screen.findByText('Approve once'));
    await waitFor(() => expect(decideTaskPermission)
      .toHaveBeenCalledWith('brain-thread', 'call-1', 'ALLOW', undefined));
  });
});
