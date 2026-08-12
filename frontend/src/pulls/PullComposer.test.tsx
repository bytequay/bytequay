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
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import PullComposer from './PullComposer';

afterEach(cleanup);

describe('PullComposer', () => {
  it('closes only through the explicit confirmed callback', async () => {
    const onClose = vi.fn(async () => {});
    render(<PullComposer repoCtx={{ owner: 'acme', repo: 'widget' }} onClose={onClose} />);
    fireEvent.click(screen.getByRole('button', { name: 'Close pull request' }));
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Close pull request' }));
    await waitFor(() => expect(onClose).toHaveBeenCalledOnce());
  });

  it('keeps the confirmation up until the host reports the close landed', async () => {
    // Mirrors PullDetailPane: the request marks the close pending before it
    // resolves, and the host clears it only once the PR reads closed.
    let settle = () => {};
    function Host() {
      const [pending, setPending] = useState(false);
      settle = () => setPending(false);
      return (
        <PullComposer
          repoCtx={{ owner: 'acme', repo: 'widget' }}
          closePending={pending}
          onClose={async () => { setPending(true); }}
        />
      );
    }
    render(<Host />);

    fireEvent.click(screen.getByRole('button', { name: 'Close pull request' }));
    fireEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: 'Close pull request' }),
    );

    await waitFor(() => expect(
      within(screen.getByRole('dialog')).getByRole('button', { name: 'Closing…' }),
    ).toBeTruthy());

    act(() => settle());
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });
});
