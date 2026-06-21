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
import { CheckpointsSection } from './CheckpointsSection';
import type { ThreadCheckpointDto } from '../types';

afterEach(() => { cleanup(); vi.restoreAllMocks(); Reflect.deleteProperty(window, 'bridge'); });

function checkpoint(over: Partial<ThreadCheckpointDto> = {}): ThreadCheckpointDto {
  return {
    id: 'cp-1', threadId: 'thread-1', seq: 1, isOverall: false,
    firstMsgSeq: 1, lastMsgSeq: 8, tokensCovered: 12000,
    summaryMd: '## What happened\nBumped the retry default in RetryConfig.',
    bulletTitles: ['Bumped retry default'], modelUsed: 'claude-sonnet-4-6',
    promptTokens: 900, completionTokens: 120, costUsdMilli: 42,
    generatedAt: new Date().toISOString(), supersededAt: null, taskId: null,
    ...over,
  };
}

function mockBridge(checkpoints: ThreadCheckpointDto[]) {
  (window as unknown as { bridge: unknown }).bridge = {
    getTaskCheckpoints: vi.fn().mockResolvedValue(checkpoints),
    getTaskCheckpointStatus: vi.fn().mockResolvedValue({ schedulerError: null }),
    listTasksForThread: vi.fn().mockResolvedValue([]),
  };
}

describe('CheckpointsSection', () => {
  it('opens a detail drawer with the rendered body and metadata on row click', async () => {
    mockBridge([checkpoint()]);
    render(<CheckpointsSection threadId="thread-1" />);

    const card = await screen.findByRole('button', { name: /Open checkpoint/ });
    fireEvent.click(card);

    const drawer = await screen.findByRole('dialog', { name: 'Checkpoint detail' });
    expect(drawer.textContent).toContain('Bumped the retry default in RetryConfig');
    // Metadata: model + cost.
    expect(drawer.textContent).toContain('claude-sonnet-4-6');
    expect(drawer.textContent).toContain('4.2¢');
    expect(screen.getByRole('button', { name: /Continue this work/ })).toBeTruthy();
  });

  it('copies the summary when Continue this work is clicked', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.assign(navigator, { clipboard: { writeText } });
    mockBridge([checkpoint()]);
    render(<CheckpointsSection threadId="thread-1" />);

    fireEvent.click(await screen.findByRole('button', { name: /Open checkpoint/ }));
    fireEvent.click(await screen.findByRole('button', { name: /Continue this work/ }));

    await waitFor(() => expect(writeText).toHaveBeenCalled());
    expect(writeText.mock.calls[0][0]).toContain('Bumped the retry default');
  });
});
