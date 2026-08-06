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
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { WorkspaceApiRequest } from '../types';
import WorkspaceCommitEditor from './WorkspaceCommitEditor';
import type { RewritableCommitDto } from './workspaceApi';

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(window, 'bridge');
});

/** Newest first: two unpushed commits on top of two the remote has. */
const COMMITS: RewritableCommitDto[] = [
  row('aaaaaaa1111', 'Add the durable stage protocol', false),
  row('bbbbbbb2222', 'wip', false),
  row('ccccccc3333', 'Fix merge queue status', true),
  row('ddddddd4444', 'Show AI review progress', true),
];

function row(sha: string, subject: string, pushed: boolean): RewritableCommitDto {
  return {
    sha,
    shortSha: sha.slice(0, 7),
    subject,
    body: `body of ${subject}`,
    authorName: pushed ? 'Lifeng Yuan' : 'Jack Chen',
    authorEmail: pushed
      ? 'lifeng-yuan@example.com'
      : '12345+chenjian2664@users.noreply.github.com',
    authoredAt: '2026-07-29T09:00:00Z',
    committedAt: '2026-07-29T09:00:00Z',
    additions: 12,
    deletions: 3,
    pushed,
  };
}

function installBridge() {
  const workspaceApi = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
    if (request.path.startsWith('/api/workspaces/w1/commits/rewritable')) {
      return {
        branch: 'master', trackingRef: 'origin/master', editable: true, commits: COMMITS,
      };
    }
    if (request.path.includes('/files')) return [];
    if (request.path.startsWith('/api/workspaces/w1/commits/rewrite')) {
      return {
        headSha: 'newhead',
        pushed: (request.body as { forcePush: boolean }).forcePush,
        pushError: null,
      };
    }
    return null;
  });
  (window as unknown as { bridge: unknown }).bridge = { workspaceApi };
  return workspaceApi;
}

function editor(query = '') {
  return render(<WorkspaceCommitEditor workspaceId="w1" branch="master"
    query={query} author="all" onAuthorsChange={() => {}}
    onClearQuery={() => {}} onClearAuthor={() => {}} />);
}

describe('WorkspaceCommitEditor', () => {
  it('groups the unpushed commits above the origin divider', async () => {
    installBridge();
    editor();

    await screen.findByText('Add the durable stage protocol');
    expect(screen.getByText('2 LOCAL COMMITS')).toBeTruthy();
    expect(screen.getByText('ORIGIN/MASTER')).toBeTruthy();
    expect(screen.getAllByText(/ahead of origin\/master/).length).toBe(2);
  });

  it('shows each author as their GitHub picture, not coloured initials', async () => {
    installBridge();
    editor();
    await screen.findByText('Add the durable stage protocol');

    // The private commit address names the account, so the row can ask
    // GitHub for the real picture rather than drawing "JC".
    const mine = screen.getAllByAltText('chenjian2664')[0] as HTMLImageElement;
    expect(mine.getAttribute('src')).toBe('https://github.com/chenjian2664.png?size=36');

    // A plain address leaves only the display name; the image 404s and
    // Avatar falls back to the initial, which is why it still renders one.
    expect(screen.getAllByAltText('Lifeng Yuan').length).toBeGreaterThan(0);
  });

  it('stages a squash and sends the whole queue as one rebase', async () => {
    const api = installBridge();
    editor();
    await screen.findByText('Add the durable stage protocol');

    fireEvent.click(screen.getByRole('checkbox', { name: 'Select aaaaaaa' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select bbbbbbb' }));
    fireEvent.click(screen.getAllByRole('button', { name: /Squash into one/ })[0]);

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('LANDS HERE')).toBeTruthy();
    fireEvent.change(within(dialog).getByLabelText('Summary', { exact: false }) as HTMLElement,
      { target: { value: 'Add the durable stage protocol' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /^Squash 2 commits$/ }));

    await screen.findByText('1 pending rewrite');
    expect(screen.getByText('Squash 2 → 1')).toBeTruthy();
    // Neither commit was pushed, so no force-push warning.
    expect(screen.queryByText(/force-with-lease/)).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /Rewrite history/ }));
    await waitFor(() => {
      const call = api.mock.calls.find(([r]) => r.path.endsWith('/commits/rewrite'));
      if (call === undefined) throw new Error('the rewrite was never sent');
      expect(call[0].body).toEqual({
        branch: 'master',
        // Untouched pushed history — the rebase starts here.
        base: 'ccccccc3333',
        commits: [{
          picks: ['bbbbbbb2222', 'aaaaaaa1111'],
          message: 'Add the durable stage protocol\n\n* Add the durable stage protocol\n* wip',
        }],
        forcePush: false,
      });
    });
  });

  it('warns about a force push once an edit reaches pushed history, and undo takes it back', async () => {
    installBridge();
    editor();
    await screen.findByText('Fix merge queue status');

    // Rewording a pushed commit drags it — and everything above — into
    // the rewrite zone.
    fireEvent.click(screen.getByText('Fix merge queue status'));
    const title = await screen.findByLabelText('Commit title');
    fireEvent.change(title, { target: { value: 'Fix the merge queue status field' } });
    fireEvent.click(screen.getByRole('button', { name: /Save message/ }));

    await screen.findByText('1 pending rewrite');
    expect(screen.getByText(/force-with-lease/)).toBeTruthy();
    // Only the reworded commit joins the local group — the one below it
    // is still exactly what the remote has.
    expect(screen.getByText('3 LOCAL COMMITS')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Undo' }));
    await waitFor(() => expect(screen.queryByText(/force-with-lease/)).toBeNull());
    expect(screen.getByText('2 LOCAL COMMITS')).toBeTruthy();
  });

  it('keeps the pending queue when the rebase fails', async () => {
    const api = installBridge();
    api.mockImplementation(async (request: WorkspaceApiRequest) => {
      if (request.path.startsWith('/api/workspaces/w1/commits/rewritable')) {
        return { branch: 'master', trackingRef: 'origin/master', editable: true, commits: COMMITS };
      }
      if (request.path.includes('/files')) return [];
      if (request.path.endsWith('/commits/rewrite')) {
        throw new Error('The rebase failed and was rolled back: conflict in a.txt');
      }
      return null;
    });
    editor();
    await screen.findByText('wip');

    fireEvent.click(screen.getByText('wip'));
    fireEvent.change(await screen.findByLabelText('Commit title'),
      { target: { value: 'Rename the resume owner ports' } });
    fireEvent.click(screen.getByRole('button', { name: /Save message/ }));
    await screen.findByText('1 pending rewrite');

    fireEvent.click(screen.getByRole('button', { name: /Rewrite history/ }));
    await screen.findByText(/conflict in a.txt/);
    // The queue survives so the user can fix the conflict and retry.
    expect(screen.getByText('1 pending rewrite')).toBeTruthy();
  });

  it('renames inline on Enter and stages exactly one reword', async () => {
    installBridge();
    editor();
    await screen.findByText('wip');

    fireEvent.doubleClick(screen.getByText('wip'));
    const inline = await screen.findByRole('textbox', { name: 'Rename bbbbbbb' });
    fireEvent.change(inline, { target: { value: 'Rename the resume owner ports' } });
    fireEvent.keyDown(inline, { key: 'Enter' });
    fireEvent.blur(inline);

    await screen.findByText('1 pending rewrite');
    expect(screen.getByText('Reword bbbbbbb')).toBeTruthy();
    expect(screen.getAllByText('Rename the resume owner ports').length).toBeGreaterThan(0);
  });

  it('drops an inline rename on Escape, blur included', async () => {
    installBridge();
    editor();
    await screen.findByText('wip');

    fireEvent.doubleClick(screen.getByText('wip'));
    const inline = await screen.findByRole('textbox', { name: 'Rename bbbbbbb' });
    fireEvent.change(inline, { target: { value: 'Never applied' } });
    fireEvent.keyDown(inline, { key: 'Escape' });
    fireEvent.blur(inline);

    await waitFor(() => expect(screen.getAllByText('wip').length).toBeGreaterThan(0));
    expect(screen.queryByText('1 pending rewrite')).toBeNull();
    expect(screen.queryByText('Never applied')).toBeNull();
  });

  it('matches a sha prefix as well as the title', async () => {
    installBridge();
    editor('aaaaaaa');

    await screen.findByText('Add the durable stage protocol');
    expect(screen.getByText('1 of 4 commits')).toBeTruthy();
    expect(screen.queryByText('wip')).toBeNull();
    // A prefix only — a sha is never useful from the middle.
    cleanup();
    installBridge();
    editor('bbbb2222');
    await waitFor(() => expect(screen.getByText('0 of 4 commits')).toBeTruthy());
  });

  it('appends the next page when the list scrolls to the end', async () => {
    const page = (from: number) => Array.from({ length: 100 }, (_, i) =>
      row(`${(from + i).toString(16).padStart(8, '0')}aaa`, `commit ${from + i}`, true));
    const api = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
      if (request.path.includes('/commits/rewritable')) {
        const skip = Number(/skip=(\d+)/.exec(request.path)?.[1] ?? 0);
        return {
          branch: 'master', trackingRef: 'origin/master', editable: true,
          commits: skip === 0 ? page(0) : page(100),
        };
      }
      if (request.path.includes('/files')) return [];
      return null;
    });
    (window as unknown as { bridge: unknown }).bridge = { workspaceApi: api };
    editor();

    await screen.findByText('commit 0');
    expect(screen.queryByText('commit 100')).toBeNull();

    fireEvent.scroll(screen.getByRole('listbox'));

    expect(await screen.findByText('commit 100')).toBeTruthy();
    // The first page is still there — pages append, they don't replace.
    expect(screen.getByText('commit 0')).toBeTruthy();
  });

  it('pauses reordering while a filter is on', async () => {
    installBridge();
    editor('merge');

    await screen.findByText('Fix merge queue status');
    expect(screen.getByText('1 of 4 commits')).toBeTruthy();
    expect(screen.getByText('Reordering is paused while a filter is on')).toBeTruthy();
    expect(screen.queryByText('Add the durable stage protocol')).toBeNull();
  });
});
