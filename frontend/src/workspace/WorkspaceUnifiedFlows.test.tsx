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
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { WorkspaceApiRequest, WorkspaceCardDto } from '../types';
import WorkspaceCreationToasts from './WorkspaceCreationToasts';
import WorkspaceMemoryPage from './WorkspaceMemoryPage';
import WorkspaceNotificationsPage from './WorkspaceNotificationsPage';
import WorkspaceRepoPage from './WorkspaceRepoPage';
import WorkspaceSessionsPage from './WorkspaceSessionsPage';
import WorkspaceTodayPage from './WorkspaceTodayPage';
import type {
  DistillOperationDto,
  DistillRunDto,
  WorkspaceCreationDto,
  WorkspaceMemoryDto,
  WorkspaceOnboardingDto,
  WorkspaceSessionDto,
} from './workspaceApi';
import {
  VISUAL_ISSUE_NUMBER,
  VISUAL_WORKSPACE_ID,
  visualIssueDetail,
  visualRepository,
  visualTrunks,
} from './workspaceVisualFixtureData';

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(window, 'bridge');
  window.location.hash = '';
});

describe('workspace unified interaction flows', () => {
  it('omits provider usage from Today', async () => {
    const workspaceApi = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
      if (request.path === '/api/workspaces/w1/onboarding') {
        return {
          workspaceId: 'w1', cloneComplete: true, syncState: 'ready', syncCurrent: 1, syncTotal: 1,
          memorySeedComplete: true, firstTrunkComplete: true, memoryImported: false,
          learningState: null, learningCataloged: 0, learningAnalyzed: 0,
          learningLessons: 0, learningPendingLessons: 0,
          dismissedAt: Date.now(), updatedAt: Date.now(),
        } satisfies WorkspaceOnboardingDto;
      }
      throw new Error(`Unexpected request: ${request.path}`);
    });
    setBridge(workspaceApi, { listActiveTaskTurns: vi.fn().mockResolvedValue([]) });

    const { container } = render(
      <WorkspaceTodayPage
        workspace={workspaceFixture()}
        threads={[]}
        onNewThread={() => {}}
        onOpenInsights={() => {}}
        onOpenMemory={() => {}}
      />,
    );

    expect(await screen.findByText('Nothing needs your attention.')).toBeTruthy();
    expect(screen.queryByText('Usage')).toBeNull();
    expect(screen.queryByText('Codex CLI')).toBeNull();
    expect(screen.queryByText('Claude CLI')).toBeNull();
    const labels = [...container.querySelectorAll('.wu-section-label > span:first-child')]
      .map(element => element.textContent);
    expect(labels).toEqual(['Needs you', 'Running', 'Landed today']);
    expect(workspaceApi).not.toHaveBeenCalledWith({ path: '/api/ai/plan-usage' });
    expect(workspaceApi).not.toHaveBeenCalledWith({
      path: '/api/ai/plan-usage/claude/refresh',
      method: 'POST',
    });
    expect(workspaceApi).not.toHaveBeenCalledWith({ path: '/api/ai/api-usage' });
    expect(workspaceApi).not.toHaveBeenCalledWith({ path: '/api/ai/deepseek/balance' });
  });

  it('controls a live session and opens its owning trunk', async () => {
    const session = sessionFixture();
    const workspaceApi = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
      if (request.path === '/api/workspaces/w1/sessions') return [session];
      if (request.path === '/api/sessions/s1/pause') {
        return { ...session, status: 'paused' };
      }
      throw new Error(`Unexpected request: ${request.path}`);
    });
    setBridge(workspaceApi);
    const onOpenThread = vi.fn();

    render(
      <WorkspaceSessionsPage
        workspaceId="w1"
        selectedSessionId="s1"
        onOpenThread={onOpenThread}
      />,
    );

    fireEvent.click(await screen.findByRole('button', { name: 'Pause' }));
    await waitFor(() => expect(workspaceApi).toHaveBeenCalledWith({
      path: '/api/sessions/s1/pause',
      method: 'POST',
    }));
    fireEvent.click(screen.getByRole('button', { name: /Open thread/ }));
    expect(onOpenThread).toHaveBeenCalledWith('t1');
  });

  it('opens a review session through its owning pull request without a synthetic trunk', async () => {
    const session: WorkspaceSessionDto = {
      ...sessionFixture(),
      kind: 'review',
      trunkId: null,
      taskId: null,
      stageId: null,
      reviewRoundId: 'round-1',
      durableReview: true,
    };
    const workspaceApi = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
      if (request.path === '/api/workspaces/w1/sessions') return [session];
      throw new Error(`Unexpected request: ${request.path}`);
    });
    const getAgentReviewRoundLog = vi.fn().mockResolvedValue({
      review: { pr_id: 'pr-42' },
    });
    const getLocalPrBundle = vi.fn().mockResolvedValue({
      pr: {
        id: 'pr-42',
        repo: 'acme/widget',
        remotePrNumber: 42,
        title: 'Keep session navigation PR-owned',
      },
    });
    setBridge(workspaceApi, { getAgentReviewRoundLog, getLocalPrBundle });
    const onOpenReview = vi.fn();

    render(
      <WorkspaceSessionsPage
        workspaceId="w1"
        selectedSessionId="s1"
        onOpenThread={vi.fn()}
        onOpenReview={onOpenReview}
      />,
    );

    expect(await screen.findByRole('link', { name: 'acme/widget#42' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Open thread/ })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Open pull request' }));
    expect(getAgentReviewRoundLog).toHaveBeenCalledWith('round-1');
    expect(getLocalPrBundle).toHaveBeenCalledWith('pr-42');
    expect(onOpenReview).toHaveBeenCalledWith({
      workspaceId: 'w1',
      prId: 'pr-42',
      prNumber: 42,
      roundId: 'round-1',
    });
  });

  it('keeps task review session controls and thread navigation', async () => {
    const session: WorkspaceSessionDto = {
      ...sessionFixture(),
      kind: 'review',
      durableReview: false,
    };
    const workspaceApi = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
      if (request.path === '/api/workspaces/w1/sessions') return [session];
      if (request.path === '/api/sessions/s1/pause') return { ...session, status: 'paused' };
      throw new Error(`Unexpected request: ${request.path}`);
    });
    const getAgentReviewRoundLog = vi.fn();
    setBridge(workspaceApi, { getAgentReviewRoundLog });
    const onOpenThread = vi.fn();

    render(
      <WorkspaceSessionsPage
        workspaceId="w1"
        selectedSessionId="s1"
        onOpenThread={onOpenThread}
      />,
    );

    fireEvent.click(await screen.findByRole('button', { name: 'Pause' }));
    fireEvent.click(screen.getByRole('button', { name: /Open thread/ }));
    expect(onOpenThread).toHaveBeenCalledWith('t1');
    expect(screen.queryByRole('button', { name: 'Open pull request' })).toBeNull();
    expect(getAgentReviewRoundLog).not.toHaveBeenCalled();
  });

  it('follows canonical notification links, marks all read, and edits mute rules', async () => {
    const workspaceApi = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
      if (request.path === '/api/workspaces/w1/notifications') {
        return [{
          id: 'n1',
          workspaceId: 'w1',
          publicType: 'approval-gate',
          title: 'Approval needed',
          summary: 'Review local drafts',
          itemPath: '#/workspace/w1/trunks/t1',
          status: 'UNREAD',
          createdAt: new Date().toISOString(),
          threadId: 't1',
        }];
      }
      if (request.path === '/api/workspaces/w1/notifications/mutes') {
        return [{ publicType: 'agent-update', muted: false }];
      }
      if (request.path.endsWith('/mark-all-read')) return 1;
      if (request.path.endsWith('/mutes/agent-update')) {
        return { publicType: 'agent-update', muted: true };
      }
      throw new Error(`Unexpected request: ${request.path}`);
    });
    setBridge(workspaceApi);

    render(<WorkspaceNotificationsPage workspaceId="w1" />);

    fireEvent.click(await screen.findByText(/Approval needed/));
    expect(window.location.hash).toBe('#/workspace/w1/trunks/t1');
    fireEvent.click(screen.getByRole('button', { name: 'Mark all read' }));
    await waitFor(() => expect(workspaceApi).toHaveBeenCalledWith({
      path: '/api/workspaces/w1/notifications/mark-all-read',
      method: 'POST',
    }));
    fireEvent.click(screen.getByRole('button', { name: 'Edit rules' }));
    fireEvent.click(screen.getByRole('checkbox', {
      name: 'Successful agent completions',
    }));
    await waitFor(() => expect(workspaceApi).toHaveBeenCalledWith({
      path: '/api/workspaces/w1/notifications/mutes/agent-update',
      method: 'POST',
      body: { muted: true },
    }));
    expect(screen.getByText(
      'Approval gates and agent questions always remain visible.',
    )).toBeTruthy();
  });

  it('requires every distill operation to be decided before applying', async () => {
    const operation: DistillOperationDto = {
      id: 'op-1',
      target: 'brain',
      action: 'add',
      brainItemId: null,
      kbEntryId: null,
      category: 'Decisions',
      title: null,
      body: 'Keep one repository per workspace.',
      audience: [],
      decision: 'pending',
      originalBody: null,
    };
    const run: DistillRunDto = {
      id: 'distill-1',
      workspaceId: 'w1',
      trigger: 'manual',
      status: 'pending',
      sources: [{ label: 'Workspace routing trunk' }],
      operations: [operation],
      createdAt: Date.now(),
      appliedAt: null,
      revertedAt: null,
    };
    let applied = false;
    const workspaceApi = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
      if (request.path === '/api/workspaces/w1/memory/aggregate') {
        return memoryFixture(applied ? [] : [run]);
      }
      if (request.path.endsWith('/decisions')) {
        return {
          ...run,
          operations: (request.body as { operations: DistillOperationDto[] }).operations,
        };
      }
      if (request.path.endsWith('/apply')) {
        applied = true;
        return { ...run, status: 'applied' };
      }
      throw new Error(`Unexpected request: ${request.path}`);
    });
    setBridge(workspaceApi);

    render(<WorkspaceMemoryPage workspaceId="w1" />);

    await screen.findByText('Distill from threads');
    expect((screen.getByRole('button', {
      name: 'Apply 0 changes',
    }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Accept' }));
    fireEvent.click(screen.getByRole('button', { name: 'Apply 1 changes' }));
    await waitFor(() => expect(workspaceApi).toHaveBeenCalledWith({
      path: '/api/workspaces/w1/memory/distill-runs/distill-1/decisions',
      method: 'PUT',
      body: {
        operations: [{
          ...operation,
          decision: 'accepted',
        }],
      },
    }));
    expect(workspaceApi).toHaveBeenCalledWith({
      path: '/api/workspaces/w1/memory/distill-runs/distill-1/apply',
      method: 'POST',
    });
  });

  it('shows persisted setup progress and lets a failed operation retry', async () => {
    const failed = creationFixture({
      state: 'failed',
      errorMessage: 'Clone verification failed',
    });
    const workspaceApi = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
      if (request.path === '/api/workspace-creations') return [failed];
      if (request.path === '/api/workspace-creations/create-1/retry') {
        return creationFixture({
          state: 'queued',
          stageMessage: 'Waiting to retry',
          errorMessage: null,
        });
      }
      throw new Error(`Unexpected request: ${request.path}`);
    });
    setBridge(workspaceApi);

    render(<WorkspaceCreationToasts onOpenWorkspace={() => {}} />);
    fireEvent(window, new CustomEvent(
      'bytequay:workspace-creation-started',
      { detail: failed },
    ));

    expect(await screen.findByText("Couldn't connect widget")).toBeTruthy();
    expect(screen.getByText('Clone verification failed')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(workspaceApi).toHaveBeenCalledWith({
      path: '/api/workspace-creations/create-1/retry',
      method: 'POST',
    }));
    expect(await screen.findByText('Preparing widget…')).toBeTruthy();
  });

  it('renders onboarding milestones and starts memory seeding', async () => {
    const onboarding: WorkspaceOnboardingDto = {
      workspaceId: 'w1',
      cloneComplete: true,
      syncState: 'ready',
      syncCurrent: 3,
      syncTotal: 3,
      memorySeedComplete: false,
      firstTrunkComplete: false,
      memoryImported: false,
      learningState: null,
      learningCataloged: 0,
      learningAnalyzed: 0,
      learningLessons: 0,
      learningPendingLessons: 0,
      dismissedAt: null,
      updatedAt: Date.now(),
    };
    const workspaceApi = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
      if (request.path === '/api/workspaces/w1/onboarding') return onboarding;
      if (request.path.endsWith('/distill-runs/seed')) {
        return {
          id: 'seed-1',
          workspaceId: 'w1',
          trigger: 'seed',
          status: 'pending',
          sources: [],
          operations: [],
          createdAt: Date.now(),
          appliedAt: null,
          revertedAt: null,
        };
      }
      throw new Error(`Unexpected request: ${request.path}`);
    });
    setBridge(workspaceApi, {
      listActiveTaskTurns: vi.fn().mockResolvedValue([]),
    });
    const onOpenMemory = vi.fn();

    render(
      <WorkspaceTodayPage
        workspace={workspaceFixture()}
        threads={[]}
        onNewThread={() => {}}
        onOpenInsights={() => {}}
        onOpenMemory={onOpenMemory}
      />,
    );

    expect(await screen.findByText('2 of 4 done')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Seed now' }));
    await waitFor(() => expect(workspaceApi).toHaveBeenCalledWith({
      path: '/api/workspaces/w1/memory/distill-runs/seed',
      method: 'POST',
    }));
    expect(onOpenMemory).toHaveBeenCalledOnce();
  });

  it('keeps onboarding visible while project learning is unfinished', async () => {
    const onboarding: WorkspaceOnboardingDto = {
      workspaceId: 'w1',
      cloneComplete: true,
      syncState: 'ready',
      syncCurrent: 3,
      syncTotal: 3,
      memorySeedComplete: true,
      firstTrunkComplete: true,
      memoryImported: false,
      learningState: 'analyzing',
      learningCataloged: 120,
      learningAnalyzed: 40,
      learningLessons: 12,
      learningPendingLessons: 2,
      dismissedAt: null,
      updatedAt: Date.now(),
    };
    const workspaceApi = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
      if (request.path === '/api/workspaces/w1/onboarding') return onboarding;
      throw new Error(`Unexpected request: ${request.path}`);
    });
    setBridge(workspaceApi, { listActiveTaskTurns: vi.fn().mockResolvedValue([]) });

    render(
      <WorkspaceTodayPage
        workspace={workspaceFixture()}
        threads={[]}
        onNewThread={() => {}}
        onOpenInsights={() => {}}
        onOpenMemory={() => {}}
      />,
    );

    expect(await screen.findByText('Learn this project')).toBeTruthy();
    expect(screen.getByText('4 of 5 done')).toBeTruthy();
    expect(screen.getByText(/120 cataloged · 40 analyzed · 12 lessons/)).toBeTruthy();
  });

  it('starts an issue in an existing trunk without pasting stale issue context', async () => {
    const trunk = visualTrunks.find(value => value.kind === 'dev' && value.endedAt === null);
    if (trunk === undefined) throw new Error('Visual fixture requires an active development trunk');
    const workspaceApi = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
      if (request.path === `/api/workspaces/${VISUAL_WORKSPACE_ID}/repository`) {
        return visualRepository;
      }
      if (request.path === `/api/workspaces/${VISUAL_WORKSPACE_ID}/issues/${VISUAL_ISSUE_NUMBER}`) {
        return visualIssueDetail;
      }
      if (request.path === `/api/workspaces/${VISUAL_WORKSPACE_ID}/trunks`) {
        return visualTrunks;
      }
      if (request.path === `/api/workspaces/${VISUAL_WORKSPACE_ID}/issues/${VISUAL_ISSUE_NUMBER}/trunks`) {
        return [];
      }
      if (request.path === `/api/workspaces/${VISUAL_WORKSPACE_ID}/issues/${VISUAL_ISSUE_NUMBER}/start`) {
        return { trunkId: trunk.id };
      }
      throw new Error(`Unexpected request: ${request.path}`);
    });
    setBridge(workspaceApi);
    const onOpenTrunk = vi.fn();

    render(
      <WorkspaceRepoPage
        workspaceId={VISUAL_WORKSPACE_ID}
        section="issues"
        selectedNumber={VISUAL_ISSUE_NUMBER}
        onOpenPr={() => {}}
        onOpenIssue={() => {}}
        onOpenTrunk={onOpenTrunk}
        onBackToList={() => {}}
      />,
    );

    fireEvent.click(await screen.findByRole('button', {
      name: 'Start thread from issue',
    }));
    const picker = screen.getByRole('dialog');
    expect(picker.querySelector('.wu-trunk-picker__note')?.textContent).toBe(
      `Posts "Work on issue #${VISUAL_ISSUE_NUMBER}" into the chosen thread. The agent fetches body + comments itself via the issue tool — no stale pasted context.`,
    );
    fireEvent.click(within(picker).getByText(trunk.title));

    await waitFor(() => expect(workspaceApi).toHaveBeenCalledWith({
      path: `/api/workspaces/${VISUAL_WORKSPACE_ID}/issues/${VISUAL_ISSUE_NUMBER}/start`,
      method: 'POST',
      body: { trunkId: trunk.id },
    }));
    expect(onOpenTrunk).toHaveBeenCalledWith(trunk.id);
  });
});

function setBridge(
  workspaceApi: ReturnType<typeof vi.fn>,
  extra: Record<string, unknown> = {},
) {
  (window as unknown as { bridge: unknown }).bridge = {
    workspaceApi,
    ...extra,
  };
}

function sessionFixture(): WorkspaceSessionDto {
  return {
    id: 's1',
    workspaceId: 'w1',
    trunkId: 't1',
    kind: 'dev',
    status: 'running',
    provider: 'claude-code',
    model: 'sonnet',
    taskId: 't1.k1',
    stageId: 'stage-1',
    durableReview: false,
    costUsdMilli: 250,
    tokensIn: 1_000,
    tokensOut: 250,
    stepCursor: 1,
    budget: 1_000,
    headline: 'Implement workspace routing',
    durationMs: 60_000,
    launchInput: 'Implement workspace routing',
    pauseReason: null,
    outcome: null,
    startedAt: Date.now() - 60_000,
    finishedAt: null,
    trunkTitle: 'Workspace unification',
    taskNumber: 1,
    branch: 'dev/workspace-routing',
  };
}

function memoryFixture(distillRuns: DistillRunDto[]): WorkspaceMemoryDto {
  return {
    markdown: '# Decisions\n\nKeep one repository per workspace.',
    characters: 51,
    characterBudget: 8_000,
    blocks: [{
      id: 1,
      category: 'Decisions',
      body: 'Keep one repository per workspace.',
      provenance: 'Workspace routing trunk',
      tags: [],
      createdAt: Date.now(),
    }],
    knowledge: [],
    distillRuns,
  };
}

function creationFixture(
  overrides: Partial<WorkspaceCreationDto> = {},
): WorkspaceCreationDto {
  return {
    id: 'create-1',
    operationKind: 'connect',
    owner: 'acme',
    repo: 'widget',
    writeMode: 'DIRECT',
    state: 'cloning',
    stageMessage: 'Cloning repository',
    progressCurrent: 1,
    progressTotal: 3,
    workspaceId: null,
    clonePath: null,
    previousClonePath: null,
    errorMessage: null,
    attempt: 1,
    createdAt: Date.now(),
    updatedAt: Date.now(),
    ...overrides,
  };
}

function workspaceFixture(): WorkspaceCardDto {
  return {
    id: 'w1',
    name: 'Widget',
    color: '#ec6b5d',
    isScratch: false,
    repos: ['widget'],
    activeThreadCount: 0,
    tasksInFlight: 0,
    spendTodayMilliUsd: 0,
    needsAttentionCount: 0,
    memory: {
      decisionCount: 0,
      blockerCount: 0,
      tokensUsed: 0,
      tokensCap: 8_000,
    },
    lastActivityMs: null,
    repository: {
      owner: 'acme',
      repo: 'widget',
      fullName: 'acme/widget',
      defaultBaseBranch: 'main',
      clonePath: '/repos/widget',
      verified: true,
    },
  };
}
