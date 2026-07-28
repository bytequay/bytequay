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
import { afterEach, expect, it, vi } from 'vitest';
import { StageRecoveryPrompt } from './StageRecoveryPrompt';

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(window, 'bridge');
});

it('sends the projected CI episode instead of inferring a latest failure', async () => {
  const recoverV2Ci = vi.fn().mockResolvedValue({});
  (window as unknown as { bridge: unknown }).bridge = { recoverV2Ci };
  const complete = vi.fn();

  render(<StageRecoveryPrompt
    taskId="task-exact"
    recovery={{
      ci: {
        episodeId: 'episode-exact',
        rerunCount: 1,
        rerunLimit: 1,
        fixAttemptCount: 2,
        fixAttemptLimit: 2,
        pushCount: 1,
        pushLimit: 1,
        actions: ['EXTEND_BUDGET', 'CONTINUE_WITH_PER_PUSH_APPROVAL'],
      },
      cleanup: null,
    }}
    onComplete={complete}
    onError={() => {}}
  />);

  fireEvent.click(screen.getByRole('button', { name: 'Extend budget' }));
  await waitFor(() => expect(recoverV2Ci).toHaveBeenCalledWith(
    'task-exact',
    'episode-exact',
    expect.objectContaining({
      action: 'EXTEND_BUDGET',
      rerunDelta: 1,
      fixDelta: 1,
      pushDelta: 1,
    }),
  ));
  expect(complete).toHaveBeenCalledOnce();
});

it('offers only the exact Cleanup actions projected by the owner', async () => {
  const recoverV2Cleanup = vi.fn().mockResolvedValue({});
  (window as unknown as { bridge: unknown }).bridge = { recoverV2Cleanup };

  render(<StageRecoveryPrompt
    taskId="task-exact"
    recovery={{
      ci: null,
      cleanup: {
        stepId: 'cleanup-step-exact',
        kind: 'DELETE_REMOTE_BRANCH',
        requirement: 'OPTIONAL',
        attemptCount: 3,
        attemptLimit: 3,
        error: 'GitHub was unavailable',
        actions: ['WAIVE_OPTIONAL'],
      },
    }}
    onComplete={() => {}}
    onError={() => {}}
  />);

  expect(screen.queryByRole('button', { name: 'Retry cleanup step' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Waive optional step' }));
  await waitFor(() => expect(recoverV2Cleanup).toHaveBeenCalledWith(
    'task-exact',
    'cleanup-step-exact',
    expect.objectContaining({ action: 'WAIVE_OPTIONAL' }),
  ));
});
