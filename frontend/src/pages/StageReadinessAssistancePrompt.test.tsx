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
import { StageReadinessAssistancePrompt } from './StageReadinessAssistancePrompt';

const exact = {
  taskEpoch: 7,
  stageId: 'remote-stage-exact',
  stageGeneration: 3,
  snapshotId: 'snapshot-exact',
  readinessId: 'readiness-exact',
  policyId: 'policy-exact',
  headSha: 'head-exact',
  baseSha: 'base-exact',
  viewerLogin: 'author',
  actions: ['POST_MAINTAINER_NUDGE', 'REQUEST_REVIEWER'] as const,
};

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(window, 'bridge');
});

it('does not render or authorize when the backend projects no exact authority', async () => {
  const authorize = vi.fn();
  (window as unknown as { bridge: unknown }).bridge = {
    getV2ReadinessAssistance: vi.fn().mockResolvedValue(null),
    authorizeV2ReadinessAssistance: authorize,
  };

  render(<StageReadinessAssistancePrompt taskId="task-1" stageId="stage-1"
    onComplete={() => {}} onError={() => {}} />);

  await waitFor(() => expect(
    screen.queryByTestId('readiness-assistance-card')).toBeNull());
  expect(authorize).not.toHaveBeenCalled();
});

it('requires a second explicit click and sends every projected owner fence', async () => {
  const authorize = vi.fn().mockResolvedValue({
    actionId: 'assistance-1', status: 'REQUESTED',
  });
  (window as unknown as { bridge: unknown }).bridge = {
    getV2ReadinessAssistance: vi.fn().mockResolvedValue(exact),
    authorizeV2ReadinessAssistance: authorize,
  };
  const complete = vi.fn();

  render(<StageReadinessAssistancePrompt taskId="task-exact"
    stageId="remote-stage-exact" onComplete={complete} onError={() => {}} />);

  fireEvent.click(await screen.findByRole('button', { name: 'Nudge a maintainer' }));
  expect(authorize).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: 'Post nudge' }));

  await waitFor(() => expect(authorize).toHaveBeenCalledWith(
    'task-exact',
    'remote-stage-exact',
    expect.objectContaining({
      taskEpoch: 7,
      stageGeneration: 3,
      snapshotId: 'snapshot-exact',
      readinessId: 'readiness-exact',
      policyId: 'policy-exact',
      headSha: 'head-exact',
      baseSha: 'base-exact',
      kind: 'POST_MAINTAINER_NUDGE',
      externalTarget: null,
    }),
  ));
  expect(complete).toHaveBeenCalledOnce();
});

it('freezes the manually entered reviewer as both target and payload', async () => {
  const authorize = vi.fn().mockResolvedValue({
    actionId: 'assistance-2', status: 'REQUESTED',
  });
  (window as unknown as { bridge: unknown }).bridge = {
    getV2ReadinessAssistance: vi.fn().mockResolvedValue(exact),
    authorizeV2ReadinessAssistance: authorize,
  };

  render(<StageReadinessAssistancePrompt taskId="task-exact"
    stageId="remote-stage-exact" onComplete={() => {}} onError={() => {}} />);

  fireEvent.click(await screen.findByRole('button', { name: 'Request reviewer' }));
  fireEvent.change(screen.getByLabelText('GitHub username'), {
    target: { value: 'maintainer-one' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Request reviewer' }));

  await waitFor(() => expect(authorize).toHaveBeenCalledWith(
    'task-exact',
    'remote-stage-exact',
    expect.objectContaining({
      kind: 'REQUEST_REVIEWER',
      externalTarget: 'maintainer-one',
      payload: 'maintainer-one',
    }),
  ));
});
