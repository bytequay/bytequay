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
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import type { WorkspaceSettingsDto } from '../../types';
import SettingsWindow from '../SettingsWindow';
import '../../css/base.css';
import '../../css/settings-window.css';

const settle = () => new Promise<void>(resolve => setTimeout(() => requestAnimationFrame(() => resolve()), 0));

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

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
  const bridge = {
    getAppVersion: async () => ({ version: '0.4.0' }),
    listWorkspaces: async () => WORKSPACES,
    getAppPreferences: async () => ({ taskRetentionDays: 7, recentRetentionDays: 14, refreshIntervalMinutes: 6, theme: 'SYSTEM' }),
    getWorkspaceSettings: async (id: string): Promise<WorkspaceSettingsDto> => {
      reads.push(id);
      return {
        remotes: [{ name: 'origin', url: `https://github.com/${id}.git`, role: 'ORIGIN' }],
        commitsTarget: { remote: 'upstream', branch: 'master', overridden: false },
        agents: [],
        githubToken: null,
      };
    },
    controlWindow: async (): Promise<void> => undefined,
  };
  Object.defineProperty(window, 'bridge', { value: bridge, configurable: true });
}

function fixture(reads: string[]) {
  stubBridge(reads);
  const host = document.createElement('div');
  host.className = 'app-shell';
  host.style.cssText = 'display:flex;height:600px;width:1200px;overflow:hidden';
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<SettingsWindow section="ws-repository" workspaceId="ws-1" onBack={() => {}} />));

  return {
    host,
    text: () => host.textContent ?? '',
    async click(label: string) {
      const button = [...host.querySelectorAll('button')]
        .find(item => item.getAttribute('aria-label') === label || item.textContent?.trim() === label);
      assert(button !== undefined && !button.disabled, `Button ${label} must be available`);
      button.click();
      await settle();
    },
    /** Options carry an avatar initial in their text, so they are matched on their label alone. */
    async pick(label: string) {
      const option = [...host.querySelectorAll('.setw-ws .setw-menu-item')]
        .find(item => item.querySelector('.setw-menu-label')?.textContent?.trim() === label);
      assert(option instanceof HTMLButtonElement && !option.disabled, `Workspace ${label} must be offered`);
      option.click();
      await settle();
    },
    menu: () => host.querySelector<HTMLElement>('.setw-ws .setw-menu'),
    dispose() { flushSync(() => root.unmount()); host.remove(); },
  };
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  const check = async (name: string, test: (view: ReturnType<typeof fixture>, reads: string[]) => Promise<void>) => {
    const reads: string[] = [];
    const view = fixture(reads);
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

  await check('the workspace menu is not clipped by the sidebar it opens in', async view => {
    await view.click('Workspace being edited');
    const menu = view.menu();
    assert(menu !== null, 'The menu must open');
    const nav = view.host.querySelector('.setw-nav');
    assert(nav !== null, 'The sidebar nav must exist');
    // An ancestor that scrolls clips an absolutely positioned menu at its padding box.
    const clip = nav.getBoundingClientRect();
    const box = menu.getBoundingClientRect();
    assert(
      Math.round(box.right) <= Math.round(clip.right),
      `The menu overflows the scrolling nav by ${Math.round(box.right - clip.right)}px and is clipped`,
    );
    assert(box.width > 100, `The menu collapsed to ${Math.round(box.width)}px`);
  });

  return results;
}
