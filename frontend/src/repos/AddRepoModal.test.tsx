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
import AddRepoModal from './AddRepoModal';

afterEach(cleanup);

describe('AddRepoModal', () => {
  it('warns about token write permission when direct mode is selected', async () => {
    (window as unknown as { bridge: unknown }).bridge = {
      getManagedClonePlan: vi.fn().mockResolvedValue({
        viewerLogin: 'chenjian2664',
        directAvailable: true,
        forkAvailable: true,
        defaultWriteMode: 'FORK',
        destination: '/managed/bytequay',
      }),
    };

    render(
      <AddRepoModal
        owner="bytequay"
        repo="bytequay"
        onClose={() => {}}
        onStarted={() => {}}
      />,
    );

    await screen.findByText('Write directly');
    expect(screen.queryByRole('alert')).toBeNull();

    fireEvent.click(screen.getByText('Write directly').closest('button') as HTMLButtonElement);

    const warning = screen.getByRole('alert');
    expect(warning.textContent).toContain('configured GitHub token');
    expect(warning.textContent).toContain('bytequay/bytequay');
  });
});
