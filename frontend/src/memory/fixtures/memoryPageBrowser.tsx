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
import { createRoot } from 'react-dom/client';
import { flushSync } from 'react-dom';
import { assert, settle } from '../../fixtures/harness';
import type { WorkspaceDto } from '../../types';
import type { MemoryDto, MemoryPageDto } from '../contracts';
import MemoryPage from '../MemoryPage';
import '../../css/base.css';

const workspace: WorkspaceDto = {
  id: 'trino', name: 'Trino', selectedOwner: 'trinodb', selectedRepo: 'trino', monitoredOwner: 'trinodb', monitoredRepo: 'trino',
  selectedRepositoryId: 1, monitoredRepositoryId: 1, upstreamRepositoryId: null, upstreamOwner: null, upstreamRepo: null,
  topology: 'DIRECT', defaultBranch: 'main', upstreamDefaultBranch: null, localRepositoryPath: '/managed/trino',
  lastCodeSyncAt: null, lastMetadataSyncAt: null, displayOrder: 0,
};
const page: MemoryPageDto = {
  memories: Array.from({ length: 18 }, (_, index): MemoryDto => ({
    id: `m${index}`, kind: index === 0 ? 'RECIPE' : 'RULE', title: index === 0 ? 'Run connector tests' : `Connector convention ${index}`,
    text: 'When changing a connector, add a regression test that covers the reported behavior.', scope: ['plugin/trino-iceberg/'],
    sources: [{ kind: 'PAGE', url: 'https://example.com/spec', quote: 'Cover changed behavior.', at: '2026-01-01T00:00:00Z',
      section: null, pullRequest: null, user: null, command: null }],
    origin: { user: true, description: 'From you', taskId: null }, version: 1, previous: null, delivered: 0, cited: 0,
    reports: [], missingScope: [], createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
  })),
  switches: { reviews: true, development: true }, guidelines: [], head: '1404575', harvest: { cursor: null, last: null },
};

export function mount() {
  const calls: string[] = [];
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    getMemoryPage: async () => page,
    getRepositoryContextSpecChecks: async () => [],
    getRepositoryContextBuild: async () => ({ buildRequestSequence: 0, canStart: true, build: null }),
    listReviewReplays: async () => [],
    getModelSettings: async () => { throw new Error('No configured model'); },
    getRepositoryContextSpecModules: async () => { calls.push('modules'); return []; },
    startMemoryHarvest: async () => { calls.push('harvest'); throw new Error('Unexpected run'); },
    startRepositoryContextSpecCheck: async () => { calls.push('check'); throw new Error('Unexpected run'); },
  } satisfies Partial<typeof window.bridge> });
  const host = document.createElement('main');
  host.style.cssText = 'height:100vh';
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<MemoryPage workspace={workspace} />));
  return { host, calls, showSpec: () => flushSync(() => root.render(<MemoryPage workspace={workspace} spec={{ directory: null }} />)),
    close: () => { flushSync(() => root.unmount()); host.remove(); } };
}

export async function run() {
  const view = mount();
  try {
    await settle();
    const tab = (id: string) => {
      const button = view.host.querySelector<HTMLButtonElement>(`#mem-tab-${id}`);
      assert(button !== null, 'Tab exists: ' + id);
      return button;
    };
    const selected = (id: string) => {
      assert(tab(id).getAttribute('aria-selected') === 'true', 'Selected: ' + id);
      const panels = view.host.querySelectorAll('[role="tabpanel"]');
      assert(panels.length === 1 && panels[0].id === `mem-panel-${id}`, 'Only selected content is rendered');
      assert(panels[0].querySelectorAll('section').length === 1, 'Sections are not stacked');
    };
    selected('memories');
    assert(view.host.querySelector('.mem-learn') === null, 'Harvest history does not push memories down');
    const scroll = view.host.querySelector<HTMLElement>('.mem-page-scroll');
    assert(scroll !== null && scroll.scrollHeight > scroll.clientHeight, 'Long memories scroll within the panel');
    const top = tab('memories').getBoundingClientRect().top;
    scroll.scrollTop = 500;
    await settle();
    assert(tab('memories').getBoundingClientRect().top === top, 'Tabs stay visible while scrolling');
    const recipes = [...view.host.querySelectorAll<HTMLButtonElement>('.mem-tab')].find(button => button.textContent?.startsWith('Recipes'));
    assert(recipes !== undefined, 'Recipe filter exists');
    recipes.click(); await settle();
    assert(view.host.querySelectorAll('.mem-row').length === 1, 'Filter applied');
    for (const id of ['learn', 'needs', 'specs', 'replays', 'guidelines', 'memories']) {
      tab(id).click(); await settle(); selected(id);
      assert(scroll.scrollTop === 0, 'Switching sections resets the scroll');
    }
    assert(view.host.querySelectorAll('.mem-row').length === 1, 'Memory filters survive tab switching');
    for (const [key, expected] of [['ArrowLeft', 'guidelines'], ['Home', 'memories'], ['ArrowRight', 'learn'], ['End', 'guidelines']]) {
      const current = view.host.querySelector('[role="tab"][aria-selected="true"]');
      current?.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
      await settle(); selected(expected);
      assert(document.activeElement === tab(expected), 'Keyboard navigation moves focus');
    }
    const check = view.host.querySelector<HTMLButtonElement>('.rcp-head__actions button');
    check?.click(); await settle(); selected('specs');
    assert(view.host.querySelector('[role="dialog"]') !== null, 'Check action opens drawer');
    view.host.querySelector<HTMLButtonElement>('[aria-label="Close"]')?.click(); await settle(); selected('specs');
    tab('memories').click(); await settle();
    view.showSpec(); await settle(); selected('specs');
    assert(view.host.querySelector('[role="dialog"]') !== null, 'External spec request selects its section and opens drawer');
    assert(!view.calls.includes('harvest') && !view.calls.includes('check'), 'Navigation starts no agent runs');
    return [{ name: 'Memory section navigation, filters, scrolling and spec entry' }];
  } finally { view.close(); }
}
