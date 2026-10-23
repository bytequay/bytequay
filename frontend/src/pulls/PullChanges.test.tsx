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
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { timelineActivity, timelineComment, pr as pullRequest } from './fixtures/pullRequest';
import type { DiffCommitDto, DiffFileDto, InlineReviewCommentDto, PullRequestDetailDto, PullTimelinePayloadDto, UserProfileDto } from '../types';
import { parseUnifiedDiff } from '../diffParse';
import type { AgentReviewDto } from './agent-review/contracts';
import { reviewFixture } from './agent-review/fixtures/review';
import {
  DiffViewer,
  filenameTooltip,
  headLines,
  nextCommitRange,
  PendingReviewCommentRow,
  reviewTarget,
  submitInlineReviewComment,
  updatePendingReviewComment,
  type DiffViewerProps,
} from './PullChanges';

const commits: DiffCommitDto[] = [
  { sha: '1111111', parentSha: '0000000', message: 'First', author: 'alice', authoredAt: '2026-08-25T10:00:00Z' },
  { sha: '2222222', parentSha: '1111111', message: 'Second', author: 'bob', authoredAt: '2026-06-20T10:00:00Z' },
  { sha: '3333333', parentSha: '2222222', message: 'Third', author: 'carol', authoredAt: '2026-08-26T09:00:00Z' },
];

const file: DiffFileDto = {
  filename: 'src/Example.java',
  status: 'modified',
  additions: 1,
  deletions: 1,
  patch: [
    '@@ -95,3 +95,3 @@',
    ' context',
    '-old value',
    '+aVeryLongReplacementValueThatMustWrapInsideTheVisibleDiffPane',
    ' trailing',
  ].join('\n'),
};

function changedFile(filename: string, status = 'modified'): DiffFileDto {
  return { ...file, filename, status, additions: 11, deletions: 32, patch: null };
}

function reviewComment(overrides: Partial<InlineReviewCommentDto> = {}): InlineReviewCommentDto {
  return {
    path: file.filename,
    body: 'Pending feedback',
    line: 96,
    side: 'RIGHT',
    startLine: null,
    startSide: null,
    commitId: null,
    ...overrides,
  };
}

function reviewActivity(id: number, body: string, payload: Partial<PullTimelinePayloadDto> = {}): PullRequestDetailDto['recentActivity'][number] {
  return timelineActivity({ timestamp: '2026-08-25T10:00:00Z', githubId: id }, {
      path: file.filename, diffHunk: file.patch, line: 96, originalLine: 96, side: 'RIGHT',
      comments: [timelineComment({ githubId: id, actorAvatarUrl: 'https://example.com/bob.png', timestamp: '2026-08-25T10:00:00Z', body })],
      ...payload,
    });
}

function reviewThreads(activities: PullRequestDetailDto['recentActivity']): NonNullable<DiffViewerProps['reviewThreads']> {
  const pr = pullRequest({
    repo: 'owner/repo', title: 'Example pull request', author: 'alice',
    htmlUrl: 'https://github.com/owner/repo/pull/42', createdAt: '2026-08-25T09:00:00Z', updatedAt: '2026-08-25T10:00:00Z',
    labelColors: {}, deletions: 1, commentCount: activities.length, headRef: 'feature/review',
  });
  return { activities, pr, unavailable: false, onRefresh: async () => {}, onDetailUpdate: () => {} };
}

const viewer: UserProfileDto = {
  login: 'alice', name: 'Alice', avatarUrl: 'https://example.com/alice.png', htmlUrl: 'https://github.com/alice',
  publicRepos: 1, followers: 1, following: 1, bio: null, location: null, company: null, email: null,
};

describe('DiffViewer', () => {
  it('shows the selected commit title and full body above the file diffs', () => {
    const selectedCommits = [{ ...commits[0], message: 'First\r\n\r\nWhy this exists.\r\n\r\n    keep indentation\r\n' }, commits[1]];
    const render = (commitRange: DiffViewerProps['commitRange'], loading = false, message = selectedCommits[0].message) => renderToStaticMarkup(
      <DiffViewer files={[file]} commits={[{ ...selectedCommits[0], message }, selectedCommits[1]]} commitRange={commitRange} loading={loading} />);
    const html = render({ start: 0, end: 0 });

    expect(html).toContain('<h3>First</h3><pre>Why this exists.\n\n    keep indentation</pre></div><footer>');
    expect(html).toContain('src="https://github.com/alice.png?size=40" alt="alice"');
    expect(html).toContain('<strong>alice</strong> authored <time dateTime="2026-08-25T10:00:00Z"');
    expect(html.indexOf('class="pull-commit-message"')).toBeLessThan(html.indexOf('class="pull-diff-file"'));
    expect(render({ start: 0, end: 0 }, true)).toContain('class="pull-commit-message"');
    expect(render({ start: 0, end: 0 }, false, null)).toContain('<h3>Untitled commit</h3></div><footer>');
    expect(render({ start: 1, end: 1 })).toContain('<h3>Second</h3></div><footer>');
    for (const range of [null, { start: 0, end: 1 }, { start: 2, end: 2 }]) {
      expect(render(range)).not.toContain('class="pull-commit-message"');
    }
  });

  it('keeps the author footer readable when commit attribution is unavailable', () => {
    const html = renderToStaticMarkup(<DiffViewer files={[file]} commits={[{ ...commits[0], author: null, authoredAt: null }]}
      commitRange={{ start: 0, end: 0 }} />);

    expect(html).toContain('<strong>Unknown author</strong> authored');
    expect(html).toContain('aria-label="unknown user"');
    expect(html).not.toContain('<time');
    expect(html).not.toContain('<img');
  });

  it('renders expandable unchanged regions and line-review controls', () => {
    const html = renderToStaticMarkup(<DiffViewer
      files={[file]}
      commits={commits}
      review={{
        repo: 'owner/repo',
        number: 42,
        canReview: true,
        pendingComments: [],
        onPendingCommentsChange: () => {},
        onDetailChange: () => {},
      }}
      loadFileContent={async () => ({ oldContent: '', newContent: '' })}
    />);

    expect(html).toContain('↕ 94 unmodified lines');
    expect(html).toContain('↕ Show unchanged lines below');
    expect(html).toContain('aria-label="Select old line 95">95</button>');
    expect(html.match(/pull-diff-code__add-comment/g)).toHaveLength(4);
    expect(html).toContain('aria-label="Add a comment on new line 95">+</button>');
    expect(html).toContain('aria-label="Add a comment on old line 96">+</button>');
    expect(html).toContain('aria-label="Add a comment on new line 96">+</button>');
    expect(html).toContain('aria-label="Add a comment on new line 97">+</button>');
    expect(html).toContain('aVeryLongReplacementValueThatMustWrapInsideTheVisibleDiffPane');
    expect(html.match(/pull-diff-code__line-column/g)).toHaveLength(2);

    const readOnlyHtml = renderToStaticMarkup(<DiffViewer files={[file]} />);
    expect(readOnlyHtml).not.toContain('pull-diff-code__add-comment');
  });

  it('offers a copy button for the file path outside the collapse toggle', () => {
    const html = renderToStaticMarkup(<DiffViewer files={[file]} />);
    const header = html.match(/<div class="pull-diff-file__header">[\s\S]*?<span class="pull-diff-file__status"/)?.[0] ?? '';

    expect(header).toContain('class="pull-diff-file__toggle"');
    expect(header).toContain('aria-label="Copy file path"');
    const toggle = header.match(/<button [^>]*class="pull-diff-file__toggle"[\s\S]*?<\/button>/)?.[0] ?? '';
    expect(toggle).toContain('pull-diff-file__name');
    expect(toggle).not.toContain('pr-copy-button');
  });

  it('keeps the changed-files column closed by default', () => {
    const firstPath = 'core/trino-main/src/main/java/io/trino/QueryManagerConfig.java';
    const html = renderToStaticMarkup(<DiffViewer files={[
      changedFile(firstPath),
      changedFile('core/trino-main/src/main/java/io/trino/QueryContext.java', 'added'),
    ]} />);
    const treeId = html.match(/pull-changes__files-toggle[^>]*aria-controls="([^"]+)"/)?.[1];

    expect(html).toMatch(/<button[^>]*class="pull-changes__files-toggle"[^>]*aria-expanded="false"[^>]*title="Show changed files"[^>]*>[\s\S]*?<span>2 changed files<\/span><\/button>/);
    expect(html).not.toContain('aria-label="Toggle changed files"');
    expect(treeId).toBeTruthy();
    expect(html).not.toContain('class="pull-file-tree"');
    expect(html).not.toContain('aria-label="Resize changed files"');
    expect(html).toContain(`<span class="pull-diff-file__path" aria-label="${firstPath}">`);
    expect(html).toContain('<bdi dir="ltr">core/trino-main/src/main/java/io/trino/</bdi>');
    expect(html).toContain('<span class="pull-diff-file__name">QueryManagerConfig.java</span>');
  });

  it('renders one patch body at a time for large pull requests', () => {
    const files = Array.from({ length: 21 }, (_, index) => ({
      ...file,
      filename: `src/File${index}.java`,
    }));
    const largeHtml = renderToStaticMarkup(<DiffViewer files={files} />);
    const smallHtml = renderToStaticMarkup(<DiffViewer files={files.slice(0, 2)} />);

    expect(largeHtml.match(/<article class="pull-diff-file"/g)).toHaveLength(21);
    expect(largeHtml.match(/<table class="pull-diff-code"/g)).toHaveLength(1);
    expect(largeHtml).toContain('aria-label="Expand all file diffs"');
    expect(largeHtml).toContain('>Expand all</button>');
    expect(smallHtml.match(/<table class="pull-diff-code"/g)).toHaveLength(2);
    expect(smallHtml).toContain('aria-label="Collapse all file diffs"');
    expect(smallHtml).toContain('>Collapse all</button>');
  });

  it('renders foldable pending comments below their referenced side and line', () => {
    const html = renderToStaticMarkup(<DiffViewer
      files={[file]}
      review={{
        repo: 'owner/repo',
        number: 42,
        canReview: true,
        pendingComments: [
          reviewComment({ body: 'Old-side pending', side: 'LEFT' }),
          reviewComment({ body: 'New-side pending' }),
          reviewComment({ body: 'Range pending', line: 97, startLine: 95, startSide: 'RIGHT' }),
          reviewComment({ path: 'src/Other.java', body: 'Other-file pending' }),
        ],
        onPendingCommentsChange: () => {},
        onDetailChange: () => {},
      }}
    />);

    expect(html.match(/<details class="lrc is-github pr-inline-thread pull-diff-pending" open="">/g)).toHaveLength(3);
    expect(html).toContain('Comment on line <strong>L96</strong>');
    expect(html).toContain('Comment on line <strong>R96</strong>');
    expect(html).toContain('Comment on lines <strong>R95–R97</strong>');
    expect(html).not.toContain('Other-file pending');
    expect(html.indexOf('Old-side pending')).toBeGreaterThan(html.indexOf('old value'));
    expect(html.indexOf('Old-side pending')).toBeLessThan(html.indexOf('aVeryLongReplacementValue'));
    expect(html.indexOf('New-side pending')).toBeGreaterThan(html.indexOf('aVeryLongReplacementValue'));
    expect(html.indexOf('Range pending')).toBeGreaterThan(html.indexOf('trailing'));
  });

  it('renders a pending new-side suggestion against the diff lines it covers', () => {
    const html = renderToStaticMarkup(<DiffViewer
      files={[file]}
      review={{
        repo: 'owner/repo',
        number: 42,
        canReview: true,
        pendingComments: [
          reviewComment({ body: '```suggestion\nshortValue;\n```' }),
          reviewComment({ body: '```suggestion\nkept\n```', side: 'LEFT' }),
        ],
        onPendingCommentsChange: () => {},
        onDetailChange: () => {},
      }}
    />);

    expect(html.match(/class="md-suggestion"/g)).toHaveLength(1);
    expect(html).toContain('<td class="md-suggestion__number">96</td><td class="md-suggestion__code"><span>−</span>aVeryLongReplacementValueThatMustWrapInsideTheVisibleDiffPane</td>');
    expect(html).toContain('<span>+</span>shortValue;</td>');
    expect(html).toContain('<div class="md-code-block"><span>suggestion</span>');
  });

  it('places existing threads at the matching side and range endpoint with replies and avatars', () => {
    const rightThread = reviewActivity(102, 'New-side discussion');
    rightThread.payload.comments.push({
      ...rightThread.payload.comments[0], githubId: 103, actor: 'carol', actorAvatarUrl: 'https://example.com/carol.png',
      body: 'A reply in this discussion', inReplyToId: 102,
    });
    const html = renderToStaticMarkup(<DiffViewer files={[file]} reviewThreads={reviewThreads([
      reviewActivity(101, 'Old-side discussion', { side: 'LEFT' }),
      rightThread,
      reviewActivity(104, 'Range discussion', { line: 97, startLine: 95 }),
      reviewActivity(105, 'Resolved discussion', { line: 95, resolved: true }),
    ])} />);

    expect(html.match(/<tr class="is-review-thread is-/g)).toHaveLength(4);
    expect(html.match(/<tr class="is-review-thread is-context">/g)).toHaveLength(2);
    expect(html).toContain('<tr class="is-review-thread is-add">');
    expect(html).toContain('<tr class="is-review-thread is-del">');
    expect(html.indexOf('Old-side discussion')).toBeGreaterThan(html.indexOf('old value'));
    expect(html.indexOf('Old-side discussion')).toBeLessThan(html.indexOf('aVeryLongReplacementValue'));
    expect(html.indexOf('New-side discussion')).toBeGreaterThan(html.indexOf('aVeryLongReplacementValue'));
    expect(html.indexOf('Range discussion')).toBeGreaterThan(html.indexOf('trailing'));
    expect(html).toContain('Comment on line <strong>L96</strong>');
    expect(html).toContain('Comment on line <strong>R96</strong>');
    expect(html).toContain('Comment on lines <strong>R95–R97</strong>');
    expect(html).not.toContain('<code>src/Example.java');
    expect(html).toContain('A reply in this discussion');
    expect(html).toContain('src="https://example.com/bob.png"');
    expect(html).toContain('src="https://example.com/carol.png"');
    expect(html).toContain('<details class="lrc is-github pr-thread-row" data-review-closed="true"><summary class="lrc__head">');
    expect(html).toContain('Resolved discussion');
    expect(html).toContain('>Unresolve</button>');
    expect(html).toContain('>Resolve</button>');
    expect(html).not.toContain('is-excerpt');
    expect(html).not.toContain('Review comments outside the visible lines');
  });

  it('keeps outdated, unmapped, and unavailable-patch threads visible below their file', () => {
    const binary = changedFile('assets/logo.png');
    const html = renderToStaticMarkup(<DiffViewer files={[file, binary]} reviewThreads={reviewThreads([
      reviewActivity(101, 'Outdated discussion', { outdated: true, line: null }),
      reviewActivity(102, 'Hidden-line discussion', { line: 20 }),
      reviewActivity(103, 'Unknown-side discussion', { side: null }),
      reviewActivity(104, 'Missing-line discussion', { line: null, originalLine: null }),
      reviewActivity(105, 'Binary-file discussion', { path: binary.filename }),
    ])} />);

    expect(html).not.toContain('<tr class="is-review-thread ');
    expect(html.match(/aria-label="Review comments outside the visible lines"/g)).toHaveLength(2);
    expect(html).toContain('Outdated</span>');
    for (const body of ['Outdated discussion', 'Hidden-line discussion', 'Unknown-side discussion', 'Missing-line discussion', 'Binary-file discussion']) {
      expect(html.split(`<p>${body}</p>`)).toHaveLength(2);
    }
    expect(html.indexOf('Outdated discussion')).toBeGreaterThan(html.indexOf('</table>'));
    expect(html.indexOf('Binary-file discussion')).toBeGreaterThan(html.indexOf('Binary file or diff unavailable.'));
  });

  it('projects agent findings only onto the displayed validated revision', () => {
    const review = reviewFixture();
    const finding = { ...review.findings[0], path: file.filename, startLine: 96, endLine: 96,
      validatedHeadSha: commits[2].sha };
    const projection: NonNullable<DiffViewerProps['agentReview']> = {
      review: { ...review, findings: [finding] }, viewer: null, navigation: null,
      onReviewChange: () => {}, onOpenInReview: () => {},
    };
    const render = (agentReview = projection, displayedCommits = commits) => renderToStaticMarkup(
      <DiffViewer files={[file]} commits={displayedCommits} agentReview={agentReview} />);

    expect(render()).toContain('is-review-finding');
    expect(render(projection, commits.slice(0, 2))).not.toContain('is-review-finding');
    expect(render({ ...projection, headSha: 'different-worktree-head' })).not.toContain('is-review-finding');
    expect(render({ ...projection, headSha: finding.validatedHeadSha }, commits.slice(0, 2)))
      .toContain('is-review-finding');
    expect(render(projection, [])).toContain('is-review-finding');
  });

  it('guards finding navigation and highlighting against a different published revision', () => {
    const review = reviewFixture();
    const finding = { ...review.findings[0], path: file.filename, side: 'RIGHT' as const, startLine: 96, endLine: 96,
      validatedHeadSha: commits[2].sha };
    const navigation = { path: file.filename, side: 'RIGHT' as const, startLine: 96, endLine: 96, findingId: finding.id, token: 1 };
    const projection: NonNullable<DiffViewerProps['agentReview']> = {
      review: { ...review, findings: [finding] }, viewer: null, navigation,
      onReviewChange: () => {}, onOpenInReview: () => {},
    };
    const render = (agentReview = projection, displayedCommits = commits, commitRange: DiffViewerProps['commitRange'] = null) => renderToStaticMarkup(
      <DiffViewer files={[file]} commits={displayedCommits} commitRange={commitRange} agentReview={agentReview} />);

    expect(render()).toContain('is-review-target');
    expect(render()).not.toContain('This finding does not match');
    for (const html of [
      render(projection, commits.slice(0, 2)),
      render(projection, []),
      render(projection, commits, { start: 2, end: 2 }),
      render({ ...projection, navigation: { ...navigation, startLine: 95 } }),
      render({ ...projection, navigation: { ...navigation, findingId: 'missing-finding' } }),
    ]) {
      expect(html).not.toContain('is-review-target');
      expect(html).toContain('This finding does not match the displayed revision');
      expect(html).toContain('Its saved location and discussion remain in Summary.');
      expect(html).toContain('>Open in Summary</button>');
    }

    // A local review still opens its matching historical snapshot, even after the finding became outdated.
    const historical: NonNullable<DiffViewerProps['agentReview']> = { ...projection, headSha: finding.validatedHeadSha,
      review: { ...projection.review, number: null, findings: [{ ...finding, outdated: true }] } };
    expect(render(historical, [])).toContain('is-review-target');
    expect(render(historical, [])).not.toContain('This finding does not match');
  });

  it('retains threads for files outside the diff and excludes unrelated timeline events', () => {
    const outside = reviewActivity(101, 'Discussion on a removed file', { path: 'src/Removed.java' });
    const unrelated = { ...reviewActivity(102, 'Unrelated event'), eventType: 'reviewed' };
    const html = renderToStaticMarkup(<DiffViewer files={[file]} reviewThreads={reviewThreads([outside, unrelated])} />);

    expect(html).toContain('aria-label="Review comments on files outside this diff"');
    expect(html).toContain('Discussion on a removed file');
    expect(html).not.toContain('Unrelated event');
  });

  it('anchors a commit-range thread only while the lines it quotes still read the same', () => {
    const moved = reviewActivity(102, 'Moved discussion', {
      diffHunk: '@@ -95,3 +95,3 @@\n context\n+a line this commit range does not show\n trailing',
    });
    const html = renderToStaticMarkup(<DiffViewer files={[file]} commits={commits} commitRange={{ start: 1, end: 1 }}
      reviewThreads={reviewThreads([reviewActivity(101, 'Anchored discussion'), moved])} />);

    expect(html).toContain('<tr class="is-review-thread ');
    expect(html.indexOf('Anchored discussion')).toBeLessThan(html.indexOf('</table>'));
    expect(html).toContain('aria-label="Review comments on the full pull request"');
    expect(html.indexOf('Moved discussion')).toBeGreaterThan(html.indexOf('</table>'));
  });

  it('reports unavailable review comments without hiding the code', () => {
    const html = renderToStaticMarkup(<DiffViewer files={[file]}
      reviewThreads={{ ...reviewThreads([]), unavailable: true }} />);

    expect(html).toContain('role="alert">Review comments could not be loaded. Refresh the pull request to try again.');
    expect(html).toContain('aVeryLongReplacementValue');
  });

  it('offers a Copilot-style consecutive commit range', () => {
    const now = vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-08-26T10:00:00Z'));
    const html = renderToStaticMarkup(<DiffViewer
      files={[file]}
      commits={commits}
      commitRange={{ start: 1, end: 2 }}
      onCommitRangeChange={() => {}}
    />);
    now.mockRestore();

    expect(html).toContain('aria-label="Choose commits, 2 selected"');
    expect(html).toContain('<span>Commits</span><span>2</span>');
    expect(html).toContain('aria-pressed="false"');
    expect(html).toContain('<span>3 commits</span>');
    expect(html).toContain('role="group" aria-label="Select a consecutive commit range; commits between selected endpoints are included"');
    expect(html).toContain('<code>2222222</code><span>Second</span><time dateTime="2026-06-20T10:00:00Z">2mo ago</time>');
    expect(html.match(/type="checkbox"/g)).toHaveLength(3);
    expect(html.match(/checked=""/g)).toHaveLength(2);
    expect(html).not.toContain('<select');
  });

  it('offers a collapsed message disclosure only for commits with a body', () => {
    const html = renderToStaticMarkup(<DiffViewer
      files={[file]}
      commits={[{ ...commits[0], message: 'First\n\nWhy the first commit exists.' }, commits[1]]}
      onCommitRangeChange={() => {}}
    />);

    expect(html).toContain('<button type="button" class="pull-commit-picker__disclosure" aria-expanded="false" aria-label="Show message">');
    expect(html.match(/pull-commit-picker__disclosure/g)).toHaveLength(1);
    expect(html).toContain('<span aria-hidden="true"></span><code>2222222</code>');
    expect(html).not.toContain('Why the first commit exists.');
  });

  it('targets the new side when available and the old side for deleted lines', () => {
    expect(reviewTarget(95, 95)).toEqual({ side: 'RIGHT', line: 95 });
    expect(reviewTarget(null, 96)).toEqual({ side: 'RIGHT', line: 96 });
    expect(reviewTarget(96, null)).toEqual({ side: 'LEFT', line: 96 });
    expect(reviewTarget(null, null)).toBeNull();
  });

  it('applies the successful inline-comment response without refreshing files', async () => {
    const updated = {} as PullRequestDetailDto;
    const comment: InlineReviewCommentDto = {
      path: 'src/Example.java',
      body: 'Please simplify this',
      line: 18,
      side: 'RIGHT',
      startLine: null,
      startSide: null,
      commitId: 'head-sha',
    };
    const postPrReviewComment = vi.fn(async () => updated);
    const onDetailChange = vi.fn();

    await submitInlineReviewComment(
      { postPrReviewComment }, 'owner/repo', 42, comment, 'comment-box-key', onDetailChange);

    expect(postPrReviewComment).toHaveBeenCalledWith('owner/repo', 42, comment, 'comment-box-key');
    expect(onDetailChange).toHaveBeenCalledWith(updated);
  });
});

describe('pending review comments', () => {
  it('edits only the selected duplicate and preserves its range and commit payload', () => {
    const first = reviewComment({ line: 97, startLine: 95, startSide: 'RIGHT', commitId: 'head-sha' });
    const second = { ...first };
    const comments = [first, second];
    const updated = updatePendingReviewComment(comments, second, '  Updated feedback  ');

    expect(updated).toEqual([first, { ...second, body: 'Updated feedback' }]);
    expect(updated[0]).toBe(first);
    expect(comments[1].body).toBe('Pending feedback');
    expect(updatePendingReviewComment(comments, second, ' \n\t ')).toBe(comments);
  });

  it('discards only the selected comment even when another comment has identical content', () => {
    const first = reviewComment();
    const second = { ...first };

    expect(updatePendingReviewComment([first, second], first, null)).toEqual([second]);
    expect(updatePendingReviewComment([first, second], first, null)[0]).toBe(second);
  });

  it('shows the signed-in avatar and edit/discard controls on the pending card', () => {
    const html = renderToStaticMarkup(<table><tbody><PendingReviewCommentRow
      comment={reviewComment({ body: '**Pending feedback**' })} rowKind="add" viewer={viewer} repository="owner/repo"
      editBody={null} onEdit={() => {}} onSave={() => {}} onDiscard={() => {}}
    /></tbody></table>);

    expect(html).toContain('src="https://example.com/alice.png" alt="alice"');
    expect(html).toContain('<strong>Pending feedback</strong>');
    expect(html).toContain('>Edit</button>');
    expect(html).toContain('>Discard</button>');
    expect(html).not.toContain('<textarea');
  });

  it.each([
    { body: 'Updated feedback', disabled: false },
    { body: ' \n\t ', disabled: true },
  ])('offers cancel and guards saving an editor with body "$body"', ({ body, disabled }) => {
    const html = renderToStaticMarkup(<table><tbody><PendingReviewCommentRow
      comment={reviewComment()} rowKind="add" viewer={viewer} repository="owner/repo"
      editBody={body} onEdit={() => {}} onSave={() => {}} onDiscard={() => {}}
    /></tbody></table>);

    expect(html).toContain('aria-label="Edit pending review comment"');
    expect(html).toContain('>Cancel</button>');
    expect(html).toContain(`<button type="button" class="is-primary"${disabled ? ' disabled=""' : ''}>Save</button>`);
    expect(html).not.toContain('>Edit</button>');
    expect(html).not.toContain('>Discard</button>');
  });
});

describe('filenameTooltip', () => {
  it('shows the filename only when its label is clipped', () => {
    expect(filenameTooltip('QueryManagerConfig.java', 201, 200)).toBe('QueryManagerConfig.java');
    expect(filenameTooltip('QueryManagerConfig.java', 200, 200)).toBeUndefined();
    expect(filenameTooltip('QueryManagerConfig.java', 199, 200)).toBeUndefined();
  });
});

describe('nextCommitRange', () => {
  it('starts a range and expands it through every commit between its endpoints', () => {
    expect(nextCommitRange(null, 2, 5)).toEqual({ start: 2, end: 2 });
    expect(nextCommitRange({ start: 2, end: 2 }, 4, 5)).toEqual({ start: 2, end: 4 });
    expect(nextCommitRange({ start: 2, end: 4 }, 0, 5)).toEqual({ start: 0, end: 4 });
  });

  it('shrinks range endpoints and returns a single selection to all commits', () => {
    expect(nextCommitRange({ start: 1, end: 3 }, 1, 5)).toEqual({ start: 2, end: 3 });
    expect(nextCommitRange({ start: 1, end: 3 }, 3, 5)).toEqual({ start: 1, end: 2 });
    expect(nextCommitRange({ start: 2, end: 2 }, 2, 5)).toBeNull();
  });

  it('trims the nearer edge when an interior commit is deselected', () => {
    expect(nextCommitRange({ start: 0, end: 4 }, 1, 5)).toEqual({ start: 2, end: 4 });
    expect(nextCommitRange({ start: 0, end: 4 }, 3, 5)).toEqual({ start: 0, end: 2 });
  });

  it('ignores an invalid commit', () => {
    const range = { start: 0, end: 3 };

    expect(nextCommitRange(range, 5, 5)).toBe(range);
  });
});

describe('suggestions', () => {
  it('reads head lines from diff rows, falls back to the loaded file, and gives up on unknown lines', () => {
    const hunks = parseUnifiedDiff(file.patch);
    const newLines = Array.from({ length: 98 }, (_, index) => `file line ${index + 1}`);

    expect(headLines(hunks, [], 95, 97)).toEqual(['context', 'aVeryLongReplacementValueThatMustWrapInsideTheVisibleDiffPane', 'trailing']);
    expect(headLines(hunks, newLines, 96, 98)).toEqual(['aVeryLongReplacementValueThatMustWrapInsideTheVisibleDiffPane', 'trailing', 'file line 98']);
    expect(headLines(hunks, [], 97, 98)).toBeNull();
  });
});

describe('the same finding in Changes', () => {
  const PATCH = [
    '@@ -114,4 +114,18 @@ class IcebergSortField',
    '     }',
    ' ',
    '     // sort fields are stored by field id',
    ' ',
    '+    private static int resolveNestedFieldId(Types.StructType struct, List<String> path)',
    '+    {',
    '+        Types.NestedField head = struct.field(path.get(0));',
    '+        if (path.size() == 1) {',
    '+            return head.fieldId();',
    '+        }',
    '+        // fall back to any descendant whose name matches the leaf',
    '+        for (Types.NestedField field : struct.fields()) {',
    '+            Optional<Integer> match = findByLeafName(field, path.get(path.size() - 1));',
    '+            if (match.isPresent()) {',
    '+                return match.get();',
    '+            }',
    '+        }',
    '+        throw new TrinoException(INVALID_TABLE_PROPERTY, "Unknown sort field");',
    '+    }',
  ].join('\n');

  const FILE: DiffFileDto = {
    filename: 'plugin/trino-iceberg/src/main/java/io/trino/plugin/iceberg/IcebergSortField.java',
    status: 'modified',
    additions: 41,
    deletions: 6,
    patch: PATCH,
  };

  function changes(review: AgentReviewDto | null, navigation: { path: string; side: 'LEFT' | 'RIGHT'; startLine: number; endLine: number } | null = null): string {
    return renderToStaticMarkup(
      <DiffViewer
        files={[FILE]}
        agentReview={review === null ? undefined : {
          review,
          viewer,
          headSha: review.runs[0]?.headSha,
          navigation: navigation === null ? null : { ...navigation, findingId: 'finding-1', token: 1 },
          onReviewChange: () => {},
          onOpenInReview: () => {},
        }} />);
  }
  const review = reviewFixture();

  it('mounts the finding card at the end line of its range', () => {
    const html = changes(review);
    expect(html).toContain('is-review-finding');
    expect(html).toContain('data-finding-id="finding-1"');
    // One card, at one line: the range ends at R131.
    expect(html.match(/data-finding-id="finding-1"/g) ?? []).toHaveLength(1);
    expect(html).toContain('data-diff-row="plugin/trino-iceberg/src/main/java/io/trino/plugin/iceberg/IcebergSortField.java:RIGHT:131"');
  });

  it('colors the diff rows by their file language, as the cards beside them do', () => {
    const html = changes(null);
    expect(html).toContain('<td class="is-source"><span>+</span><code class="hljs">                <span class="hljs-keyword">return</span> match.<span class="hljs-title function_ invoke__">get</span>();</code></td>');
  });

  it('offers the same actions and the way back to Summary', () => {
    const html = changes(review);
    expect(html).toContain('Ask reviewer');
    expect(html).toContain('>Resolve</button>');
    expect(html).toContain('Add to my review');
    expect(html).toContain('Post now');
    expect(html).toContain('Open in Summary');
    expect(html).not.toContain('View in Changes');
  });

  it('shows the same private discussion, not a copy', () => {
    const talking = reviewFixture({
      messages: [{
        id: 'm1', findingId: 'finding-1', author: 'USER', body: 'Is the leaf name really ambiguous?',
        targetRevision: 1, turnId: 'turn-2', queuedInputId: null, createdAt: '2026-09-06T16:20:00Z',
      }],
    });
    expect(changes(talking)).toContain('Is the leaf name really ambiguous?');
  });

  it('leaves an outdated finding out of the diff until reassessment, even when its lines map', () => {
    const outdated = reviewFixture({ findings: [{ ...review.findings[0], outdated: true }] });
    expect(changes(outdated)).not.toContain('data-finding-id');
  });

  it('leaves a withdrawn finding out of the diff', () => {
    const withdrawn = reviewFixture({ findings: [{ ...review.findings[0], state: 'WITHDRAWN' }] });
    expect(changes(withdrawn)).not.toContain('data-finding-id');
  });

  it('highlights exactly the navigated range', () => {
    const html = changes(review, { path: FILE.filename, side: 'RIGHT', startLine: 118, endLine: 131 });
    const highlighted = html.match(/is-review-target/g) ?? [];
    expect(highlighted).toHaveLength(14);
    expect(changes(review, null)).not.toContain('is-review-target');
  });

  it('tints the lines an open finding comments on, but not a resolved one', () => {
    expect(changes(review, null).match(/ is-commented/g) ?? []).toHaveLength(14);
    const resolved = reviewFixture({ findings: [{ ...review.findings[0], state: 'RESOLVED' }] });
    expect(changes(resolved, null)).not.toContain('is-commented');
  });

  it('draws nothing of its own when the pull request has no review', () => {
    const html = changes(null);
    expect(html).not.toContain('is-review-finding');
    expect(html).not.toContain('is-review-target');
    expect(html).toContain('resolveNestedFieldId');
  });
});
