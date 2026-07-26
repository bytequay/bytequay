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
import type { WorkspaceCreationDto } from './workspace/workspaceApi';
import AddRepoModal from './AddRepoModal';

vi.mock('./repos/AddRepoModal', () => ({
  default: ({ onStarted }: { onStarted: (operation: WorkspaceCreationDto) => void }) => (
    <button
      type="button"
      onClick={() => onStarted({
        id: 'create-1',
        operationKind: 'connect',
        owner: 'bytequay',
        repo: 'bytequay',
        writeMode: 'DIRECT',
        state: 'queued',
        stageMessage: 'Waiting to start',
        progressCurrent: 0,
        progressTotal: 3,
        workspaceId: null,
        clonePath: null,
        previousClonePath: null,
        errorMessage: null,
        attempt: 1,
        createdAt: Date.now(),
        updatedAt: Date.now(),
      })}
    >
      Start setup
    </button>
  ),
}));

afterEach(cleanup);

describe('watch repository modal', () => {
  it('closes the repository picker when workspace setup starts', async () => {
    (window as unknown as { bridge: unknown }).bridge = {
      getUserRepos: vi.fn().mockResolvedValue([{
        owner: 'bytequay',
        name: 'bytequay',
        fullName: 'bytequay/bytequay',
        description: 'Desktop GitHub client',
        language: 'Java',
        stars: 1,
      }]),
    };
    const onAdded = vi.fn();
    const onClose = vi.fn();
    render(
      <AddRepoModal
        watchedRepos={[]}
        onAdded={onAdded}
        onClose={onClose}
      />,
    );

    fireEvent.click(await screen.findByText('Watch…'));
    fireEvent.click(screen.getByRole('button', { name: 'Start setup' }));

    expect(onAdded).toHaveBeenCalledOnce();
    expect(onClose).toHaveBeenCalledOnce();
  });
});
