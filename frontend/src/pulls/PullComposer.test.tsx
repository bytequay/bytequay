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
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
});
