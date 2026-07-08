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
import { SubmitReviewDrawer } from './SubmitReviewDrawer';

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
});
