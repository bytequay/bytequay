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
import { invalidate } from '../dataCache';
import type { LocalPRBundle } from '../types/localPr';
import type { DashboardPR } from '../types/dashboardPr';
import { buildTimeline, type TimelineItem } from './detailModel';
import type { PullRow } from './model';
import PullOverview from './PullOverview';
import PullTimeline from './PullTimeline';

afterEach(() => {
  cleanup();
  invalidate('home:profile');
  delete (globalThis as { bridge?: unknown }).bridge;
});

function row(reviewers: string[]): PullRow {
  return {
    id: 'pr-1',
    repo: 'trinodb/trino',
    num: 1,
    title: 'A change',
    author: 'octocat',
    time: '1h ago',
    kind: 'pr',
    chips: [],
    status: null,
    add: 0,
    del: 0,
    comments: 0,
    hasAgent: false,
    dto: {
      requestedReviewers: reviewers,
      reviewerVerdicts: null,
      labels: [],
      createdAt: null,
    } as DashboardPR,
  };
}

const bundle = {
  pr: {
    author: 'octocat',
    createdAt: 0,
    description: '# Description\n\nBody',
    status: 'remote-open',
    remotePrNumber: 1,
  },
  comments: [],
  timeline: [],
  checks: [],
  commits: [],
} as LocalPRBundle;

describe('PullOverview', () => {
  it('shows structured skeleton cards while a large pull request loads', () => {
    const { container } = render(
      <PullOverview row={row([])} bundle={undefined} isMerged={false} />,
    );

    expect(screen.getByRole('status', { name: 'Loading pull request details' })).not.toBeNull();
    expect(container.querySelectorAll('.pl-pr-skeleton-card')).toHaveLength(2);
    expect(container.querySelectorAll('.pl-pr-skeleton-line').length).toBeGreaterThan(3);
    expect(screen.queryByText('No description provided.')).toBeNull();
  });

  it('shows only avatars with username tooltips when there are more than three reviewers', () => {
    const { container } = render(
      <PullOverview row={row(['one', 'two', 'three', 'four', 'five'])} bundle={bundle} isMerged={false} />,
    );

    for (const login of ['one', 'two', 'three', 'four', 'five']) {
      expect(screen.queryByText(login)).toBeNull();
      expect(screen.getByTitle(login).querySelector('img')?.getAttribute('alt')).toBe(login);
    }
    expect(container.querySelector('.pl-pr-description')?.classList.contains('md-body')).toBe(true);
    expect(screen.getByText('octocat').getAttribute('style')).toContain('font-weight: 600');
  });

  it('uses the signed-in GitHub avatar when a task PR has no stored author', async () => {
    window.bridge = {
      getUserProfile: vi.fn().mockResolvedValue({
        login: 'octocat', avatarUrl: 'https://avatars.example/octocat.png',
      }),
    } as unknown as typeof window.bridge;
    const taskRow = row([]);
    taskRow.author = '';
    const taskBundle = { ...bundle, pr: { ...bundle.pr, author: null } } as LocalPRBundle;

    render(<PullOverview row={taskRow} bundle={taskBundle} isMerged={false} />);

    const avatar = await screen.findByRole('img', { name: 'octocat' });
    expect(avatar.getAttribute('src')).toBe('https://avatars.example/octocat.png');
  });

  it('uses regular weight for every timeline username', () => {
    const items: TimelineItem[] = [
      { kind: 'comment', id: 'c', at: 1, time: 'now', author: 'commenter', bot: false, body: 'body', remoteId: null, replies: [
        { id: 'reply', author: 'replier', bot: false, body: 'reply', time: 'now' },
      ] },
      { kind: 'review', id: 'r', at: 2, time: 'now', author: 'reviewer', bot: false, verdict: 'approved', body: null, remoteId: null },
      { kind: 'merged', id: 'm', at: 3, time: 'now', author: 'merger', sha: null, base: 'main' },
    ];

    render(<PullTimeline items={items} repo="trinodb/trino" />);

    for (const login of ['commenter', 'replier', 'reviewer', 'merger']) {
      expect(screen.getByText(login).getAttribute('style')).toContain('font-weight: 400');
    }
  });

  it('renders comment-only reviews without calling them changes requested', () => {
    const items: TimelineItem[] = [
      { kind: 'review', id: 'r', at: 1, time: 'now', author: 'reviewer', bot: false, verdict: 'commented', body: null, remoteId: null },
    ];

    render(<PullTimeline items={items} repo="trinodb/trino" />);

    expect(screen.getByText('Commented')).toBeTruthy();
    expect(screen.queryByText('Changes requested')).toBeNull();
  });

  it('renders a pending local code review thread with location, reply, resolve, and send actions', async () => {
    const local = {
      ...bundle,
      pr: {
        ...bundle.pr,
        id: 'pr-1', taskId: 'task-1', branchName: 'dev/review', baseBranch: 'main', title: 'Review me',
        status: 'local-open', origin: 'task', repo: null, remotePrNumber: null, remotePrUrl: null,
      },
      comments: [{
        id: 'local-1', localPrId: 'pr-1', origin: 'local', scope: 'file-line',
        filePath: 'src/Foo.java', lineNumber: 41, side: 'RIGHT', startLine: null, startSide: null,
        author: 'you', body: 'Please make this null-safe.', createdAt: 1000, resolvedAt: null,
        dismissedAt: null, strippedOnPushAt: null, parentCommentId: null, publishedAt: null,
      }],
    } as LocalPRBundle;
    const onLocalReply = vi.fn().mockResolvedValue(undefined);
    const onLocalResolve = vi.fn().mockResolvedValue(undefined);
    const onSubmitLocalReview = vi.fn().mockResolvedValue(undefined);

    render(
      <PullTimeline
        items={buildTimeline(local)}
        repo="trinodb/trino"
        localPr={local.pr}
        onLocalReply={onLocalReply}
        onLocalResolve={onLocalResolve}
        onSubmitLocalReview={onSubmitLocalReview}
      />,
    );

    expect(screen.getByText('src/Foo.java:41')).toBeTruthy();
    expect(screen.getByText('PENDING REVIEW')).toBeTruthy();
    fireEvent.click(screen.getByPlaceholderText('Reply…'));
    fireEvent.change(screen.getByPlaceholderText('Write a reply'), { target: { value: 'One more detail.' } });
    fireEvent.click(screen.getByRole('button', { name: 'Reply' }));
    await waitFor(() => expect(onLocalReply).toHaveBeenCalledWith(local.comments[0], 'One more detail.'));

    fireEvent.click(screen.getByRole('button', { name: 'Resolve conversation' }));
    await waitFor(() => expect(onLocalResolve).toHaveBeenCalledWith('local-1'));
    expect(screen.getByRole('button', { name: 'Send to dev' }).hasAttribute('disabled')).toBe(true);
    expect(screen.getByPlaceholderText('Reply…').hasAttribute('disabled')).toBe(true);
    expect(onSubmitLocalReview).not.toHaveBeenCalled();
  });

  it('shows submitted user findings and open brain findings as waiting workflow roles', () => {
    const local = {
      ...bundle,
      pr: {
        ...bundle.pr,
        id: 'pr-1', taskId: 'task-1', branchName: 'dev/review', baseBranch: 'main', title: 'Review me',
        status: 'local-open', origin: 'task', repo: null, remotePrNumber: null, remotePrUrl: null,
      },
      timeline: [{
        id: 'submitted', localPrId: 'pr-1', eventType: 'review', actor: 'you', isLocalOnly: true,
        strippedOnPushAt: null, createdAt: 1200,
        payload: { reviewEvent: 'submitted', verdict: 'COMMENT', commentIds: ['user-1'] },
      }],
      comments: [{
        id: 'user-1', localPrId: 'pr-1', origin: 'local', scope: 'file-line', filePath: 'src/A.java',
        lineNumber: 7, side: 'RIGHT', startLine: null, startSide: null, author: 'you', body: 'Fix A',
        createdAt: 1000, resolvedAt: null, dismissedAt: null, strippedOnPushAt: null,
        parentCommentId: null, publishedAt: null,
      }, {
        id: 'brain-1', localPrId: 'pr-1', origin: 'local', scope: 'file-line', filePath: 'src/B.java',
        lineNumber: 9, side: 'RIGHT', startLine: null, startSide: null, author: 'brain', body: 'Fix B',
        createdAt: 1100, resolvedAt: null, dismissedAt: null, strippedOnPushAt: null,
        parentCommentId: null, publishedAt: null,
      }],
    } as LocalPRBundle;

    render(
      <PullTimeline
        items={buildTimeline(local)}
        repo="trinodb/trino"
        localPr={local.pr}
        onLocalResolve={vi.fn().mockResolvedValue(undefined)}
      />,
    );

    expect(screen.getByText('SENT TO DEV')).toBeTruthy();
    expect(screen.getByText('WAITING FOR DEV')).toBeTruthy();
    expect(screen.getByText('BRAIN')).toBeTruthy();
    expect(screen.queryByText('claude-code')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Send to dev' })).toBeNull();
    // Both the user finding and the brain finding are manually resolvable now
    // (brain findings used to have no resolve control).
    expect(screen.getAllByRole('button', { name: 'Resolve conversation' })).toHaveLength(2);
  });

  it('routes a review thread into the agent composer via Ask agent', () => {
    const onAskAgentThread = vi.fn();
    const local = {
      ...bundle,
      pr: {
        ...bundle.pr, id: 'pr-1', taskId: 'task-1', status: 'local-open',
        origin: 'task', repo: null, remotePrNumber: null, remotePrUrl: null,
      },
      comments: [{
        id: 'c1', localPrId: 'pr-1', origin: 'local', scope: 'file-line', filePath: 'src/A.java',
        lineNumber: 7, side: 'RIGHT', startLine: null, startSide: null, author: 'you', body: 'Fix A',
        createdAt: 1000, resolvedAt: null, dismissedAt: null, strippedOnPushAt: null,
        parentCommentId: null, publishedAt: null,
      }],
    } as LocalPRBundle;

    render(
      <PullTimeline
        items={buildTimeline(local)}
        repo="trinodb/trino"
        localPr={local.pr}
        onAskAgentThread={onAskAgentThread}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: /Ask agent/ }));
    expect(onAskAgentThread).toHaveBeenCalledWith(expect.objectContaining({ id: 'c1' }));
  });

  it('renders the real user avatar and can unresolve a resolved comment', async () => {
    const local = {
      ...bundle,
      pr: {
        ...bundle.pr, id: 'pr-1', taskId: 'task-1', status: 'local-open',
        origin: 'task', repo: null, remotePrNumber: null, remotePrUrl: null,
      },
      comments: [{
        id: 'mine', localPrId: 'pr-1', origin: 'local', scope: 'file-line', filePath: 'src/A.java',
        lineNumber: 7, side: 'RIGHT', startLine: null, startSide: null, author: 'you', body: 'Fix A',
        createdAt: 1000, resolvedAt: 2000, dismissedAt: null, strippedOnPushAt: null,
        parentCommentId: null, publishedAt: null, resolvedBy: 'you',
      }],
    } as LocalPRBundle;
    const onLocalReopen = vi.fn().mockResolvedValue(undefined);

    render(
      <PullTimeline
        items={buildTimeline(local)}
        repo="trinodb/trino"
        localPr={local.pr}
        currentUserLogin="octocat"
        onLocalResolve={vi.fn()}
        onLocalReopen={onLocalReopen}
      />,
    );

    // Resolved comment folds; expand to reveal the message + action.
    fireEvent.click(screen.getByTitle('Expand thread'));
    expect(screen.getAllByAltText('octocat').some(
      img => img.getAttribute('src')?.includes('github.com/octocat.png'))).toBe(true);
    expect(screen.getByText(/marked this conversation as resolved/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Unresolve conversation' }));
    await waitFor(() => expect(onLocalReopen).toHaveBeenCalledWith('mine'));
  });

  it('does not reopen a resolved Brain finding through an unrouteable reply', () => {
    const local = {
      ...bundle,
      pr: {
        ...bundle.pr,
        id: 'pr-1', taskId: 'task-1', status: 'local-open', origin: 'task', repo: null,
      },
      comments: [{
        id: 'brain-1', localPrId: 'pr-1', origin: 'local', scope: 'file-line', filePath: 'src/B.java',
        lineNumber: 9, side: 'RIGHT', startLine: null, startSide: null, author: 'brain', body: 'Fix B',
        createdAt: 1000, resolvedAt: 1200, dismissedAt: null, strippedOnPushAt: null,
        parentCommentId: null, publishedAt: null,
      }],
    } as LocalPRBundle;

    render(
      <PullTimeline
        items={buildTimeline(local)}
        repo="trinodb/trino"
        localPr={local.pr}
        onLocalReply={vi.fn().mockResolvedValue(undefined)}
      />,
    );

    fireEvent.click(screen.getByTitle('Expand thread'));
    expect(screen.getByText('Fix B')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Reply' })).toBeNull();
  });

  it('lets a pending finding-backed review thread be sent to Development', async () => {
    const local = {
      ...bundle,
      pr: {
        ...bundle.pr,
        id: 'pr-1', taskId: 'task-1', branchName: 'dev/review', baseBranch: 'main', title: 'Review me',
        status: 'local-open', origin: 'task', repo: null, remotePrNumber: null, remotePrUrl: null,
      },
      comments: [{
        id: 'agent-1', localPrId: 'pr-1', origin: 'local', scope: 'file-line', filePath: 'src/C.java',
        lineNumber: 12, side: 'RIGHT', startLine: null, startSide: null, author: 'agent', body: 'Fix C',
        createdAt: 1000, resolvedAt: null, dismissedAt: null, strippedOnPushAt: null,
        parentCommentId: null, publishedAt: null, findingId: 'finding-1',
      }],
    } as LocalPRBundle;
    const onSubmitLocalReview = vi.fn().mockResolvedValue(undefined);

    render(
      <PullTimeline
        items={buildTimeline(local)}
        repo="trinodb/trino"
        localPr={local.pr}
        onSubmitLocalReview={onSubmitLocalReview}
      />,
    );

    expect(screen.getByText('src/C.java:12')).toBeTruthy();
    expect(screen.getByText('PENDING REVIEW')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Send to dev' }));
    await waitFor(() => expect(onSubmitLocalReview).toHaveBeenCalledWith(['agent-1']));
  });

  it('renders GitHub review threads as compact actionable desktop cards', async () => {
    const reviewBundle = {
      ...bundle,
      timeline: [{
        id: 'review-event', localPrId: 'pr-1', eventType: 'review', actor: '@reviewer',
        isLocalOnly: false, strippedOnPushAt: null, createdAt: 1_750_412_800_000,
        payload: { verdict: 'COMMENTED', body: null }, remoteEventId: 9001,
      }],
    } as LocalPRBundle;
    const replyToReviewThread = vi.fn().mockResolvedValue(undefined);
    const setReviewThreadResolved = vi.fn().mockResolvedValue(undefined);
    window.bridge = {
      fetchPullRequestDetail: vi.fn().mockResolvedValue({
        recentActivity: [{
          actor: 'reviewer', eventType: 'reviewed', timestamp: '2025-06-20T10:00:00Z',
          body: null, state: 'COMMENTED', beforeSha: null, afterSha: null,
          requestedReviewer: null, reviewId: 77, authorAssociation: 'MEMBER', githubId: 9001,
          reactions: null, labelName: null, labelColor: null, milestoneTitle: null,
          assigneeLogin: null, crossRefNumber: null, crossRefTitle: null, crossRefUrl: null,
          crossRefIsPullRequest: false,
        }],
        reviewThreads: [{
          rootGithubId: 501, filePath: 'src/Foo.java', line: 41, side: 'RIGHT',
          diffHunk: '@@ -41,1 +41,1 @@\n-return oldValue;\n+return currentValue;',
          messages: [{
            githubId: 601, author: 'reviewer', body: 'Please keep the current value here.',
            createdAt: '2025-06-20T10:00:00Z', reactions: null, reviewId: 77,
            authorAssociation: 'MEMBER',
          }, {
            githubId: 602, author: 'octocat', body: 'Updated as requested.',
            createdAt: '2025-06-20T11:00:00Z', reactions: null, reviewId: null,
            authorAssociation: 'OWNER',
          }],
          resolved: false, outdated: true, startLine: null, startSide: null,
          originalLine: 41, originalStartLine: null,
        }],
      }),
      refreshPullRequestDetail: vi.fn().mockImplementation(() => new Promise(() => {})),
      replyToReviewThread,
      setReviewThreadResolved,
      addReviewCommentReaction: vi.fn().mockResolvedValue(undefined),
    } as unknown as typeof window.bridge;

    const { container } = render(<PullOverview row={row([])} bundle={reviewBundle} isMerged={false} />);

    expect(await screen.findByText('Please keep the current value here.')).toBeTruthy();
    expect(container.querySelector('.prc-review-thread--compact')).not.toBeNull();
    expect(container.querySelector('.diff-row--del')?.textContent).toContain('return oldValue;');
    expect(container.querySelector('.diff-row--add')?.textContent).toContain('return currentValue;');
    expect(screen.getByText('1 reply')).toBeTruthy();
    expect(screen.queryByText('Commented')).toBeNull();
    expect(screen.getByText('outdated')).toBeTruthy();

    fireEvent.click(screen.getByTitle('Collapse thread'));
    expect(screen.queryByText('Please keep the current value here.')).toBeNull();
    expect(container.querySelector('.diff-row--add')).toBeNull();
    expect(screen.getByText('1 reply')).toBeTruthy();
    fireEvent.click(screen.getByTitle('Expand thread'));
    expect(screen.getByText('Please keep the current value here.')).toBeTruthy();

    fireEvent.click(screen.getByPlaceholderText('Reply…'));
    fireEvent.change(screen.getByPlaceholderText('Write a reply'), { target: { value: 'Looks good now.' } });
    fireEvent.click(screen.getByRole('button', { name: 'Reply' }));
    await waitFor(() => expect(replyToReviewThread).toHaveBeenCalledWith('trinodb/trino', 1, 501, 'Looks good now.'));

    fireEvent.click(screen.getByRole('button', { name: 'Resolve conversation' }));
    await waitFor(() => expect(setReviewThreadResolved).toHaveBeenCalledWith('trinodb/trino', 0, 501, true));
  });

  it('selects reviewers, assignees, and labels from the searchable popovers', async () => {
    const getPullRequestMetadataChoices = vi.fn().mockResolvedValue({
      users: [
        { login: 'alice', avatarUrl: null, name: null },
        { login: 'bob', avatarUrl: null, name: null },
      ],
      labels: [{ name: 'jdbc', color: '007f8b' }, { name: 'ui', color: '0e8a16' }],
      assignees: ['alice'],
      selectedLabels: ['jdbc'],
    });
    const addRequestedReviewer = vi.fn().mockResolvedValue(undefined);
    const setPullRequestAssignee = vi.fn().mockResolvedValue(undefined);
    const setPullRequestLabel = vi.fn().mockResolvedValue(undefined);
    window.bridge = {
      getPullRequestMetadataChoices,
      addRequestedReviewer,
      removeRequestedReviewer: vi.fn().mockResolvedValue(undefined),
      setPullRequestAssignee,
      setPullRequestLabel,
    } as unknown as typeof window.bridge;
    render(<PullOverview row={row([])} bundle={bundle} isMerged={false} />);

    fireEvent.click(screen.getByRole('button', { name: 'Reviewers' }));
    fireEvent.click(await screen.findByRole('option', { name: /bob/ }));
    await waitFor(() => expect(addRequestedReviewer).toHaveBeenCalledWith('trinodb/trino', 1, 'bob'));

    fireEvent.click(screen.getByRole('button', { name: /Assignees/ }));
    fireEvent.click(screen.getByRole('option', { name: /bob/ }));
    await waitFor(() => expect(setPullRequestAssignee).toHaveBeenCalledWith('trinodb/trino', 1, 'bob', true));

    fireEvent.click(screen.getByRole('button', { name: '1 label' }));
    fireEvent.click(screen.getByRole('option', { name: /ui/ }));
    await waitFor(() => expect(setPullRequestLabel).toHaveBeenCalledWith('trinodb/trino', 1, 'ui', true));
  });

  it('opens the emoji picker and adds a reaction to the pull request description', async () => {
    const addPullRequestReaction = vi.fn().mockResolvedValue(undefined);
    window.bridge = { addPullRequestReaction } as unknown as typeof window.bridge;
    render(<PullOverview row={row([])} bundle={bundle} isMerged={false} />);

    fireEvent.click(screen.getByTitle('Add a reaction'));
    fireEvent.click(screen.getByTitle('Heart'));

    await waitFor(() => expect(addPullRequestReaction).toHaveBeenCalledWith('trinodb/trino', 1, 'heart'));
    expect(await screen.findByText('Reaction added')).not.toBeNull();
  });

  it('edits and saves the pull request description from the overflow menu', async () => {
    const updatePrBody = vi.fn().mockResolvedValue(undefined);
    const onDescriptionSaved = vi.fn();
    window.bridge = { updatePrBody } as unknown as typeof window.bridge;
    render(
      <PullOverview
        row={row([])}
        bundle={bundle}
        isMerged={false}
        onDescriptionSaved={onDescriptionSaved}
      />,
    );

    fireEvent.click(screen.getByTitle('Comment actions'));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Edit' }));
    expect(screen.getByRole('tab', { name: 'Write' })).not.toBeNull();
    expect(screen.getByRole('tab', { name: 'Preview' })).not.toBeNull();
    const editor = document.querySelector<HTMLTextAreaElement>('.editable-comment-body__textarea');
    if (editor === null) throw new Error('Description editor did not open');
    fireEvent.change(editor, { target: { value: 'Updated description' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(updatePrBody).toHaveBeenCalledWith('trinodb/trino', 1, 'Updated description'));
    expect(onDescriptionSaved).toHaveBeenCalledOnce();
    expect(await screen.findByText('Updated description')).not.toBeNull();
  });

  it('updates a task directly from the rendered pull request description', async () => {
    const updatePrBody = vi.fn().mockResolvedValue(undefined);
    const onDescriptionSaved = vi.fn();
    const taskBundle = {
      ...bundle,
      pr: { ...bundle.pr, description: '- [ ] First task\n1. [ ] Second task' },
    } as LocalPRBundle;
    window.bridge = { updatePrBody } as unknown as typeof window.bridge;
    render(
      <PullOverview
        row={row([])}
        bundle={taskBundle}
        isMerged={false}
        onDescriptionSaved={onDescriptionSaved}
      />,
    );

    const checkboxes = screen.getAllByRole('checkbox') as HTMLInputElement[];
    expect(checkboxes[1].disabled).toBe(false);
    fireEvent.click(checkboxes[1]);

    await waitFor(() => expect(updatePrBody).toHaveBeenCalledWith(
      'trinodb/trino',
      1,
      '- [ ] First task\n1. [x] Second task',
    ));
    expect(onDescriptionSaved).toHaveBeenCalledOnce();
    expect((screen.getAllByRole('checkbox')[1] as HTMLInputElement).checked).toBe(true);
  });

  it('keeps a long description editor at the same height after previewing', () => {
    const longDescription = Array.from({ length: 40 }, (_, index) => `Line ${index + 1}`).join('\n');
    const longBundle = {
      ...bundle,
      pr: { ...bundle.pr, description: longDescription },
    } as LocalPRBundle;
    render(<PullOverview row={row([])} bundle={longBundle} isMerged={false} />);

    fireEvent.click(screen.getByTitle('Comment actions'));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Edit' }));
    const initialEditor = document.querySelector<HTMLTextAreaElement>('.editable-comment-body__textarea');
    if (initialEditor === null) throw new Error('Description editor did not open');
    expect(initialEditor.rows).toBe(30);
    expect(initialEditor.style.height).toBe('');

    fireEvent.click(screen.getByRole('tab', { name: 'Preview' }));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    fireEvent.click(screen.getByTitle('Comment actions'));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Edit' }));

    const reopenedEditor = document.querySelector<HTMLTextAreaElement>('.editable-comment-body__textarea');
    if (reopenedEditor === null) throw new Error('Description editor did not reopen');
    expect(reopenedEditor.rows).toBe(30);
    expect(reopenedEditor.style.height).toBe('');
  });

  it('adds a reaction to a remote timeline comment by its GitHub id', async () => {
    const onCommentReaction = vi.fn().mockResolvedValue(undefined);
    const items: TimelineItem[] = [{
      kind: 'comment', id: 'c', at: 1, time: 'now', author: 'commenter', bot: false,
      body: 'body', replies: [], remoteId: 4357983764,
    }];
    render(<PullTimeline items={items} repo="trinodb/trino" onCommentReaction={onCommentReaction} />);

    fireEvent.click(screen.getByTitle('Add a reaction'));
    fireEvent.click(screen.getByTitle('Rocket'));

    await waitFor(() => expect(onCommentReaction).toHaveBeenCalledWith(4357983764, 'rocket'));
  });
});
