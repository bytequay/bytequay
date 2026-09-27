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
import { createRoot, type Root } from 'react-dom/client';
import JiraScreen from '../JiraScreen';
import { jiraDetail, jiraIssue } from './jira';
import { jiraRoute, type JiraConnection, type JiraIssue, type JiraIssueDetail, type JiraQuery } from '../contracts';
import type { Bridge, CreateAgentTaskRequest, WorkspaceDto } from '../../types';
import { createPreviewBridge, previewTask } from '../../agents/fixtures/previewBridge';
import { assert, settle } from '../../fixtures/harness';
import '../../css/base.css';
import '../../css/pulls.css';
import '../../css/agents.css';
import '../../css/reference-card.css';

let root: Root | null = null;
const calls: { name: string; args: unknown[] }[] = [];
let issue: JiraIssue;
let detail: JiraIssueDetail;
let commentFails = true;
let assignmentFails = false;
let taskRequest: CreateAgentTaskRequest | null = null;
const connection: JiraConnection = { id: 'connection', authType: 'oauth', tokenEmail: null, account: { accountId: 'alex', displayName: 'Alex Chen', email: 'alex@example.com' }, duplicateOf: null, state: 'connected', sites: [{ id: 'site', name: 'Engineering', url: 'https://example.atlassian.net' }], authorizationUrl: null, pairingCode: null, error: null };
const record = (name: string, ...args: unknown[]) => { calls.push({ name, args }); };
const button = (label: string, scope: ParentNode = document): HTMLButtonElement => {
  const found = [...scope.querySelectorAll<HTMLButtonElement>('button')].find(value => value.getAttribute('aria-label') === label || value.textContent?.trim() === label);
  assert(found !== undefined, `Missing button: ${label}`); return found;
};
const select = (label: string): HTMLSelectElement => {
  const found = document.querySelector<HTMLSelectElement>(`select[aria-label="${label}"]`) ?? [...document.querySelectorAll('label')].find(value => value.textContent?.startsWith(label))?.querySelector('select');
  assert(found != null, `Missing selector: ${label}`); return found;
};
function choose(element: HTMLSelectElement, value: string) { Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set?.call(element, value); element.dispatchEvent(new Event('change', { bubbles: true })); }
function edit(element: HTMLInputElement | HTMLTextAreaElement, value: string) { Object.getOwnPropertyDescriptor(element instanceof HTMLInputElement ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype, 'value')?.set?.call(element, value); element.dispatchEvent(new Event('input', { bubbles: true })); }
async function until(condition: () => boolean, message = 'UI did not settle') { for (let i = 0; i < 45 && !condition(); i++) await settle(); assert(condition(), message); }
async function keyPress(keyCode: 'Enter' | 'Escape') {
  console.log(`input:${JSON.stringify({ type: 'keyDown', keyCode })}`);
  if (keyCode === 'Enter') console.log(`input:${JSON.stringify({ type: 'char', keyCode: '\r' })}`);
  console.log(`input:${JSON.stringify({ type: 'keyUp', keyCode })}`);
  await settle();
}
async function clickAt(x: number, y: number) {
  for (const type of ['mouseDown', 'mouseUp']) console.log(`input:${JSON.stringify({ type, x: Math.round(x), y: Math.round(y), button: 'left', clickCount: 1 })}`);
  await settle();
}

function currentIssues(): JiraIssue[] {
  return [issue,
    { ...issue, id: '1008', key: 'ENG-1008', title: 'Improve schema discovery for catalog listings', priority: { id: '2', name: 'Expedite' }, type: 'Task', status: { id: 'progress', name: 'In progress', category: 'indeterminate' } },
    { ...issue, id: '1009', key: 'ENG-1009', title: 'Preserve timestamps when reading Iceberg metadata', type: 'Bug', priority: { id: '9', name: 'Low' }, assignee: { accountId: 'sam', displayName: 'Sam Rivera', avatarUrl: null }, status: { id: 'review', name: 'Awaiting review', category: 'indeterminate' } },
    { ...issue, id: '1010', key: 'ENG-1010', title: 'Handle empty partitions during schema inference', type: 'Task', status: { id: 'done', name: 'Resolved', category: 'done' }, completedAt: new Date().toISOString() }];
}
function completedIssues(): JiraIssue[] {
  const now = new Date();
  const date = (days: number) => new Date(now.getFullYear(), now.getMonth(), now.getDate() + days, 10).toISOString();
  return [
    ['2001', 'Keep schema discovery stable for empty catalogs', date(0)],
    ['2002', 'Restore retry handling for expired credentials', date(-1)],
    ['2003', 'Use consistent timestamp precision in query results', date(-3)],
    ['2004', 'Reduce metadata calls when listing partitions', date(-8)],
    ['2005', 'Preserve catalog identifiers when names contain spaces', date(-30)],
    ['2006', 'Migrate legacy connector configuration', null],
  ].map(([id, title, completedAt]) => ({ ...issue, id: id ?? '', key: `ENG-${id}`, title: title ?? '', status: { id: 'done', name: 'Resolved', category: 'done' }, completedAt }));
}

export async function mountPreview(offline = false, openIssue = true) {
  root?.unmount(); document.body.innerHTML = ''; calls.length = 0; taskRequest = null; commentFails = true; assignmentFails = false;
  issue = { ...jiraIssue }; detail = jiraDetail(issue);
  window.location.hash = openIssue ? jiraRoute({ connectionId: 'connection', cloudId: 'site', issueId: issue.id }) : jiraRoute();
  const preview = createPreviewBridge('workspace');
  const fake = {
    ...preview,
    listJiraConnections: async () => [connection, { ...connection, id: 'other', account: { accountId: 'sam', displayName: 'Sam Rivera', email: 'sam@another.com' }, sites: [{ id: 'other-site', name: 'Engineering', url: 'https://another.atlassian.net' }] }],
    listJiraBoards: async (_connection, _cloud, refresh) => { if (offline && refresh) throw new Error('Jira is offline'); return [{ id: 828, name: 'Connectors', type: 'scrum' }]; },
    listJiraSprints: async (_connection, _cloud, _board, refresh) => { if (offline && refresh) throw new Error('Jira is offline'); return [{ id: 11, name: 'Connector reliability', state: 'active', startDate: null, endDate: null }]; },
    listJiraIssues: async (query, refresh) => { if (offline && refresh) throw new Error('Jira is offline'); record('issues', query, refresh); return { issues: query.completed ? completedIssues() : currentIssues(), fetchedAt: issue.updatedAt, error: null, partial: false }; },
    getJiraIssue: async (location, refresh, automatic) => { if (offline && refresh) throw new Error('Jira is offline'); record('detail', location, refresh, automatic); return { ...detail, issue }; },
    assignJiraWorkspace: async (location, workspaceId) => { record('assign', location, workspaceId); if (assignmentFails) { assignmentFails = false; throw new Error('Workspace assignment failed'); } issue = { ...issue, workspace: { id: workspaceId, name: 'Trino' } }; return { ...detail, issue }; },
    setJiraGoal: async (_location, isGoal) => { record('goal', isGoal); assert(!isGoal || issue.workspace !== null, 'Goal requires a saved workspace'); issue = { ...issue, isGoal }; return { ...detail, issue }; },
    updateJiraIssue: async (_location, fields, expectedUpdatedAt) => { record('fields', fields, expectedUpdatedAt); issue = { ...issue, labels: fields.labels as string[] }; return { ...detail, issue }; },
    transitionJiraIssue: async (_location, id, fields, expectedUpdatedAt) => { record('transition', id, fields, expectedUpdatedAt); const transition = detail.transitions.find(value => value.id === id); assert(transition !== undefined, 'Known transition required'); issue = { ...issue, status: transition.to }; return { ...detail, issue }; },
    commentJiraIssue: async (_location, body, operationId) => { record('comment', body, operationId); if (commentFails) { commentFails = false; throw new Error('Connection lost; draft retained'); } detail = { ...detail, comments: [...detail.comments, { id: 'c2', body, author: { accountId: 'me', displayName: 'Alex Chen', avatarUrl: null }, created: issue.updatedAt, updated: issue.updatedAt }] }; return { ...detail, issue }; },
    moveJiraIssue: async (_location, sprintId, boardId, expectedUpdatedAt) => { record('sprint', sprintId, boardId, expectedUpdatedAt); return { ...detail, issue }; },
    listWorkspaces: async () => [{ id: 'workspace', name: 'Trino', monitoredOwner: 'trinodb', monitoredRepo: 'trino' } as WorkspaceDto],
    createAgentTask: async request => { taskRequest = request; return previewTask('workspace', request.taskId); },
    openInAppBrowser: async url => { record('browser', url); },
    openExternal: async url => { record('external', url); },
  } satisfies Partial<Bridge>;
  window.bridge = fake as unknown as Bridge;
  const host = document.createElement('div'); host.style.height = '100vh'; document.body.append(host); root = createRoot(host);
  const mounted = root; flushSync(() => mounted.render(<JiraScreen />));
  await until(() => (!openIssue || document.querySelector('.jira-detail h2') !== null) && document.querySelectorAll('.jira-ticket').length === 4 && select('Sprint').value === '11');
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  for (const [name, test] of Object.entries({
    async 'loads current sprint and edits supported fields'() {
      await mountPreview();
      assert(calls.some(call => call.name === 'issues' && (call.args[0] as { sprintId: number; mine: boolean }).sprintId === 11 && (call.args[0] as { mine: boolean }).mine), 'Active sprint must use the assigned-to-me filter');
      assert(document.querySelectorAll('.jira-column').length === 4, 'Board keeps all workflow groups visible behind the ticket');
      button('Edit fields').click(); await settle();
      const labels = document.querySelector<HTMLInputElement>('input[placeholder="Comma-separated labels"]'); assert(labels !== null, 'Label editor exists'); edit(labels, 'connectors, regression, regression'); await settle();
      button('Save changes').click(); await until(() => calls.some(call => call.name === 'fields'));
      const update = calls.find(call => call.name === 'fields'); assert(JSON.stringify((update?.args[0] as { labels: string[] }).labels) === '["connectors","regression"]', 'Labels are trimmed and unique'); assert(update?.args[1] === jiraIssue.updatedAt, 'Updates carry snapshot version');
      choose(select('Move to sprint'), 'backlog'); await settle(); button('Move ticket').click(); await until(() => calls.some(call => call.name === 'sprint')); assert(calls.find(call => call.name === 'sprint')?.args[0] === null, 'Backlog is explicit');
    },
    async 'requires transition fields and retains a failed comment operation'() {
      await mountPreview(); choose(select('Change status'), 'complex'); await settle(); assert(button('Complete with approvals').disabled, 'Complex required fields must be completed in Jira'); assert(document.body.textContent?.includes('must be completed in Jira') ?? false, 'Unsupported field explains how to complete the transition'); choose(select('Change status'), 'done'); await settle(); assert(button('Mark done').disabled, 'Required resolution prevents incomplete transition');
      choose(select('Resolution'), '1'); await settle(); button('Mark done').click(); await until(() => calls.some(call => call.name === 'transition'));
      const transition = calls.find(call => call.name === 'transition'); assert((transition?.args[1] as { resolution: string }).resolution === '1', 'Chosen transition fields are sent');
      const composer = document.querySelector<HTMLTextAreaElement>('textarea[placeholder="Write a reply…"]'); assert(composer !== null, 'Comment composer exists'); edit(composer, 'The fix is ready for review.'); await settle(); button('Send comment').click(); await until(() => document.body.textContent?.includes('Connection lost') ?? false);
      assert(composer.value === 'The fix is ready for review.', 'Failed comment retains draft'); button('Send comment').click(); await until(() => composer.value === '');
      const attempts = calls.filter(call => call.name === 'comment'); assert(attempts.length === 2 && attempts[0].args[1] === attempts[1].args[1], 'Retry retains operation identity');
    },
    async 'assigns a workspace before Goal marking and preserves cancellation and failures'() {
      await mountPreview(false, false);
      const card = document.querySelector('.jira-ticket'); assert(card !== null, 'Card exists');
      const assignment = () => document.querySelector<HTMLDialogElement>('dialog[aria-label="Assign workspace for ENG-1007"][open]');
      button('Mark ENG-1007 as Goal', card).click(); await until(() => assignment() !== null);
      assert(document.querySelector('.jira-detail') === null, 'Goal action does not also open ticket details');
      assert(select('Workspace').value === '' && button('Assign workspace').disabled, 'Workspace is not inferred and confirmation requires a selection');
      await keyPress('Escape'); await until(() => assignment() === null);
      assert(issue.workspace === null && !issue.isGoal && !calls.some(call => call.name === 'assign' || call.name === 'goal'), 'Cancel leaves assignment and Goal untouched');
      button('Mark ENG-1007 as Goal', card).click(); await until(() => assignment() !== null);
      choose(select('Workspace'), 'workspace'); await settle();
      assert(!calls.some(call => call.name === 'assign' || call.name === 'goal'), 'Selecting a workspace waits for explicit confirmation');
      assignmentFails = true; button('Assign workspace').click(); await until(() => assignment()?.textContent?.includes('Workspace assignment failed') ?? false);
      assert(select('Workspace').value === 'workspace' && issue.workspace === null && !issue.isGoal && !calls.some(call => call.name === 'goal'), 'Failed assignment retains the choice without marking Goal');
      button('Assign workspace').click(); await until(() => card.querySelector('.jira-goal-active') !== null && assignment() === null);
      const workspace = card.querySelector('.jira-workspace'); assert(workspace?.textContent?.includes('Trino') ?? false, 'Assigned workspace appears on the card');
      const workspaceBounds = workspace?.getBoundingClientRect(); assert(workspaceBounds !== undefined, 'Workspace badge is visible');
      assert(workspace?.contains(document.elementFromPoint(workspaceBounds.left + workspaceBounds.width / 2, workspaceBounds.top + workspaceBounds.height / 2)) ?? false, 'Workspace hover tooltip remains reachable');
      assert(![...card.querySelectorAll('button')].some(value => value.title === 'Use agent to develop'), 'Goal cannot start');
      button('Unmark ENG-1007 as Goal', card).click(); await until(() => card.querySelector('.jira-goal-active') === null);
      button('Mark ENG-1007 as Goal', card).click(); await until(() => card.querySelector('.jira-goal-active') !== null);
      assert(assignment() === null && calls.filter(call => call.name === 'assign').length === 2, 'Saved workspace is reused for later Goal actions');
    },
    async 'retains development workspace on cancellation and launches with the saved assignment'() {
      await mountPreview(); const pane = document.querySelector('.jira-detail'); assert(pane !== null, 'Details exist');
      button('Use agent to develop ENG-1007', pane).click(); await until(() => document.querySelector('dialog[aria-label="Assign workspace for ENG-1007"][open]') !== null);
      assert(select('Workspace').value === '', 'Workspace is never inferred from active app state'); assert(document.querySelector('.agt-compose') === null, 'No composer until explicit workspace assignment');
      choose(select('Workspace'), 'workspace'); await settle(); button('Assign workspace').click(); await until(() => document.querySelector('.agt-compose textarea') !== null);
      assert(calls.find(call => call.name === 'assign')?.args[1] === 'workspace', 'Explicit workspace assignment is persisted before composer opens');
      button('Close development').click(); await until(() => document.querySelector('.agt-compose') === null);
      assert(pane.querySelector('.jira-workspace')?.textContent?.includes('Trino') && document.querySelector('.jira-ticket .jira-workspace')?.textContent?.includes('Trino'), 'Composer cancellation retains workspace on detail and card');
      button('Use agent to develop ENG-1007', pane).click(); await until(() => document.querySelector('.agt-compose textarea') !== null);
      assert(document.querySelector('dialog[aria-label^="Assign workspace"]') === null && calls.filter(call => call.name === 'assign').length === 1, 'Later development reuses the assigned workspace');
      assert(document.querySelector('.agt-issue-attachment strong')?.textContent === 'ENG-1007', 'Source stays above composer');
      const instructions = document.querySelector<HTMLTextAreaElement>('.agt-compose textarea'); assert(instructions !== null, 'Task input exists'); edit(instructions, 'Implement the ticket and preserve compatibility.'); await settle();
      issue = { ...issue, title: 'Updated title from Jira', updatedAt: '2026-09-26T09:00:00Z' };
      button('Refresh development source').click(); await until(() => document.querySelector('.agt-issue-attachment')?.textContent?.includes('Updated title') ?? false);
      assert(instructions.value === 'Implement the ticket and preserve compatibility.', 'Source refresh preserves user instructions');
      const send = document.querySelector<HTMLButtonElement>('.agt-compose button[aria-label="Start task"]') ?? document.querySelector<HTMLButtonElement>('.agt-send'); assert(send !== null, 'Task send exists');
      await until(() => !send.disabled); send.click(); await until(() => taskRequest !== null);
      const created = taskRequest as unknown as CreateAgentTaskRequest; assert(created.workspaceId === 'workspace' && created.jiraSource?.issueId === '1007' && created.issueSource === undefined, 'Canonical task request carries Jira metadata and chosen workspace');
      assert(created.jiraSource?.updatedAt === '2026-09-26T09:00:00Z', 'Launch uses the deliberately refreshed source');
      assert(!created.message.includes('https://'), 'User prompt does not direct remote ticket fetching');
    },
    async 'suspends Jira modals for the embedded browser without losing drafts or assignment'() {
      await mountPreview();
      const mounted = root; assert(mounted !== null, 'Screen is mounted');
      const browserOpen = async (open: boolean) => { flushSync(() => mounted.render(<JiraScreen browserOpen={open} />)); await settle(); };
      const pane = document.querySelector<HTMLDialogElement>('dialog.jira-detail'); assert(pane !== null, 'Ticket detail exists');
      const reply = pane.querySelector<HTMLTextAreaElement>('textarea[placeholder="Write a reply…"]'); assert(reply !== null, 'Comment draft exists');
      edit(reply, 'Keep this reply while I check Jira.'); await settle();
      button('Open ENG-1007 in Jira', pane).click(); await until(() => calls.some(call => call.name === 'browser' && call.args[0] === jiraIssue.url));
      await browserOpen(true);
      assert(!pane.open && !pane.matches(':modal') && document.querySelector('dialog:modal') === null, 'Embedded browser is not blocked by the ticket modal');
      await browserOpen(false);
      assert(pane.matches(':modal') && document.querySelector('dialog.jira-detail') === pane && reply.value === 'Keep this reply while I check Jira.', 'Returning restores the same ticket and unsent reply');
      button('Mark ENG-1007 as Goal', pane).click(); await until(() => document.querySelector('dialog[aria-label="Assign workspace for ENG-1007"][open]') !== null);
      choose(select('Workspace'), 'workspace'); await settle();
      await browserOpen(true); assert(document.querySelector('dialog:modal') === null, 'Workspace assignment also releases the embedded browser');
      await browserOpen(false); assert(select('Workspace').value === 'workspace', 'Returning retains the pending workspace choice');
      const assignment = document.querySelector('dialog[aria-label="Assign workspace for ENG-1007"]'); assert(assignment !== null, 'Workspace assignment remains mounted');
      const choiceBounds = select('Workspace').getBoundingClientRect();
      assert(assignment.contains(document.activeElement) && assignment.contains(document.elementFromPoint(choiceBounds.left + choiceBounds.width / 2, choiceBounds.top + choiceBounds.height / 2)), 'Restored workspace assignment stays above the underlying ticket');
      button('Assign workspace').click(); await until(() => pane.querySelector('.jira-goal-active') !== null);
      button('Unmark ENG-1007 as Goal', pane).click(); await until(() => pane.querySelector('.jira-goal-active') === null);
      button('Use agent to develop ENG-1007', pane).click(); await until(() => document.querySelector('.agt-compose textarea') !== null);
      const launch = document.querySelector<HTMLDialogElement>('dialog[aria-label="Develop ENG-1007"]'); assert(launch !== null, 'Development dialog exists');
      const instructions = launch.querySelector<HTMLTextAreaElement>('.agt-compose textarea'); assert(instructions !== null, 'Development draft exists');
      edit(instructions, 'Keep the partition column predicates intact.'); await settle();
      await browserOpen(true); assert(document.querySelector('dialog:modal') === null, 'Both development and underlying ticket release the browser');
      await browserOpen(false);
      assert(launch.matches(':modal') && document.querySelector('dialog[aria-label="Develop ENG-1007"]') === launch && instructions.value === 'Keep the partition column predicates intact.', 'Returning restores the same development dialog and draft');
      assert(launch.contains(document.activeElement), 'Restored development dialog stays above the underlying ticket');
      assert(reply.value === 'Keep this reply while I check Jira.' && launch.querySelector('.jira-workspace')?.textContent?.includes('Trino'), 'Ticket reply and workspace survive the full browser round trip');
    },
    async 'shows Jira card metadata and completed board history'() {
      await mountPreview(false, false);
      assert(document.querySelector('.jira-header h1')?.textContent === 'Jira', 'Native page is named Jira');
      const columns = [...document.querySelectorAll('.jira-column h2')].map(value => value.firstChild?.textContent);
      assert(JSON.stringify(columns) === '["To do","In progress","In review","Done"]', 'All four groups are visible together');
      assert(document.querySelector('.jira-column .jira-status') !== null && document.body.textContent?.includes('Awaiting review'), 'Actual workflow status survives grouping');
      const card = document.querySelector('.jira-ticket'); assert(card !== null, 'Card exists');
      assert(card.querySelector('.jira-ticket__title')?.textContent === jiraIssue.title, 'Card title leads');
      assert(card.querySelector('[aria-label="Priority: High"]') !== null && card.querySelector('[aria-label="Assignee: Alex Chen"]')?.textContent === 'AC', 'Card exposes priority and assigned person');
      assert(document.querySelector('[aria-label="Priority: Expedite"]')?.classList.contains('is-neutral') ?? false, 'Tenant-specific priority ID never defines severity');
      button('Open board in Jira').click(); await settle(); assert(calls.some(call => call.name === 'browser' && call.args[0] === 'https://example.atlassian.net/secure/RapidBoard.jspa?rapidView=828'), 'Open board uses the selected site and board');
      button('Completed').click(); await until(() => document.querySelectorAll('.jira-history-group').length > 0);
      assert(calls.some(call => call.name === 'issues' && (call.args[0] as JiraQuery).completed && (call.args[0] as JiraQuery).boardId === 828), 'Completed requests board history across sprint boundaries');
      const groups = [...document.querySelectorAll('.jira-history-group h2')].map(value => value.firstChild?.textContent);
      assert(groups.includes('Today') && groups.includes('Yesterday') && groups.includes('Earlier') && groups.includes('Completion date unavailable'), 'Completion groups expose recent and unknown dates');
      assert(document.querySelectorAll('.jira-ticket').length === 6 && !document.querySelector('.jira-items button[title="Use agent to develop"]'), 'Completed cards do not offer new development');
      button('Current sprint').click(); await until(() => document.querySelectorAll('.jira-column').length === 4);
      button('List view').click(); await settle(); assert(document.querySelector('.jira-items.is-list') !== null, 'List remains available');
      button('ENG-1009').click(); await until(() => window.location.hash.includes('issueId=1009'));
    },
    async 'remembers a valid account and site while source links retain their binding'() {
      await mountPreview(); const sites = select('Jira site');
      assert(sites.options[0].text.includes('alex@example.com') && sites.options[1].text.includes('sam@another.com') && sites.options[1].text.includes('another.atlassian.net'), 'Same-name sites identify account and domain');
      button('Close ticket').click(); await until(() => document.querySelector('.jira-detail') === null);
      choose(sites, 'other/other-site'); await until(() => select('Jira site').value === 'other/other-site' && window.location.hash === '#/jira');
      await mountPreview(false, false); assert(select('Jira site').value === 'other/other-site', 'Ordinary navigation restores the selected site');
      await mountPreview(); assert(select('Jira site').value === 'connection/site', 'Task and source deep links override remembered site');
      button('Close ticket').click(); await until(() => document.querySelector('.jira-detail') === null);
      button('Manage accounts').click(); await until(() => window.location.hash === '#/settings/integrations/jira');
      assert(!document.body.textContent?.includes('Authentication service URL'), 'Jira page links to the shared account settings');
    },
    async 'filters workspace tickets and retains workspace navigation'() {
      await mountPreview(false, false);
      const workspace = { id: 'workspace / one', name: 'Trino' };
      issue = { ...issue, workspace };
      const listIssues = window.bridge.listJiraIssues;
      window.bridge.listJiraIssues = async (...args) => {
        const page = await listIssues(...args);
        return { ...page, issues: page.issues.map((value, index) => ({ ...value, workspace: index === 0 ? workspace : index === 1 ? { id: 'other', name: 'Other' } : null })) };
      };
      window.location.hash = jiraRoute(undefined, workspace.id); await settle();
      const mounted = root; assert(mounted !== null, 'Screen is mounted');
      flushSync(() => mounted.render(<JiraScreen workspace={workspace} />));
      button('Refresh tickets').click(); await until(() => document.querySelectorAll('.jira-ticket').length === 1);
      assert(document.querySelector('.jira-header p')?.textContent === 'Tickets assigned to Trino.', 'Workspace scope is visible');
      assert(document.querySelector('.jira-ticket__key')?.textContent === 'ENG-1007', 'Current sprint excludes other-workspace and unassigned tickets');
      assert(JSON.stringify([...select('Filter status').options].map(option => option.value)) === '["","todo"]', 'Status options contain only workspace tickets');
      button('List view').click(); await settle();
      assert(document.querySelectorAll('.jira-items.is-list .jira-ticket').length === 1, 'List uses the same workspace scope');
      button('ENG-1007').click(); await until(() => document.querySelector('.jira-detail h2') !== null);
      assert(window.location.hash === jiraRoute({ connectionId: 'connection', cloudId: 'site', issueId: '1007' }, workspace.id), 'Opening a ticket keeps its workspace route');
      button('Close ticket').click(); await until(() => document.querySelector('.jira-detail') === null);
      assert(window.location.hash === jiraRoute(undefined, workspace.id), 'Closing a ticket returns to workspace Jira');
      button('Connector reliability', document.querySelector('.jira-ticket') ?? document).click(); await until(() => document.querySelector('.jira-detail h2') !== null);
      assert(window.location.hash === jiraRoute({ connectionId: 'connection', cloudId: 'site', issueId: '1000' }, workspace.id), 'Parent links retain the workspace route');
      button('Close ticket').click(); await until(() => document.querySelector('.jira-detail') === null);
      button('Completed').click(); await until(() => document.querySelectorAll('.jira-history-group .jira-ticket').length === 1);
      assert(document.querySelector('.jira-ticket__key')?.textContent === 'ENG-2001', 'Completed history also excludes other-workspace and unassigned tickets');
      choose(select('Jira site'), 'other/other-site'); await until(() => select('Jira site').value === 'other/other-site');
      assert(window.location.hash === jiraRoute(undefined, workspace.id), 'Site changes retain the workspace route');
    },
    async 'keeps cached tickets visible offline'() {
      await mountPreview(true); await until(() => document.body.textContent?.includes('Jira is offline') ?? false);
      assert(document.querySelectorAll('.jira-ticket').length === 4, 'Cached tickets remain visible after remote refresh fails');
      assert(document.querySelector('.jira-detail h2')?.textContent === jiraIssue.title, 'Cached detail remains available offline');
    },
    async 'marks automatic detail refresh and keeps its board update local'() {
      const interval = window.setInterval;
      const hidden = Object.getOwnPropertyDescriptor(document, 'hidden');
      let poll: (() => void) | undefined;
      window.setInterval = ((handler: TimerHandler, delay?: number, ...args: unknown[]) => {
        if (delay === 60_000 && typeof handler === 'function') { poll = () => handler(...args); return -1; }
        return interval(handler, delay, ...args);
      }) as typeof window.setInterval;
      Object.defineProperty(document, 'hidden', { configurable: true, value: false });
      try {
        await mountPreview(); await settle(); calls.length = 0;
        assert(poll !== undefined, 'Visible ticket schedules automatic refresh');
        issue = { ...issue, title: 'Cached ticket updated during quiet hours' };
        poll(); await until(() => calls.some(call => call.name === 'issues')); await settle();
        assert(calls.some(call => call.name === 'detail' && call.args[1] === true && call.args[2] === true), 'Timer labels detail request as automatic for the backend quiet-hours check');
        assert(!calls.some(call => call.name === 'issues' && call.args[1] === true), 'Cached automatic detail never triggers a remote board refresh');
        assert(document.querySelector('.jira-detail h2')?.textContent === issue.title && document.querySelector('.jira-ticket__title')?.textContent === issue.title, 'Cached detail still updates both visible ticket surfaces');
        calls.length = 0; button('Refresh ticket').click();
        await until(() => calls.some(call => call.name === 'issues' && call.args[1] === true)); await settle();
        assert(calls.some(call => call.name === 'detail' && call.args[1] === true && call.args[2] !== true), 'Explicit ticket refresh remains a manual remote request');
        button('Close ticket').click(); await until(() => document.querySelector('.jira-detail') === null);
        calls.length = 0; button('Refresh tickets').click();
        await until(() => calls.some(call => call.name === 'issues' && call.args[1] === true));
      }
      finally {
        root?.unmount(); root = null; window.setInterval = interval;
        if (hidden) Object.defineProperty(document, 'hidden', hidden); else Reflect.deleteProperty(document, 'hidden');
      }
    },
    async 'opens the whole card as a centered modal with keyboard and focus restoration'() {
      await mountPreview(false, false);
      const card = document.querySelector<HTMLElement>('.jira-ticket'); assert(card !== null, 'Card exists');
      const title = card.querySelector<HTMLButtonElement>('.jira-ticket__title'); assert(title !== null, 'Card has a native keyboard button');
      for (const selector of ['.jira-assignee', '.jira-priority']) {
        const target = card.querySelector<HTMLElement>(selector); assert(target !== null, 'Card metadata exists');
        const bounds = target.getBoundingClientRect();
        assert(target.contains(document.elementFromPoint(bounds.left + bounds.width / 2, bounds.top + bounds.height / 2)), 'Whole-card click target preserves native metadata hover tooltips');
      }
      const before = document.querySelector('.jira-items')?.getBoundingClientRect(); assert(before !== undefined, 'Board exists');
      const cardBounds = card.getBoundingClientRect();
      await clickAt(cardBounds.left + cardBounds.width / 2, cardBounds.bottom - 8);
      await until(() => document.querySelector('dialog.jira-detail[open] h2') !== null, 'Clicking the bottom of the card opens ticket details');
      const modal = document.querySelector<HTMLDialogElement>('dialog.jira-detail[open]'); assert(modal !== null, 'Whole-card click opens native modal');
      assert(modal.getAttribute('aria-label') === 'Ticket details' && modal.matches(':modal'), 'Ticket details are an accessible modal');
      await Promise.all(modal.getAnimations().map(animation => animation.finished));
      const bounds = modal.getBoundingClientRect(); const board = document.querySelector('.jira-items')?.getBoundingClientRect();
      assert(bounds.width > window.innerWidth * 0.75 && bounds.left > 0 && bounds.right < window.innerWidth && Math.abs(bounds.left - (window.innerWidth - bounds.right)) < 2, 'Ticket expands into a large centered dialog');
      assert(bounds.top >= 0 && bounds.bottom <= window.innerHeight + 1 && board?.width === before.width, 'Modal stays within viewport without narrowing the board');
      await keyPress('Escape'); await until(() => document.querySelector('dialog.jira-detail[open]') === null, 'Escape closes ticket details');
      assert(document.activeElement === title, 'Escape restores focus to the card');
      await keyPress('Enter'); await until(() => document.querySelector('dialog.jira-detail[open] h2') !== null, 'Enter activates the focused card');
      button('Close ticket').click(); await until(() => document.querySelector('dialog.jira-detail[open]') === null);
      assert(document.activeElement === title, 'Close restores focus to the keyboard trigger');
      button('Connector reliability', card).click(); await until(() => window.location.hash.includes('issueId=1000'));
      assert(!window.location.hash.includes('issueId=1007'), 'Parent ticket action remains independent of card click');
    },
  })) {
    try { await test(); results.push({ name }); } catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
    root?.unmount(); root = null;
  }
  return results;
}
