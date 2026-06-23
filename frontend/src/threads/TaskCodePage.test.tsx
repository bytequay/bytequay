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
import TaskCodePage from './TaskCodePage';

// jsdom doesn't implement scrollIntoView; the shared ContinuousDiff calls it
// when the active file changes.
beforeAll(() => { Element.prototype.scrollIntoView = vi.fn(); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); Reflect.deleteProperty(window, 'bridge'); });

const CUMULATIVE = [{
  filename: 'src/Foo.ts', status: 'modified', additions: 1, deletions: 1,
  patch: '@@ -1,2 +1,2 @@\n context\n-old line\n+new line\n',
}];
const PER_COMMIT = [{
  filename: 'src/Bar.ts', status: 'added', additions: 1, deletions: 0,
  patch: '@@ -0,0 +1,1 @@\n+brand new\n',
}];

function mockBridge(overrides: Record<string, unknown> = {}) {
  const bridge = {
    listTasksForThread: vi.fn().mockResolvedValue([
      { id: 'task-1', seq: 1, name: 'Fix typos', branchName: 'jack/fix' },
    ]),
    listTaskCommits: vi.fn().mockResolvedValue([
      { sha: 'abc123def', shortSha: 'abc123d', authorName: 'me', authorEmail: 'm@e', authoredAt: '2026-06-20T10:00:00Z', subject: 'Fix typos in docs' },
    ]),
    getTaskCumulativeDiff: vi.fn().mockResolvedValue(CUMULATIVE),
    getTaskCommitDiffFiles: vi.fn().mockResolvedValue(PER_COMMIT),
    ...overrides,
  };
  (window as unknown as { bridge: unknown }).bridge = bridge;
  return bridge;
}

describe('TaskCodePage', () => {
  it('renders the cumulative diff via the shared continuous renderer', async () => {
    mockBridge();
    const { container } = render(<TaskCodePage threadId="thread-1" taskId="task-1" onBack={() => {}} />);

    // Toolbar + title.
    expect(await screen.findByRole('button', { name: '← Back' })).toBeTruthy();
    // Commits column: cumulative entry + the commit.
    expect(screen.getByText('All commits')).toBeTruthy();
    expect(await screen.findByText('Fix typos in docs')).toBeTruthy();
    // Continuous diff body: the changed file header + actual diff rows from
    // its patch (the shared renderer parsed and rendered the hunks).
    expect((await screen.findAllByText('src/Foo.ts')).length).toBeGreaterThan(0);
    await waitFor(() => expect(container.querySelectorAll('.diff-row--add').length).toBeGreaterThan(0));
    expect(container.querySelectorAll('.diff-row--del').length).toBeGreaterThan(0);
  });

  it('scopes to a single commit when a commit row is clicked', async () => {
    const bridge = mockBridge();
    render(<TaskCodePage threadId="thread-1" taskId="task-1" onBack={() => {}} />);

    fireEvent.click(await screen.findByText('Fix typos in docs'));
    await waitFor(() => expect(bridge.getTaskCommitDiffFiles).toHaveBeenCalledWith('thread-1', 'abc123def'));
    // The per-commit diff replaces the cumulative one.
    expect((await screen.findAllByText('src/Bar.ts')).length).toBeGreaterThan(0);
  });

  it('back button fires onBack', async () => {
    mockBridge();
    const onBack = vi.fn();
    render(<TaskCodePage threadId="thread-1" taskId="task-1" onBack={onBack} />);
    fireEvent.click(await screen.findByRole('button', { name: '← Back' }));
    expect(onBack).toHaveBeenCalledTimes(1);
  });
});
