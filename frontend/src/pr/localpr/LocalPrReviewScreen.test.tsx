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
import { LocalPrReviewScreen } from './LocalPrReviewScreen';
import type { DiffFileDto } from '../../types';
import type { LocalPRComment } from '../../types/localPr';

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

describe('LocalPrReviewScreen', () => {
  it('renders the file in the tree and the diff rows', () => {
    const { container } = render(
      <LocalPrReviewScreen title="Review · X" files={[FILE]} comments={[]} onBack={() => {}} />,
    );
    // File-tree pane (left) lists the file; diff pane (right) renders the rows.
    expect(screen.getAllByText('Composer.java').length).toBeGreaterThan(0);
    expect(container.querySelectorAll('.diff-row--add').length).toBe(2);
    expect(container.querySelectorAll('.diff-row--del').length).toBe(1);
    expect(screen.getByText('// half-open window')).toBeTruthy();
  });

  it('expands unmodified lines when a task file fetcher is provided', async () => {
    const fetchFileBlob = vi.fn().mockResolvedValue({
      lines: ['new one', 'same two', 'same three', 'same four', 'new five'],
    });
    render(
      <LocalPrReviewScreen
        title="Review"
        files={[{
          filename: 'backend/src/Composer.java', status: 'modified', additions: 2, deletions: 2,
          patch: '@@ -1,1 +1,1 @@\n-old one\n+new one\n@@ -5,1 +5,1 @@\n-old five\n+new five\n',
        }]}
        comments={[]}
        fetchFileBlob={fetchFileBlob}
        onBack={() => {}}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '3 unmodified lines' }));

    await waitFor(() => expect(fetchFileBlob).toHaveBeenCalledWith('backend/src/Composer.java'));
    expect(await screen.findByText('same two')).toBeTruthy();
    expect(screen.getByText('same three')).toBeTruthy();
    expect(screen.getByText('same four')).toBeTruthy();
  });

  it('shows a loading state until files arrive and an empty state for no changes', () => {
    const { rerender } = render(
      <LocalPrReviewScreen title="Review" files={null} comments={[]} onBack={() => {}} />,
    );
    expect(screen.getByText('Loading diff…')).toBeTruthy();
    rerender(<LocalPrReviewScreen title="Review" files={[]} comments={[]} onBack={() => {}} />);
    // Both the file-tree pane and the diff pane surface the empty state.
    expect(screen.getAllByText('No files changed.').length).toBeGreaterThan(0);
  });

  it('renders an existing file-line comment inline in a threaded card', () => {
    const { container } = render(
      <LocalPrReviewScreen title="Review" files={[FILE]} comments={[comment()]} allowLocalComments onBack={() => {}} />,
    );
    expect(screen.getByText(/Split this into a wrapper/)).toBeTruthy();
    expect(container.querySelector('.ic-thread')).not.toBeNull();
  });

  it('opens a composer on a line click and submits a local comment on ⌘↵', () => {
    const onAddComment = vi.fn();
    const { container } = render(
      <LocalPrReviewScreen
        title="Review" files={[FILE]} comments={[]} allowLocalComments
        onAddComment={onAddComment} onBack={() => {}}
      />,
    );
    // The second add line ("new filter") is new-side line 182 and has no
    // thread. Locate it by its new-side gutter (syntax highlighting splits the
    // content into token spans, so match on the line number instead of text).
    const row = Array.from(container.querySelectorAll('.diff-row--add')).find(
      r => (r.querySelectorAll('.diff-row__gutter')[1]?.textContent ?? '').startsWith('182')) as HTMLElement;
    fireEvent.click(row);
    const composer = container.querySelector('.ic-composer') as HTMLTextAreaElement;
    expect(composer).not.toBeNull();
    fireEvent.change(composer, { target: { value: 'please memoize' } });
    fireEvent.keyDown(composer, { key: 'Enter', metaKey: true });
    expect(onAddComment).toHaveBeenCalledWith(
      'backend/src/Composer.java', 'RIGHT', 182, undefined, undefined, 'please memoize',
    );
  });

  it('opens a composer on a removed line and anchors the comment LEFT', () => {
    const onAddComment = vi.fn();
    const { container } = render(
      <LocalPrReviewScreen
        title="Review" files={[FILE]} comments={[]} allowLocalComments
        onAddComment={onAddComment} onBack={() => {}}
      />,
    );
    const row = container.querySelector('.diff-row--del') as HTMLElement;
    fireEvent.click(row);
    const composer = container.querySelector('.ic-composer') as HTMLTextAreaElement;
    expect(composer).not.toBeNull();
    fireEvent.change(composer, { target: { value: 'why remove this?' } });
    fireEvent.keyDown(composer, { key: 'Enter', metaKey: true });
    expect(onAddComment).toHaveBeenCalledWith(
      'backend/src/Composer.java', 'LEFT', 181, undefined, undefined, 'why remove this?',
    );
  });

  it('shift-clicking a second add row extends the composer into a multi-line range', () => {
    const onAddComment = vi.fn();
    const { container } = render(
      <LocalPrReviewScreen
        title="Review" files={[FILE]} comments={[]} allowLocalComments
        onAddComment={onAddComment} onBack={() => {}}
      />,
    );
    const addRows = Array.from(container.querySelectorAll('.diff-row--add')) as HTMLElement[];
    fireEvent.click(addRows[0]);
    fireEvent.click(addRows[1], { shiftKey: true });
    expect(screen.getByText(/Commenting on/)).toBeTruthy();
    const composer = container.querySelector('.ic-composer') as HTMLTextAreaElement;
    fireEvent.change(composer, { target: { value: 'range comment' } });
    fireEvent.keyDown(composer, { key: 'Enter', metaKey: true });
    expect(onAddComment).toHaveBeenCalledWith(
      'backend/src/Composer.java', 'RIGHT', 182, 181, 'RIGHT', 'range comment',
    );
  });

  it('closes the open composer on Esc without adding a comment', () => {
    const onAddComment = vi.fn();
    const { container } = render(
      <LocalPrReviewScreen
        title="Review" files={[FILE]} comments={[]} allowLocalComments
        onAddComment={onAddComment} onBack={() => {}}
      />,
    );
    const row = Array.from(container.querySelectorAll('.diff-row--add')).find(
      r => (r.querySelectorAll('.diff-row__gutter')[1]?.textContent ?? '').startsWith('182')) as HTMLElement;
    fireEvent.click(row);
    const composer = container.querySelector('.ic-composer') as HTMLTextAreaElement;
    expect(composer).not.toBeNull();
    fireEvent.keyDown(composer, { key: 'Escape' });
    expect(container.querySelector('.ic-composer')).toBeNull();
    expect(onAddComment).not.toHaveBeenCalled();
  });

  it('offers no add affordance and opens no composer when comments are read-only', () => {
    const { container } = render(
      <LocalPrReviewScreen title="Review" files={[FILE]} comments={[]} onBack={() => {}} />,
    );
    // allowLocalComments defaults to false — rows carry no comment affordance.
    expect(container.querySelector('.diff-row.has-comment')).toBeNull();
    const row = container.querySelector('.diff-row--add') as HTMLElement;
    fireEvent.click(row);
    expect(container.querySelector('.ic-composer')).toBeNull();
  });

  it('fires onResolve and onDismiss from an existing comment thread', () => {
    const onResolveComment = vi.fn();
    const onDismissComment = vi.fn();
    render(
      <LocalPrReviewScreen
        title="Review" files={[FILE]} comments={[comment()]} allowLocalComments
        onResolveComment={onResolveComment} onDismissComment={onDismissComment} onBack={() => {}}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Resolve' }));
    fireEvent.click(screen.getByRole('button', { name: 'Discard' }));
    expect(onResolveComment).toHaveBeenCalledWith('cm1');
    expect(onDismissComment).toHaveBeenCalledWith('cm1');
  });

  it('does not offer Reopen for a removed local draft', () => {
    render(
      <LocalPrReviewScreen
        title="Review" files={[FILE]} comments={[comment({ dismissedAt: Date.now() })]} allowLocalComments
        onBack={() => {}}
      />,
    );
    expect(screen.queryByRole('button', { name: 'Reopen' })).toBeNull();
  });

  it('fires onBack from the Back button', () => {
    const onBack = vi.fn();
    render(<LocalPrReviewScreen title="Review" files={[FILE]} comments={[]} onBack={onBack} />);
    fireEvent.click(screen.getByRole('button', { name: /Back/ }));
    expect(onBack).toHaveBeenCalled();
  });

  it('embedded keeps the Files, Commits, and Review column tabs by default', () => {
    render(
      <LocalPrReviewScreen
        embedded
        title="Review"
        files={[FILE]}
        comments={[comment()]}
        commits={[{
          id: 'c1', localPrId: 'pr1', sha: 'abc1234', message: 'Ship it',
          authoredAt: Date.now(), pushedAt: null, additions: 1, deletions: 0,
        }]}
        onBack={() => {}}
        onSubmitReview={() => {}}
      />,
    );

    expect(screen.queryByRole('button', { name: /Back/ })).toBeNull();
    expect(screen.getByRole('tab', { name: /Files/ })).toBeTruthy();
    expect(screen.getByRole('tab', { name: /Commits/ })).toBeTruthy();
    fireEvent.click(screen.getByRole('tab', { name: /Review/ }));
    expect(document.querySelector('.review-pending-panel__label')?.textContent).toBe('Pending review');
    expect(screen.getByRole('button', { name: /Open submit panel/ })).toBeTruthy();
  });

  it('embeds without the full-page toolbar or aux tabs', () => {
    render(
      <LocalPrReviewScreen
        embedded
        showAuxTabs={false}
        title="Review"
        files={[FILE]}
        comments={[]}
        commits={[{
          id: 'c1', localPrId: 'pr1', sha: 'abc1234', message: 'Ship it',
          authoredAt: Date.now(), pushedAt: null, additions: 1, deletions: 0,
        }]}
        onBack={() => {}}
      />,
    );

    expect(screen.queryByRole('button', { name: /Back/ })).toBeNull();
    expect(screen.queryByRole('tab', { name: /Commits/ })).toBeNull();
    expect(screen.getByText('Changed files')).toBeTruthy();
  });
});
