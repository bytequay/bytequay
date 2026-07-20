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
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PrDetailsView } from './PrDetailsView';
import { useExternalPrActions } from './useExternalPrActions';
import type { LocalPRBundle } from '../../types/localPr';
import { createAgentReviewFixture } from '../../review/agentReviewTestData';

vi.mock('./useExternalPrActions', () => ({
  useExternalPrActions: vi.fn(),
}));

vi.mock('./LocalPrReviewScreen', () => ({
  LocalPrReviewScreen: ({ fetchFileBlob }: { fetchFileBlob?: (path: string) => Promise<{ lines: string[] }> }) => (
    <button type="button" onClick={() => { void fetchFileBlob?.('src/Foo.ts'); }}>
      expand
    </button>
  ),
}));

vi.mock('./PRView', () => ({
  PRView: ({ headerAction }: { headerAction?: ReactNode }) => <div>{headerAction}</div>,
}));

vi.mock('./PushDialog', () => ({
  PushDialog: () => <div />,
}));

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  Reflect.deleteProperty(window, 'bridge');
});

function bundle(): LocalPRBundle {
  return {
    pr: {
      id: 'pr-1',
      taskId: null,
      branchName: 'feature',
      baseBranch: 'main',
      title: 'Test PR',
      description: '',
      status: 'remote-open',
      createdAt: 1,
      pushedAt: null,
      remotePrNumber: 42,
      remotePrUrl: null,
      mergedAt: null,
      closedAt: null,
      origin: 'external',
      repo: 'owner/repo',
      author: 'octocat',
      syncedAt: 1,
      syncedAdditions: 1,
      syncedDeletions: 1,
      syncedMergeable: true,
      syncedMergeableState: 'clean',
      syncedMergeQueueEnabled: false,
      syncedMergeQueueState: null,
      branchDeletedAt: null,
    },
    commits: [
      { id: 'c1', localPrId: 'pr-1', sha: 'oldsha', message: 'old', additions: 0, deletions: 0, authoredAt: 1, pushedAt: null },
      { id: 'c2', localPrId: 'pr-1', sha: 'headsha', message: 'head', additions: 0, deletions: 0, authoredAt: 2, pushedAt: null },
    ],
    timeline: [],
    checks: [],
    comments: [],
  };
}

function actions(
  value: LocalPRBundle,
  overrides: Partial<ReturnType<typeof useExternalPrActions>> = {},
): ReturnType<typeof useExternalPrActions> {
  return {
    bundle: value,
    refresh: vi.fn(),
    syncing: false,
    localPr: value.pr,
    capabilities: {
      draftLocalComments: true,
      publishReview: true,
      push: false,
      merge: true,
      chatAgent: false,
      postRemoteComment: true,
    },
    localComment: '',
    setLocalComment: vi.fn(),
    submitLocalComment: vi.fn(),
    confirmPush: vi.fn(),
    confirmMerge: vi.fn(),
    dequeuePr: vi.fn(),
    deleteBranch: vi.fn(),
    publishReview: vi.fn(),
    publishBusy: false,
    addLocalLineComment: vi.fn(),
    replyLocalLineComment: vi.fn(),
    replyLocalPrComment: vi.fn(),
    resolveLocalComment: vi.fn(),
    deleteLocalComment: vi.fn(),
    dismissLocalComment: vi.fn(),
    pushOpen: false,
    setPushOpen: vi.fn(),
    reviewOpen: false,
    setReviewOpen: vi.fn(),
    prBusy: false,
    reviewFiles: [],
    reviewError: null,
    runLocalTests: vi.fn(),
    testsBusy: false,
    ...overrides,
  };
}

describe('PrDetailsView', () => {
  it('starts with the real PR untouched and exposes the review entry action', async () => {
    const b = bundle();
    const live = createAgentReviewFixture(b, []);
    window.bridge = {
      listWorkspaces: vi.fn().mockResolvedValue([{
        id: 'ws-owner', repository: { fullName: 'owner/repo' },
      }]),
      listWorkspaceRepos: vi.fn().mockResolvedValue([{ repoFullName: 'owner/repo' }]),
      getAgentReview: vi.fn().mockResolvedValue(null),
      startAgentReview: vi.fn().mockResolvedValue(live),
    } as unknown as typeof window.bridge;
    vi.mocked(useExternalPrActions).mockReturnValue(actions(b));

    render(<PrDetailsView pr={{ id: 1, repo: 'owner/repo', number: 42 }} workspaceId="ws-owner" />);
    const start = await screen.findByRole('button', { name: /^Full review$/ });
    expect(screen.queryByRole('button', { name: /Submit review/ })).toBeNull();

    fireEvent.click(start);

    await waitFor(() => expect(screen.getByRole('button', { name: /Full review • running/ })).toBeTruthy());
  });

  it('puts manual draft submission in the PR header with its pending count', async () => {
    const b = bundle();
    b.comments = [{
      id: 'manual-draft', localPrId: b.pr.id, origin: 'local', scope: 'file-line',
      filePath: 'src/Foo.ts', lineNumber: 18, side: 'RIGHT', startLine: null, startSide: null,
      author: 'you', body: 'Please cover this branch.', createdAt: 1, resolvedAt: null,
      dismissedAt: null, strippedOnPushAt: null, parentCommentId: null, publishedAt: null,
      findingId: null,
    }];
    window.bridge = {
      listWorkspaces: vi.fn().mockResolvedValue([{
        id: 'ws-owner', repository: { fullName: 'owner/repo' },
      }]),
      getAgentReview: vi.fn().mockResolvedValue(null),
    } as unknown as typeof window.bridge;
    vi.mocked(useExternalPrActions).mockReturnValue(actions(b));

    render(<PrDetailsView pr={{ id: 1, repo: 'owner/repo', number: 42 }} workspaceId="ws-owner" />);

    expect(await screen.findByRole('button', { name: 'Submit review • 1 ▾' })).toBeTruthy();
  });

  it('passes a PR-head blob fetcher to the review diff', async () => {
    const fetchFileBlob = vi.fn().mockResolvedValue({ lines: [] });
    (window as unknown as { bridge: { fetchFileBlob: typeof fetchFileBlob } }).bridge = { fetchFileBlob };
    const b = bundle();
    vi.mocked(useExternalPrActions).mockReturnValue(actions(b, { reviewOpen: true }));

    render(<PrDetailsView pr={{ id: 1, repo: 'owner/repo', number: 42 }} />);
    fireEvent.click(screen.getByRole('button', { name: 'expand' }));

    await waitFor(() => expect(fetchFileBlob).toHaveBeenCalledWith('owner/repo', 'src/Foo.ts', 'headsha'));
  });

  it('hands an unwatched team PR to quick review or watched full review setup', async () => {
    const b = bundle();
    const onOpenReviewSetup = vi.fn();
    window.bridge = {
      listWorkspaces: vi.fn().mockResolvedValue([]),
      getAgentReview: vi.fn().mockResolvedValue(null),
    } as unknown as typeof window.bridge;
    vi.mocked(useExternalPrActions).mockReturnValue(actions(b));

    render(
      <PrDetailsView
        pr={{ id: 1, repo: 'owner/repo', number: 42 }}
        workspaceId="another-workspace"
        onOpenReviewSetup={onOpenReviewSetup}
      />,
    );

    fireEvent.click(await screen.findByRole('button', { name: 'Run quick review' }));
    expect(onOpenReviewSetup).toHaveBeenCalledWith('quick', 'owner/repo', 42);
    fireEvent.click(screen.getByRole('button', { name: 'Watch repo · Full review' }));
    expect(onOpenReviewSetup).toHaveBeenCalledWith('watch', 'owner/repo', 42);
    expect(screen.queryByRole('button', { name: /^Full review$/ })).toBeNull();
  });
});
