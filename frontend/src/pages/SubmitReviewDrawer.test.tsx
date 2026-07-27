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

describe('SubmitReviewDrawer', () => {
  it('renders nothing when closed', () => {
    render(<SubmitReviewDrawer open={false} onClose={() => {}} onSubmit={() => {}} />);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('submits the typed body and selected verdict', () => {
    const onSubmit = vi.fn();
    render(<SubmitReviewDrawer open onClose={() => {}} onSubmit={onSubmit} />);
    fireEvent.change(screen.getByPlaceholderText('Leave a comment on this pull request…'), {
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
    const pending: DiffInlineComment[] = [{
      id: 'c1', filePath: 'src/Foo.java', lineNumber: 42, side: 'RIGHT',
      startLine: null, startSide: null, author: 'you', body: 'Guard against null here.',
      origin: 'local', parentCommentId: null, resolved: false, dismissed: false,
    }];
    render(
      <SubmitReviewDrawer
        open
        onClose={() => {}}
        onSubmit={() => {}}
        pendingComments={pending}
        onRemovePending={onRemovePending}
      />,
    );
    expect(screen.getByText('1 pending')).toBeTruthy();
    expect(screen.getByText('Guard against null here.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Remove comment' }));
    expect(onRemovePending).toHaveBeenCalledWith('c1');
  });

  it('shows the empty-pending hint when there are no drafts', () => {
    render(<SubmitReviewDrawer open onClose={() => {}} onSubmit={() => {}} />);
    expect(screen.getByText(/No pending comments/)).toBeTruthy();
  });

  it('keeps the drawer open and shows a submission error', async () => {
    render(<SubmitReviewDrawer open onClose={() => {}} onSubmit={() => Promise.reject(new Error('GitHub rejected this approval.'))} />);

    fireEvent.click(screen.getByRole('button', { name: 'Submit review' }));

    expect((await screen.findByRole('alert')).textContent).toContain('GitHub rejected this approval.');
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
