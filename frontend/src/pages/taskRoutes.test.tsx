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
import type { PlanCardDto, StageDetailData } from '../types/brainView';
import type { DiffFileDto, ThreadCommitDto } from '../types';
import type { LocalPRBundle, LocalPRCommit } from '../types/localPr';

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

  it('opens the embedded PR Changes tab from a route request and the changed-files controls', async () => {
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
    expect(screen.getByRole('button', { name: 'Toggle PR panel' }).getAttribute('aria-pressed')).toBe('true');
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
    render(<StageDetailRoute threadId="t1" taskId="task-1" stageId="stage-1" />);

    expect(document.querySelector('.shell')).toBeTruthy();
    expect(document.querySelector('.workspace-task-header__badge')?.textContent).toBe('DEV STAGE');

    const box = screen.getByRole('textbox');
    fireEvent.change(box, { target: { value: 'fix the import' } });
    fireEvent.keyDown(box, { key: 'Enter' });
    await waitFor(() => expect(steerStage).toHaveBeenCalledWith('stage-1', 'fix the import', []));
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
