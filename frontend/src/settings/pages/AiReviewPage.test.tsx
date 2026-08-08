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
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import AiReviewPage from './AiReviewPage';
import type { AiLedgerDto, WorkModelOptionsDto } from '../../types';

afterEach(() => { cleanup(); vi.restoreAllMocks(); Reflect.deleteProperty(window, 'bridge'); });

const LEDGER: AiLedgerDto = {
  month: '2026-06', totalCents: 600, totalCalls: 24,
  byProvider: [
    { provider: 'anthropic', callsCount: 20, costCents: 500 },
    { provider: 'openai', callsCount: 4, costCents: 100 },
  ],
  byTaskType: [
    { type: 'dev', callsCount: 12, costCents: 300 },
    { type: 'review', callsCount: 8, costCents: 200 },
  ],
  apiByProvider: [],
};

const OPTIONS: WorkModelOptionsDto = {
  cliAgents: [
    { id: 'claude-code', displayName: 'Claude Code CLI', installed: true, authed: true, defaultModel: 'opus', models: [] },
    { id: 'codex', displayName: 'Codex CLI', installed: true, authed: true, defaultModel: 'gpt', models: [] },
  ],
  apiProviders: [],
};

function installEngineBridge(extra: Record<string, unknown> = {}) {
  (window as unknown as { bridge: unknown }).bridge = {
    getWorkModelOptions: vi.fn(async () => OPTIONS),
    refreshWorkModelOptions: vi.fn(async () => OPTIONS),
    getDs4Status: vi.fn(async () => ({ state: 'DISABLED' })),
    ...extra,
  };
}

describe('AiReviewPage', () => {
  it('renders the monthly ledger totals and breakdowns', async () => {
    const getAiLedger = vi.fn().mockResolvedValue(LEDGER);
    installEngineBridge({ getAiLedger });

    render(<AiReviewPage initialTab="usage" />);

    await waitFor(() => expect(screen.getByText('$6.00')).toBeTruthy());
    await waitFor(() => expect(screen.getByText('24')).toBeTruthy());
    await waitFor(() => expect(screen.getByText('anthropic')).toBeTruthy());
    await waitFor(() => expect(screen.getByText('By work type')).toBeTruthy());
    await waitFor(() => expect(screen.getByText('review')).toBeTruthy());
    // It asked the backend for the most recent month.
    expect(getAiLedger).toHaveBeenCalled();
  });

});
