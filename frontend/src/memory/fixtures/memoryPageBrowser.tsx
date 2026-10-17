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
import { workspaceFixture } from '../../workspaces/fixtures/workspace';
import { assert, changeInput, settle } from '../../fixtures/harness';
import type { MemoryDto, MemoryPageDto, MemoryPullDto, StartMemoryHarvestRequest } from '../contracts';
import type { ContextSpecCandidateDto, ContextSpecCheckDto } from '../../repository-context/contracts';
import MemoryPage from '../MemoryPage';
import '../../css/base.css';

const workspace = workspaceFixture({
  id: 'trino', name: 'Trino', selectedOwner: 'trinodb', selectedRepo: 'trino', monitoredOwner: 'trinodb', monitoredRepo: 'trino',
  selectedRepositoryId: 1, monitoredRepositoryId: 1, localRepositoryPath: '/managed/trino',
});
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

const finding: ContextSpecCandidateDto = {
  id: 'gap-1', type: 'BUG', predicted: 'DIVERGES', title: 'Deletion-vector decoding discards the high 32 bits of row indexes',
  rule: 'If a snapshot contains logical files with records that are invalidated by a deletion vector, those records must not be returned in the output.',
  specUrl: 'https://github.com/delta-io/delta/blob/master/PROTOCOL.md#reader-requirements-for-deletion-vectors',
  code: ['plugin/trino-delta-lake/src/main/java/io/trino/plugin/deltalake/delete/DeletionVectors.java:194-214'],
  reasoning: 'The decoded index loses the upper bits.', severity: 1, duplicate: null, status: 'CONFIRMED', reason: null, dismissed: null,
  test: { path: 'plugin/trino-delta-lake/TestDeletionVectors.java', command: './mvnw test', exitCode: 1, output: 'Expected the full row index',
    source: 'assertEquals(decoded, expected);' },
  draft: { title: 'Deletion-vector decoding discards the high 32 bits of row indexes', body: 'Preserve the full row index.', labels: ['bug'] },
};
const completedCheck: ContextSpecCheckDto = {
  id: 'check-1', directory: 'plugin/trino-delta-lake/', baselineSha: 'a'.repeat(40), requestKey: 'check-key',
  specs: [finding.specUrl], checkedAgainst: [finding.specUrl], labels: [], state: 'DONE',
  candidates: [finding, { ...finding, id: 'gap-2', type: 'IMPROVEMENT', title: 'Use the specified commit timestamp in Delta Lake table changes',
    draft: { title: 'Use the specified commit timestamp in Delta Lake table changes', body: 'Use the specified commit timestamp.', labels: ['delta-lake'] } },
  { ...finding, id: 'dismissed', dismissed: 'Not a real gap', title: 'A dismissed result' },
  { ...finding, id: 'unconfirmed', status: 'NOT_REPRODUCED', title: 'An unconfirmed result', draft: null, test: null }],
  covered: ['Deletion vectors'], next: ['Commit timestamps'], reproductions: 3, maxReproductions: 3, detail: null,
  startedAt: '2026-01-01T00:00:00Z', endedAt: '2026-01-01T01:00:00Z',
};

const found: MemoryPullDto = { number: 31354, title: 'Fix checkpoint write for struct null counts', closedAt: '2026-09-20T00:00:00Z', merged: true };

export function mount(withFindings = false) {
  const calls: string[] = [];
  const opened: string[] = [];
  const copied: string[] = [];
  const searches: string[] = [];
  const harvests: StartMemoryHarvestRequest[] = [];
  let checks: ContextSpecCheckDto[] = withFindings ? [completedCheck] : [];
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async (text: string) => { copied.push(text); } } });
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    getMemoryPage: async () => page,
    getRepositoryContextSpecChecks: async () => checks,
    getRepositoryContextBuild: async () => ({ buildRequestSequence: 0, canStart: true, build: null }),
    listReviewReplays: async () => [],
    getModelSettings: async () => { throw new Error('No configured model'); },
    getRepositoryContextSpecModules: async () => { calls.push('modules'); return []; },
    getRepositoryContextSpecSources: async () => [],
    specWorkItem: async (_workspace, checkId, candidateId) => {
      calls.push(`local:${checkId}:${candidateId}`);
      return { id: 'local-finding', workspaceId: 'workspace', goalId: null, title: finding.title, kind: 'DEVELOP', acceptanceCriteria: finding.draft?.body ?? '',
        completionRule: 'MERGED_DELIVERY', state: 'PLANNED', revision: 0, links: [], history: [], createdAt: completedCheck.startedAt, updatedAt: completedCheck.startedAt,
        priority: 'NORMAL', rank: 0, blockedBy: [] };
    },
    openInAppBrowser: async (url: string) => { opened.push(url); },
    dismissRepositoryContextSpecFinding: async (_workspace, checkId, candidateId, request) => {
      calls.push('dismiss');
      checks = checks.map(check => check.id !== checkId ? check : { ...check,
        candidates: check.candidates.map(candidate => candidate.id !== candidateId ? candidate : { ...candidate, dismissed: request.reason }) });
      return checks[0];
    },
    startMemoryHarvest: async (_workspace, request) => {
      calls.push('harvest');
      harvests.push(request);
      return { started: true, pulls: 1, threads: 3, view: { buildRequestSequence: 1, canStart: true, build: null } };
    },
    searchMemoryPulls: async (_workspace, query) => { searches.push(query); return query === '#31354' ? [found] : []; },
    startRepositoryContextSpecCheck: async () => { calls.push('check'); throw new Error('Unexpected run'); },
  } satisfies Partial<typeof window.bridge> });
  const host = document.createElement('main');
  host.style.cssText = 'height:100vh';
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<MemoryPage workspace={workspace} />));
  return { host, calls, opened, copied, searches, harvests,
    showSpec: () => flushSync(() => root.render(<MemoryPage workspace={workspace} spec={{ directory: null }} />)),
    close: () => { flushSync(() => root.unmount()); host.remove(); } };
}

export async function runLearnOne() {
  const view = mount();
  // Longer than the search's pause after typing.
  const paused = () => new Promise(resolve => setTimeout(resolve, 600));
  try {
    await settle();
    view.host.querySelector<HTMLButtonElement>('#mem-tab-learn')?.click(); await settle();
    const input = view.host.querySelector<HTMLInputElement>('.mem-pull-search input');
    assert(input !== null, 'The Learn tab has a box to find one pull request');
    changeInput(input, 'check');
    changeInput(input, 'checkpoint'); await paused();
    assert(view.searches.length === 1 && view.searches[0] === 'checkpoint', 'Typing searches once, after it pauses');
    assert(view.host.textContent?.includes('No closed pull request matches “checkpoint”.') === true, 'An empty search says so');
    changeInput(input, ' #31354 '); await paused(); await settle();
    const row = view.host.querySelector<HTMLElement>('.mem-pull-search .mem-learn__item');
    assert(row !== null && row.textContent?.includes('#31354 Fix checkpoint write for struct null counts') === true && row.textContent.includes('merged'),
      'A number finds that pull request with its title');
    assert(view.calls.filter(call => call === 'harvest').length === 0, 'Finding a pull request starts nothing');
    [...row.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Learn')?.click(); await settle(); await settle();
    assert(view.harvests.length === 1 && view.harvests[0].pull === 31354 && view.harvests[0].repo === 'trinodb/trino',
      'Learn starts one run for that pull request in the monitored repository');
    assert(document.body.textContent?.includes('Learning from #31354 · 3 maintainer threads to read') === true, 'The toast says what started');
    return [{ name: 'Find one closed pull request by number or title words and learn from it' }];
  } finally { view.close(); }
}

export async function runFindings() {
  const view = mount(true);
  const openedCount = () => view.opened.length;
  try {
    await settle();
    view.host.querySelector<HTMLButtonElement>('#mem-tab-specs')?.click(); await settle();
    const panel = view.host.querySelector<HTMLElement>('#mem-panel-specs');
    assert(panel !== null, 'Spec checks panel is visible');
    const findings = () => panel.querySelectorAll<HTMLElement>('.rcp-finding');
    const button = (host: Element, label: string) => {
      const value = [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.trim() === label);
      assert(value !== undefined, 'Button exists: ' + label);
      return value;
    };
    assert(view.host.querySelector('[role="dialog"]') === null && findings().length === 2, 'Both confirmed findings appear without opening the drawer');
    assert(panel.textContent?.includes('Findings · 2') === true, 'Findings count is visible');
    assert(panel.textContent?.includes('A dismissed result') === false && panel.textContent?.includes('An unconfirmed result') === false,
      'Dismissed and unconfirmed results are not presented as findings');
    assert(openedCount() === 0, 'Rendering findings does not contact GitHub');
    button(findings()[0], 'Open local work item').click(); await settle();
    assert(view.opened.length === 0, 'A finding opens locally without contacting GitHub');
    assert(window.location.hash.includes('work=local-finding'), 'The full finding is opened as a work item');
    assert(view.calls.includes(`local:check-1:${finding.id}`), 'The selected finding supplies its durable source identity');
    button(findings()[0], 'Copy body').click(); await settle();
    assert(view.copied[0] === finding.draft?.body, 'Copy body remains available on the page');
    panel.querySelector<HTMLButtonElement>('.rcp-review')?.click(); await settle();
    const drawer = view.host.querySelector('[role="dialog"]');
    assert(drawer !== null && drawer.querySelector('.rcp-finding') === null && drawer.textContent?.includes('Covered: Deletion vectors') === true,
      'Drawer shows check details without duplicating findings');
    view.host.querySelector<HTMLButtonElement>('[aria-label="Close"]')?.click(); await settle();
    for (const remaining of [1, 0]) {
      button(findings()[0], 'Dismiss').click(); await settle();
      button(findings()[0], 'Dismiss').click(); await settle();
      assert(findings().length === remaining && panel.textContent?.includes(`Findings · ${remaining}`) === true, 'Dismiss updates the visible findings and count');
    }
    assert(panel.textContent?.includes('No confirmed findings to review.') === true, 'Last dismissal leaves a clear empty state');
    assert(openedCount() === 0, 'Dismissal publishes nothing');
    return [{ name: 'Inline spec findings and retained local work' }];
  } finally { view.close(); }
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
