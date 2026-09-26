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
let taskRequest: CreateAgentTaskRequest | null = null;
const connection: JiraConnection = { id: 'connection', account: { accountId: 'alex', displayName: 'Alex Chen', email: 'alex@example.com' }, duplicateOf: null, state: 'connected', sites: [{ id: 'site', name: 'Engineering', url: 'https://example.atlassian.net' }], authorizationUrl: null, pairingCode: null, error: null };
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
async function until(condition: () => boolean) { for (let i = 0; i < 45 && !condition(); i++) await settle(); assert(condition(), 'UI did not settle'); }

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
  root?.unmount(); document.body.innerHTML = ''; calls.length = 0; taskRequest = null; commentFails = true;
  issue = { ...jiraIssue }; detail = jiraDetail(issue);
  window.location.hash = openIssue ? jiraRoute({ connectionId: 'connection', cloudId: 'site', issueId: issue.id }) : jiraRoute();
  const preview = createPreviewBridge('workspace');
  const fake = {
    ...preview,
    listJiraConnections: async () => [connection, { ...connection, id: 'other', account: { accountId: 'sam', displayName: 'Sam Rivera', email: 'sam@another.com' }, sites: [{ id: 'other-site', name: 'Engineering', url: 'https://another.atlassian.net' }] }],
    listJiraBoards: async (_connection, _cloud, refresh) => { if (offline && refresh) throw new Error('Jira is offline'); return [{ id: 828, name: 'Connectors', type: 'scrum' }]; },
    listJiraSprints: async (_connection, _cloud, _board, refresh) => { if (offline && refresh) throw new Error('Jira is offline'); return [{ id: 11, name: 'Connector reliability', state: 'active', startDate: null, endDate: null }]; },
    listJiraIssues: async (query, refresh) => { if (offline && refresh) throw new Error('Jira is offline'); record('issues', query); return { issues: query.completed ? completedIssues() : currentIssues(), fetchedAt: issue.updatedAt, error: null, partial: false }; },
    getJiraIssue: async (_location, refresh) => { if (offline && refresh) throw new Error('Jira is offline'); return { ...detail, issue }; },
    setJiraGoal: async (_location, isGoal) => { issue = { ...issue, isGoal }; return { ...detail, issue }; },
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
      button('Board view').click(); await settle(); assert(document.querySelectorAll('.jira-column').length === 4, 'Board keeps all workflow groups visible');
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
    async 'marks Goals and requires explicit workspace before development'() {
      await mountPreview(); const pane = document.querySelector('.jira-detail'); assert(pane !== null, 'Details exist');
      button('Mark ENG-1007 as Goal', pane).click(); await until(() => pane.querySelector('.jira-goal-active') !== null); assert(![...pane.querySelectorAll('button')].some(value => value.title === 'Use agent to develop'), 'Goal cannot start');
      button('Unmark ENG-1007 as Goal', pane).click(); await until(() => pane.querySelector('.jira-goal-active') === null);
      button('Use agent to develop ENG-1007', pane).click(); await until(() => document.querySelector('dialog[open]') !== null);
      assert(select('Workspace').value === '', 'Workspace is never inferred from active app state'); assert(document.querySelector('.agt-compose') === null, 'No composer until explicit workspace choice');
      choose(select('Workspace'), 'workspace'); await until(() => document.querySelector('.agt-compose textarea') !== null);
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
    async 'shows Jira card metadata and completed board history'() {
      await mountPreview();
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
      choose(sites, 'other/other-site'); await until(() => select('Jira site').value === 'other/other-site' && window.location.hash === '#/jira');
      await mountPreview(false, false); assert(select('Jira site').value === 'other/other-site', 'Ordinary navigation restores the selected site');
      await mountPreview(); assert(select('Jira site').value === 'connection/site', 'Task and source deep links override remembered site');
      button('Manage accounts').click(); await until(() => window.location.hash === '#/settings/integrations/jira');
      assert(!document.body.textContent?.includes('Authentication service URL'), 'Jira page links to the shared account settings');
    },
    async 'keeps cached tickets visible offline'() {
      await mountPreview(true); await until(() => document.body.textContent?.includes('Jira is offline') ?? false);
      assert(document.querySelectorAll('.jira-ticket').length === 4, 'Cached tickets remain visible after remote refresh fails');
      assert(document.querySelector('.jira-detail h2')?.textContent === jiraIssue.title, 'Cached detail remains available offline');
    },
    async 'keeps narrow detail inside the viewport'() {
      await mountPreview(); const detail = document.querySelector('.jira-detail'); assert(detail !== null, 'Detail visible'); const bounds = detail.getBoundingClientRect(); assert(bounds.width > 350 && bounds.right <= window.innerWidth + 1, 'Detail stays within native app');
    },
  })) {
    try { await test(); results.push({ name }); } catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
    root?.unmount(); root = null;
  }
  return results;
}
