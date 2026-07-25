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
import { invalidate, setCached } from '../dataCache';
import type { AiReviewDraftDto } from '../types';
import type { DashboardPR } from '../types/dashboardPr';
import type { WorkspaceCreationDto } from '../workspace/workspaceApi';
import type { PullRow } from './model';
import PullsScreen from './PullsScreen';

const queuedCreation: WorkspaceCreationDto = {
  id: 'create-1', operationKind: 'create', owner: 'trinodb', repo: 'trino', writeMode: 'FORK',
  state: 'queued', stageMessage: null, progressCurrent: 0, progressTotal: 1, workspaceId: null,
  clonePath: null, previousClonePath: null, errorMessage: null, attempt: 1, createdAt: 1, updatedAt: 1,
};

vi.mock('../repos/AddRepoModal', () => ({
  default: ({ owner, repo, onClose, onStarted }: {
    owner: string;
    repo: string;
    onClose: () => void;
    onStarted: (operation: WorkspaceCreationDto) => void;
  }) => (
    <div role="dialog">
      <span>{owner}/{repo}</span>
      <button onClick={() => onStarted({
        id: 'create-1', operationKind: 'create', owner, repo, writeMode: 'FORK',
        state: 'queued', stageMessage: null, progressCurrent: 0, progressTotal: 1, workspaceId: null,
        clonePath: null, previousClonePath: null, errorMessage: null, attempt: 1, createdAt: 1, updatedAt: 1,
      })}>confirm-watch</button>
      <button onClick={onClose}>cancel-watch</button>
    </div>
  ),
}));

vi.mock('./PullDetailPane', () => ({
  default: ({ row, noWorkspace, onAssignAgent, onRunQuickReview, onWatchRepoForFullReview, onWorkWithAgent, onOpenInWorkspace, quickReview, fullReviewPreparation, onToggleZoom, zoomed }: {
    row: PullRow;
    noWorkspace?: boolean;
    onAssignAgent?: () => void;
    onRunQuickReview?: () => void;
    onWatchRepoForFullReview?: () => void;
    onWorkWithAgent?: () => void;
    onOpenInWorkspace?: () => void;
    quickReview?: { state: string; result: AiReviewDraftDto | null };
    fullReviewPreparation?: { state: string };
    onToggleZoom?: () => void;
    zoomed?: boolean;
  }) => (
    <div
      data-testid="pull-detail"
      data-unwatched={noWorkspace === true ? 'true' : 'false'
      }
      data-quick={quickReview?.state ?? 'idle'}
      data-watch={fullReviewPreparation?.state ?? 'idle'}
    >
      {row.id}
      <span>{quickReview?.result?.summary}</span>
      <button onClick={onAssignAgent} disabled={onAssignAgent === undefined}>full-hook</button>
      <button onClick={onRunQuickReview} disabled={onRunQuickReview === undefined}>quick-hook</button>
      <button onClick={onWatchRepoForFullReview} disabled={onWatchRepoForFullReview === undefined}>watch-hook</button>
      <button onClick={onWorkWithAgent} disabled={onWorkWithAgent === undefined}>open-hook</button>
      <button onClick={onOpenInWorkspace} disabled={onOpenInWorkspace === undefined}>workspace-hook</button>
      {onToggleZoom !== undefined && (
        <button onClick={onToggleZoom}>
          {zoomed === true ? 'Close pull request details' : 'Maximize pull request details'}
        </button>
      )}
    </div>
  ),
}));

const pr: DashboardPR = {
  id: 'known-pr', repo: 'trinodb/trino', number: 4074, title: 'Fix snapshot expiry', author: 'octocat',
  htmlUrl: 'https://github.com/trinodb/trino/pull/4074', createdAt: null, updatedAt: null,
  origin: 'REVIEW_REQUESTED', labels: [], labelColors: null, draft: false, viewedAt: null,
  reviewedAt: null, handledAction: null, requestedReviewers: [], ciStatus: null, additions: 1,
  deletions: 1, commentCount: 0, attentionReason: null, state: 'open', closedAt: null,
  mergedAt: null, mergeable: null, mergeableState: null, headPushedAt: null,
  reviewerVerdicts: null, snoozedUntil: null, snoozeWakeReason: null,
};

afterEach(() => {
  cleanup();
  invalidate('prs:list');
  window.localStorage.clear();
  delete (globalThis as { bridge?: unknown }).bridge;
});

const quickDraft: AiReviewDraftDto = {
  id: 9,
  prId: 1,
  summary: 'One diff-only concern',
  providerId: 'openai',
  model: 'review-model',
  headSha: 'abc',
  status: 'DRAFT',
  createdAt: '2026-07-19T00:00:00Z',
  updatedAt: '2026-07-19T00:00:00Z',
  comments: [],
};

describe('PullsScreen', () => {
  it('shows 20 newest rows per tab and loads the next page at the bottom', async () => {
    const makePrs = (prefix: string, origin: DashboardPR['origin'], state: DashboardPR['state']) =>
      Array.from({ length: 25 }, (_, index): DashboardPR => ({
        ...pr,
        id: `${prefix}-${index}`,
        number: index,
        title: `${prefix} ${index}`,
        origin,
        state,
        updatedAt: new Date(Date.now() - index * 1_000).toISOString(),
      }));
    const prs = [
      ...makePrs('mine', 'AUTHORED', 'open'),
      ...makePrs('request', 'REVIEW_REQUESTED', 'open'),
      ...makePrs('done', 'AUTHORED', 'merged'),
    ];
    setCached('prs:list', prs);
    window.bridge = {
      listWorkspaces: vi.fn().mockResolvedValue([]),
      fetchDashboardPrs: vi.fn().mockResolvedValue(prs),
      syncDashboardPrs: vi.fn().mockResolvedValue(prs),
      recordSurfaceVisit: vi.fn().mockResolvedValue(undefined),
    } as unknown as typeof window.bridge;

    render(<PullsScreen />);

    const list = screen.getByRole('list', { name: 'Pull requests' });
    const rowCount = () => list.querySelectorAll('.pl-hov-row').length;
    const scrollToBottom = () => {
      Object.defineProperties(list, {
        clientHeight: { configurable: true, value: 200 },
        scrollHeight: { configurable: true, value: 1_000 },
      });
      fireEvent.scroll(list, { target: { scrollTop: 800 } });
    };

    expect(rowCount()).toBe(20);
    expect(screen.getByText('mine 0')).toBeTruthy();
    expect(screen.queryByText('request 20')).toBeNull();
    scrollToBottom();
    expect(rowCount()).toBe(40);

    for (const tab of ['Active', 'Review requests', 'Done']) {
      fireEvent.click(screen.getByRole('button', { name: tab }));
      expect(rowCount()).toBe(20);
      scrollToBottom();
      expect(rowCount()).toBe(25);
    }
  });

  it('executes a quick-review handoff from another PR surface without opening an agent route', async () => {
    setCached('prs:list', [pr]);
    const startQuickReview = vi.fn().mockResolvedValue({ state: 'RUNNING' });
    const openWorkspacePr = vi.fn();
    window.bridge = {
      listWorkspaces: vi.fn().mockResolvedValue([]),
      fetchDashboardPrs: vi.fn().mockResolvedValue([pr]),
      syncDashboardPrs: vi.fn().mockResolvedValue([pr]),
      recordSurfaceVisit: vi.fn().mockResolvedValue(undefined),
      startQuickReview,
      getQuickReviewStatus: vi.fn().mockResolvedValue({ state: 'DONE', error: null }),
      getLatestQuickReview: vi.fn().mockResolvedValue(quickDraft),
    } as unknown as typeof window.bridge;

    render(
      <PullsScreen
        initialPr={{ repo: pr.repo, number: pr.number }}
        initialReviewAction="quick"
        onOpenWorkspacePr={openWorkspacePr}
      />,
    );

    await waitFor(() => expect(startQuickReview).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.getByTestId('pull-detail').dataset.quick).toBe('done'));
    expect(openWorkspacePr).not.toHaveBeenCalled();
  });

  it('opens a cached deep-linked PR without resolving it through GitHub again', async () => {
    setCached('prs:list', [pr]);
    const getPrForRepoPull = vi.fn();
    window.bridge = {
      listWorkspaces: vi.fn().mockResolvedValue([]),
      fetchDashboardPrs: vi.fn().mockResolvedValue([pr]),
      syncDashboardPrs: vi.fn().mockResolvedValue([pr]),
      getPrForRepoPull,
      recordSurfaceVisit: vi.fn().mockResolvedValue(undefined),
    } as unknown as typeof window.bridge;

    render(<PullsScreen initialPr={{ repo: pr.repo, number: pr.number }} />);

    expect((await screen.findByTestId('pull-detail')).textContent).toContain(pr.id);
    await waitFor(() => expect(window.bridge.fetchDashboardPrs).toHaveBeenCalled());
    expect(getPrForRepoPull).not.toHaveBeenCalled();
  });

  it('zooms the selected PR in place without routing to a workspace', async () => {
    setCached('prs:list', [pr]);
    const openWorkspacePr = vi.fn();
    window.bridge = {
      listWorkspaces: vi.fn().mockResolvedValue([]),
      fetchDashboardPrs: vi.fn().mockResolvedValue([pr]),
      syncDashboardPrs: vi.fn().mockResolvedValue([pr]),
      recordSurfaceVisit: vi.fn().mockResolvedValue(undefined),
    } as unknown as typeof window.bridge;

    render(
      <PullsScreen
        initialPr={{ repo: pr.repo, number: pr.number }}
        onOpenWorkspacePr={openWorkspacePr}
      />,
    );

    const detail = await screen.findByTestId('pull-detail');
    const hash = window.location.hash;
    fireEvent.click(screen.getByRole('button', { name: 'Maximize pull request details' }));

    expect(screen.getByRole('dialog', { name: 'Pull request details' })).toBeTruthy();
    expect(screen.getByTestId('pull-detail')).toBe(detail);
    expect(screen.getByText(pr.title)).toBeTruthy();
    expect(openWorkspacePr).not.toHaveBeenCalled();
    expect(window.location.hash).toBe(hash);

    fireEvent.click(screen.getByRole('button', { name: 'Close pull request details' }));
    expect(screen.queryByRole('dialog', { name: 'Pull request details' })).toBeNull();
    expect(screen.getByTestId('pull-detail')).toBe(detail);
  });

  it('runs quick review inline and prepares a watched repo before starting full review', async () => {
    setCached('prs:list', [pr]);
    let quickState: 'IDLE' | 'DONE' = 'IDLE';
    let finishCreation!: (operation: WorkspaceCreationDto) => void;
    const creation = new Promise<WorkspaceCreationDto>(resolve => { finishCreation = resolve; });
    const startQuickReview = vi.fn().mockImplementation(async () => { quickState = 'DONE'; return { state: 'RUNNING' }; });
    const startAgentReview = vi.fn().mockResolvedValue({});
    const openWorkspacePr = vi.fn();
    const quickReview = vi.fn();
    const watchForFullReview = vi.fn();
    const workspaceRequest = vi.fn().mockImplementation(({ path }: { path: string }) => {
      if (path === '/api/workspace-creations/create-1') return creation;
      throw new Error(`Unexpected workspace request: ${path}`);
    });
    window.bridge = {
      listWorkspaces: vi.fn().mockResolvedValue([]),
      fetchDashboardPrs: vi.fn().mockResolvedValue([pr]),
      syncDashboardPrs: vi.fn().mockResolvedValue([pr]),
      recordSurfaceVisit: vi.fn().mockResolvedValue(undefined),
      startQuickReview,
      getQuickReviewStatus: vi.fn().mockImplementation(async () => ({ state: quickState, error: null })),
      getLatestQuickReview: vi.fn().mockImplementation(async () => quickState === 'DONE' ? quickDraft : null),
      startAgentReview,
      workspaceApi: workspaceRequest,
    } as unknown as typeof window.bridge;

    render(
      <PullsScreen
        initialPr={{ repo: pr.repo, number: pr.number }}
        onOpenWorkspacePr={openWorkspacePr}
        onRunQuickReview={quickReview}
        onWatchRepoForFullReview={watchForFullReview}
      />,
    );

    await waitFor(() => expect(screen.getByTestId('pull-detail').dataset.unwatched).toBe('true'));
    expect((screen.getByRole('button', { name: 'full-hook' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: 'open-hook' }) as HTMLButtonElement).disabled).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: 'quick-hook' }));
    await waitFor(() => expect(screen.getByTestId('pull-detail').dataset.quick).toBe('done'));
    expect(startQuickReview).toHaveBeenCalledWith(pr.id);
    expect(screen.getByText(quickDraft.summary as string)).not.toBeNull();
    expect(quickReview).toHaveBeenCalledWith(expect.objectContaining({ id: pr.id, repo: pr.repo, num: pr.number }));
    expect(openWorkspacePr).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'watch-hook' }));
    expect(watchForFullReview).toHaveBeenCalledWith(expect.objectContaining({ id: pr.id, repo: pr.repo, num: pr.number }));
    expect(screen.getByRole('dialog').textContent).toContain(pr.repo);
    fireEvent.click(screen.getByRole('button', { name: 'confirm-watch' }));

    await waitFor(() => expect(screen.getByTestId('pull-detail').dataset.watch).toBe('preparing'));
    expect(JSON.parse(window.localStorage.getItem('bytequay.pending-full-review') ?? '{}'))
      .toMatchObject({ operationId: 'create-1', prId: pr.id, repo: pr.repo, prNumber: pr.number });
    expect(startAgentReview).not.toHaveBeenCalled();

    finishCreation({ ...queuedCreation, state: 'ready', workspaceId: 'ws-created' });
    await waitFor(() => expect(startAgentReview).toHaveBeenCalledWith(pr.id, { workspaceId: 'ws-created' }));
    await waitFor(() => expect(screen.getByTestId('pull-detail').dataset.unwatched).toBe('false'));
    expect(window.localStorage.getItem('bytequay.pending-full-review')).toBeNull();
  });

  it('resumes a persisted watch intent after remount', async () => {
    setCached('prs:list', [pr]);
    window.localStorage.setItem('bytequay.pending-full-review', JSON.stringify({
      operationId: 'create-1', prId: pr.id, repo: pr.repo, prNumber: pr.number,
    }));
    const startAgentReview = vi.fn().mockResolvedValue({});
    const workspaceRequest = vi.fn().mockResolvedValue({
      ...queuedCreation, state: 'ready', workspaceId: 'ws-resumed',
    });
    window.bridge = {
      listWorkspaces: vi.fn().mockResolvedValue([]),
      fetchDashboardPrs: vi.fn().mockResolvedValue([pr]),
      syncDashboardPrs: vi.fn().mockResolvedValue([pr]),
      recordSurfaceVisit: vi.fn().mockResolvedValue(undefined),
      getQuickReviewStatus: vi.fn().mockResolvedValue({ state: 'IDLE', error: null }),
      getLatestQuickReview: vi.fn().mockResolvedValue(null),
      startAgentReview,
      workspaceApi: workspaceRequest,
    } as unknown as typeof window.bridge;

    render(<PullsScreen initialPr={{ repo: pr.repo, number: pr.number }} />);

    await waitFor(() => expect(startAgentReview).toHaveBeenCalledWith(pr.id, { workspaceId: 'ws-resumed' }));
    expect(workspaceRequest).toHaveBeenCalledWith({ path: '/api/workspace-creations/create-1' });
    expect(window.localStorage.getItem('bytequay.pending-full-review')).toBeNull();
  });

  it('starts full review only with the watched workspace id and opens its agent column route', async () => {
    setCached('prs:list', [pr]);
    const startAgentReview = vi.fn().mockResolvedValue({});
    const openWorkspacePr = vi.fn();
    window.bridge = {
      listWorkspaces: vi.fn().mockResolvedValue([{
        id: 'ws-1',
        repository: { fullName: pr.repo },
      }]),
      fetchDashboardPrs: vi.fn().mockResolvedValue([pr]),
      syncDashboardPrs: vi.fn().mockResolvedValue([pr]),
      recordSurfaceVisit: vi.fn().mockResolvedValue(undefined),
      startAgentReview,
    } as unknown as typeof window.bridge;

    render(
      <PullsScreen
        initialPr={{ repo: pr.repo, number: pr.number }}
        onOpenWorkspacePr={openWorkspacePr}
      />,
    );

    await waitFor(() => expect((screen.getByRole('button', { name: 'full-hook' }) as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'workspace-hook' }));
    expect(openWorkspacePr).toHaveBeenCalledWith(pr.repo, pr.number, {
      agent: false,
      prId: pr.id,
    });
    openWorkspacePr.mockClear();

    fireEvent.click(screen.getByRole('button', { name: 'full-hook' }));
    expect(startAgentReview).toHaveBeenCalledWith(pr.id, { workspaceId: 'ws-1' });

    await waitFor(() => expect((screen.getByRole('button', { name: 'open-hook' }) as HTMLButtonElement).disabled)
      .toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'open-hook' }));
    expect(openWorkspacePr).toHaveBeenCalledWith(pr.repo, pr.number, {
      agent: true,
      prId: pr.id,
    });
  });

  it('does not hand off to AgentColumn when an optimistic full-review start fails', async () => {
    setCached('prs:list', [pr]);
    let failStart!: (reason: Error) => void;
    const startAgentReview = vi.fn().mockReturnValue(new Promise((_resolve, reject) => {
      failStart = reject;
    }));
    const openWorkspacePr = vi.fn();
    window.bridge = {
      listWorkspaces: vi.fn().mockResolvedValue([{
        id: 'ws-1',
        repository: { fullName: pr.repo },
      }]),
      fetchDashboardPrs: vi.fn().mockResolvedValue([pr]),
      syncDashboardPrs: vi.fn().mockResolvedValue([pr]),
      recordSurfaceVisit: vi.fn().mockResolvedValue(undefined),
      startAgentReview,
    } as unknown as typeof window.bridge;

    render(
      <PullsScreen
        initialPr={{ repo: pr.repo, number: pr.number }}
        onOpenWorkspacePr={openWorkspacePr}
      />,
    );

    await waitFor(() => expect((screen.getByRole('button', { name: 'full-hook' }) as HTMLButtonElement).disabled)
      .toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'full-hook' }));
    await waitFor(() => expect((screen.getByRole('button', { name: 'open-hook' }) as HTMLButtonElement).disabled)
      .toBe(true));
    fireEvent.click(screen.getByRole('button', { name: 'open-hook' }));
    expect(openWorkspacePr).not.toHaveBeenCalled();

    failStart(new Error('review could not start'));
    await waitFor(() => expect((screen.getByRole('button', { name: 'full-hook' }) as HTMLButtonElement).disabled)
      .toBe(false));
    expect(openWorkspacePr).not.toHaveBeenCalled();
  });
});
