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
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
import type { IssueDetailDto } from '../types';
import type { WorkspaceRepositoryDto, WorkspaceTrunkDto } from './workspaceApi';

const VISUAL_WORKSPACE_ID = 'workspace-bytequay';
const VISUAL_TRUNK_ID = 'trunk-codex-v2';
const VISUAL_ISSUE_NUMBER = 30311;
const fourDaysAgo = new Date(Date.now() - 4 * 24 * 60 * 60 * 1000).toISOString();

const visualRepository: WorkspaceRepositoryDto = {
  owner: 'chenjian2664',
  repo: 'ByteQuay',
  fullName: 'chenjian2664/ByteQuay',
  defaultBaseBranch: 'master',
  local: {
    owner: 'chenjian2664',
    repo: 'ByteQuay',
    localClonePath: '/Users/chenjian2664/ByteQuay',
    state: 'CLEAN',
    currentBranch: 'dev/clamp-fix',
    dirtyFileCount: 0,
    errorMessage: null,
    upstreamRemoteName: null,
    defaultBranch: 'master',
    viewFocus: 'fork',
  },
};

const visualIssueDetail = {
  id: VISUAL_ISSUE_NUMBER,
  number: VISUAL_ISSUE_NUMBER,
  title: 'Regression in 482: SELECT on Iceberg $partitions fails for wide tables',
  body: 'Since upgrading to 482, querying the `$partitions` metadata table throws.',
  author: 'guyco33',
  authorAvatarUrl: null,
  state: 'open',
  htmlUrl: `https://github.com/chenjian2664/ByteQuay/issues/${VISUAL_ISSUE_NUMBER}`,
  createdAt: fourDaysAgo,
  updatedAt: fourDaysAgo,
  closedAt: null,
  labels: [{ name: 'RELEASE-BLOCKER', color: 'cf222e' }],
  assignees: [],
  milestone: { title: '482', state: 'open' },
  comments: [],
  timeline: [],
  subscribed: true,
  origin: 'user',
  participants: ['mderoy', 'guyco33'],
  linkedWork: [],
} as unknown as IssueDetailDto;

const visualTrunks: WorkspaceTrunkDto[] = [
  {
    id: VISUAL_TRUNK_ID,
    workspaceId: VISUAL_WORKSPACE_ID,
    title: 'Fix $partitions regression',
    kind: 'dev',
    status: 'running',
    provider: 'anthropic',
    model: 'claude-opus-5',
    prRef: null,
    costUsdMilli: 0,
    tokensIn: 0,
    tokensOut: 0,
    createdAt: Date.parse(fourDaysAgo),
    updatedAt: Date.parse(fourDaysAgo),
    endedAt: null,
    taskCount: 0,
  },
];

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

  it('keeps projected sessions read-only and opens the owning trunk', async () => {
    const session = sessionFixture();
    const workspaceApi = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
      if (request.path === '/api/workspaces/w1/sessions') return [session];
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

    fireEvent.click(await screen.findByRole('button', { name: /Open thread/ }));
    expect(onOpenThread).toHaveBeenCalledWith('t1');
    expect(screen.queryByRole('button', { name: 'Pause' })).toBeNull();
    expect(workspaceApi.mock.calls.some(([request]) => request.path.startsWith('/api/sessions/')))
      .toBe(false);
  });

  it('hides lifecycle controls when the session owner exposes none', async () => {
    const session: WorkspaceSessionDto = {
      ...sessionFixture(),
      controls: { pause: false, resume: false, stop: false, restart: false },
    };
    const workspaceApi = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
      if (request.path === '/api/workspaces/w1/sessions') return [session];
      throw new Error(`Unexpected request: ${request.path}`);
    });
    setBridge(workspaceApi);

    render(
      <WorkspaceSessionsPage
        workspaceId="w1"
        selectedSessionId="s1"
        onOpenThread={vi.fn()}
      />,
    );

    expect(await screen.findByRole('button', { name: /Open thread/ })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Pause' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Stop' })).toBeNull();
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
      controls: { pause: false, resume: false, stop: false, restart: false },
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

  it('keeps task review session navigation without generic controls', async () => {
    const session: WorkspaceSessionDto = {
      ...sessionFixture(),
      kind: 'review',
      durableReview: false,
    };
    const workspaceApi = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
      if (request.path === '/api/workspaces/w1/sessions') return [session];
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

    fireEvent.click(await screen.findByRole('button', { name: /Open thread/ }));
    expect(onOpenThread).toHaveBeenCalledWith('t1');
    expect(screen.queryByRole('button', { name: 'Pause' })).toBeNull();
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

  it('selects proposed distill changes with a checkbox before applying', async () => {
    const operation: DistillOperationDto = {
      id: 'op-1',
      target: 'brain',
      action: 'add',
      brainItemId: null,
      kbEntryId: null,
      category: 'Decisions',
      title: null,
      body: '## Repository rules\n\n- Keep **one repository** per workspace.',
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
    expect(screen.getByRole('heading', { name: 'Repository rules', level: 2 })).toBeTruthy();
    expect(screen.getByText('one repository').tagName).toBe('STRONG');
    const include = screen.getByRole('checkbox', { name: 'Include Decisions change' });
    expect((include as HTMLInputElement).checked).toBe(true);
    expect((screen.getByRole('button', { name: 'Apply 1 change' }) as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(include);
    expect(screen.getByRole('button', { name: 'Discard proposal' })).toBeTruthy();
    fireEvent.click(include);
    fireEvent.click(screen.getByRole('button', { name: 'Apply 1 change' }));
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

  it('dismisses a ready setup notification before opening the workspace', async () => {
    const ready = creationFixture({
      state: 'ready',
      stageMessage: 'Workspace ready',
      workspaceId: 'w1',
    });
    const workspaceApi = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
      if (request.path === '/api/workspace-creations') return [creationFixture()];
      throw new Error(`Unexpected request: ${request.path}`);
    });
    setBridge(workspaceApi);
    const onOpenWorkspace = vi.fn();

    render(<WorkspaceCreationToasts onOpenWorkspace={onOpenWorkspace} />);
    await screen.findByText('Cloning widget…');
    fireEvent(window, new CustomEvent(
      'bytequay:workspace-creation-started',
      { detail: ready },
    ));

    const open = await screen.findByRole('button', { name: 'Open' });
    fireEvent.click(open);
    expect(open.isConnected).toBe(false);
    expect(onOpenWorkspace).toHaveBeenCalledWith('w1');
    expect(screen.queryByLabelText('Workspace setup progress')).toBeNull();
  });

  it('auto-dismisses a ready setup notification', () => {
    vi.useFakeTimers();
    try {
      setBridge(vi.fn(() => new Promise<unknown>(() => {})));
      render(<WorkspaceCreationToasts onOpenWorkspace={() => {}} />);
      fireEvent(window, new CustomEvent(
        'bytequay:workspace-creation-started',
        { detail: creationFixture({ state: 'ready', workspaceId: 'w1' }) },
      ));

      expect(screen.getByText('widget is ready')).toBeTruthy();
      act(() => { vi.advanceTimersByTime(5_000); });
      expect(screen.queryByLabelText('Workspace setup progress')).toBeNull();
    }
    finally {
      vi.useRealTimers();
    }
  });

  it('keeps required onboarding visible and marks unfinished memory seeding as skipped', async () => {
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
      dismissedAt: Date.now(),
      updatedAt: Date.now(),
    };
    const workspaceApi = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
      if (request.path === '/api/workspaces/w1/onboarding') return onboarding;
      throw new Error(`Unexpected request: ${request.path}`);
    });
    setBridge(workspaceApi, {
      listActiveTaskTurns: vi.fn().mockResolvedValue([]),
    });
    render(
      <WorkspaceTodayPage
        workspace={workspaceFixture()}
        threads={[]}
        onNewThread={() => {}}
        onOpenInsights={() => {}}
        onOpenMemory={() => {}}
      />,
    );

    expect(await screen.findByText('3 of 4 done')).toBeTruthy();
    expect(screen.getByText('Seed memory')).toBeTruthy();
    expect(screen.getByText('Workspace memory is still in progress — skipped for now')).toBeTruthy();
    expect(screen.getByText('Skipped')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Seed now' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Dismiss' })).toBeNull();
  });

  it('shows project learning as paused, not counted toward onboarding milestones', async () => {
    const onboarding: WorkspaceOnboardingDto = {
      workspaceId: 'w1',
      cloneComplete: true,
      syncState: 'ready',
      syncCurrent: 3,
      syncTotal: 3,
      memorySeedComplete: true,
      firstTrunkComplete: false,
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
    expect(screen.getByText('Project learning is still in progress — paused for now')).toBeTruthy();
    expect(screen.getByText('3 of 4 done')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Pause' })).toBeNull();
    expect(screen.queryByText(/120 cataloged/)).toBeNull();
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
      name: 'Start trunk from issue',
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
    controls: { pause: true, resume: false, stop: true, restart: false },
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
