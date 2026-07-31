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
import { SubmitReviewDrawer } from './SubmitReviewDrawer';
import type { DiffInlineComment } from '../diff/DiffInlineComments';

afterEach(cleanup);

const pendingComment: DiffInlineComment = {
  id: 'c1', filePath: 'src/Foo.java', lineNumber: 42, side: 'RIGHT',
  startLine: null, startSide: null, author: 'you', body: 'Guard against null here.',
  origin: 'local', parentCommentId: null, resolved: false, dismissed: false,
};

describe('SubmitReviewDrawer', () => {
  it('renders nothing when closed', () => {
    render(<SubmitReviewDrawer open={false} onClose={() => {}} onSubmit={() => {}} />);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('submits the typed body and selected verdict', () => {
    const onSubmit = vi.fn();
    render(<SubmitReviewDrawer open onClose={() => {}} onSubmit={onSubmit} />);
    fireEvent.change(screen.getByPlaceholderText('Leave an overall comment on this pull request…'), {
      target: { value: 'Looks good, one nit.' },
    });
    fireEvent.click(screen.getByRole('radio', { name: /Request changes/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Submit review' }));
    expect(onSubmit).toHaveBeenCalledWith('Looks good, one nit.', 'REQUEST_CHANGES');
  });

  it('Cancel closes without submitting', () => {
    const onSubmit = vi.fn();
    const onClose = vi.fn();
    render(<SubmitReviewDrawer open onClose={onClose} onSubmit={onSubmit} />);
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalledOnce();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('Escape closes the drawer', () => {
    const onClose = vi.fn();
    render(<SubmitReviewDrawer open onClose={onClose} onSubmit={() => {}} />);
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('lists pending comments and removes one on click', () => {
    const onRemovePending = vi.fn();
    render(
      <SubmitReviewDrawer
        open
        onClose={() => {}}
        onSubmit={() => {}}
        pendingComments={[pendingComment]}
        onRemovePending={onRemovePending}
      />,
    );
    expect(screen.getByRole('button', { name: /Pending comments/ })).toBeTruthy();
    expect(screen.getByText('Guard against null here.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Remove comment' }));
    expect(onRemovePending).toHaveBeenCalledWith('c1');
  });

  it('collapses and reopens the pending list', () => {
    render(
      <SubmitReviewDrawer open onClose={() => {}} onSubmit={() => {}} pendingComments={[pendingComment]} />,
    );
    const toggle = screen.getByRole('button', { name: /Pending comments/ });
    fireEvent.click(toggle);
    expect(screen.queryByText('Guard against null here.')).toBeNull();
    fireEvent.click(toggle);
    expect(screen.getByText('Guard against null here.')).toBeTruthy();
  });

  it('jumps to a pending comment location', () => {
    const onJumpToComment = vi.fn();
    render(
      <SubmitReviewDrawer
        open
        onClose={() => {}}
        onSubmit={() => {}}
        pendingComments={[pendingComment]}
        onJumpToComment={onJumpToComment}
      />,
    );
    fireEvent.click(screen.getByText('Guard against null here.'));
    expect(onJumpToComment).toHaveBeenCalledWith(pendingComment);
  });

  it('offers Discard review only when discarding is wired up', () => {
    const onDiscard = vi.fn();
    render(<SubmitReviewDrawer open onClose={() => {}} onSubmit={() => {}} />);
    expect(screen.queryByRole('button', { name: 'Discard review' })).toBeNull();
    cleanup();

    render(<SubmitReviewDrawer open onClose={() => {}} onSubmit={() => {}} onDiscard={onDiscard} />);
    fireEvent.click(screen.getByRole('button', { name: 'Discard review' }));
    expect(onDiscard).toHaveBeenCalledOnce();
  });

  it('submits on Cmd+Enter from the composer', () => {
    const onSubmit = vi.fn();
    render(<SubmitReviewDrawer open onClose={() => {}} onSubmit={onSubmit} />);
    const composer = screen.getByPlaceholderText('Leave an overall comment on this pull request…');
    fireEvent.change(composer, { target: { value: 'Ship it.' } });
    fireEvent.keyDown(composer, { key: 'Enter', metaKey: true });
    expect(onSubmit).toHaveBeenCalledWith('Ship it.', 'COMMENT');
  });

  it('shows the empty-pending hint when there are no drafts', () => {
    render(<SubmitReviewDrawer open onClose={() => {}} onSubmit={() => {}} />);
    expect(screen.getByText(/No pending inline comments/)).toBeTruthy();
  });

  it('keeps the drawer open and shows a submission error', async () => {
    render(<SubmitReviewDrawer open onClose={() => {}} onSubmit={() => Promise.reject(new Error('GitHub rejected this approval.'))} />);

    fireEvent.click(screen.getByRole('button', { name: 'Submit review' }));

    expect((await screen.findByRole('alert')).textContent).toContain('GitHub rejected this approval.');
  });

  it('offers to watch the repository when that is why the review was refused', async () => {
    const onWatchRepo = vi.fn();
    const refusal = new Error(
      'ByteQuay must watch trinodb/trino before publishing to its pull requests');
    render(
      <SubmitReviewDrawer
        open
        onClose={() => {}}
        onSubmit={() => Promise.reject(refusal)}
        onWatchRepo={onWatchRepo}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Submit review' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain("ByteQuay isn't watching");
    expect(alert.textContent).toContain('trinodb/trino');
    fireEvent.click(screen.getByRole('button', { name: 'Watch trinodb/trino' }));
    expect(onWatchRepo).toHaveBeenCalledOnce();
  });

  it('shows the refusal without a watch button when watching is not wired up', async () => {
    const refusal = new Error(
      'ByteQuay must watch trinodb/trino before publishing to its pull requests');
    render(<SubmitReviewDrawer open onClose={() => {}} onSubmit={() => Promise.reject(refusal)} />);

    fireEvent.click(screen.getByRole('button', { name: 'Submit review' }));

    expect((await screen.findByRole('alert')).textContent).toContain("isn't watching");
    expect(screen.queryByRole('button', { name: /^Watch / })).toBeNull();
  });

  it('removes Electron IPC noise from a submission error', async () => {
    const message = "Error invoking remote method 'pr:publishReview': Error: "
      + 'The configured GitHub token cannot perform this action.';
    render(<SubmitReviewDrawer open onClose={() => {}} onSubmit={() => Promise.reject(new Error(message))} />);

    fireEvent.click(screen.getByRole('button', { name: 'Submit review' }));

    expect((await screen.findByRole('alert')).textContent)
      .toBe('The configured GitHub token cannot perform this action.');
  });
});
