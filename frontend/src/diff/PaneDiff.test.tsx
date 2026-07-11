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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PaneDiff } from './PaneDiff';
import type { DiffFileDto } from '../types';
import type { LocalPRComment } from '../types/localPr';

afterEach(cleanup);

const FILE: DiffFileDto = {
  filename: 'backend/src/Composer.java',
  status: 'modified',
  additions: 2,
  deletions: 1,
  patch: '@@ -180,3 +180,4 @@\n context line\n-old filter\n+// half-open window\n+new filter\n',
};

function comment(over: Partial<LocalPRComment> = {}): LocalPRComment {
  return {
    id: 'cm1', localPrId: 'pr1', origin: 'local', scope: 'file-line',
    filePath: 'backend/src/Composer.java', lineNumber: 181, side: 'RIGHT', startLine: null, startSide: null,
    author: 'you',
    body: 'Split this into a wrapper + memoized inner component.',
    createdAt: Date.now(), resolvedAt: null, dismissedAt: null, strippedOnPushAt: null, parentCommentId: null,
    publishedAt: null, ...over,
  };
}

describe('PaneDiff', () => {
  it('renders the file header with ± counts and parsed add/del lines', () => {
    const { container } = render(<PaneDiff files={[FILE]} />);
    expect(screen.getByText('Composer.java')).toBeTruthy();
    expect(screen.getByText('+2')).toBeTruthy();
    expect(screen.getByText('−1')).toBeTruthy();
    expect(container.querySelectorAll('.diff-line.add').length).toBe(2);
    expect(container.querySelectorAll('.diff-line.del').length).toBe(1);
    expect(screen.getByText('// half-open window')).toBeTruthy();
  });

  it('drops file-header lines and keeps a hunk marker', () => {
    const { container } = render(<PaneDiff files={[{
      ...FILE,
      patch: 'diff --git a/x b/x\nindex 1..2\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n',
    }]}
    />);
    // No row for the diff/index/+++/--- header lines; one hunk + one del + one add.
    expect(container.querySelectorAll('.diff-line.hunk').length).toBe(1);
    expect(container.querySelectorAll('.diff-line.add').length).toBe(1);
    expect(container.querySelectorAll('.diff-line.del').length).toBe(1);
  });

  it('shows no comment anchors when local comments are disabled', () => {
    const { container } = render(<PaneDiff files={[FILE]} />);
    expect(container.querySelectorAll('.comment-anchor').length).toBe(0);
  });

  it('renders comment anchors on every line, including deletions, when allowLocalComments is on', () => {
    const { container } = render(<PaneDiff files={[FILE]} allowLocalComments />);
    // 1 context + 2 adds + 1 del.
    expect(container.querySelectorAll('.comment-anchor').length).toBe(4);
  });

  it('renders an existing file-line comment inline in a threaded card', () => {
    const { container } = render(<PaneDiff files={[FILE]} comments={[comment()]} />);
    const thread = container.querySelector('.ic-thread');
    expect(thread).not.toBeNull();
    expect(screen.getByText(/Split this into a wrapper/)).toBeTruthy();
    // The line carrying the thread flags has-comment (orange ⚠ anchor).
    expect(container.querySelector('.diff-line.has-comment')).not.toBeNull();
  });

  it('renders a range label on a persisted multi-line comment', () => {
    render(<PaneDiff files={[FILE]} comments={[comment({ startLine: 180, startSide: 'RIGHT' })]} />);
    expect(screen.getByText(/R180 to R181/)).toBeTruthy();
  });

  it('renders a remote-origin comment with the ext avatar tint', () => {
    const { container } = render(<PaneDiff files={[FILE]} comments={[comment({ origin: 'remote', author: '@octocat' })]} />);
    expect(container.querySelector('.ic-comment__avatar--ext')).not.toBeNull();
    expect(container.querySelector('.ic-comment__avatar--you')).toBeNull();
  });

  it('opens a composer on anchor click and submits a local comment on ⌘↵', () => {
    const onAddComment = vi.fn();
    const { container } = render(
      <PaneDiff files={[FILE]} allowLocalComments onAddComment={onAddComment} />,
    );
    // The first add line is new-side line 181.
    const addLine = Array.from(container.querySelectorAll('.diff-line.add'))
      .find(l => l.querySelector('.ln')?.textContent === '181') as HTMLElement;
    fireEvent.click(addLine.querySelector('.comment-anchor') as Element);
    const composer = container.querySelector('.ic-composer') as HTMLTextAreaElement;
    expect(composer).not.toBeNull();
    fireEvent.change(composer, { target: { value: 'please memoize' } });
    fireEvent.keyDown(composer, { key: 'Enter', metaKey: true });
    expect(onAddComment).toHaveBeenCalledWith(
      'backend/src/Composer.java', 'RIGHT', 181, undefined, undefined, 'please memoize',
    );
  });

  it('opens a composer on a removed line and anchors the comment LEFT', () => {
    const onAddComment = vi.fn();
    const { container } = render(
      <PaneDiff files={[FILE]} allowLocalComments onAddComment={onAddComment} />,
    );
    const delLine = container.querySelector('.diff-line.del') as HTMLElement;
    fireEvent.click(delLine.querySelector('.comment-anchor') as Element);
    const composer = container.querySelector('.ic-composer') as HTMLTextAreaElement;
    expect(composer).not.toBeNull();
    fireEvent.change(composer, { target: { value: 'why remove this?' } });
    fireEvent.keyDown(composer, { key: 'Enter', metaKey: true });
    expect(onAddComment).toHaveBeenCalledWith(
      'backend/src/Composer.java', 'LEFT', 181, undefined, undefined, 'why remove this?',
    );
  });

  it('shift-clicking a second line on the same side extends the composer into a range', () => {
    const onAddComment = vi.fn();
    const { container } = render(
      <PaneDiff files={[FILE]} allowLocalComments onAddComment={onAddComment} />,
    );
    const addLines = Array.from(container.querySelectorAll('.diff-line.add')) as HTMLElement[];
    fireEvent.click(addLines[0].querySelector('.comment-anchor') as Element);
    fireEvent.click(addLines[1].querySelector('.comment-anchor') as Element, { shiftKey: true });
    expect(screen.getByText(/Commenting on/)).toBeTruthy();
    const composer = container.querySelector('.ic-composer') as HTMLTextAreaElement;
    fireEvent.change(composer, { target: { value: 'range comment' } });
    fireEvent.keyDown(composer, { key: 'Enter', metaKey: true });
    expect(onAddComment).toHaveBeenCalledWith(
      'backend/src/Composer.java', 'RIGHT', 182, 181, 'RIGHT', 'range comment',
    );
  });

  it('fires onResolveComment from an open thread', () => {
    const onResolveComment = vi.fn();
    render(
      <PaneDiff files={[FILE]} allowLocalComments comments={[comment()]} onResolveComment={onResolveComment} />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Resolve' }));
    expect(onResolveComment).toHaveBeenCalledWith('cm1');
  });

  it('fires onDismissComment from an open thread', () => {
    const onDismissComment = vi.fn();
    render(
      <PaneDiff files={[FILE]} allowLocalComments comments={[comment()]} onDismissComment={onDismissComment} />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Discard' }));
    expect(onDismissComment).toHaveBeenCalledWith('cm1');
  });

  it('submits a reply through the existing file-line thread', () => {
    const onReplyComment = vi.fn();
    const { container } = render(
      <PaneDiff files={[FILE]} allowLocalComments comments={[comment()]} onReplyComment={onReplyComment} />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Reply' }));
    const composer = container.querySelector('.ic-composer') as HTMLTextAreaElement;
    expect(composer).not.toBeNull();
    fireEvent.change(composer, { target: { value: 'done in the next commit' } });
    fireEvent.keyDown(composer, { key: 'Enter', metaKey: true });
    expect(onReplyComment).toHaveBeenCalledWith(
      'cm1',
      'backend/src/Composer.java',
      'RIGHT',
      181,
      undefined,
      undefined,
      'done in the next commit',
    );
  });

  it('hides actions and shows the dismissed badge once dismissed', () => {
    render(<PaneDiff files={[FILE]} allowLocalComments comments={[comment({ dismissedAt: Date.now() })]} />);
    expect(screen.getByText('dismissed')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Resolve' })).toBeNull();
  });
});
