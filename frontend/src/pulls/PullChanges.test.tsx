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
import type { DiffCommitDto, DiffFileDto, InlineReviewCommentDto, PullRequestDetailDto, PullRequestDto, PullTimelinePayloadDto, UserProfileDto } from '../types';
import {
  applyMarkdownCommand,
  buildFileTree,
  DiffViewer,
  draggedLineSelection,
  filenameTooltip,
  nextCommitRange,
  nextLineSelection,
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
  return {
    actor: 'bob', eventType: 'review_thread', timestamp: '2026-08-25T10:00:00Z', body: null, githubId: id,
    payload: {
      actorAvatarUrl: null, actorBot: false, state: null, htmlUrl: null, label: null, labelColor: null,
      renameFrom: null, renameTo: null, commitSha: null, commitMessage: null, sourceTitle: null,
      sourceUrl: null, sourceNumber: null, milestoneTitle: null, requestedReviewer: null,
      path: file.filename, diffHunk: file.patch, line: 96, originalLine: 96, startLine: null,
      originalStartLine: null, side: 'RIGHT', resolved: false, outdated: false, reactions: [],
      comments: [{
        githubId: id, actor: 'bob', actorAvatarUrl: 'https://example.com/bob.png', actorBot: false,
        timestamp: '2026-08-25T10:00:00Z', body, htmlUrl: null, reviewId: 9, inReplyToId: null, reactions: [],
      }],
      ...payload,
    },
  };
}

function reviewThreads(activities: PullRequestDetailDto['recentActivity']): NonNullable<DiffViewerProps['reviewThreads']> {
  const pr: PullRequestDto = {
    id: 1, repo: 'owner/repo', number: 42, title: 'Example pull request', author: 'alice',
    htmlUrl: 'https://github.com/owner/repo/pull/42', createdAt: '2026-08-25T09:00:00Z',
    updatedAt: '2026-08-25T10:00:00Z', origin: 'AUTHORED', labels: [], labelColors: {}, draft: false,
    viewedAt: null, reviewedAt: null, handledAction: null, requestedReviewers: [], ciStatus: 'PASSING',
    additions: 1, deletions: 1, commentCount: activities.length, attentionReason: null, state: 'open',
    mergeable: true, mergeableState: 'clean', headPushedAt: null, headRef: 'feature/review',
  };
  return { activities, pr, unavailable: false, onRefresh: async () => {}, onDetailUpdate: () => {} };
}

const viewer: UserProfileDto = {
  login: 'alice', name: 'Alice', avatarUrl: 'https://example.com/alice.png', htmlUrl: 'https://github.com/alice',
  publicRepos: 1, followers: 1, following: 1, bio: null, location: null, company: null, email: null,
};

describe('DiffViewer', () => {
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

    expect(html.match(/<details class="pull-diff-pending" open="">/g)).toHaveLength(3);
    expect(html).toContain('Comment on line <strong>L96</strong>');
    expect(html).toContain('Comment on line <strong>R96</strong>');
    expect(html).toContain('Comment on lines <strong>R95–R97</strong>');
    expect(html).not.toContain('Other-file pending');
    expect(html.indexOf('Old-side pending')).toBeGreaterThan(html.indexOf('old value'));
    expect(html.indexOf('Old-side pending')).toBeLessThan(html.indexOf('aVeryLongReplacementValue'));
    expect(html.indexOf('New-side pending')).toBeGreaterThan(html.indexOf('aVeryLongReplacementValue'));
    expect(html.indexOf('Range pending')).toBeGreaterThan(html.indexOf('trailing'));
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

    expect(html.match(/<tr class="is-review-thread">/g)).toHaveLength(4);
    expect(html.indexOf('Old-side discussion')).toBeGreaterThan(html.indexOf('old value'));
    expect(html.indexOf('Old-side discussion')).toBeLessThan(html.indexOf('aVeryLongReplacementValue'));
    expect(html.indexOf('New-side discussion')).toBeGreaterThan(html.indexOf('aVeryLongReplacementValue'));
    expect(html.indexOf('Range discussion')).toBeGreaterThan(html.indexOf('trailing'));
    expect(html).toContain('<code>src/Example.java:L96</code>');
    expect(html).toContain('<code>src/Example.java:R96</code>');
    expect(html).toContain('<code>src/Example.java:R95–R97</code>');
    expect(html).toContain('A reply in this discussion');
    expect(html).toContain('src="https://example.com/bob.png"');
    expect(html).toContain('src="https://example.com/carol.png"');
    expect(html).toContain('<details class="pr-thread-row"><summary>');
    expect(html).toContain('Resolved discussion');
    expect(html).toContain('>Unresolve</button>');
    expect(html).toContain('>Resolve</button>');
    expect(html).not.toContain('pr-commented-code-line');
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

    expect(html).not.toContain('<tr class="is-review-thread">');
    expect(html.match(/aria-label="Review comments outside the visible lines"/g)).toHaveLength(2);
    expect(html).toContain('Outdated</span>');
    for (const body of ['Outdated discussion', 'Hidden-line discussion', 'Unknown-side discussion', 'Missing-line discussion', 'Binary-file discussion']) {
      expect(html.split(body)).toHaveLength(2);
    }
    expect(html.indexOf('Outdated discussion')).toBeGreaterThan(html.indexOf('</table>'));
    expect(html.indexOf('Binary-file discussion')).toBeGreaterThan(html.indexOf('Binary file or diff unavailable.'));
  });

  it('retains threads for files outside the diff and excludes unrelated timeline events', () => {
    const outside = reviewActivity(101, 'Discussion on a removed file', { path: 'src/Removed.java' });
    const unrelated = { ...reviewActivity(102, 'Unrelated event'), eventType: 'reviewed' };
    const html = renderToStaticMarkup(<DiffViewer files={[file]} reviewThreads={reviewThreads([outside, unrelated])} />);

    expect(html).toContain('aria-label="Review comments on files outside this diff"');
    expect(html).toContain('Discussion on a removed file');
    expect(html).not.toContain('Unrelated event');
  });

  it('shows full-PR threads separately when a selected commit range changes line positions', () => {
    const html = renderToStaticMarkup(<DiffViewer files={[file]} commits={commits} commitRange={{ start: 1, end: 1 }}
      reviewThreads={reviewThreads([reviewActivity(101, 'Full-PR discussion')])} />);

    expect(html).not.toContain('<tr class="is-review-thread">');
    expect(html).toContain('aria-label="Review comments on the full pull request"');
    expect(html).toContain('Full-PR discussion');
    expect(html.indexOf('Full-PR discussion')).toBeGreaterThan(html.indexOf('</table>'));
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

  it('selects a consecutive range on one side of the diff', () => {
    const first = nextLineSelection(null, 'LEFT', 48, false, 2);

    expect(nextLineSelection(first, 'LEFT', 59, true, 4)).toEqual({ side: 'LEFT', start: 48, end: 59, rowStart: 2, rowEnd: 4 });
    expect(nextLineSelection(first, 'RIGHT', 60, true, 5)).toEqual({ side: 'RIGHT', start: 60, end: 60, rowStart: 5, rowEnd: 5 });
  });

  it('targets the new side when available and the old side for deleted lines', () => {
    expect(reviewTarget(95, 95)).toEqual({ side: 'RIGHT', line: 95 });
    expect(reviewTarget(null, 96)).toEqual({ side: 'RIGHT', line: 96 });
    expect(reviewTarget(96, null)).toEqual({ side: 'LEFT', line: 96 });
    expect(reviewTarget(null, null)).toBeNull();
  });

  it('keeps extending a drag from anywhere on each diff row', () => {
    const anchor = { side: 'LEFT' as const, line: 48, oldLine: 48, newLine: 48, row: 2 };

    expect(draggedLineSelection(anchor, 59, null, 4)).toEqual({ side: 'LEFT', start: 48, end: 59, rowStart: 2, rowEnd: 4 });
    expect(draggedLineSelection({ ...anchor, newLine: null }, null, 60, 5)).toBeNull();
  });

  it('continues a downward drag from context into added lines', () => {
    const anchor = { side: 'LEFT' as const, line: 303, oldLine: 303, newLine: 303, row: 6 };

    expect(draggedLineSelection(anchor, null, 316, 9)).toEqual({ side: 'RIGHT', start: 303, end: 316, rowStart: 6, rowEnd: 9 });
    expect(draggedLineSelection({ ...anchor, oldLine: 303, newLine: null }, null, 316, 9)).toBeNull();
  });

  it('keeps every visible row between same-side drag endpoints selected', () => {
    const anchor = { side: 'RIGHT' as const, line: 144, oldLine: null as number | null, newLine: 144, row: 0 };

    expect(draggedLineSelection(anchor, 144, null, 3)).toBeNull();
    expect(draggedLineSelection(anchor, null, 147, 4)).toEqual({
      side: 'RIGHT', start: 144, end: 147, rowStart: 0, rowEnd: 4,
    });
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
      { postPrReviewComment }, 'owner/repo', 42, comment, onDetailChange);

    expect(postPrReviewComment).toHaveBeenCalledWith('owner/repo', 42, comment);
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
    expect(html).toContain(`<button type="submit" class="is-primary"${disabled ? ' disabled=""' : ''}>Save</button>`);
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

describe('buildFileTree', () => {
  it('compacts directory chains without crossing branches or direct files', () => {
    const tree = buildFileTree([
      changedFile('core/trino-main/src/main/java/io/trino/connector/Connector.java'),
      changedFile('core/trino-main/src/main/java/io/trino/connector/system/SystemPageSource.java'),
      changedFile('core/trino-main/src/main/java/io/trino/operator/TableScanOperator.java'),
      changedFile('core/trino-main/src/test/java/io/trino/execution/TestPageSource.java'),
      changedFile('core/trino-spi/pom.xml'),
      changedFile('LICENSE'),
      changedFile('legacy', 'removed'),
      changedFile('legacy/new.txt', 'added'),
    ]);

    expect(tree.map(item => item.name)).toEqual(['core', 'LICENSE', 'legacy', 'legacy']);
    const core = tree[0];
    if (core.kind !== 'directory') throw new Error('core should be a directory');
    expect(core.children.map(item => item.name)).toEqual(['trino-main/src', 'trino-spi']);
    const trinoMain = core.children[0];
    if (trinoMain.kind !== 'directory') throw new Error('trino-main/src should be a directory');
    expect(trinoMain.children.map(item => item.name)).toEqual(['main/java/io/trino', 'test/java/io/trino/execution']);
    const main = trinoMain.children[0];
    if (main.kind !== 'directory') throw new Error('main/java/io/trino should be a directory');
    expect(main.children.map(item => item.name)).toEqual(['connector', 'operator']);
    const connector = main.children[0];
    if (connector.kind !== 'directory') throw new Error('connector should be a directory');
    expect(connector.children.map(item => item.name)).toEqual(['Connector.java', 'system']);
    expect(tree.filter(item => item.path === 'legacy').map(item => item.kind)).toEqual(['file', 'directory']);
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

describe('applyMarkdownCommand', () => {
  it('wraps the selection and keeps it selected', () => {
    expect(applyMarkdownCommand('a bold word', 2, 6, 'bold'))
      .toEqual({ value: 'a **bold** word', start: 4, end: 8 });
  });

  it('parks the caret between the markers when nothing is selected', () => {
    expect(applyMarkdownCommand('', 0, 0, 'italic')).toEqual({ value: '__', start: 1, end: 1 });
  });

  it('fences a multi-line code selection', () => {
    expect(applyMarkdownCommand('one\ntwo', 0, 7, 'code'))
      .toEqual({ value: '```\none\ntwo\n```', start: 4, end: 11 });
  });

  it('selects the placeholder of an inserted link', () => {
    expect(applyMarkdownCommand('see docs', 4, 8, 'link'))
      .toEqual({ value: 'see [docs](url)', start: 11, end: 14 });
  });

  it('prefixes every line the selection touches', () => {
    expect(applyMarkdownCommand('one\ntwo\nthree', 5, 5, 'quote'))
      .toEqual({ value: 'one\n> two\nthree', start: 4, end: 9 });
    expect(applyMarkdownCommand('one\ntwo', 0, 7, 'number'))
      .toEqual({ value: '1. one\n2. two', start: 0, end: 13 });
  });
});
