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
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Bridge, Ds4MetricsDto, Ds4StateDto, Ds4StatusDto } from '../types';
import { Ds4StatusWidget } from './Ds4StatusWidget';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

afterEach(() => {
  cleanup();
  delete (window as unknown as { bridge?: unknown }).bridge;
});

function status(state: Ds4StateDto, overrides: Partial<Ds4StatusDto> = {}): Ds4StatusDto {
  return {
    state,
    endpoint: 'http://127.0.0.1:8000',
    pid: state === 'RUNNING' ? 12345 : -1,
    startedAt: state === 'RUNNING' ? '2026-06-08T00:00:00Z' : null,
    spawnedByUs: true,
    restartAttempts: 0,
    uptimeSec: state === 'RUNNING' ? 3600 : 0,
    lastError: null,
    ...overrides,
  };
}

function metrics(currentTps = 26): Ds4MetricsDto {
  return {
    memory: {
      weightsBytes: 81_000_000_000,
      kvCacheBytes: 12_000_000_000,
      freeBytes: 35_000_000_000,
      ceilingBytes: 128_000_000_000,
      pct: 0.73,
    },
    throughput: { currentTps, avg1mTps: 24.5, peakTodayTps: 31 },
    latency: { firstTokenMs: 182, avg1mMs: 198 },
    kvOnDisk: { usedBytes: 28_000_000_000, budgetBytes: 40_000_000_000, pct: 0.7 },
    requestsToday: { count: 412, tokensIn: 3_400_000, tokensOut: 810_000 },
    memorySpark30m: [],
    recentRequests: [],
  };
}

describe('Ds4StatusWidget', () => {
  it('renders a Running chip showing throughput and uptime on mount', async () => {
    installBridge({
      getDs4Status: vi.fn(async () => status('RUNNING')),
      getDs4Metrics: vi.fn(async () => metrics(102)),
    });
    render(<Ds4StatusWidget onOpenManagement={() => {}} />);
    await waitFor(() => {
      expect(screen.getByRole('button').textContent).toContain('102 t/s');
    });
    expect(screen.getByRole('button').textContent).toContain('up 1h');
  });

  it('shows an inline Start button when stopped', async () => {
    const startDs4 = vi.fn(async () => status('STARTING'));
    installBridge({
      getDs4Status: vi.fn(async () => status('STOPPED')),
      startDs4,
    });
    render(<Ds4StatusWidget onOpenManagement={() => {}} />);
    await waitFor(() => {
      expect(screen.getByText(/Start/)).toBeTruthy();
    });
    await act(async () => { fireEvent.click(screen.getByText(/Start/)); });
    expect(startDs4).toHaveBeenCalled();
  });

  it('opens the popover on chip click and routes Stop through the bridge', async () => {
    const stopDs4 = vi.fn(async () => ({
      requiresConfirm: false,
      status: status('STOPPING'),
      message: null as string | null,
    }));
    installBridge({
      getDs4Status: vi.fn(async () => status('RUNNING')),
      getDs4Metrics: vi.fn(async () => metrics(26)),
      stopDs4,
    });
    render(<Ds4StatusWidget onOpenManagement={() => {}} />);
    await waitFor(() => screen.getByRole('button'));
    // First button is the chip (no role=dialog yet).
    await act(async () => { fireEvent.click(screen.getByRole('button')); });
    await waitFor(() => screen.getByRole('dialog'));
    const stopBtn = screen.getAllByText(/Stop/).find((el) => el.tagName === 'BUTTON');
    expect(stopBtn).toBeTruthy();
    await act(async () => { fireEvent.click(stopBtn as HTMLElement); });
    expect(stopDs4).toHaveBeenCalledWith(/* confirm */ false);
  });

  it('uses confirm=true when stopping an attached server', async () => {
    const stopDs4 = vi.fn(async () => ({
      requiresConfirm: false,
      status: status('STOPPING'),
      message: null as string | null,
    }));
    installBridge({
      getDs4Status: vi.fn(async () => status('RUNNING', { spawnedByUs: false })),
      getDs4Metrics: vi.fn(async () => metrics(26)),
      stopDs4,
    });
    render(<Ds4StatusWidget onOpenManagement={() => {}} />);
    await waitFor(() => screen.getByRole('button'));
    await act(async () => { fireEvent.click(screen.getByRole('button')); });
    await waitFor(() => screen.getByRole('dialog'));
    const stopBtn = screen.getAllByText(/Stop/).find((el) => el.tagName === 'BUTTON');
    await act(async () => { fireEvent.click(stopBtn as HTMLElement); });
    expect(stopDs4).toHaveBeenCalledWith(/* confirm */ true);
  });

  it('routes Open management to the parent callback and dismisses the popover', async () => {
    const onOpen = vi.fn();
    installBridge({
      getDs4Status: vi.fn(async () => status('RUNNING')),
      getDs4Metrics: vi.fn(async () => metrics(26)),
    });
    render(<Ds4StatusWidget onOpenManagement={onOpen} />);
    await waitFor(() => screen.getByRole('button'));
    await act(async () => { fireEvent.click(screen.getByRole('button')); });
    await waitFor(() => screen.getByRole('dialog'));

    const openBtn = screen.getByText(/Open management/);
    await act(async () => { fireEvent.click(openBtn); });
    expect(onOpen).toHaveBeenCalled();
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
  });

  it('renders nothing when hidden=true (immersive surfaces)', async () => {
    installBridge({ getDs4Status: vi.fn(async () => status('RUNNING')) });
    const { container } = render(<Ds4StatusWidget hidden onOpenManagement={() => {}} />);
    // Wait one tick so any pending effect would have rendered.
    await act(async () => { await Promise.resolve(); });
    expect(container.firstChild).toBeNull();
  });

  it('renders nothing when local AI is disabled', async () => {
    installBridge({ getDs4Status: vi.fn(async () => status('DISABLED')) });
    const { container } = render(<Ds4StatusWidget onOpenManagement={() => {}} />);
    // Let the status poll resolve, then confirm the chip stayed hidden.
    await act(async () => { await Promise.resolve(); });
    await act(async () => { await Promise.resolve(); });
    expect(container.firstChild).toBeNull();
  });
});

function installBridge(overrides: Partial<Bridge>) {
  (window as unknown as { bridge: Partial<Bridge> }).bridge = {
    getDs4Status: vi.fn(async () => status('STOPPED')),
    getDs4Metrics: vi.fn(async () => metrics(0)),
    startDs4: vi.fn(async () => status('STARTING')),
    stopDs4: vi.fn(async () => ({ requiresConfirm: false, status: status('STOPPED'), message: null as string | null })),
    restartDs4: vi.fn(async () => status('STARTING')),
    ...overrides,
  };
}
