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
import { DiffViewer, draggedLineSelection, nextLineSelection, submitInlineReviewComment } from './PullChanges';

const commits: DiffCommitDto[] = [
  { sha: '1111111', parentSha: '0000000', message: 'First', author: 'alice', authoredAt: null },
  { sha: '2222222', parentSha: '1111111', message: 'Second', author: 'bob', authoredAt: null },
  { sha: '3333333', parentSha: '2222222', message: 'Third', author: 'carol', authoredAt: null },
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

  it('offers all commits or a consecutive commit range', () => {
    const html = renderToStaticMarkup(<DiffViewer
      files={[file]}
      commits={commits}
      commitRange={{ start: 1, end: 2 }}
      onCommitRangeChange={() => {}}
    />);

    expect(html).toContain('Commits: 2222222…3333333');
    expect(html).toContain('All commits');
    expect(html).toContain('Commit ranges are consecutive.');
    expect(html).toContain('<option value="0" disabled="">1. First</option>');
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
