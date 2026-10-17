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
import { workspaceFixture } from '../../workspaces/fixtures/workspace';
import { assert, changeInput, settle } from '../../fixtures/harness';
import type { Bridge } from '../../types';
import type { WorkItemDto } from '../../goals/contracts';
import type { UnifiedWorkItem } from '../contracts';
import { EPIC_START, takeEpicStart, type EpicStart } from '../../workspaces/epics';
import WorkItemsScreen from '../WorkItemsScreen';
import WorkspaceIssuesScreen from '../../workspaces/WorkspaceIssuesScreen';
import { workItemRoute } from '../contracts';
import '../../css/base.css';
import '../../css/pulls.css';

const workspace = workspaceFixture({
  id: 'workspace', name: 'ByteQuay', selectedOwner: 'bytequay', selectedRepo: 'bytequay', monitoredOwner: 'bytequay', monitoredRepo: 'bytequay',
  selectedRepositoryId: 1, monitoredRepositoryId: 1, localRepositoryPath: '/managed/bytequay',
});
const now = '2026-10-07T00:00:00Z';
function local(id: string, title: string, body: string): WorkItemDto {
  return { id, workspaceId: workspace.id, goalId: null, title, acceptanceCriteria: body, kind: 'DEVELOP', completionRule: 'MERGED_DELIVERY', state: 'PLANNED', revision: 0,
    links: [], history: [], createdAt: now, updatedAt: now, priority: 'NORMAL', rank: 0, blockedBy: [] };
}
function projected(work: WorkItemDto): UnifiedWorkItem {
  return { id: work.id, source: 'LOCAL', title: work.title, description: work.acceptanceCriteria, status: 'PLANNED', closed: false, updatedAt: now, reference: null, work };
}
function button(text: string) {
  const value = [...document.querySelectorAll<HTMLButtonElement>('button')].find(value => value.textContent?.trim() === text);
  assert(value != null, `Missing button ${text}`); return value;
}
function type(selector: string, text: string) {
  const input = document.querySelector<HTMLInputElement | HTMLTextAreaElement>(selector); assert(input != null, `Missing ${selector}`);
  changeInput(input, text);
}
export async function run() {
  window.location.hash = '#/workspaces/workspace/issues';
  let publications = 0;
  const publicationCount = () => publications;
  const seed = projected(local('seed', 'Improve work item context', '## Outcome\nAgents retain the request, acceptance criteria and prior attempts.\n\n- Preserve local privacy\n- Keep one identity after publication'));
  let items: UnifiedWorkItem[] = [seed,
    { id: 'github', source: 'GITHUB', title: 'Handle interrupted agent sessions', description: 'An existing GitHub request.', status: 'Open', closed: false, updatedAt: now,
      reference: { kind: 'ISSUE', key: 'bytequay/bytequay#42', label: '#42', title: 'Handle interrupted agent sessions', addedAt: now }, work: null },
    { id: 'jira', source: 'JIRA', title: 'Add repository onboarding guidance', description: 'An existing Jira request.', status: 'In progress', closed: false, updatedAt: now,
      reference: { kind: 'JIRA', key: 'connection:site:7', label: 'BQ-7', title: 'Add repository onboarding guidance', addedAt: now }, work: null }];
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    listUnifiedWorkItems: async () => items,
    listWorkItems: async () => [],
    fetchWorkspaceIssues: async () => ({ repositoryId: 1, repository: { owner: 'bytequay', repo: 'bytequay' }, issues: [], page: 0, pageSize: 50, totalIssues: 0, hasMore: false, syncedAt: now }),
    getWorkspaceMetadata: async () => { throw new Error('Metadata is not synchronized'); },
    listJiraConnections: async () => [],
    createWorkItem: async (_workspace, request) => { const work = local(request.id, request.title, request.acceptanceCriteria); items = [...items, projected(work)]; return work; },
    publishWorkItem: async (_workspace, id, request) => {
      assert(request.source === 'GITHUB' && request.expectedRevision === 0, 'Publication uses the selected destination and preview revision');
      publications++; const item = items.find(value => value.id === id); assert(item?.work != null, 'The local item exists before publication');
      const reference = { kind: 'ISSUE' as const, key: 'bytequay/bytequay#43', label: '#43', title: item.title, addedAt: now };
      const work: WorkItemDto = { ...item.work, links: [{ ...reference, state: 'IDLE', status: 'Open', blocked: false, url: null, activity: null }] };
      items = items.map(value => value.id === id ? { ...value, source: 'GITHUB', reference, work } : value);
      return work;
    },
    decideWorkItem: async (_workspace, id, command) => {
      const item = items.find(value => value.id === id); assert(item?.work != null, 'The decided item exists');
      const work: WorkItemDto = { ...item.work, state: command.action === 'DROP' ? 'DROPPED' : 'WORKING', revision: item.work.revision + 1 };
      items = items.map(value => value.id === id ? { ...value, closed: work.state === 'DROPPED', work } : value);
      return work;
    },
  } satisfies Partial<Bridge> });
  const host = document.createElement('main'); host.style.cssText = 'height:100vh'; document.body.append(host);
  createRoot(host).render(<WorkItemsScreen workspace={workspace} tab="issues" github={<WorkspaceIssuesScreen
    workspaceId={workspace.id} repository="bytequay/bytequay" initialIssueNumber={null} />} />); await settle(); await settle();
  assert([...document.querySelectorAll('[role="tab"]')].map(value => value.textContent).join('|') === 'GitHub issues|Jira|Local', 'Work items has three source tabs');
  assert(document.querySelector('.issues-page .issues-search') !== null, 'The default tab keeps the original GitHub issue UI');
  assert(document.querySelector('.work-items') === null, 'GitHub is not rendered in the local list');
  button('GitHub issues').dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true })); await settle(); await settle();
  assert(document.querySelector('.jira-screen .jira-header') !== null, 'Jira keeps the original screen under its tab');
  assert(document.querySelector('[role="tab"][aria-selected="true"]')?.textContent === 'Jira', 'Jira links select the Jira subtab');
  assert(document.activeElement === button('Jira'), 'Arrow keys move focus with the selected subtab');
  button('Local').click(); await settle(); await settle();
  const rows = () => [...document.querySelectorAll<HTMLElement>('.work-items .pull-row')];
  const states = () => document.querySelector('.work-items .pulls-tabs')?.textContent;
  assert(rows().length === 1 && rows()[0].querySelector('.card-local') !== null, 'Local lists only work kept on this Mac');
  assert(states() === 'Open 1Done 0Closed 0', `The state tabs count the work: ${states()}`);
  assert(document.querySelector('[aria-label="Work item source"]') === null, 'Source tabs replace the mixed-list filter');
  button('New').click(); await settle();
  type('.work-items__form input', 'Investigate repeated refreshes');
  type('.work-items__form textarea', 'Reproduce repeated refreshes. Done when one request is active per workspace.'); await settle();
  button('Save locally').click(); await settle(); await settle();
  assert(publicationCount() === 0 && items.length === 4, 'Creation remains local and never calls publication');
  assert(document.querySelector('.work-items .pull-detail')?.textContent?.includes('one request is active per workspace'), 'Full context survives creation and selection');
  const savedId = items[3].id;
  button('Push to remote…').click(); await settle();
  assert(publicationCount() === 0 && document.querySelector('.work-items__publish')?.textContent?.includes('one request is active per workspace'), 'Preview is reviewable before the explicit remote action');
  button('Create GitHub issue').click(); await settle(); await settle();
  assert(publicationCount() === 1 && items[3].id === savedId && items[3].source === 'GITHUB', 'Explicit publication keeps the same local identity');
  assert(rows().length === 2 && rows()[1].querySelector('.card-key')?.textContent === '#43', 'Pushed work stays in Local, naming its remote copy');
  assert(document.querySelector('.work-items__publish') === null && document.querySelector('.work-items .pr-meta')?.textContent?.includes('Local + GitHub #43') === true,
    'The pushed item says where it is visible');
  const where = document.querySelector<HTMLSelectElement>('[aria-label="Filter work items by where they are"]'); assert(where != null, 'The where filter exists');
  const show = async (value: string) => { where.value = value; where.dispatchEvent(new Event('change', { bubbles: true })); await settle(); };
  await show('GITHUB');
  assert(rows().length === 1 && states() === 'Open 1Done 0Closed 0', 'On GitHub lists only pushed work, and the tabs count what the filters leave');
  await show('LOCAL');
  assert(rows().length === 1 && rows()[0].textContent?.includes('Improve work item context') === true, 'Local only lists work that was never pushed');
  await show('');
  button('New').click(); await settle(); button('Cancel').click(); await settle();
  assert(document.querySelector('[role="tab"][aria-selected="true"]')?.textContent === 'Local', 'Closing a local draft stays in Local');
  button('GitHub issues').click(); await settle();
  window.location.hash = workItemRoute(workspace.id, 'seed'); await settle(); await settle();
  assert(document.querySelector('[role="tab"][aria-selected="true"]')?.textContent === 'Local', 'Direct work-item links select Local');
  const first = document.querySelector<HTMLButtonElement>('.work-items .pull-row .issues-row__select'); assert(first != null, 'First row exists'); first.click(); await settle();
  let start: EpicStart | null = null;
  const capture = () => { start = takeEpicStart(workspace.id); };
  window.addEventListener(EPIC_START, capture);
  button('Start development').click();
  window.removeEventListener(EPIC_START, capture);
  assert(JSON.stringify(start).includes('"itemId":"seed"'), 'Development is bound to the retained work item instead of a copied title');
  assert(JSON.stringify(start).includes('"send":true'), 'Start development starts the agent without asking the user to send a generated prompt');
  window.location.hash = workItemRoute(workspace.id, 'seed'); await settle();
  button('Close locally').click(); await settle(); await settle();
  assert(states() === 'Open 1Done 0Closed 1' && rows().length === 1, `Closed work leaves Open for its own tab: ${states()}`);
  button('Closed 1').click(); await settle();
  assert(rows().length === 1 && rows()[0].textContent?.includes('Improve work item context') === true, 'The Closed tab lists it');
  assert([...document.querySelectorAll('button')].some(value => value.textContent?.trim() === 'Reopen local work'), 'Closed work offers reopening');
  return { sources: 3, publications: publicationCount(), count: items.length };
}
