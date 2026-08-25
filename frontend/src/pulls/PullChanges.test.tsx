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
import type { DiffCommitDto, DiffFileDto, InlineReviewCommentDto, PullRequestDetailDto } from '../types';
import {
  DiffViewer,
  draggedLineSelection,
  nextCommitRange,
  nextLineSelection,
  submitInlineReviewComment,
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
    expect(html).toContain('Drag across line numbers, or Shift-click another line, to select a range.');
    expect(html).toContain('<button type="button">95</button>');
    expect(html).toContain('aVeryLongReplacementValueThatMustWrapInsideTheVisibleDiffPane');
    expect(html.match(/pull-diff-code__line-column/g)).toHaveLength(2);
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
    const first = nextLineSelection(null, 'LEFT', 48, false);

    expect(nextLineSelection(first, 'LEFT', 59, true)).toEqual({ side: 'LEFT', start: 48, end: 59 });
    expect(nextLineSelection(first, 'RIGHT', 60, true)).toEqual({ side: 'RIGHT', start: 60, end: 60 });
  });

  it('keeps extending a drag from anywhere on each diff row', () => {
    const anchor = { side: 'LEFT' as const, line: 48, oldLine: 48, newLine: 48 };

    expect(draggedLineSelection(anchor, 59, null)).toEqual({ side: 'LEFT', start: 48, end: 59 });
    expect(draggedLineSelection({ ...anchor, newLine: null }, null, 60)).toBeNull();
  });

  it('continues a downward drag from context into added lines', () => {
    const anchor = { side: 'LEFT' as const, line: 303, oldLine: 303, newLine: 303 };

    expect(draggedLineSelection(anchor, null, 316)).toEqual({ side: 'RIGHT', start: 303, end: 316 });
    expect(draggedLineSelection({ ...anchor, oldLine: 303, newLine: null }, null, 316)).toBeNull();
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
