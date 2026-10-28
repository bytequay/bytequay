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
import { prDetail } from '../../pulls/fixtures/pullRequest';
import { assert, changeInput, settle, until, element } from '../../fixtures/harness';
import type { Bridge } from '../../types';
import type { EpicRecordDto, WorkItemDto, UnifiedWorkItem, WorkItemContextLink } from '../contracts';
import { EPIC_START, takeEpicStart, type EpicStart } from '../../workspaces/epics';
import WorkItemsScreen, { LocalWorkItems } from '../WorkItemsScreen';
import { WorkItemPrompt } from '../../agents/WorkItemPrompt';
import { ZOOM_REFERENCE, type ZoomedReference } from '../../githubReference';
import { GOALS_CHANGED } from '../changes';
import WorkspaceIssuesScreen from '../../workspaces/WorkspaceIssuesScreen';
import { workItemRoute } from '../contracts';
import '../../css/base.css';
import '../../css/pulls.css';
import '../../css/reference-card.css';

const workspace = workspaceFixture({
  id: 'workspace', name: 'Example', selectedOwner: 'example-org', selectedRepo: 'example-repo', monitoredOwner: 'example-org', monitoredRepo: 'example-repo',
  selectedRepositoryId: 1, monitoredRepositoryId: 1, localRepositoryPath: '/managed/example-repo',
});
const now = '2026-10-07T00:00:00Z';
function local(id: string, title: string, body: string): WorkItemDto {
  return { id, workspaceId: workspace.id, isEpic: false, contextLinks: [], title, acceptanceCriteria: body, kind: 'DEVELOP', completionRule: 'MERGED_DELIVERY', state: 'PLANNED', revision: 0,
    links: [], history: [], createdAt: now, updatedAt: now, priority: 'NORMAL', blockedBy: [] };
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

export async function markdownHistory() {
  const criteria = '### Description\nOriginal scope for `ExampleComponent` and #42.\n\n- Preserve **expected behavior**.\n\n'
    + '```text\n' + 'sample '.repeat(100) + '#42\n```\n\n| Check | Expected |\n| --- | --- |\n| Cleanup | Resources released |';
  const work: WorkItemDto = { ...local('markdown', 'Updated work title', '### Current context\nUpdated scope referencing #42 and unavailable #404.'), history: [
    { operationId: 'created', action: 'CREATED', detail: `Work item accepted: Original work title\nSuccess: ${criteria}\nCompletion: Merged delivery`, at: now },
    { operationId: 'receipt', action: 'RECEIPT', detail: 'Checks failed on #42: **unit-tests**', at: '2026-10-07T01:00:00Z' },
  ], links: [{ kind: 'PULL_REQUEST', key: 'example-org/example-repo#42', label: '#42', title: 'Delivered example fix', addedAt: now, state: 'MERGED', status: 'Merged',
    blocked: false, url: 'https://github.com/example-org/example-repo/pull/42', activity: null }] };
  const pull = prDetail({ repo: 'example-org/example-repo', number: 42, title: 'Fix example behavior', author: 'example-user',
    htmlUrl: 'https://github.com/example-org/example-repo/pull/42', body: '### Verification\nPreserves **example behavior**.' });
  const reads: number[] = [];
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    listUnifiedWorkItems: async () => [projected(work)], listWorkItems: async () => [work], listWorkspaces: async () => [workspace],
    fetchWorkspaceIssue: async () => { throw new Error('Not a cached issue'); },
    fetchGitHubIssuePreview: async (owner, repo, number) => {
      assert(`${owner}/${repo}` === 'example-org/example-repo', 'Bare references use the monitored repository, not the selected fork');
      if (number !== pull.number) throw new Error('Unavailable');
      return { title: pull.title ?? '', body: pull.body ?? '', author: pull.author ?? '', labels: [], updatedAt: now, state: 'open', pullRequest: true, htmlUrl: pull.htmlUrl ?? '', authorAvatarUrl: '' };
    },
    fetchPullRequestDetailByRef: async (repo, number) => {
      assert(repo === pull.repo, 'Pull references retain their repository'); reads.push(number);
      if (number !== pull.number) throw new Error('Unavailable');
      return pull;
    },
    listJiraConnections: async () => [],
  } satisfies Partial<Bridge> });
  window.location.hash = workItemRoute(workspace.id, work.id);
  const host = document.createElement('main'); host.style.cssText = 'height:100vh'; document.body.append(host);
  const root = createRoot(host);
  const zooms: ZoomedReference[] = [];
  const capture = (event: Event) => zooms.push((event as CustomEvent<ZoomedReference>).detail);
  window.addEventListener(ZOOM_REFERENCE, capture);
  try {
    root.render(<LocalWorkItems workspace={{ ...workspace, selectedOwner: 'fork' }} />);
    await until(() => host.querySelectorAll('a[href$="/pull/42"]').length === 3 && reads.includes(404), 'Description and history resolve pull titles');
    const description = element<HTMLElement>('.pr-description > .markdown-body', host);
    const history = element<HTMLElement>('.work-items__history', host);
    const details = [...history.querySelectorAll<HTMLElement>('.work-items__event-detail')];
    assert(details[0]?.textContent?.startsWith('Checks failed on ') === true && details[1]?.textContent?.includes('Original work title') === true,
      'History shows the latest event first and creation last');
    assert(work.history[0].operationId === 'created' && work.history[1].operationId === 'receipt', 'Displaying history does not mutate its saved order');
    assert(description.querySelector('h3')?.textContent === 'Current context', 'The current description renders Markdown');
    assert(history.querySelector('h3')?.textContent === 'Description' && history.textContent?.includes('Original work title') === true
      && !history.textContent?.includes('Updated scope'), 'Creation renders its original Markdown snapshot, not the edited description');
    assert(history.querySelector('li strong')?.textContent === 'expected behavior' && history.querySelector('code')?.textContent === 'ExampleComponent',
      'History renders lists, emphasis and inline code');
    assert(history.querySelector('pre')?.textContent?.includes('#42') === true && history.querySelector('pre a') === null, 'Fenced references stay literal code');
    assert([...host.querySelectorAll('a[href$="/pull/42"]')].every(link => link.textContent === `${pull.title} #42`), 'References display the title and number');
    assert(element<HTMLAnchorElement>('a[href$="/issues/404"]', description).textContent === '#404', 'Unavailable references retain their number');
    assert(reads.filter(number => number === pull.number).length === 1, 'Repeated references share their preview request');
    const pane = element<HTMLElement>('.pull-detail', host); pane.style.width = '360px'; await settle();
    assert(details.every(detail => detail.scrollWidth <= detail.clientWidth + 1) && pane.scrollWidth <= pane.clientWidth + 1,
      'Markdown tables and long code remain inside a narrow detail pane');
    const link = element<HTMLAnchorElement>('a[href$="/pull/42"]', history);
    link.scrollIntoView({ block: 'center' }); await settle();
    link.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
    await until(() => document.querySelector('.ref-card__title')?.textContent === pull.title, 'Hover opens the pull request card');
    assert(document.querySelector('.ref-card h3')?.textContent === 'Verification' && document.querySelector('.ref-card strong')?.textContent === 'example behavior',
      'The hover card renders the pull description');
    link.click(); await settle();
    assert(JSON.stringify(zooms) === JSON.stringify([{ kind: 'pull', repo: pull.repo, number: pull.number }]), 'Clicking the reference opens the native pull detail');
    assert(document.querySelector('.ref-card') === null, 'Opening the reference closes the hover card');
    link.blur(); link.focus({ preventScroll: true });
    assert(link.isConnected && document.activeElement === link, 'The keyboard reference remains connected and focusable');
    // The hidden Electron window updates activeElement without dispatching native focus events.
    link.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
    await until(() => document.querySelector('.ref-card__title')?.textContent === pull.title, 'Keyboard focus also opens the preview');
    link.blur();
    button('Delivered example fix').click();
    assert(JSON.stringify(zooms.at(-1)) === JSON.stringify({ kind: 'pull', repo: pull.repo, number: pull.number, workspaceId: workspace.id }),
      'The Development pull request opens the native pull detail');
    button('Push to remote…').click();
    await until(() => host.querySelector('.work-items__preview a[href$="/pull/42"]') !== null, 'The publication preview resolves the same references');
    return true;
  }
  finally { window.removeEventListener(ZOOM_REFERENCE, capture); root.unmount(); host.remove(); }
}

export async function contextLinks() {
  const reference = { kind: 'ISSUE' as const, key: 'example-org/example-repo#42', label: '#42', title: 'Published context owner', addedAt: now };
  let work: WorkItemDto = { ...local('owner', reference.title, 'Retained acceptance criteria.'), revision: 4, epicId: 'LOCAL:parent', blockedBy: ['blocker'],
    contextLinks: [{ targetId: 'ISSUE:github.com:50', title: 'Before publication' }, { targetId: 'removed', title: 'Removed reference' }],
    links: [{ ...reference, state: 'IDLE', status: 'Open', blocked: false, url: null, activity: null },
      { kind: 'TASK', key: 'attempt', label: 'Task', title: 'Existing attempt', addedAt: now, state: 'ENDED', status: 'Completed', blocked: false, url: null, activity: null }] };
  const retained = JSON.stringify({ links: work.links, epicId: work.epicId, blockedBy: work.blockedBy });
  const related: UnifiedWorkItem[] = [projected(local('local-reference', 'Local design notes', 'Details.')),
    { id: 'ISSUE:github.com:43', source: 'GITHUB', title: 'GitHub regression request', description: 'Details.', status: 'Open', closed: false, updatedAt: now,
      reference: { ...reference, key: 'other/repository#43', label: '#43', title: 'GitHub regression request' }, work: null },
    { id: 'JIRA:connection:site:7', source: 'JIRA', title: 'Jira compatibility request', description: 'Details.', status: 'In progress', closed: false, updatedAt: now,
      reference: { kind: 'JIRA', key: 'connection:site:7', label: 'BQ-7', title: 'Jira compatibility request', addedAt: now }, work: null },
    { ...projected(local('published-reference', 'Published local reference', 'Details.')), source: 'GITHUB', reference: { ...reference, key: 'example-org/example-repo#50', label: '#50' } }];
  const owner = (): UnifiedWorkItem => ({ ...projected(work), source: 'GITHUB', reference });
  const contextCount = () => work.contextLinks.length;
  const requests: string[] = [];
  const catalogReads: boolean[] = [];
  let failRead = true;
  let failWrite = false;
  let importedVisible = false;
  const summaries = (): WorkItemContextLink[] => work.contextLinks.map(link => {
    const target = related.find(item => item.id === (link.targetId === 'ISSUE:github.com:50' ? 'published-reference' : link.targetId));
    return { targetId: link.targetId, id: target?.id ?? link.targetId, source: target?.source ?? 'LOCAL', title: target?.title ?? link.title,
      status: target?.status ?? 'Unavailable', available: target !== undefined,
      reference: target?.reference?.kind === 'ISSUE' || target?.reference?.kind === 'JIRA' ? { ...target.reference, kind: target.reference.kind } : null };
  });
  const mutate = (revision: number, targetId: string, remove: boolean) => {
    assert(revision === work.revision, 'Context changes use the current work revision');
    if (failWrite) { failWrite = false; throw new Error('Work changed; refresh before editing context.'); }
    requests.push(`${remove ? 'unlink' : 'link'} ${targetId}`);
    const title = related.find(item => item.id === targetId)?.title ?? '';
    work = { ...work, revision: work.revision + 1,
      contextLinks: remove ? work.contextLinks.filter(link => link.targetId !== targetId) : [...work.contextLinks, { targetId, title }] };
    return work;
  };
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    listUnifiedWorkItems: async (_workspace, localOnly = false) => {
      catalogReads.push(localOnly);
      return [owner(), ...related.map(item => !importedVisible && item.id === 'published-reference'
        ? { ...item, id: 'ISSUE:github.com:50', work: null } : item)].filter(item => !localOnly || item.work !== null);
    }, listWorkItems: async () => [work],
    listWorkItemContextLinks: async () => { if (failRead) { failRead = false; throw new Error('Context lookup failed'); } return summaries(); },
    linkWorkItemContext: async (_workspace, id, revision, targetId) => { assert(id === 'owner', 'The retained owner receives links'); return mutate(revision, targetId, false); },
    unlinkWorkItemContext: async (_workspace, _id, revision, targetId) => mutate(revision, targetId, true),
  } satisfies Partial<Bridge> });
  window.location.hash = workItemRoute(workspace.id, work.id);
  const host = document.createElement('main'); host.style.cssText = 'height:100vh'; document.body.append(host);
  const root = createRoot(host);
  const zooms: ZoomedReference[] = [];
  const capture = (event: Event) => zooms.push((event as CustomEvent<ZoomedReference>).detail);
  const openContext = (title: string) => {
    const link = [...document.querySelectorAll<HTMLButtonElement>('.work-items__context-links > .work-items__dev-row .work-items__link')]
      .find(value => value.textContent === title);
    assert(link !== undefined, `Missing context link ${title}`); link.click();
  };
  window.addEventListener(ZOOM_REFERENCE, capture);
  try {
    root.render(<LocalWorkItems workspace={workspace} />);
    await until(() => document.querySelector('.work-items__context-links [role="alert"]') !== null, 'Failed lookup is visible');
    assert(catalogReads.every(localOnly => localOnly), 'Opening local work does not load the remote catalog');
    assert(document.querySelector('[aria-label="Unlink Before publication"]') !== null, 'Saved links can still be removed when lookup fails');
    button('Retry').click();
    await until(() => document.querySelector('.work-items__context-links')?.textContent?.includes('Published local reference') === true, 'Retry resolves current titles');
    assert(document.querySelector('.work-items__context-links')?.textContent?.includes('Unavailable') === true, 'Unavailable targets retain their saved label');
    openContext('Published local reference');
    assert(JSON.stringify(zooms.at(-1)) === JSON.stringify({ kind: 'work', workspaceId: workspace.id, itemId: 'published-reference' }), 'Resolved local context opens its retained detail even before the catalog catches up');
    importedVisible = true;
    window.dispatchEvent(new CustomEvent(GOALS_CHANGED, { detail: workspace.id })); await settle(); await settle();
    button('Link work item').click(); await settle();
    assert(catalogReads.includes(false), 'Opening the context picker loads remote work on demand');
    const picker = () => document.querySelector('.work-items__context-picker');
    assert(!picker()?.textContent?.includes(work.title) && !picker()?.textContent?.includes('Published local reference'), 'Picker excludes self and resolved aliases already linked');
    type('[aria-label="Search work to link"]', 'BQ-7'); await settle();
    assert(picker()?.querySelectorAll('.work-items__dev-row').length === 1 && picker()?.textContent?.includes('Jira compatibility request') === true, 'Search matches source keys');
    type('[aria-label="Search work to link"]', ''); await settle();
    failWrite = true;
    document.querySelector<HTMLButtonElement>('[aria-label="Link Local design notes"]')?.click();
    await until(() => document.querySelector('.work-items__context-links')?.textContent?.includes('Work changed; refresh') === true, 'Revision errors keep the picker available');
    assert(contextCount() === 2, 'Rejected writes do not add a local phantom link');
    for (const title of ['Local design notes', 'GitHub regression request', 'Jira compatibility request']) {
      document.querySelector<HTMLButtonElement>(`[aria-label="Link ${title}"]`)?.click();
      await until(() => [...document.querySelectorAll('.work-items__context-links > .work-items__dev-row .work-items__link')].some(value => value.textContent === title), `${title} is linked`);
    }
    assert(picker()?.textContent?.includes('No other work items to link.') === true, 'Linked candidates leave the picker');
    button('Done linking').click(); await settle();
    openContext('Local design notes'); openContext('GitHub regression request'); openContext('Jira compatibility request');
    assert(JSON.stringify(zooms.slice(-3)) === JSON.stringify([
      { kind: 'work', workspaceId: workspace.id, itemId: 'local-reference' },
      { kind: 'issue', workspaceId: workspace.id, repo: 'other/repository', number: 43 },
      { kind: 'jira', connectionId: 'connection', cloudId: 'site', issueId: '7' },
    ]), `Each source opens the correct native detail: ${JSON.stringify(zooms.slice(-3))}`);
    document.querySelector<HTMLButtonElement>('[aria-label="Unlink Published local reference"]')?.click();
    await until(() => !document.querySelector('[aria-label="Unlink Published local reference"]'), 'Unlink removes the saved alias');
    document.querySelector<HTMLButtonElement>('[aria-label="Unlink Removed reference"]')?.click();
    await until(() => !document.querySelector('[aria-label="Unlink Removed reference"]'), 'Unavailable context can be removed');
    assert(requests.includes('unlink ISSUE:github.com:50') && requests.includes('unlink removed'), 'Unlink uses saved keys, even after target publication or removal');
    assert(JSON.stringify({ links: work.links, epicId: work.epicId, blockedBy: work.blockedBy }) === retained, 'Context changes preserve source/attempt ownership, Epic membership and blockers');
    assert(contextCount() === 3 && related.length === 4, 'Unlink leaves the target work items intact');
    return true;
  }
  finally { window.removeEventListener(ZOOM_REFERENCE, capture); root.unmount(); host.remove(); }
}

export async function run() {
  window.location.hash = '#/workspaces/workspace/issues';
  let publications = 0;
  const publicationCount = () => publications;
  const seed = projected(local('seed', 'Improve work item context', '## Outcome\nAgents retain the request, acceptance criteria and prior attempts.\n\n- Preserve local privacy\n- Keep one identity after publication'));
  let items: UnifiedWorkItem[] = [seed,
    { id: 'github', source: 'GITHUB', title: 'Handle interrupted agent sessions', description: 'An existing GitHub request.', status: 'Open', closed: false, updatedAt: now,
      reference: { kind: 'ISSUE', key: 'example-org/example-repo#42', label: '#42', title: 'Handle interrupted agent sessions', addedAt: now }, work: null },
    { id: 'jira', source: 'JIRA', title: 'Add repository onboarding guidance', description: 'An existing Jira request.', status: 'In progress', closed: false, updatedAt: now,
      reference: { kind: 'JIRA', key: 'connection:site:7', label: 'BQ-7', title: 'Add repository onboarding guidance', addedAt: now }, work: null }];
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    listUnifiedWorkItems: async () => items,
    listWorkItems: async () => [],
    fetchWorkspaceIssues: async () => ({ repositoryId: 1, repository: { owner: 'example-org', repo: 'example-repo' }, issues: [], page: 0, pageSize: 50, totalIssues: 0, hasMore: false, syncedAt: now }),
    getWorkspaceMetadata: async () => { throw new Error('Metadata is not synchronized'); },
    listJiraConnections: async () => [],
    createWorkItem: async (_workspace, request) => { const work = local(request.id, request.title, request.acceptanceCriteria); items = [...items, projected(work)]; return work; },
    publishWorkItem: async (_workspace, id, request) => {
      assert(request.source === 'GITHUB' && request.expectedRevision === 0, 'Publication uses the selected destination and preview revision');
      publications++; const item = items.find(value => value.id === id); assert(item?.work != null, 'The local item exists before publication');
      const reference = { kind: 'ISSUE' as const, key: 'example-org/example-repo#43', label: '#43', title: item.title, addedAt: now };
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
    workspaceId={workspace.id} repository="example-org/example-repo" initialIssueNumber={null} />} />); await settle(); await settle();
  assert([...document.querySelectorAll('[role="tab"]')].map(value => value.textContent).join('|') === 'Local|GitHub issues|Jira', 'Local is the leftmost source tab');
  assert(document.querySelector('[role="tab"][aria-selected="true"]')?.textContent === 'Local' && document.querySelector('.work-items') !== null, 'Work items opens on Local by default');
  button('Local').dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true })); await settle(); await settle();
  assert(document.querySelector('[role="tab"][aria-selected="true"]')?.textContent === 'GitHub issues', 'The GitHub tab remains selectable');
  assert(document.querySelector('.issues-page .issues-search') !== null, 'GitHub keeps the original issue UI');
  assert(document.querySelector('.work-items') === null, 'GitHub is not rendered in the local list');
  button('GitHub issues').dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true })); await settle(); await settle();
  assert(document.querySelector('.jira-screen .jira-header') !== null, 'Jira keeps the original screen under its tab');
  assert(document.querySelector('[role="tab"][aria-selected="true"]')?.textContent === 'Jira', 'Jira links select the Jira subtab');
  assert(document.activeElement === button('Jira'), 'Arrow keys move focus with the selected subtab');
  button('Jira').dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true })); await settle(); await settle();
  assert(document.activeElement === button('Local'), 'Home moves focus to the leftmost Local tab');
  window.location.hash = '#/workspaces/workspace/issues/42'; await settle(); await settle();
  assert(document.querySelector('[role="tab"][aria-selected="true"]')?.textContent === 'GitHub issues', 'Direct GitHub issue links select GitHub');
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

export async function finishLocalWork() {
  let work = local('finish', 'Finish the connector cleanup', 'Keep the original completion rule.');
  const decisions: string[] = [];
  let failFinish = true;
  let pauseFinish = true;
  const pending: { resolve?: () => void } = {};
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    listUnifiedWorkItems: async () => [{ ...projected(work), status: work.state, closed: work.state === 'DONE' || work.state === 'DROPPED' }],
    listWorkItems: async () => [work],
    decideWorkItem: async (_workspace, id, command) => {
      assert(id === work.id && command.expectedRevision === work.revision, 'Decisions use the retained item and current revision');
      assert(command.action === 'FINISH' || command.action === 'REOPEN' || command.action === 'DROP', 'Only explicit lifecycle actions are sent');
      decisions.push(command.action);
      if (command.action === 'FINISH') {
        assert(command.completionRule === undefined, 'Finishing does not replace the completion rule');
        if (failFinish) { failFinish = false; throw new Error('Unable to save finished status'); }
        if (pauseFinish) { await new Promise<void>(resolve => { pending.resolve = resolve; }); pauseFinish = false; }
      }
      work = { ...work, state: command.action === 'FINISH' ? 'DONE' : command.action === 'DROP' ? 'DROPPED' : 'WORKING', revision: work.revision + 1,
        history: [...work.history, { operationId: command.operationId, action: command.action, detail: command.detail, at: now }] };
      return work;
    },
  } satisfies Partial<Bridge> });
  window.location.hash = workItemRoute(workspace.id, work.id);
  const host = document.createElement('main'); host.style.cssText = 'height:100vh'; document.body.append(host);
  const root = createRoot(host);
  const states = () => document.querySelector('.work-items .pulls-tabs')?.textContent;
  const status = () => document.querySelector('.work-items .pr-state')?.textContent?.trim();
  const rows = () => document.querySelectorAll('.work-items .pull-row');
  try {
    root.render(<LocalWorkItems workspace={workspace} />); await settle(); await settle();
    const pane = element<HTMLElement>('.work-items .pull-detail');
    const width = pane.style.width; pane.style.width = '360px'; await settle();
    const bounds = pane.getBoundingClientRect();
    assert([...pane.querySelectorAll('.pull-detail__header-actions button')].every(control => {
      const rectangle = control.getBoundingClientRect(); return rectangle.left >= bounds.left && rectangle.right <= bounds.right;
    }), 'All detail actions remain inside a narrow work-item pane');
    pane.style.width = width; await settle();
    button('Mark finished').click();
    await until(() => document.querySelector('[role="alert"]')?.textContent === 'Unable to save finished status', 'Save failures are visible');
    assert(states() === 'Open 1Done 0Closed 0' && !button('Mark finished').disabled, 'A failed finish leaves work open and can be retried');
    button('Mark finished').click(); await settle();
    assert(button('Mark finished').disabled && button('Close locally').disabled, 'Lifecycle actions are disabled while finishing');
    button('Mark finished').click(); await settle();
    assert(decisions.length === 2 && states() === 'Open 1Done 0Closed 0', 'Pending clicks do not send duplicate decisions or show unsaved completion');
    assert(pending.resolve !== undefined, 'The finish request is waiting'); pending.resolve();
    await until(() => status() === 'Finished', 'Successful completion shows Finished');
    assert(states() === 'Open 0Done 1Closed 0' && rows().length === 0, 'Finished work leaves Open and counts under Done');
    assert(document.querySelector('[role="alert"]') === null, 'Retry clears the failed save error');
    assert([...document.querySelectorAll('button')].every(value => value.textContent?.trim() !== 'Mark finished'), 'Finished work does not offer completion again');
    assert(document.querySelector('.work-items__history')?.textContent?.includes('Marked this finished') === true, 'History names the completion action');
    assert(element<HTMLSelectElement>('[aria-label="Done when"]').value === 'MERGED_DELIVERY', 'The completion rule remains unchanged');
    button('Done 1').click(); await settle();
    assert(rows().length === 1 && document.querySelector('.work-items__state')?.getAttribute('title') === 'Finished', 'Done lists the item with its Finished status');
    button('Reopen local work').click();
    await until(() => states() === 'Open 1Done 0Closed 0', 'Finished work can be reopened');
    button('Open 1').click(); await settle();
    button('Close locally').click();
    await until(() => status() === 'Closed', 'Closing still records the separate Closed state');
    assert(states() === 'Open 0Done 0Closed 1', 'Closed work remains separate from Done');
    button('Closed 1').click(); await settle();
    assert(rows().length === 1, 'The Closed tab lists closed work');
    button('Mark finished').click();
    await until(() => status() === 'Finished', 'Closed work can be marked Finished directly');
    assert(states() === 'Open 0Done 1Closed 0' && rows().length === 0, 'Finishing closed work moves it from Closed to Done');
    assert(decisions.join(',') === 'FINISH,FINISH,REOPEN,DROP,FINISH', 'Only the intended lifecycle decisions were sent');
    return true;
  }
  finally { root.unmount(); host.remove(); }
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
      const reference = { kind: 'ISSUE' as const, key: 'example-org/example-repo#50', label: '#50', title: item.title, addedAt: now };
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
  const result = element<HTMLElement>('.epic-view__results .goal-result', document, 'Existing local work is available to add');
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
  const workTab = element<HTMLButtonElement>('.epic-tabs button:last-child', document, 'The published local Epic retains its Work tab');
  workTab.click(); await settle();
  assert(document.querySelector('.epic-view__counts')?.textContent?.includes('0 of 2 done') === true, 'Publication keeps progress under the local identity');

  const promptHost = document.createElement('div'); document.body.append(promptHost); const promptRoot = createRoot(promptHost);
  let zoomed: ZoomedReference | null = null;
  const onZoom = (event: Event) => { zoomed = (event as CustomEvent<ZoomedReference>).detail; };
  window.addEventListener(ZOOM_REFERENCE, onZoom);
  promptRoot.render(<WorkItemPrompt workspaceId={workspace.id} itemId="existing"><span>Fallback prompt</span></WorkItemPrompt>);
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
