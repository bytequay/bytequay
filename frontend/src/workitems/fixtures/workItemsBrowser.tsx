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
import { assert, changeInput, settle, until } from '../../fixtures/harness';
import type { Bridge } from '../../types';
import type { EpicRecordDto, WorkItemDto } from '../../goals/contracts';
import type { UnifiedWorkItem } from '../contracts';
import { EPIC_START, takeEpicStart, type EpicStart } from '../../workspaces/epics';
import WorkItemsScreen, { LocalWorkItems } from '../WorkItemsScreen';
import { WorkItemPrompt } from '../../agents/WorkItemPrompt';
import { ZOOM_REFERENCE, type ZoomedReference } from '../../githubReference';
import { GOALS_CHANGED } from '../../goals/useGoals';
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
  return { id, workspaceId: workspace.id, isEpic: false, goalId: null, title, acceptanceCriteria: body, kind: 'DEVELOP', completionRule: 'MERGED_DELIVERY', state: 'PLANNED', revision: 0,
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

export async function localEpic() {
  const epicId = 'LOCAL:parent';
  window.location.hash = workItemRoute(workspace.id, 'parent');
  const parent = local('parent', 'Private connector cleanup', 'Coordinate connector fixes without publishing an issue.');
  let items = [projected(parent), projected({ ...local('existing', 'Fix the Hive parser', 'Retain slash field names.'), epicId: 'LOCAL:other' }),
    projected({ ...local('other', 'Earlier local Epic', ''), isEpic: true })];
  let record: EpicRecordDto = { id: epicId, children: [], synced: [], former: [], childrenReadAt: null, childrenTruncated: false, offered: false,
    concludedAt: null, outcome: '', proposals: [], breakingDown: false, breakdownError: null, breakdownNote: null };
  const calls: string[] = [];
  const workItems = () => items.flatMap(item => item.work === null ? [] : [item.work]);
  const bridge: Partial<Bridge> = {
    listUnifiedWorkItems: async () => items,
    listWorkItems: async () => workItems(),
    setLocalWorkItemEpic: async (_workspace, id, isEpic) => {
      calls.push(`mark ${id} ${isEpic}`);
      items = items.map(item => item.work === null ? item : { ...item, work: { ...item.work,
        ...(item.id === id ? { isEpic } : {}), ...(!isEpic && item.work.epicId === epicId ? { epicId: null } : {}) } });
    },
    assignWorkItemEpic: async (_workspace, itemId, target) => {
      calls.push(`assign ${itemId} ${target}`);
      const item = workItems().find(item => item.id === itemId); assert(item !== undefined, 'The assigned item exists');
      const joined = { ...item, epicId: target };
      items = items.map(value => value.id === itemId ? { ...value, work: joined } : value);
      return { item: joined, movedFrom: item.epicId ?? null };
    },
    createWorkItem: async (_workspace, request) => {
      calls.push(`create ${request.title} ${request.epicId}`);
      const work = { ...local(request.id, request.title, request.acceptanceCriteria), epicId: request.epicId };
      items = [...items, projected(work)]; return work;
    },
    getEpic: async () => record,
    refreshEpicChildren: async (_workspace, id) => { calls.push(`read ${id}`); return record; },
    searchGoalCandidates: async () => [],
    concludeEpic: async (_workspace, id, outcome) => { calls.push(`conclude ${id}`); record = { ...record, concludedAt: now, outcome }; return record; },
    publishWorkItem: async (_workspace, id) => {
      calls.push(`publish ${id}`);
      const item = items.find(item => item.id === id); assert(item?.work != null, 'The published Epic exists');
      const reference = { kind: 'ISSUE' as const, key: 'bytequay/bytequay#50', label: '#50', title: item.title, addedAt: now };
      const work: WorkItemDto = { ...item.work, links: [{ ...reference, state: 'IDLE', status: 'Open', blocked: false, url: null, activity: null }] };
      items = items.map(value => value.id === id ? { ...value, source: 'GITHUB', reference, work } : value);
      return work;
    },
    listJiraConnections: async () => [],
  };
  Object.defineProperty(window, 'bridge', { configurable: true, value: bridge });
  const host = document.createElement('main'); host.style.cssText = 'height:100vh'; document.body.append(host);
  const root = createRoot(host);
  root.render(<LocalWorkItems workspace={workspace} />); await settle(); await settle();
  button('Mark as Epic').click();
  await until(() => document.querySelector('.epic-view__title')?.textContent === parent.title, 'Marking a local item opens its Work tab');
  assert(calls.includes('mark parent true') && calls.includes(`read ${epicId}`) && !calls.some(call => call.startsWith('publish')), 'Marking and reading the Epic stay local');
  assert(document.querySelector('[aria-label="Start development of Private connector cleanup"]') === null, 'An Epic is not offered as an executable task');
  assert([...document.querySelectorAll('button')].every(value => value.textContent?.trim() !== 'Start development'), 'The parent detail cannot start development');
  button('Add existing').click(); await settle();
  const result = document.querySelector<HTMLElement>('.epic-view__results .goal-result'); assert(result !== null, 'Existing local work is available to add');
  assert(result.textContent?.includes('Fix the Hive parser') === true && !document.querySelector('.epic-view__results')?.textContent?.includes('Earlier local Epic'), 'Epics cannot be nested');
  result.querySelector<HTMLButtonElement>('button')?.click(); await settle(); await settle();
  assert(calls.includes(`assign existing ${epicId}`) && document.querySelector('.epic-view__notice')?.textContent?.includes('Moved here') === true, 'Existing local work moves into this Epic');
  type('.epic-view__new input', 'Add an Iceberg regression test');
  document.querySelector<HTMLFormElement>('.epic-view__new')?.requestSubmit(); await settle(); await settle();
  assert(calls.includes(`create Add an Iceberg regression test ${epicId}`), 'Quick add creates a child under the stable local Epic ID');

  button('New').click(); await settle();
  const epicSelect = [...document.querySelectorAll<HTMLSelectElement>('.work-items__form select')].find(select => select.options[0]?.text === 'No epic');
  assert(epicSelect !== undefined && [...epicSelect.options].some(option => option.value === epicId && option.text === parent.title), 'The creation form offers local Epics without relying on Home');
  button('Cancel').click(); await settle();
  window.location.hash = workItemRoute(workspace.id, 'parent'); await settle(); await settle();
  document.querySelector<HTMLButtonElement>('[aria-label="Push Private connector cleanup to GitHub or Jira"]')?.click(); await settle();
  assert(document.querySelector('.work-items__publish') !== null && !calls.some(call => call.startsWith('publish')), 'The row publication action opens the preview even when the Epic Work tab was selected');
  button('Create GitHub issue').click(); await settle(); await settle();
  assert(items.find(item => item.id === 'parent')?.work?.isEpic === true && workItems().filter(item => item.epicId === epicId).length === 2, 'Publication preserves the Epic marker and children');
  const workTab = document.querySelector<HTMLButtonElement>('.epic-tabs button:last-child'); assert(workTab !== null, 'The published local Epic retains its Work tab');
  workTab.click(); await settle();
  assert(document.querySelector('.epic-view__counts')?.textContent?.includes('0 of 2 done') === true, 'Publication keeps progress under the local identity');

  const promptHost = document.createElement('div'); document.body.append(promptHost); const promptRoot = createRoot(promptHost);
  let zoomed: ZoomedReference | null = null;
  const onZoom = (event: Event) => { zoomed = (event as CustomEvent<ZoomedReference>).detail; };
  window.addEventListener(ZOOM_REFERENCE, onZoom);
  promptRoot.render(<WorkItemPrompt workspaceId={workspace.id} itemId="existing" prompt="Fix the Hive parser"><span>Fallback prompt</span></WorkItemPrompt>);
  await until(() => promptHost.querySelector('.agt-work-request__epic a') !== null, 'The task request resolves its local parent after publication');
  (promptHost.querySelector('.agt-work-request__epic a') as HTMLAnchorElement).click();
  assert(JSON.stringify(zoomed) === JSON.stringify({ kind: 'work', workspaceId: workspace.id, itemId: 'parent' }), 'The task opens the local Epic instead of its published issue');
  promptRoot.unmount(); promptHost.remove();
  window.location.hash = workItemRoute(workspace.id, 'existing'); await settle(); await settle();
  const parentLink = [...document.querySelectorAll<HTMLButtonElement>('.work-items__dev-row button')].find(value => value.textContent?.includes(parent.title));
  assert(parentLink !== undefined, 'A child shows its local Epic'); zoomed = null; parentLink.click();
  assert(JSON.stringify(zoomed) === JSON.stringify({ kind: 'work', workspaceId: workspace.id, itemId: 'parent' }), 'The child detail opens its local Epic after publication');
  window.removeEventListener(ZOOM_REFERENCE, onZoom);
  assert(document.querySelector<HTMLButtonElement>('[aria-label="Mark as Epic"]')?.disabled === true, 'A child cannot itself become an Epic');

  items = items.map(item => item.work?.epicId === epicId ? { ...item, work: { ...item.work, state: 'DONE' } } : item);
  window.dispatchEvent(new CustomEvent(GOALS_CHANGED, { detail: workspace.id }));
  window.location.hash = workItemRoute(workspace.id, 'parent'); await settle(); await settle();
  button('Conclude Epic').click(); await settle(); button('Conclude').click(); await settle(); await settle();
  assert(calls.includes(`conclude ${epicId}`) && document.querySelector('.epic-view__concluded') !== null, 'Local Epics conclude with an outcome');
  assert(document.querySelector('.epic-view__concluded')?.textContent?.includes('GitHub') === false, 'Concluding a local Epic does not offer an unrelated remote close action');
  document.querySelector<HTMLButtonElement>('[aria-label="Unmark as Epic"]')?.click(); await settle(); await settle();
  assert(document.querySelector('dialog')?.textContent?.includes('2 work items leave the Epic') === true, 'Unmarking previews the local membership changes');
  button('Unmark').click(); await settle(); await settle();
  assert(calls.includes('mark parent false') && workItems().filter(item => item.epicId === epicId).length === 0 && items.length === 4, 'Unmarking preserves all work and removes only the grouping');
  assert(document.querySelector('.epic-tabs') === null && document.querySelector('[aria-label="Mark as Epic"]') !== null, 'Unmarked work returns to its normal detail');
  root.unmount(); host.remove();
  return true;
}
