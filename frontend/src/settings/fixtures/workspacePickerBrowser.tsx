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
import { useState } from 'react';
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import AppSidebar from '../../AppSidebar';
import type { WorkspaceDto, WorkspaceRelationshipsDto, WorkspaceSettingsDto } from '../../types';
import SettingsWindow from '../SettingsWindow';
import '../../css/base.css';
import '../../css/settings-window.css';
import { assert, changeInput, element } from '../../fixtures/harness';

const settle = () => new Promise<void>(resolve => setTimeout(() => requestAnimationFrame(() => resolve()), 0));


function workspace(id: string, owner: string, repo: string) {
  return {
    id,
    name: `${owner}/${repo}`,
    selectedOwner: owner,
    selectedRepo: repo,
    monitoredOwner: owner,
    monitoredRepo: repo,
    localRepositoryPath: `/managed/${id}/repository`,
  };
}

const WORKSPACES = [workspace('ws-1', 'chenjian2664', 'trino_new'), workspace('ws-2', 'trinodb', 'charts')];

/** Counts what the window asked for, so a reload can be told apart from a stuck page. */
function stubBridge(reads: string[]) {
  const writes: { workspaceId: string; value: string }[] = [];
  const bridge = {
    getAppVersion: async () => ({ version: '0.4.0' }),
    listWorkspaces: async () => WORKSPACES,
    getWorkspaceRelationships: async (): Promise<WorkspaceRelationshipsDto> => ({ links: [], related: [], line: [] }),
    getAppPreferences: async () => ({ taskRetentionDays: 7, recentRetentionDays: 14, prWarmCount: 30 as const, autoMergeDelayMinutes: 60, theme: 'SYSTEM' }),
    getGitHubAccount: async () => ({ configured: false }),
    getWorkspaceSettings: async (id: string): Promise<WorkspaceSettingsDto> => {
      reads.push(id);
      return {
        remotes: [{ name: 'origin', url: `https://github.com/${id}.git`, role: 'ORIGIN' }],
        commitsTarget: { remote: 'upstream', branch: 'master', overridden: false },
        agents: [],
        githubToken: null,
      };
    },
    setWorkspaceCommitsTarget: async (workspaceId: string, value: string) => { writes.push({ workspaceId, value }); },
    saveWorkspaceGitHubToken: async (workspaceId: string, value: string) => { writes.push({ workspaceId, value }); },
    controlWindow: async (): Promise<void> => undefined,
  };
  Object.defineProperty(window, 'bridge', { value: bridge, configurable: true });
  return writes;
}

/**
 * The app's own composition: one sidebar beside the content pane. Mounting
 * SettingsWindow alone would no longer exercise the picker — settings shares the
 * app sidebar instead of carrying one of its own.
 */
function Harness({ exits, section }: { exits: () => void; section: 'ws-repository' | 'ws-token' }) {
  const [workspaceId, setWorkspaceId] = useState('ws-1');
  const picked = (WORKSPACES.find(candidate => candidate.id === workspaceId) ?? WORKSPACES[0]) as WorkspaceDto;
  return (
    <>
      <AppSidebar
        page="settings"
        browserRailVisible={true}
        onToggleBrowserRail={() => {}}
        onNavigate={() => {}}
        canGoBack={false}
        canGoForward={false}
        onBack={() => {}}
        onForward={() => {}}
        workspace={null}
        workspaceTab="home"
        onWorkspaceTab={() => {}}
        settings={{
          section,
          workspaces: WORKSPACES as WorkspaceDto[],
          workspaceId: picked.id,
          onSection: () => {},
          onWorkspace: setWorkspaceId,
          onExit: exits,
        }}
      />
      <main className="app-content">
        <SettingsWindow section={section} workspace={picked} onWorkspacesChanged={() => {}} />
      </main>
    </>
  );
}

function fixture(reads: string[], onExit: () => void = () => {}, section: 'ws-repository' | 'ws-token' = 'ws-repository') {
  const writes = stubBridge(reads);
  const host = document.createElement('div');
  host.className = 'app-shell';
  host.style.cssText = 'display:flex;height:600px;width:1200px;overflow:hidden';
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<Harness exits={onExit} section={section} />));

  return {
    host,
    writes,
    text: () => host.textContent ?? '',
    sidebar: () => host.querySelector<HTMLElement>('.app-sidebar'),
    async click(label: string) {
      const button = [...host.querySelectorAll('button')]
        .find(item => item.getAttribute('aria-label') === label || item.textContent?.trim() === label);
      assert(button !== undefined && !button.disabled, `Button ${label} must be available`);
      button.click();
      await settle();
    },
    /** Options carry an avatar in their text, so they are matched on their label alone. */
    async pick(label: string) {
      const option = [...host.querySelectorAll('.setw-menu-item')]
        .find(item => item.querySelector('.setw-menu-label')?.textContent?.trim() === label);
      assert(option instanceof HTMLButtonElement && !option.disabled, `Workspace ${label} must be offered`);
      option.click();
      await settle();
    },
    menu: () => host.querySelector<HTMLElement>('.setw-menu'),
    async type(label: string, value: string) {
      const input = element<HTMLInputElement>(`input[aria-label="${label}"]`, host, `Input ${label} must be available`);
      changeInput(input, value);
      await settle();
    },
    /** Drags the sidebar's right edge the way a person would. */
    async dragBoundary(byPixels: number) {
      const handle = element('.app-sidebar__resize', host, 'The sidebar must have a resize handle');
      const from = handle.getBoundingClientRect().left;
      handle.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, clientX: from }));
      window.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientX: from + byPixels }));
      window.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
      await settle();
    },
    dispose() { flushSync(() => root.unmount()); host.remove(); },
  };
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  const check = async (
    name: string,
    test: (view: ReturnType<typeof fixture>, reads: string[]) => Promise<void>,
    section: 'ws-repository' | 'ws-token' = 'ws-repository',
  ) => {
    const reads: string[] = [];
    const view = fixture(reads, undefined, section);
    try {
      await settle();
      await settle();
      await test(view, reads);
      results.push({ name });
    }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
    finally { view.dispose(); }
  };

  await check('re-picking the workspace already open leaves its settings on screen', async (view, reads) => {
    assert(view.text().includes('origin'), 'The workspace settings must load first');
    assert(reads.length === 1, `Expected one read, got ${reads.length}`);

    await view.click('Workspace being edited');
    await view.pick('chenjian2664/trino_new');
    await settle();

    // The bug: choosing the open workspace blanked the page with nothing left to refill it.
    assert(!view.text().includes('Loading remotes'), 'Re-picking the open workspace must not blank the page');
    assert(view.text().includes('origin'), 'The remotes must still be shown');
  });

  await check('switching workspace loads the one that was chosen', async (view, reads) => {
    await view.click('Workspace being edited');
    await view.pick('trinodb/charts');
    await settle();
    await settle();

    assert(reads.includes('ws-2'), `Expected ws-2 to be read, got ${JSON.stringify(reads)}`);
    assert(view.text().includes('ws-2'), 'The chosen workspace remotes must be shown');
    assert(!view.text().includes('Loading remotes'), 'The page must not stay in its loading state');
  });

  await check('switching workspace clears repository drafts before saving another target', async view => {
    await view.type('Default target branch', 'release-for-first');
    await view.click('Add remote');
    await view.type('Remote name', 'first-only');
    await view.type('Remote URL', 'https://github.com/first/repo.git');
    await view.click('Workspace being edited');
    await view.pick('trinodb/charts');
    await settle();

    assert(view.host.querySelector<HTMLInputElement>('[aria-label="Default target branch"]')?.value === 'master', 'The target must come from the chosen workspace');
    assert(view.host.querySelector('[aria-label="Remote name"]') === null, 'An unfinished remote must not follow the workspace picker');
    assert(view.writes.length === 0, 'Switching workspaces must not save drafts');
    await view.type('Default target branch', 'release-for-second');
    await view.click('Save');
    assert(JSON.stringify(view.writes) === JSON.stringify([{ workspaceId: 'ws-2', value: 'release-for-second' }]), 'Only the new workspace draft may be saved');
  });

  await check('switching workspace clears an unfinished token', async view => {
    await view.click('Use a workspace token…');
    await view.type('Workspace GitHub token', 'fake-first-workspace-token');
    await view.click('Workspace being edited');
    await view.pick('trinodb/charts');
    await settle();

    assert(view.host.querySelector('[aria-label="Workspace GitHub token"]') === null, 'The previous workspace token editor must close');
    await view.click('Use a workspace token…');
    assert(view.host.querySelector<HTMLInputElement>('[aria-label="Workspace GitHub token"]')?.value === '', 'The new workspace token draft must be empty');
    assert(view.writes.length === 0, 'Switching workspaces must not save credentials');
    await view.type('Workspace GitHub token', 'fake-second-workspace-token');
    await view.click('Save');
    assert(JSON.stringify(view.writes) === JSON.stringify([{ workspaceId: 'ws-2', value: 'fake-second-workspace-token' }]), 'Only the newly entered token may reach the selected workspace');
  }, 'ws-token');

  await check('the workspace menu is not clipped by the sidebar it opens in', async view => {
    await view.click('Workspace being edited');
    const menu = view.menu();
    assert(menu !== null, 'The menu must open');
    const side = view.sidebar();
    assert(side !== null, 'The settings nav must be the app sidebar');
    // An ancestor that scrolls clips an absolutely positioned menu at its padding box.
    const clip = side.getBoundingClientRect();
    const box = menu.getBoundingClientRect();
    assert(
      Math.round(box.right) <= Math.round(clip.right) && Math.round(box.left) >= Math.round(clip.left),
      `The menu overflows the sidebar horizontally by ${Math.round(box.right - clip.right)}px`,
    );
    assert(
      Math.round(box.bottom) <= Math.round(clip.bottom),
      `The menu overflows the sidebar bottom by ${Math.round(box.bottom - clip.bottom)}px and is clipped`,
    );
    assert(box.width > 100, `The menu collapsed to ${Math.round(box.width)}px`);
  });

  await (async () => {
    const name = 'Back to app leaves settings through its own handler';
    let left = 0;
    const view = fixture([], () => { left += 1; });
    try {
      await settle();
      await view.click('Back to app');
      assert(left === 1, `Back to app must leave settings, called ${left} times`);
      results.push({ name });
    }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
    finally { view.dispose(); }
  })();

  await check('the settings boundary drags, because it is the app sidebar', async view => {
    const side = view.sidebar();
    assert(side !== null, 'The settings nav must be the app sidebar');
    const before = side.getBoundingClientRect().width;

    await view.dragBoundary(60);

    const after = side.getBoundingClientRect().width;
    assert(after > before, `Dragging the boundary must widen the sidebar, ${before} -> ${after}`);
  });

  await check('settings shares the sidebar rather than carrying its own', async view => {
    assert(view.host.querySelectorAll('.app-sidebar').length === 1, 'There must be exactly one sidebar');
    assert(view.host.querySelector('.setw-side') === null, 'The settings-only sidebar must be gone');
    // Shares the chrome, not the app's own tabs.
    assert(view.host.querySelector('[aria-label="Settings navigation"]') !== null, 'Settings supplies the nav body');
    assert(view.host.querySelector('[aria-label="Global navigation"]') === null, 'Settings must not show the app tabs');
    assert(view.host.querySelector('.app-sidebar__resize') !== null, 'The shared resize handle must be present');

    // The pane scrolls inside .app-content rather than pushing past it.
    const pane = element('.setw-scroll', view.host, 'The settings content pane must be present');
    const content = element('.app-content', view.host, 'Settings must sit in the shell content area');
    assert(
      Math.round(pane.getBoundingClientRect().bottom) <= Math.round(content.getBoundingClientRect().bottom),
      'The settings pane must not overflow the content area',
    );
    assert(getComputedStyle(pane).overflowY === 'auto', 'The settings pane must be what scrolls');
  });

  return results;
}
