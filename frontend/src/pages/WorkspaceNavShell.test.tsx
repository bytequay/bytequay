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
import { WorkspaceNavShell } from './WorkspaceNavShell';
import { logoColorFor, monogram, threadStatusDot } from './useWorkspaceNav';

afterEach(() => { cleanup(); vi.restoreAllMocks(); Reflect.deleteProperty(window, 'bridge'); });

function mockBridge(over: Record<string, unknown> = {}) {
  const bridge = {
    listWorkspaces: vi.fn().mockResolvedValue([
      { id: 'bq', name: 'ByteQuay', color: '#8b5cf6', isScratch: false, repos: ['web', 'trino', 'docs'], activeThreadCount: 5, tasksInFlight: 3 },
      { id: 'tr', name: 'Trino', color: '#0d9488', isScratch: false, repos: ['trino', 'web'], activeThreadCount: 3, tasksInFlight: 1 },
    ]),
    listTasks: vi.fn().mockResolvedValue([
      { id: 't1', title: 'Backend cleanup review', status: 'RUNNING', workspaceId: 'bq', activeTask: { workingDir: '/x/web' } },
      { id: 't2', title: 'Fix Delta Lake timestamp', status: 'AWAITING_REVIEW', workspaceId: 'bq', activeTask: { workingDir: '/x/trino' } },
    ]),
    // The no-workspace rail body is the recently-visited list.
    getFootprints: vi.fn().mockResolvedValue({
      date: '2026-07-03',
      stops: [{
        surfaceType: 'PR', surfaceId: 'org/web#42', title: 'org/web #42',
        context: 'org/web', latestVisitAt: '2026-07-03T10:00:00Z', visitCount: 2,
      }],
      totalStops: 1,
    }),
    fetchPrs: vi.fn().mockResolvedValue([]),
    ...over,
  };
  (window as unknown as { bridge: unknown }).bridge = bridge;
  return bridge;
}

describe('useWorkspaceNav helpers', () => {
  it('logoColorFor is deterministic', () => {
    expect(logoColorFor('web')).toBe(logoColorFor('web'));
  });
  it('monogram lowercases the first two alphanumerics', () => {
    expect(monogram('ByteQuay')).toBe('by');
    expect(monogram('trino')).toBe('tr');
  });
  it('maps thread status to the right dot', () => {
    expect(threadStatusDot('RUNNING')).toBe('active');
    expect(threadStatusDot('AWAITING_REVIEW')).toBe('planning');
    expect(threadStatusDot('COMPLETED')).toBe('done');
    expect(threadStatusDot('IDLE')).toBe('sleep');
  });
});

describe('WorkspaceNavShell', () => {
  it('shows the recently-visited list when no workspace is active', async () => {
    mockBridge();
    const onResumeVisit = vi.fn();
    render(<WorkspaceNavShell activeWorkspaceId={null} onResumeVisit={onResumeVisit} />);
    // The footprint stop shows once, as a Continue row. (The Today "Working on"
    // line is PR-derived now, and this mock has no PRs.)
    const rows = await screen.findAllByText('org/web #42');
    expect(rows.length).toBe(1);
    fireEvent.click(rows[0]);
    expect(onResumeVisit).toHaveBeenCalledWith(expect.objectContaining({ surfaceId: 'org/web#42' }));
  });

  it('shows the switcher + thread list (with trunk tiles) when a workspace is active', async () => {
    const bridge = mockBridge();
    const onOpenThread = vi.fn();
    const { container } = render(
      <WorkspaceNavShell activeWorkspaceId="bq" selectedThreadId="t1" onOpenThread={onOpenThread} />,
    );
    await waitFor(() => expect(bridge.listTasks).toHaveBeenCalledWith({ workspaceId: 'bq' }));
    // Switcher shows the active workspace.
    expect(container.querySelector('.ws-switcher')?.textContent).toContain('ByteQuay');
    // Thread rows render with their trunk tiles + the selected one highlights.
    expect(await screen.findByText('Backend cleanup review')).toBeTruthy();
    expect(container.querySelector('.thread-item.active')?.textContent).toContain('Backend cleanup review');
    expect(container.querySelector('.thread-item .trunk-tile svg')).toBeTruthy();
    fireEvent.click(screen.getByText('Fix Delta Lake timestamp'));
    expect(onOpenThread).toHaveBeenCalledWith('t2');
  });

  it('the switcher ▾ fires onSwitchWorkspace', async () => {
    mockBridge();
    const onSwitchWorkspace = vi.fn();
    const { container } = render(
      <WorkspaceNavShell activeWorkspaceId="bq" onSwitchWorkspace={onSwitchWorkspace} />,
    );
    await waitFor(() => expect(container.querySelector('.ws-switcher')).toBeTruthy());
    fireEvent.click(container.querySelector('.ws-switcher') as HTMLElement);
    expect(onSwitchWorkspace).toHaveBeenCalledOnce();
  });
});
