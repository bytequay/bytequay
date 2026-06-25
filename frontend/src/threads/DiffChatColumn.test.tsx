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
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { DiffChatColumn } from './DiffChatColumn';

beforeAll(() => { Element.prototype.scrollIntoView = vi.fn(); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); Reflect.deleteProperty(window, 'bridge'); });

function mockBridge(over: Record<string, unknown> = {}) {
  const bridge = {
    getStageDetail: vi.fn().mockResolvedValue({
      stage: { id: 'st-1', type: 'DEVELOPMENT_STAGE', state: 'ACTIVE', iterationCount: 1 },
      task: { id: 'task-1', title: 'Backend cleanup', branch: 'jack/x' },
      conversation: [
        { id: 'r1', kind: 'user', text: 'tidy the imports' },
        { id: 'r2', kind: 'agent', text: 'Done — removed the unused ones.' },
      ],
    }),
    steerStage: vi.fn().mockResolvedValue({ turnId: 't1' }),
    ...over,
  };
  (window as unknown as { bridge: unknown }).bridge = bridge;
  return bridge;
}

describe('DiffChatColumn', () => {
  it('shows the stage transcript and steers the stage on submit', async () => {
    const bridge = mockBridge();
    render(<DiffChatColumn stageId="st-1" />);

    expect(await screen.findByText('tidy the imports')).toBeTruthy();
    expect(screen.getByText('Done — removed the unused ones.')).toBeTruthy();

    const box = screen.getByRole('textbox');
    fireEvent.change(box, { target: { value: 'also sort them' } });
    fireEvent.keyDown(box, { key: 'Enter' });
    await waitFor(() => expect(bridge.steerStage).toHaveBeenCalledWith('st-1', 'also sort them'));
  });

  it('renders the PR-agent placeholder when there is no stage', () => {
    mockBridge();
    render(<DiffChatColumn />);
    expect(screen.getByText(/coming soon/i)).toBeTruthy();
    // Composer is parked (disabled) until PR-agent support lands.
    expect((screen.getByRole('textbox') as HTMLTextAreaElement).disabled).toBe(true);
  });
});
