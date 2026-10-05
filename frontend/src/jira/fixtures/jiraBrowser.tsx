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
import JiraScreen, { JiraTicketLayer } from '../JiraScreen';
import { LayerCovered } from '../../Zoom';
import { jiraDetail, jiraIssue } from './jira';
import { jiraRoute, type JiraConnection, type JiraIssue, type JiraIssueDetail, type JiraQuery, type JiraUser } from '../contracts';
import type { Bridge, CreateAgentTaskRequest, WorkspaceDto } from '../../types';
import { createPreviewBridge, previewTask } from '../../agents/fixtures/previewBridge';
import { assert, settle } from '../../fixtures/harness';
import { ZOOM_REFERENCE } from '../../githubReference';
import { loadHandled, visibleAttention } from '../../home/attention';
import { loadPinned } from '../../home/pinned';
import { jiraAttentionItem } from '../attention';
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
const workspaces = [
  { id: 'workspace', name: 'Trino', monitoredOwner: 'trinodb', monitoredRepo: 'trino' },
  { id: 'other-workspace', name: 'Connectors', monitoredOwner: 'example', monitoredRepo: 'connectors' },
] as WorkspaceDto[];
const record = (name: string, ...args: unknown[]) => { calls.push({ name, args }); };
const button = (label: string, scope: ParentNode = document): HTMLButtonElement => {
  const found = [...scope.querySelectorAll<HTMLButtonElement>('button')].find(value => value.getAttribute('aria-label') === label || value.textContent?.trim() === label);
  assert(found !== undefined, `Missing button: ${label}`); return found;
};
const assignButton = (): HTMLButtonElement => {
  const dialog = document.querySelector('dialog.jira-assignment[open]'); assert(dialog !== null, 'Workspace chooser is open');
  return button('Assign workspace', dialog);
};
const select = (label: string): HTMLSelectElement => {
  const found = document.querySelector<HTMLSelectElement>(`select[aria-label="${label}"]`) ?? [...document.querySelectorAll('label')].find(value => value.textContent?.startsWith(label))?.querySelector('select');
  assert(found != null, `Missing selector: ${label}`); return found;
};
function choose(element: HTMLSelectElement, value: string) { Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set?.call(element, value); element.dispatchEvent(new Event('change', { bubbles: true })); }
function edit(element: HTMLInputElement | HTMLTextAreaElement, value: string) { Object.getOwnPropertyDescriptor(element instanceof HTMLInputElement ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype, 'value')?.set?.call(element, value); element.dispatchEvent(new Event('input', { bubbles: true })); }
async function until(condition: () => boolean, message = 'UI did not settle') { for (let i = 0; i < 45 && !condition(); i++) await settle(); assert(condition(), message); }
const cards = () => document.querySelectorAll('.jira-card');
const scope = (): HTMLButtonElement => { const found = document.querySelector<HTMLButtonElement>('.jira-scope'); assert(found !== null, 'Scope control exists'); return found; };
const menuItem = (text: string): HTMLButtonElement => {
  const found = [...document.querySelectorAll<HTMLButtonElement>('.jira-pop .jira-menu__item')].find(value => value.textContent?.includes(text));
  assert(found !== undefined, `Missing menu item: ${text}`); return found;
};
const siteItems = () => [...document.querySelectorAll<HTMLButtonElement>('.jira-scope-menu section[aria-label="Site"] .jira-menu__item')];
/** The site the scope menu marks as chosen, read by opening and closing it. */
async function chosenSite(): Promise<string> {
  scope().click(); await settle();
  const text = siteItems().find(value => value.getAttribute('aria-pressed') === 'true')?.textContent ?? '';
  scope().click(); await settle();
  return text;
}
const statusFilter = (): HTMLButtonElement => {
  const found = [...document.querySelectorAll<HTMLButtonElement>('.jira-chip-button')].find(value => value.textContent?.startsWith('Status'));
  assert(found !== undefined, 'Status filter exists'); return found;
};
async function filterStatus(group: string) { statusFilter().click(); await settle(); menuItem(group).click(); await settle(); }
const press = (element: EventTarget, value: string, init: KeyboardEventInit = {}) => element.dispatchEvent(new KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true, ...init }));
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
    { ...issue, id: '1008', key: 'ENG-1008', title: 'Improve schema discovery for catalog listings', priority: { id: '2', name: 'Expedite' }, type: 'Task', status: { id: 'progress', name: 'In progress', category: 'indeterminate' }, task: { id: 'task-1008', workspaceId: 'workspace' }, workspace: { id: 'workspace', name: 'Trino' } },
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

export async function mountPreview(offline = false, openIssue = true, body = jiraIssue.body) {
  root?.unmount(); document.body.innerHTML = ''; calls.length = 0; taskRequest = null; commentFails = true; assignmentFails = false;
  issue = { ...jiraIssue, body }; detail = jiraDetail(issue);
  window.location.hash = openIssue ? jiraRoute({ connectionId: 'connection', cloudId: 'site', issueId: issue.id }) : jiraRoute();
  const preview = createPreviewBridge('workspace');
  const fake = {
    ...preview,
    // No Epic here holds work, so unmarking needs no confirmation.
    listWorkItems: async () => [],
    listJiraConnections: async () => [connection, { ...connection, id: 'other', account: { accountId: 'sam', displayName: 'Sam Rivera', email: 'sam@another.com' }, sites: [{ id: 'other-site', name: 'Engineering', url: 'https://another.atlassian.net' }] }],
    listJiraBoards: async (_connection, _cloud, refresh) => { if (offline && refresh) throw new Error('Jira is offline'); return [{ id: 828, name: 'Connectors', type: 'scrum' }]; },
    listJiraSprints: async (_connection, _cloud, _board, refresh) => { if (offline && refresh) throw new Error('Jira is offline'); return [{ id: 11, name: 'Connector reliability', state: 'active', startDate: null, endDate: null }]; },
    listJiraIssues: async (query, refresh) => { if (offline && refresh) throw new Error('Jira is offline'); record('issues', query, refresh); return { issues: query.completed ? completedIssues() : currentIssues(), fetchedAt: issue.updatedAt, error: null, nextPageToken: null }; },
    recordJiraRecentTouch: async () => {},
    getJiraIssue: async (location, refresh, automatic) => { if (offline && refresh) throw new Error('Jira is offline'); record('detail', location, refresh, automatic); return { ...detail, issue }; },
    assignJiraWorkspace: async (location, workspaceId) => { record('assign', location, workspaceId); if (assignmentFails) { assignmentFails = false; throw new Error('Workspace assignment failed'); } const workspace = workspaces.find(value => value.id === workspaceId); assert(workspace !== undefined, 'Selected workspace exists'); issue = { ...issue, workspace: { id: workspace.id, name: workspace.name } }; return { ...detail, issue }; },
    setJiraEpic: async (_location, isEpic) => { record('epic', isEpic); assert(!isEpic || issue.workspace !== null, 'Epic requires a saved workspace'); issue = { ...issue, isEpic }; return { ...detail, issue }; },
    updateJiraIssue: async (_location, fields, expectedUpdatedAt) => { record('fields', fields, expectedUpdatedAt); issue = { ...issue, labels: fields.labels as string[] }; return { ...detail, issue }; },
    transitionJiraIssue: async (_location, id, fields, expectedUpdatedAt) => { record('transition', id, fields, expectedUpdatedAt); const transition = detail.transitions.find(value => value.id === id); assert(transition !== undefined, 'Known transition required'); issue = { ...issue, status: transition.to }; return { ...detail, issue }; },
    commentJiraIssue: async (_location, body, operationId, parentId) => { record('comment', body, operationId, parentId); if (commentFails) { commentFails = false; throw new Error('Connection lost; draft retained'); } detail = { ...detail, comments: [...detail.comments, { id: `posted-${detail.comments.length}`, parentId: parentId ?? null, body, author: { accountId: 'me', displayName: 'Alex Chen', avatarUrl: null }, created: issue.updatedAt, updated: issue.updatedAt, mine: true, editableText: body }] }; return { ...detail, issue }; },
    editJiraComment: async (_location, commentId, body, expectedUpdated) => { record('edit', commentId, body, expectedUpdated); detail = { ...detail, comments: detail.comments.map(value => value.id === commentId ? { ...value, body, editableText: body, updated: issue.updatedAt } : value) }; return { ...detail, issue }; },
    moveJiraIssue: async (_location, sprintId, boardId, expectedUpdatedAt) => { record('sprint', sprintId, boardId, expectedUpdatedAt); return { ...detail, issue }; },
    listJiraChildTypes: async () => [{ id: '10003', name: 'Subtask' }, { id: '10004', name: 'Bug subtask' }],
    createJiraChild: async (_location, issueTypeId, summary) => { record('child', issueTypeId, summary); detail = { ...detail, children: [...detail.children, { id: '1030', key: 'ENG-1030', title: summary, type: 'Subtask', status: { id: 'todo', name: 'To do', category: 'new' }, priority: null, assignee: null }] }; issue = { ...issue, hasChildren: true }; return { ...detail, issue }; },
    listJiraLinkTypes: async () => [{ id: '10000', name: 'Blocks', inward: 'is blocked by', outward: 'blocks' }, { id: '10003', name: 'Relates', inward: 'relates to', outward: 'relates to' }],
    linkJiraIssue: async (_location, typeId, outward, key) => { record('link', typeId, outward, key); detail = { ...detail, links: [...detail.links, { id: 'l3', relation: outward ? 'blocks' : 'is blocked by', issue: { id: '1013', key, title: 'Upgrade the metastore client', type: 'Task', status: { id: 'todo', name: 'To do', category: 'new' }, priority: null, assignee: null } }] }; return { ...detail, issue }; },
    listWorkspaces: async () => workspaces,
    getAgentTask: async (taskId, workspaceId) => ({ ...previewTask(workspaceId, taskId), pullRequest: { number: 42, title: 'Improve schema discovery', state: 'open', draft: false } }),
    createAgentTask: async request => { taskRequest = request; return previewTask('workspace', request.taskId); },
    openInAppBrowser: async url => { record('browser', url); },
    openExternal: async url => { record('external', url); },
  } satisfies Partial<Bridge>;
  window.bridge = fake as unknown as Bridge;
  const host = document.createElement('div'); host.style.height = '100vh'; document.body.append(host); root = createRoot(host);
  const mounted = root; flushSync(() => mounted.render(<JiraScreen />));
  await until(() => (!openIssue || document.querySelector('.jira-detail h2') !== null) && cards().length === 4 && scope().textContent?.includes('Connector reliability') === true);
}

export async function run(richDescription: string): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  for (const [name, test] of Object.entries({
    async 'handles a ticket only after closing and records successful comments and global pins'() {
      await mountPreview();
      const key = `jira:${issue.cloudId}:${issue.id}`;
      assert(document.querySelector('.attention-visit-status') === null && loadHandled()[key] === undefined, 'Opening a ticket names no visit state and keeps its notification');
      button('Refresh ticket').click(); await until(() => !button('Refresh ticket').disabled);
      const composer = document.querySelector<HTMLTextAreaElement>('textarea[placeholder="Write a comment…"]');
      assert(composer !== null, 'Comment composer exists');
      edit(composer, 'First reply'); await settle(); button('Send').click();
      await until(() => document.body.textContent?.includes('Connection lost') ?? false);
      issue = { ...issue, updatedAt: '2026-09-26T09:00:00Z' };
      button('Send').click(); await until(() => composer.value === '');
      assert(loadHandled()[key] === undefined, 'Successful comments keep the notification during the visit');
      edit(composer, 'Second reply'); await settle(); press(composer, 'Enter'); await settle();
      assert(composer.value === 'Second reply', 'Enter alone keeps typing');
      press(composer, 'Enter', { metaKey: true }); await until(() => composer.value === '');
      button(`Pin ${issue.title}`).click(); await settle();
      assert(loadPinned().some(item => item.key === key), 'The header stores a global pin');
      button(`Unpin ${issue.title}`).click(); await settle();
      const zoomHost = document.createElement('div'); document.body.append(zoomHost);
      const zoomRoot = createRoot(zoomHost);
      try {
        flushSync(() => zoomRoot.render(<JiraTicketLayer location={{ connectionId: issue.connectionId, cloudId: issue.cloudId, issueId: issue.id }} onClose={() => zoomRoot.unmount()} />));
        await until(() => zoomHost.querySelector('.jira-detail h2') !== null);
        button('Close ticket', zoomHost).click();
        await until(() => loadHandled()[key]?.label === 'Posted 2 comments');
        assert(document.querySelector('.jira-detail') !== null && zoomHost.querySelector('.jira-detail') === null,
          'Closing a second view handles the ticket with only the successful comments while the original detail remains mounted');
      }
      finally { zoomRoot.unmount(); zoomHost.remove(); }
      button('Close ticket').click(); await until(() => document.querySelector('.jira-detail') === null); await settle();
      assert(loadHandled()[key]?.label === 'Posted 2 comments', 'Closing acknowledges the visited ticket with its actual actions');
      assert(visibleAttention([jiraAttentionItem(issue)], loadHandled(), Date.now()).length === 0, 'The final displayed version stays handled');
      assert(visibleAttention([jiraAttentionItem({ ...issue, updatedAt: '2026-09-27T09:00:00Z' })], loadHandled(), Date.now()).length === 1,
        'Later ticket activity creates a new notification');
    },
    async 'renders Jira rich descriptions as headings, tables and formatted code'() {
      await mountPreview(false, true, richDescription);
      const description = document.querySelector<HTMLElement>('.jira-description .markdown-body'); assert(description !== null, 'Description exists');
      const heading = description.querySelector('h2'); assert(heading?.textContent === 'Triage data', 'ADF heading retains its level and text');
      const table = description.querySelector('table'); assert(table !== null, 'ADF table renders as a table');
      assert([...table.querySelectorAll('th')].map(cell => cell.textContent).join('|') === 'Metric|Value', 'Table retains its columns');
      assert(table.querySelector('td strong')?.textContent === 'Failures (7d)', 'Table retains bold metrics');
      assert([...table.querySelectorAll('code')].some(code => code.textContent === 'GENERIC_INTERNAL_ERROR'), 'Inline code preserves underscores');
      const code = description.querySelector('pre code[data-language="java"]'); assert(code?.textContent?.includes('setScale') ?? false, 'Java code remains a code block');
      assert(code?.querySelector('[class^="hljs-"]') !== null, 'Java code is highlighted');
      const paragraph = description.querySelector('p'); assert(paragraph !== null && heading !== null, 'Heading and paragraph have separate semantics');
      assert(parseFloat(getComputedStyle(heading).fontSize) > parseFloat(getComputedStyle(paragraph).fontSize), 'Heading has visual hierarchy');
      const dialog = document.querySelector<HTMLElement>('.jira-detail'); assert(dialog !== null && dialog.scrollWidth <= dialog.clientWidth + 1, 'Wide table and code stay inside the ticket');
    },
    async 'renders compact comment rows with author avatars and full local timestamps'() {
      await mountPreview();
      const source = `data:image/svg+xml,${encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32"><rect width="32" height="32" fill="#dbe8fb"/></svg>')}`;
      detail = { ...detail, comments: [
        { id: 'c0', parentId: null, author: { accountId: 'jack', displayName: 'Jack Chen', avatarUrl: source }, body: 'The connector now preserves the declared scale.', created: '2026-09-12T08:43:00Z', updated: '2026-09-12T08:43:00Z', mine: true, editableText: 'The connector now preserves the declared scale.' },
        { id: 'c1', parentId: null, author: { accountId: 'sam', displayName: 'Sam Rivera', avatarUrl: null }, body: '[@Jack Chen](https://example.atlassian.net/jira/people/jack) Thanks for fixing this.\n\nPlease preserve the existing null handling.', created: '2026-09-13T09:15:00Z', updated: '2026-09-13T09:15:00Z', mine: false, editableText: null },
        { id: 'c2', parentId: 'c0', author: { accountId: 'sam', displayName: 'Sam Rivera', avatarUrl: null }, body: 'Confirmed on the custom build.', created: '2026-09-14T10:00:00Z', updated: '2026-09-14T10:00:00Z', mine: false, editableText: null },
      ] };
      button('Refresh ticket').click(); await until(() => document.querySelectorAll('.jira-comment').length === 3);
      await Promise.all(document.getAnimations().map(animation => animation.finished));
      const comments = [...document.querySelectorAll<HTMLElement>('.jira-comment')];
      assert(comments.map(comment => comment.getAttribute('aria-label')).join('|') === 'Comment by Sam Rivera|Comment by Jack Chen|Reply by Sam Rivera', 'Newest thread first; a reply follows its root');
      const rootText = comments[1].querySelector('.jira-comment__content');
      assert(comments[2].parentElement?.classList.contains('jira-comment-replies') === true && comments[2].getBoundingClientRect().left === rootText?.getBoundingClientRect().left, 'A reply is nested under its root text');
      assert(getComputedStyle(comments[2]).borderLeftStyle === 'solid' && getComputedStyle(comments[1]).borderLeftWidth === '0px', 'A rail marks the replies, not the root');
      const time = comments[0].querySelector('time');
      assert(time?.dateTime === detail.comments[1].created && time.textContent === new Date(detail.comments[1].created).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }), 'Comment shows its local date and time');
      const avatar = comments[0].querySelector<HTMLElement>('.jira-avatar'); assert(avatar?.textContent === 'SR' && avatar.title === 'Sam Rivera', 'Missing avatar shows author initials and full name');
      assert(avatar.getBoundingClientRect().width === 28 && avatar.getBoundingClientRect().height === 28 && comments[2].querySelector('.jira-avatar')?.getBoundingClientRect().width === 22, 'Comment avatars use 28 pixels and replies 22');
      const image = comments[1].querySelector<HTMLImageElement>('.jira-avatar img'); await until(() => image?.naturalWidth === 32);
      assert(image?.width === 28 && image.height === 28 && image.referrerPolicy === 'no-referrer', 'Comment author images fit the avatar without sending a referrer');
      image.dispatchEvent(new Event('error')); await until(() => comments[1].querySelector('.jira-avatar')?.textContent === 'JC');
      const body = comments[0].querySelector<HTMLElement>('.markdown-body'); const name = comments[0].querySelector('.jira-comment__author'); assert(body !== null && name !== null, 'Comment body and author exist');
      assert(getComputedStyle(body).padding === '0px' && body.getBoundingClientRect().left === name.getBoundingClientRect().left, 'Comment text aligns with the author without nested Markdown padding');
      assert(getComputedStyle(comments[0]).borderWidth === '0px', 'Comments use conversation rows without card borders');
      const mention = body.querySelector('.md-mention'); assert(mention?.textContent === '@Jack Chen', 'The entire mentioned name is one pill');
      const composer = document.querySelector('.jira-composer'); assert(composer !== null && Boolean(composer.compareDocumentPosition(comments[0]) & Node.DOCUMENT_POSITION_FOLLOWING), 'Comment composer stays before the conversation');
    },
    async 'replies inside a thread and edits only your own comments'() {
      await mountPreview(); commentFails = false;
      const person = (accountId: string, displayName: string): JiraUser => ({ accountId, displayName, avatarUrl: null });
      detail = { ...detail, comments: [
        { id: 'c0', parentId: null, author: person('sam', 'Sam Rivera'), body: 'Can you check the scale?', created: '2026-09-12T08:00:00Z', updated: '2026-09-12T08:00:00Z', mine: false, editableText: null },
        { id: 'c1', parentId: 'c0', author: person('me', 'Alex Chen'), body: 'On it.', created: '2026-09-12T09:00:00Z', updated: '2026-09-12T09:00:00Z', mine: true, editableText: 'On it.' },
        { id: 'c2', parentId: null, author: person('me', 'Alex Chen'), body: 'See [the PR](https://github.com/trinodb/trino/pull/1).', created: '2026-09-13T09:00:00Z', updated: '2026-09-13T09:00:00Z', mine: true, editableText: null },
      ] };
      button('Refresh ticket').click(); await until(() => document.querySelectorAll('.jira-comment').length === 3);
      const own = document.querySelector('[aria-label="Reply by Alex Chen"]'); const other = document.querySelector('[aria-label="Comment by Sam Rivera"]');
      assert(own !== null && other !== null && other.querySelector('[aria-label^="Edit comment"]') === null, 'Only your own comments offer an edit action');
      button('Reply to Alex Chen', own).click(); await settle();
      const thread = document.querySelector<HTMLElement>('.jira-comment-replies'); const replyBox = thread?.querySelector<HTMLTextAreaElement>('textarea');
      assert(thread !== null && replyBox != null && replyBox.placeholder === 'Reply to Alex Chen…' && document.activeElement === replyBox, 'Reply opens focused at the end of its thread');
      edit(replyBox, 'Thanks, confirmed.'); await settle(); button('Reply', thread).click(); await until(() => calls.some(call => call.name === 'comment'));
      const posted = calls.find(call => call.name === 'comment'); assert(posted?.args[0] === 'Thanks, confirmed.' && posted.args[2] === 'c0', 'A reply to a reply is sent under the thread root');
      await until(() => thread.querySelectorAll('.jira-comment').length === 2 && thread.querySelector('textarea') === null, 'Reply lands in its thread and closes the composer');
      button('Edit comment', own).click(); await settle();
      const editor = document.querySelector<HTMLTextAreaElement>('textarea[aria-label="Edit comment"]'); assert(editor?.value === 'On it.', 'Edit starts from the comment text');
      edit(editor, 'On it now.'); await settle(); button('Save').click(); await until(() => calls.some(call => call.name === 'edit'));
      const saved = calls.find(call => call.name === 'edit'); assert(saved?.args[0] === 'c1' && saved.args[1] === 'On it now.' && saved.args[2] === '2026-09-12T09:00:00Z', 'Edit sends the text and the version it started from');
      await until(() => document.querySelector('textarea[aria-label="Edit comment"]') === null && own.textContent?.includes('On it now.') === true, 'Edited text replaces the comment');
      button('Edit comment in Jira').click(); await until(() => calls.some(call => call.name === 'browser'));
      assert(calls.some(call => call.name === 'browser' && call.args[0] === `${jiraIssue.url}?focusedCommentId=c2`), 'A formatted comment opens in Jira for editing');
    },
    async 'loads current sprint and edits supported fields'() {
      await mountPreview();
      assert(calls.some(call => call.name === 'issues' && (call.args[0] as { sprintId: number; mine: boolean }).sprintId === 11 && (call.args[0] as { mine: boolean }).mine), 'Active sprint must use the assigned-to-me filter');
      assert(document.querySelectorAll('.jira-column').length === 4, 'Board keeps all workflow groups visible behind the ticket');
      button('Edit fields').click(); await settle();
      const summary = document.querySelector<HTMLTextAreaElement>('textarea[aria-label="Summary"]'); assert(summary !== null && document.activeElement === summary, 'Edit fields starts at the summary');
      assert(document.querySelector('.jira-edit__count')?.textContent === 'No changes' && button('Save').disabled, 'An unchanged form cannot save');
      const assignee = document.querySelector<HTMLInputElement>('input[aria-label="Assignee"]'); assert(assignee !== null, 'Assignee search exists');
      assignee.focus(); await settle(); edit(assignee, 'unas'); await settle();
      assert([...document.querySelectorAll('.jira-people__option')].map(option => option.textContent).join('|') === 'Unassigned', 'Typing narrows the assignable people');
      press(assignee, 'Enter'); await settle();
      assert(assignee.value === 'Unassigned' && document.querySelector('.jira-people__option') === null, 'Enter picks the highlighted person and closes the list');
      const medium = document.querySelector('.jira-priority-option [aria-label="Priority: Medium"]')?.closest('button'); assert(medium != null, 'Priorities are offered as choices'); medium.click(); await settle();
      const labels = document.querySelector<HTMLInputElement>('input[aria-label="Labels"]'); assert(labels !== null, 'Label editor exists');
      edit(labels, 'regression'); await settle(); press(labels, 'Enter'); await settle();
      edit(labels, 'regression '); await settle(); press(labels, 'Enter'); await settle();
      edit(labels, 'flaky test'); await settle();
      assert(document.querySelector('.jira-edit__count')?.textContent === '3 fields changed', 'Changed fields are counted');
      button('Save').click(); await until(() => calls.some(call => call.name === 'fields'));
      const update = calls.find(call => call.name === 'fields'); const fields = update?.args[0] as Record<string, unknown>;
      assert(JSON.stringify(Object.keys(fields).sort()) === '["assignee","labels","priority"]', 'Only changed fields are sent');
      assert(JSON.stringify(fields.labels) === '["connectors","regression","flaky-test"]' && fields.assignee === null && fields.priority === '3', 'Labels are unique and spaceless, with the picked person and priority');
      assert(update?.args[1] === jiraIssue.updatedAt, 'Updates carry snapshot version');
      await until(() => document.querySelector('.jira-edit') === null);
      const sprint = document.querySelector<HTMLButtonElement>('.jira-side button[title="Move to sprint"]'); assert(sprint?.textContent === 'Connector reliability', 'Details name the sprint the page lists the ticket in');
      sprint.click(); await settle(); menuItem('Backlog').click(); await until(() => calls.some(call => call.name === 'sprint')); assert(calls.find(call => call.name === 'sprint')?.args[0] === null, 'Backlog is explicit');
    },
    async 'requires transition fields and retains a failed comment operation'() {
      await mountPreview();
      const status = () => { const found = document.querySelector<HTMLButtonElement>('.jira-status-button'); assert(found !== null, 'Status control exists'); return found; };
      status().click(); await settle();
      assert([...document.querySelectorAll('.jira-status-menu .jira-menu__item')].map(item => item.textContent).join('|') === 'In progressStart progress|DoneMark done|DoneComplete with approvals', 'Each transition names the status it moves to');
      menuItem('Complete with approvals').click(); await settle(); assert(button('Complete with approvals').disabled, 'Complex required fields must be completed in Jira'); assert(document.body.textContent?.includes('must be completed in Jira') ?? false, 'Unsupported field explains how to complete the transition');
      button('Back').click(); await settle(); menuItem('Mark done').click(); await settle(); assert(button('Mark done').disabled, 'Required resolution prevents incomplete transition');
      choose(select('Resolution'), '1'); await settle(); button('Mark done').click(); await until(() => calls.some(call => call.name === 'transition'));
      const transition = calls.find(call => call.name === 'transition'); assert((transition?.args[1] as { resolution: string }).resolution === '1', 'Chosen transition fields are sent');
      await until(() => document.querySelector('.jira-status-menu') === null && status().textContent === 'Done', 'The ticket takes its new status');
      status().click(); await settle(); menuItem('Start progress').click(); await until(() => calls.filter(call => call.name === 'transition').length === 2);
      const direct = calls.filter(call => call.name === 'transition')[1]; assert(direct.args[0] === 'start' && JSON.stringify(direct.args[1]) === '{}', 'A transition without a screen moves the ticket at once');
      const composer = document.querySelector<HTMLTextAreaElement>('textarea[placeholder="Write a comment…"]'); assert(composer !== null, 'Comment composer exists'); edit(composer, 'The fix is ready for review.'); await settle(); button('Send').click(); await until(() => document.body.textContent?.includes('Connection lost') ?? false);
      assert(composer.value === 'The fix is ready for review.', 'Failed comment retains draft'); button('Send').click(); await until(() => composer.value === '');
      assert(document.querySelector('.jira-comments article')?.textContent?.includes('The fix is ready for review.') ?? false, 'Newest comment reads first');
      const attempts = calls.filter(call => call.name === 'comment'); assert(attempts.length === 2 && attempts[0].args[1] === attempts[1].args[1], 'Retry retains operation identity');
    },
    async 'assigns and changes the local workspace from ticket Details without Jira writes'() {
      await mountPreview();
      issue = { ...issue, hasChildren: true, status: { id: 'done', name: 'Done', category: 'done' } }; detail = { ...detail, editableFields: [] };
      button('Refresh ticket').click(); await until(() => button('Edit fields').disabled && !button('Refresh ticket').disabled);
      const pane = document.querySelector('.jira-detail'); assert(pane !== null, 'Ticket detail exists');
      const assignment = () => document.querySelector<HTMLDialogElement>('dialog.jira-assignment[open]');
      const reply = pane.querySelector<HTMLTextAreaElement>('textarea[placeholder="Write a comment…"]'); assert(reply !== null, 'Comment composer exists'); edit(reply, 'Keep my unsent reply.'); await settle();
      calls.length = 0;
      assert(!button('Assign workspace', pane).disabled, 'Completed parent tickets allow local assignment even without editable Jira fields');
      button('Assign workspace', pane).click(); await until(() => assignment() !== null && !select('Workspace').disabled);
      assert(select('Workspace').value === '' && assignButton().disabled, 'Unassigned tickets require an explicit workspace choice');
      choose(select('Workspace'), 'workspace'); await settle(); button('Close workspace assignment').click(); await until(() => assignment() === null);
      assert(issue.workspace === null && calls.length === 0 && reply.value === 'Keep my unsent reply.', 'Cancel keeps assignment and comment draft unchanged');
      button('Assign workspace', pane).click(); await until(() => assignment() !== null && !select('Workspace').disabled);
      choose(select('Workspace'), 'workspace'); await settle(); assignmentFails = true; assignButton().click();
      await until(() => assignment()?.textContent?.includes('Workspace assignment failed') ?? false);
      assert(select('Workspace').value === 'workspace' && issue.workspace === null && !assignButton().disabled, 'Failed save retains the selected workspace for retry');
      assignButton().click(); await until(() => assignment() === null && button('Change ByteQuay workspace: Trino', pane).textContent?.includes('Trino') === true);
      assert(document.querySelector('.jira-card[aria-label^="ENG-1007:"] .jira-workspace')?.textContent?.includes('Trino') ?? false, 'Assignment also updates the ticket card');
      button('Change ByteQuay workspace: Trino', pane).click(); await until(() => assignment() !== null && !select('Workspace').disabled);
      assert(select('Workspace').value === 'workspace' && assignButton().disabled, 'Existing assignment is selected and unchanged submission is disabled');
      choose(select('Workspace'), 'other-workspace'); await settle(); assignButton().click();
      await until(() => assignment() === null && button('Change ByteQuay workspace: Connectors', pane).textContent?.includes('Connectors') === true);
      assert(issue.workspace?.id === 'other-workspace' && reply.value === 'Keep my unsent reply.', 'Changing workspace preserves the ticket and comment draft');
      assert(calls.filter(call => call.name === 'assign').map(call => call.args[1]).join('|') === 'workspace|workspace|other-workspace', 'Only explicit assignment submissions save the chosen workspace');
      assert(!issue.isEpic && taskRequest === null && calls.every(call => call.name === 'assign' || (call.name === 'issues' && call.args[1] !== true)), 'Local assignment creates no Epic, task, Jira write or remote board refresh');
    },
    async 'handles missing workspaces and keeps a linked task workspace read-only'() {
      await mountPreview();
      window.bridge.listWorkspaces = async () => [];
      button('Assign workspace').click();
      await until(() => document.querySelector('.jira-assignment')?.textContent?.includes('Watch a repository from Home') ?? false);
      assert(assignButton().disabled && select('Workspace').value === '', 'No workspace cannot produce an assignment');
      button('Close workspace assignment').click(); await until(() => document.querySelector('.jira-assignment') === null);
      issue = { ...issue, workspace: { id: 'workspace', name: 'Trino' }, task: { id: 'task', workspaceId: 'workspace' } };
      button('Refresh ticket').click(); await until(() => document.querySelector('.jira-side__bytequay')?.textContent?.includes('Set by the linked development task.') ?? false);
      const fields = document.querySelector('.jira-side__bytequay'); assert(fields !== null, 'ByteQuay fields exist');
      assert(fields.textContent?.includes('Workspace') && fields.textContent.includes('Trino'), 'Linked workspace remains visible in Details');
      assert(fields.querySelector('.jira-field-button') === null && !calls.some(call => call.name === 'assign'), 'A linked development task owns its read-only workspace');
      await until(() => fields.querySelector('.jira-task-line')?.textContent === 'PR #42· open', 'Details name the linked task and its pull request');
    },
    async 'assigns a workspace before Epic marking and preserves cancellation and failures'() {
      await mountPreview(false, false);
      const card = document.querySelector('.jira-card'); assert(card !== null, 'Card exists');
      const assignment = () => document.querySelector<HTMLDialogElement>('dialog[aria-label="Assign workspace for ENG-1007"][open]');
      button('Mark ENG-1007 as Epic', card).click(); await until(() => assignment() !== null);
      assert(document.querySelector('.jira-detail') === null, 'Epic action does not also open ticket details');
      assert(select('Workspace').value === '' && assignButton().disabled, 'Workspace is not inferred and confirmation requires a selection');
      await keyPress('Escape'); await until(() => assignment() === null);
      assert(issue.workspace === null && !issue.isEpic && !calls.some(call => call.name === 'assign' || call.name === 'epic'), 'Cancel leaves assignment and Epic untouched');
      button('Mark ENG-1007 as Epic', card).click(); await until(() => assignment() !== null);
      choose(select('Workspace'), 'workspace'); await settle();
      assert(!calls.some(call => call.name === 'assign' || call.name === 'epic'), 'Selecting a workspace waits for explicit confirmation');
      assignmentFails = true; assignButton().click(); await until(() => assignment()?.textContent?.includes('Workspace assignment failed') ?? false);
      assert(select('Workspace').value === 'workspace' && issue.workspace === null && !issue.isEpic && !calls.some(call => call.name === 'epic'), 'Failed assignment retains the choice without marking Epic');
      assignButton().click(); await until(() => card.querySelector('.jira-epic-active') !== null && assignment() === null);
      const workspace = card.querySelector('.jira-workspace'); assert(workspace?.textContent?.includes('Trino') ?? false, 'Assigned workspace appears on the card');
      const workspaceBounds = workspace?.getBoundingClientRect(); assert(workspaceBounds !== undefined, 'Workspace badge is visible');
      assert(workspace?.contains(document.elementFromPoint(workspaceBounds.left + workspaceBounds.width / 2, workspaceBounds.top + workspaceBounds.height / 2)) ?? false, 'Workspace hover tooltip remains reachable');
      assert(card.querySelector('[aria-label="Use agent to develop ENG-1007"]') === null && document.querySelector('.jira-detail') === null, 'Epic cannot start, and choosing a workspace never opened the ticket');
      button('Unmark ENG-1007 as Epic', card).click(); await until(() => card.querySelector('.jira-epic-active') === null);
      button('Mark ENG-1007 as Epic', card).click(); await until(() => card.querySelector('.jira-epic-active') !== null);
      assert(assignment() === null && calls.filter(call => call.name === 'assign').length === 2, 'Saved workspace is reused for later Epic actions');
    },
    async 'retains development workspace on cancellation and launches with the saved assignment'() {
      await mountPreview(); const pane = document.querySelector('.jira-detail'); assert(pane !== null, 'Details exist');
      button('Start development', pane).click(); await until(() => document.querySelector('dialog[aria-label="Assign workspace for ENG-1007"][open]') !== null);
      assert(select('Workspace').value === '', 'Workspace is never inferred from active app state'); assert(document.querySelector('.agt-compose') === null, 'No composer until explicit workspace assignment');
      choose(select('Workspace'), 'workspace'); await settle(); assignButton().click(); await until(() => document.querySelector('.agt-compose textarea') !== null);
      assert(calls.find(call => call.name === 'assign')?.args[1] === 'workspace', 'Explicit workspace assignment is persisted before composer opens');
      button('Close development').click(); await until(() => document.querySelector('.agt-compose') === null);
      assert(pane.querySelector('[aria-label="Change ByteQuay workspace: Trino"]') !== null && document.querySelector('.jira-card .jira-workspace')?.textContent?.includes('Trino'), 'Composer cancellation retains workspace on detail and card');
      button('Start development', pane).click(); await until(() => document.querySelector('.agt-compose textarea') !== null);
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
    async 'starts another attempt over an ended ticket task and otherwise opens that task'() {
      await mountPreview(false, false);
      issue = { ...issue, workspace: { id: 'workspace', name: 'Trino' }, task: { id: 'ended-task', workspaceId: 'workspace' } };
      const location = { connectionId: issue.connectionId, cloudId: issue.cloudId, issueId: issue.id };
      const zoomHost = document.createElement('div'); document.body.append(zoomHost);
      const zoomRoot = createRoot(zoomHost);
      try {
        flushSync(() => zoomRoot.render(<JiraTicketLayer key="open" location={location} start onClose={() => {}} />));
        await until(() => window.location.hash.includes('ended-task'));
        assert(document.querySelector('.agt-compose') === null, 'Without another attempt the ticket opens its task');
        flushSync(() => zoomRoot.render(<JiraTicketLayer key="another" location={location} start draft="Try the other approach." replaces="ended-task" onClose={() => {}} />));
        await until(() => document.querySelector<HTMLTextAreaElement>('.agt-compose textarea')?.value === 'Try the other approach.');
        const send = document.querySelector<HTMLButtonElement>('.agt-compose button[aria-label="Start task"]') ?? document.querySelector<HTMLButtonElement>('.agt-send'); assert(send !== null, 'Task send exists');
        await until(() => !send.disabled); send.click(); await until(() => taskRequest !== null);
        const created = taskRequest as unknown as CreateAgentTaskRequest;
        assert(created.jiraSource?.replaces === 'ended-task' && created.jiraSource.issueId === issue.id, 'The request names the ended task the attempt replaces');
      }
      finally { zoomRoot.unmount(); zoomHost.remove(); }
    },
    async 'suspends Jira modals for the embedded browser without losing drafts or assignment'() {
      await mountPreview();
      const mounted = root; assert(mounted !== null, 'Screen is mounted');
      const browserOpen = async (open: boolean) => { flushSync(() => mounted.render(<LayerCovered.Provider value={open}><JiraScreen /></LayerCovered.Provider>)); await settle(); };
      // App always renders the screen under this provider; adding it later would remount the screen.
      await browserOpen(false); await until(() => document.querySelector('.jira-detail h2') !== null);
      const pane = document.querySelector<HTMLDialogElement>('dialog.jira-detail'); assert(pane !== null, 'Ticket detail exists');
      const reply = pane.querySelector<HTMLTextAreaElement>('textarea[placeholder="Write a comment…"]'); assert(reply !== null, 'Comment draft exists');
      edit(reply, 'Keep this reply while I check Jira.'); await settle();
      button('Open ENG-1007 in Jira', pane).click(); await until(() => calls.some(call => call.name === 'browser' && call.args[0] === jiraIssue.url));
      await browserOpen(true);
      assert(!pane.open && !pane.matches(':modal') && document.querySelector('dialog:modal') === null, 'Embedded browser is not blocked by the ticket modal');
      await browserOpen(false);
      assert(pane.matches(':modal') && document.querySelector('dialog.jira-detail') === pane && reply.value === 'Keep this reply while I check Jira.', 'Returning restores the same ticket and unsent reply');
      button('Mark as Epic', pane).click(); await until(() => document.querySelector('dialog[aria-label="Assign workspace for ENG-1007"][open]') !== null);
      choose(select('Workspace'), 'workspace'); await settle();
      await browserOpen(true); assert(document.querySelector('dialog:modal') === null, 'Workspace assignment also releases the embedded browser');
      await browserOpen(false); assert(select('Workspace').value === 'workspace', 'Returning retains the pending workspace choice');
      const assignment = document.querySelector('dialog[aria-label="Assign workspace for ENG-1007"]'); assert(assignment !== null, 'Workspace assignment remains mounted');
      const choiceBounds = select('Workspace').getBoundingClientRect();
      assert(assignment.contains(document.activeElement) && assignment.contains(document.elementFromPoint(choiceBounds.left + choiceBounds.width / 2, choiceBounds.top + choiceBounds.height / 2)), 'Restored workspace assignment stays above the underlying ticket');
      assignButton().click(); await until(() => pane.querySelector('.jira-epic-active') !== null);
      button('Epic', pane).click(); await until(() => pane.querySelector('.jira-epic-active') === null);
      button('Start development', pane).click(); await until(() => document.querySelector('.agt-compose textarea') !== null);
      const launch = document.querySelector<HTMLDialogElement>('dialog[aria-label="Develop ENG-1007"]'); assert(launch !== null, 'Development dialog exists');
      const instructions = launch.querySelector<HTMLTextAreaElement>('.agt-compose textarea'); assert(instructions !== null, 'Development draft exists');
      edit(instructions, 'Keep the partition column predicates intact.'); await settle();
      await browserOpen(true); assert(document.querySelector('dialog:modal') === null, 'Both development and underlying ticket release the browser');
      await browserOpen(false);
      assert(launch.matches(':modal') && document.querySelector('dialog[aria-label="Develop ENG-1007"]') === launch && instructions.value === 'Keep the partition column predicates intact.', 'Returning restores the same development dialog and draft');
      assert(launch.contains(document.activeElement), 'Restored development dialog stays above the underlying ticket');
      assert(reply.value === 'Keep this reply while I check Jira.' && launch.querySelector('.jira-workspace')?.textContent?.includes('Trino'), 'Ticket reply and workspace survive the full browser round trip');
    },
    async 'shows Jira card metadata, filters and completed board history'() {
      await mountPreview(false, false);
      assert(document.querySelector('.jira-header h1')?.textContent === 'Jira', 'Native page is named Jira');
      const columns = [...document.querySelectorAll('.jira-column .jira-group-head__name')].map(value => value.textContent);
      assert(JSON.stringify(columns) === '["To do","In progress","In review","Done"]', 'All four groups are visible together');
      assert([...document.querySelectorAll('.jira-column .jira-status')].map(value => value.textContent).join('|') === 'Awaiting review|Resolved', 'A card names its actual status only where it differs from its column');
      const card = document.querySelector('.jira-card'); assert(card !== null, 'Card exists');
      const title = card.querySelector('.jira-card__title'); assert(title?.textContent === jiraIssue.title && getComputedStyle(title).fontWeight === '400', 'Card title leads, in regular weight');
      assert(card.querySelector('[aria-label="Priority: High"]') !== null && card.querySelector('[aria-label="Assignee: Alex Chen"]')?.textContent === 'AC', 'Card exposes priority and assigned person');
      assert(document.querySelector('[aria-label="Priority: Expedite"]')?.classList.contains('is-neutral') ?? false, 'Tenant-specific priority ID never defines severity');
      const linked = document.querySelector('.jira-card[aria-label^="ENG-1008:"]'); assert(linked !== null, 'Linked card exists');
      await until(() => linked.querySelector('.jira-task-line')?.textContent === 'PR #42· open', 'A card with a task names its pull request');
      assert(linked.querySelector('[aria-label="View task for ENG-1008"]') !== null, 'A card with a task opens it');
      await filterStatus('In progress'); assert(cards().length === 1 && statusFilter().textContent?.includes('In progress') === true, 'Status filters by workflow group');
      await filterStatus('All'); assert(cards().length === 4, 'All shows every group again');
      const search = document.querySelector<HTMLInputElement>('input[aria-label="Search tickets"]'); assert(search !== null, 'Search exists');
      press(window, '/'); await settle(); assert(document.activeElement === search, 'Slash jumps to search');
      edit(search, 'timestamps'); await settle(); assert(cards().length === 1 && document.querySelector('.jira-count-label')?.textContent === '1 ticket', 'Search narrows the tickets and their count');
      edit(search, ''); await settle();
      button('Open in Jira').click(); await settle(); assert(calls.some(call => call.name === 'browser' && call.args[0] === 'https://example.atlassian.net/secure/RapidBoard.jspa?rapidView=828'), 'Open board uses the selected site and board');
      button('Completed').click(); await until(() => document.querySelectorAll('.jira-list__group').length > 0);
      assert(calls.some(call => call.name === 'issues' && (call.args[0] as JiraQuery).completed && (call.args[0] as JiraQuery).boardId === 828), 'Completed requests board history across sprint boundaries');
      const groups = [...document.querySelectorAll('.jira-list__group .jira-group-head__name')].map(value => value.textContent);
      assert(groups.includes('Today') && groups.includes('Yesterday') && groups.includes('Earlier') && groups.includes('Completion date unavailable'), 'Completion groups expose recent and unknown dates');
      assert(document.querySelectorAll('.jira-row').length === 6 && document.querySelector('.jira-content [aria-label^="Use agent to develop"]') === null && document.querySelector('.jira-count-label')?.textContent === '6 tickets · across sprints', 'Completed rows do not offer new development');
      button('Current sprint').click(); await until(() => document.querySelectorAll('.jira-column').length === 4);
      button('List view').click(); await settle(); assert(document.querySelector('.jira-list') !== null, 'List remains available');
      document.querySelector<HTMLElement>('.jira-row[aria-label^="ENG-1009:"]')?.click(); await until(() => window.location.hash.includes('issueId=1009'));
    },
    async 'loads assignee avatars and recovers from unavailable images'() {
      await mountPreview(false, false);
      const assignee = issue.assignee; assert(assignee !== null, 'Fixture has an assignee');
      const avatar = () => document.querySelector<HTMLElement>('[aria-label^="ENG-1007:"] .jira-avatar');
      const image = () => avatar()?.querySelector('img');
      const source = `data:image/svg+xml,${encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="25" height="25"><rect width="25" height="25" fill="blue"/></svg>')}`;
      assert(avatar()?.textContent === 'AC' && !image(), 'Missing avatar retains initials without a GitHub request');
      issue = { ...issue, assignee: { ...assignee, avatarUrl: source } };
      button('Refresh tickets').click(); await until(() => image()?.naturalWidth === 25);
      const loaded = image(); assert(loaded !== null && loaded !== undefined, 'Jira avatar loads');
      assert(loaded.src === source && loaded.width === 20 && loaded.height === 20 && loaded.referrerPolicy === 'no-referrer', 'Avatar uses the supplied source in the existing circular slot');
      assert(avatar()?.title === 'Assignee: Alex Chen', 'Full assignee name remains available on hover');
      issue = { ...issue, assignee: { ...assignee, avatarUrl: 'data:image/png;base64,broken' } };
      button('Refresh tickets').click(); await until(() => !image() && avatar()?.textContent === 'AC');
      issue = { ...issue, assignee: { ...assignee, avatarUrl: source } };
      button('Refresh tickets').click(); await until(() => image()?.naturalWidth === 25);
      assert(image()?.src === source, 'A changed source retries the same assignee after failure');
      button('List view').click(); await settle(); assert(image()?.src === source, 'List cards use Jira avatars');
      button('Completed').click(); await until(() => document.querySelector('.jira-list__group .jira-avatar img') !== null);
      assert(document.querySelector<HTMLImageElement>('.jira-list__group .jira-avatar img')?.src === source, 'Completed rows use Jira avatars');
      button('Current sprint').click(); await until(() => avatar() !== null);
      issue = { ...issue, assignee: null };
      button('Refresh tickets').click(); await until(() => avatar()?.getAttribute('aria-label') === 'Assignee: Unassigned');
      assert(avatar()?.querySelector('svg') !== null && !image(), 'Unassigned tickets retain the user icon');
    },
    async 'remembers a valid account and site while source links retain their binding'() {
      await mountPreview();
      button('Close ticket').click(); await until(() => document.querySelector('.jira-detail') === null);
      scope().click(); await settle(); const sites = siteItems();
      assert(sites.length === 2 && sites[0].textContent?.includes('alex@example.com') === true && sites[1].textContent?.includes('sam@another.com') === true && sites[1].textContent.includes('another.atlassian.net'), 'Same-name sites identify account and domain');
      sites[1].click(); await until(() => window.location.hash === '#/jira' && document.querySelector('.jira-scope-menu') === null);
      assert((await chosenSite()).includes('another.atlassian.net'), 'The chosen site is marked');
      await mountPreview(false, false); assert((await chosenSite()).includes('another.atlassian.net'), 'Ordinary navigation restores the selected site');
      await mountPreview(); button('Close ticket').click(); await until(() => document.querySelector('.jira-detail') === null);
      assert((await chosenSite()).includes('example.atlassian.net'), 'Task and source deep links override remembered site');
      scope().click(); await settle(); button('Manage accounts…').click(); await until(() => window.location.hash === '#/settings/integrations/jira');
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
      button('Refresh tickets').click(); await until(() => cards().length === 1);
      assert(document.querySelector('.jira-count-label')?.textContent === '1 ticket assigned to Trino', 'Workspace scope is visible');
      assert(document.querySelector('.jira-card .card-key')?.textContent === 'ENG-1007', 'Current sprint excludes other-workspace and unassigned tickets');
      await filterStatus('In progress'); assert(cards().length === 0, 'Status filters within the workspace tickets');
      await filterStatus('All');
      button('List view').click(); await settle();
      assert(document.querySelectorAll('.jira-list .jira-row').length === 1, 'List uses the same workspace scope');
      document.querySelector<HTMLElement>('.jira-row[aria-label^="ENG-1007:"]')?.click(); await until(() => document.querySelector('.jira-detail h2') !== null);
      assert(window.location.hash === jiraRoute({ connectionId: 'connection', cloudId: 'site', issueId: '1007' }, workspace.id), 'Opening a ticket keeps its workspace route');
      button('Close ticket').click(); await until(() => document.querySelector('.jira-detail') === null);
      assert(window.location.hash === jiraRoute(undefined, workspace.id), 'Closing a ticket returns to workspace Jira');
      button('Connector reliability', document.querySelector('.jira-row') ?? document).click(); await until(() => document.querySelector('.jira-detail h2') !== null);
      assert(window.location.hash === jiraRoute({ connectionId: 'connection', cloudId: 'site', issueId: '1000' }, workspace.id), 'Parent links retain the workspace route');
      button('Close ticket').click(); await until(() => document.querySelector('.jira-detail') === null);
      button('Completed').click();
      await until(() => document.querySelectorAll('.jira-row').length === 1 && document.querySelector('.jira-row .card-key')?.textContent === 'ENG-2001', 'Completed history also excludes other-workspace and unassigned tickets');
      scope().click(); await settle(); siteItems()[1].click(); await until(() => document.querySelector('.jira-scope-menu') === null);
      assert((await chosenSite()).includes('another.atlassian.net') && window.location.hash === jiraRoute(undefined, workspace.id), 'Site changes retain the workspace route');
    },
    async 'keeps cached tickets visible offline'() {
      await mountPreview(true); await until(() => document.body.textContent?.includes('Jira is offline') ?? false);
      assert(cards().length === 4, 'Cached tickets remain visible after remote refresh fails');
      assert(document.querySelector('.jira-detail h2')?.textContent === jiraIssue.title, 'Cached detail remains available offline');
    },
    async 'shows a retryable failure for an uncached selected board and clears it on retry'() {
      await mountPreview(false, false);
      const listIssues = window.bridge.listJiraIssues;
      window.bridge.listJiraIssues = async (_query, refresh) => {
        if (refresh) throw new Error('Jira could not be reached.');
        return { issues: [], fetchedAt: null, nextPageToken: null, error: null };
      };
      button('Completed').click();
      await until(() => document.querySelector('.jira-error') !== null);
      assert(document.querySelector('.jira-empty')?.textContent === 'Tickets could not be loaded. Refresh tickets to try again.', 'A selected board is not mistaken for a missing selection');
      let finish: (() => void) | undefined;
      window.bridge.listJiraIssues = async (query, refresh) => {
        if (refresh) await new Promise<void>(resolve => { finish = resolve; });
        return listIssues(query, refresh);
      };
      button('Refresh tickets').click(); await until(() => finish !== undefined);
      assert(document.querySelector('.jira-error') === null, 'Retry clears the previous error while loading');
      finish?.(); await until(() => !button('Refresh tickets').disabled);
      assert(document.querySelectorAll('.jira-row').length === 6, 'Retry restores completed history');
    },
    async 'loads history on demand, retries the same cursor and ignores a late page after switching views'() {
      await mountPreview(false, false);
      const listIssues = window.bridge.listJiraIssues;
      const requests: JiraQuery[] = [];
      const requestCount = () => requests.length;
      const all = completedIssues();
      let fail = true;
      let slow = false;
      let finish: (() => void) | undefined;
      window.bridge.listJiraIssues = async (query, refresh) => {
        if (!query.completed) return listIssues(query, refresh);
        if (refresh) requests.push(query);
        if (query.nextPageToken !== undefined) {
          assert(query.nextPageToken === 'next/token + value', 'Load more preserves the opaque cursor');
          if (fail) { fail = false; throw new Error('Page could not be loaded.'); }
          if (slow) await new Promise<void>(resolve => { finish = resolve; });
        }
        return { issues: query.nextPageToken ? all : all.slice(0, 1), fetchedAt: issue.updatedAt, error: null, nextPageToken: query.nextPageToken ? null : 'next/token + value' };
      };
      button('Completed').click(); await until(() => document.querySelectorAll('.jira-row').length === 1 && !button('Refresh tickets').disabled);
      assert(requestCount() === 1, 'Opening history fetches only its first page');
      assert(document.querySelector('.jira-notice')?.textContent?.includes('filters apply to loaded tickets') === true, 'Filtering is clearly scoped to loaded tickets');
      button('Load more').click(); await until(() => document.querySelector('.jira-error') !== null);
      assert(document.querySelectorAll('.jira-row').length === 1 && !button('Load more').disabled, 'A failed page retains tickets and permits retry');
      button('Load more').click(); await until(() => document.querySelectorAll('.jira-row').length === 6);
      assert(document.querySelector('.jira-load-more') === null && requestCount() === 3, 'The last page removes Load more');
      button('Refresh tickets').click(); await until(() => document.querySelectorAll('.jira-row').length === 1 && !button('Refresh tickets').disabled);
      assert(requests.at(-1)?.nextPageToken === undefined, 'Refresh restarts with the first page');
      slow = true;
      button('Load more').click(); await until(() => finish !== undefined);
      assert(button('Loading…').disabled && button('Refresh tickets').disabled, 'A pending page cannot be requested twice');
      button('Current sprint').click(); await until(() => cards().length === 4);
      finish?.(); await settle();
      assert(cards().length === 4 && document.querySelector('.jira-load-more') === null, 'A late history page cannot replace the current sprint');
    },
    async 'marks automatic detail refresh and keeps its board update local'() {
      const interval = window.setInterval;
      const clearInterval = window.clearInterval;
      const hidden = Object.getOwnPropertyDescriptor(document, 'hidden');
      let poll: (() => void) | undefined;
      window.setInterval = ((handler: TimerHandler, delay?: number, ...args: unknown[]) => {
        if (delay === 60_000 && typeof handler === 'function') { poll = () => handler(...args); return -1; }
        return interval(handler, delay, ...args);
      }) as typeof window.setInterval;
      window.clearInterval = id => { if (id === -1) poll = undefined; else clearInterval(id); };
      Object.defineProperty(document, 'hidden', { configurable: true, value: false });
      try {
        await mountPreview(); await settle(); calls.length = 0;
        assert(poll !== undefined, 'Visible ticket schedules automatic refresh');
        issue = { ...issue, title: 'Cached ticket updated during quiet hours' };
        poll(); await until(() => calls.some(call => call.name === 'issues')); await settle();
        assert(calls.some(call => call.name === 'detail' && call.args[1] === true && call.args[2] === true), 'Timer labels detail request as automatic for the backend quiet-hours check');
        assert(!calls.some(call => call.name === 'issues' && call.args[1] === true), 'Cached automatic detail never triggers a remote board refresh');
        assert(document.querySelector('.jira-detail h2')?.textContent === issue.title && document.querySelector('.jira-card__title')?.textContent === issue.title, 'Cached detail still updates both visible ticket surfaces');
        button('Assign workspace').click();
        await until(() => document.querySelector('dialog.jira-assignment[open]') !== null && poll === undefined);
        assert(poll === undefined, 'Workspace chooser pauses automatic detail refresh');
        button('Close workspace assignment').click();
        await until(() => document.querySelector('.jira-assignment') === null && poll !== undefined);
        calls.length = 0; button('Refresh ticket').click();
        await until(() => calls.some(call => call.name === 'issues' && call.args[1] === true)); await settle();
        assert(calls.some(call => call.name === 'detail' && call.args[1] === true && call.args[2] !== true), 'Explicit ticket refresh remains a manual remote request');
        button('Close ticket').click(); await until(() => document.querySelector('.jira-detail') === null);
        calls.length = 0; button('Refresh tickets').click();
        await until(() => calls.some(call => call.name === 'issues' && call.args[1] === true));
      }
      finally {
        root?.unmount(); root = null; window.setInterval = interval; window.clearInterval = clearInterval;
        if (hidden) Object.defineProperty(document, 'hidden', hidden); else Reflect.deleteProperty(document, 'hidden');
      }
    },
    async 'opens the whole card as a centered modal with keyboard and focus restoration'() {
      await mountPreview(false, false);
      const card = document.querySelector<HTMLElement>('.jira-card'); assert(card !== null, 'Card exists');
      assert(card.getAttribute('role') === 'button' && card.tabIndex === 0, 'The whole card is one keyboard control');
      for (const selector of ['.jira-avatar', '.jira-priority']) {
        const target = card.querySelector<HTMLElement>(selector); assert(target !== null, 'Card metadata exists');
        const bounds = target.getBoundingClientRect();
        assert(target.contains(document.elementFromPoint(bounds.left + bounds.width / 2, bounds.top + bounds.height / 2)), 'Whole-card click target preserves native metadata hover tooltips');
      }
      const before = document.querySelector('.jira-board')?.getBoundingClientRect(); assert(before !== undefined, 'Board exists');
      const cardBounds = card.getBoundingClientRect();
      await clickAt(cardBounds.left + cardBounds.width / 2, cardBounds.bottom - 8);
      await until(() => document.querySelector('dialog.jira-detail[open] h2') !== null, 'Clicking the bottom of the card opens ticket details');
      const modal = document.querySelector<HTMLDialogElement>('dialog.jira-detail[open]'); assert(modal !== null, 'Whole-card click opens native modal');
      assert(modal.getAttribute('aria-label') === 'Ticket details' && modal.matches(':modal'), 'Ticket details are an accessible modal');
      await Promise.all(modal.getAnimations().map(animation => animation.finished));
      const bounds = modal.getBoundingClientRect(); const board = document.querySelector('.jira-board')?.getBoundingClientRect();
      assert(bounds.width > window.innerWidth * 0.75 && bounds.left > 0 && bounds.right < window.innerWidth && Math.abs(bounds.left - (window.innerWidth - bounds.right)) < 2, 'Ticket expands into a large centered dialog');
      assert(bounds.top >= 0 && bounds.bottom <= window.innerHeight + 1 && board?.width === before.width, 'Modal stays within viewport without narrowing the board');
      await keyPress('Escape'); await until(() => document.querySelector('dialog.jira-detail[open]') === null, 'Escape closes ticket details');
      assert(document.activeElement === card, 'Escape restores focus to the card');
      await keyPress('Enter'); await until(() => document.querySelector('dialog.jira-detail[open] h2') !== null, 'Enter activates the focused card');
      button('Close ticket').click(); await until(() => document.querySelector('dialog.jira-detail[open]') === null);
      assert(document.activeElement === card, 'Close restores focus to the keyboard trigger');
      button('Connector reliability', card).click(); await until(() => window.location.hash.includes('issueId=1000'));
      assert(!window.location.hash.includes('issueId=1007'), 'Parent ticket action remains independent of card click');
    },
    async 'lists subtasks and linked work items, creates a subtask and links a ticket'() {
      await mountPreview();
      const related = (id: string, key: string, title: string): JiraIssueDetail['children'][number] => ({ id, key, title, type: 'Subtask', status: { id: 'progress', name: 'In Progress', category: 'indeterminate' }, priority: { id: '2', name: 'P2' }, assignee: { accountId: 'sam', displayName: 'Sam Rivera', avatarUrl: null } });
      issue = { ...issue, hasChildren: true };
      detail = { ...detail, children: [related('1011', 'ENG-1011', 'Test the rollout against the query history table of the online cluster before enabling remote split generation for every customer')],
        links: [{ id: 'l1', relation: 'relates to', issue: { ...related('1000', 'ENG-1000', 'Connector reliability'), type: 'Epic' } }, { id: 'l2', relation: 'is blocked by', issue: related('1012', 'ENG-1012', 'Upgrade the metastore client') }] };
      button('Refresh ticket').click(); await until(() => document.querySelectorAll('.jira-work-item').length === 3);
      const subtasks = document.querySelector<HTMLElement>('section[aria-label="Subtasks"]'); const links = document.querySelector<HTMLElement>('section[aria-label="Linked work items"]');
      const description = document.querySelector('.jira-description'); const comments = document.querySelector('.jira-comments');
      assert(subtasks !== null && links !== null && description !== null && comments !== null, 'Subtasks and linked work items have their own sections');
      assert([description, subtasks, links, comments].every((section, index, all) => index === 0 || Boolean(all[index - 1].compareDocumentPosition(section) & Node.DOCUMENT_POSITION_FOLLOWING)), 'Work items sit between the description and comments');
      assert([...links.querySelectorAll('h4')].map(heading => heading.textContent).join('|') === 'relates to|is blocked by', 'Links are grouped by how this ticket reads them');
      const row = subtasks.querySelector<HTMLElement>('.jira-work-item'); const title = row?.querySelector<HTMLElement>('.jira-work-item__title'); const status = row?.querySelector<HTMLElement>('.jira-status');
      assert(row != null && title != null && status != null && row.textContent?.includes('ENG-1011') === true && status.textContent === 'In Progress', 'A subtask row shows its key, title and status');
      assert(title.scrollWidth > title.clientWidth && status.getBoundingClientRect().right <= row.getBoundingClientRect().right && row.scrollWidth <= row.clientWidth + 1, 'A long title truncates and keeps the status inside the row');
      let zoomed: unknown = null; const listen = (event: Event) => { zoomed = (event as CustomEvent).detail; };
      window.addEventListener(ZOOM_REFERENCE, listen); row.click(); window.removeEventListener(ZOOM_REFERENCE, listen);
      assert(JSON.stringify(zoomed) === JSON.stringify({ kind: 'jira', connectionId: 'connection', cloudId: 'site', issueId: '1011' }), 'A subtask opens as its own ticket over this one');

      button('+ Add subtask').click(); await until(() => document.querySelector('select[aria-label="Work type"]') !== null);
      const summary = document.querySelector<HTMLInputElement>('input[aria-label="New subtask"]'); assert(summary !== null && document.activeElement === summary, 'The new subtask summary is focused');
      choose(select('Work type'), '10004'); edit(summary, '  Verify the rollout flag '); await settle(); button('Create').click(); await until(() => calls.some(call => call.name === 'child'));
      const created = calls.find(call => call.name === 'child'); assert(created?.args[0] === '10004' && created.args[1] === 'Verify the rollout flag', 'Create sends the chosen work type and the trimmed summary');
      await until(() => subtasks.querySelectorAll('.jira-work-item').length === 2 && summary.value === '', 'The new subtask appears and the form stays ready for another');
      await keyPress('Escape'); await until(() => document.querySelector('input[aria-label="New subtask"]') === null, 'Escape closes the create form');
      assert(document.querySelector('dialog.jira-detail[open]') !== null, 'Escape in the form keeps the ticket open');

      button('+ Link ticket').click(); await until(() => document.querySelector<HTMLSelectElement>('select[aria-label="Link relationship"]')?.options.length === 4);
      assert([...select('Link relationship').options].map(option => option.textContent).join('|') === 'Relationship…|blocks|is blocked by|relates to', 'A symmetric link type is offered once');
      const target = document.querySelector<HTMLInputElement>('input[aria-label="Work item to link"]'); assert(target !== null, 'Link form asks for the other ticket');
      edit(target, 'https://example.atlassian.net/browse/eng-1013'); await settle(); assert(button('Link').disabled, 'A relationship is chosen explicitly');
      choose(select('Link relationship'), '10000:inward'); await settle(); button('Link').click(); await until(() => calls.some(call => call.name === 'link'));
      const linked = calls.find(call => call.name === 'link'); assert(linked?.args[0] === '10000' && linked.args[1] === false && linked.args[2] === 'ENG-1013', 'An inward relation sends the key a pasted link names');
      await until(() => document.querySelector('input[aria-label="Work item to link"]') === null && links.querySelectorAll('.jira-work-item').length === 3, 'The link appears and the form closes');
    },
    async 'rewrites Jira comments replies and edits only in their drafts'() {
      await mountPreview(); commentFails = false;
      const improved = 'Distributed split generation is not currently available in Harbor.';
      window.bridge.betterWords = async (...args) => { record('rewrite', ...args); return improved; };
      const composer = document.querySelector<HTMLFormElement>('.jira-composer');
      const input = composer?.querySelector('textarea'); assert(composer != null && input != null, 'Comment editor exists');
      assert(button('Better words', composer).disabled, 'An empty draft cannot be rewritten');
      edit(input, 'not available in Harbor'); await settle(); button('Better words', composer).click();
      await until(() => input.value === improved && composer.querySelector('.pr-better-words')?.textContent === 'Discard');
      assert(calls.find(call => call.name === 'rewrite')?.args.join('|') === 'not available in Harbor||', 'Unassigned Jira uses the helper without a repository or workspace');
      assert(!calls.some(call => call.name === 'comment'), 'Rewriting never sends a comment');
      input.focus(); document.execCommand('undo'); await until(() => input.value === 'not available in Harbor' && composer.querySelector('.pr-better-words')?.textContent === 'Better words');
      button('Better words', composer).click(); await until(() => input.value === improved && composer.querySelector('.pr-better-words')?.textContent === 'Discard');
      button('Discard', composer).click(); await until(() => input.value === 'not available in Harbor' && composer.querySelector('.pr-better-words')?.textContent === 'Better words');
      button('Better words', composer).click(); await until(() => input.value === improved && composer.querySelector('.pr-better-words')?.textContent === 'Discard');
      button('Send', composer).click(); await until(() => input.value === '');
      assert(button('Better words', composer).disabled && composer.querySelector('.is-discard') === null, 'A sent draft resets the rewrite action');
      assert(calls.find(call => call.name === 'comment')?.args[0] === improved, 'Send publishes the rewritten draft');

      issue = { ...issue, workspace: { id: 'workspace', name: 'Trino' } };
      button('Refresh ticket').click(); await until(() => document.querySelector('[aria-label="Change ByteQuay workspace: Trino"]') !== null);
      button('Reply to Sam Rivera').click(); await settle();
      const replyForm = document.querySelector<HTMLFormElement>('.jira-reply-form'); const reply = replyForm?.querySelector('textarea');
      assert(replyForm != null && reply != null, 'Reply editor exists');
      edit(reply, 'Harbor not have it'); await settle(); button('Better words', replyForm).click(); await until(() => reply.value === improved);
      const rewrite = calls.filter(call => call.name === 'rewrite').at(-1);
      assert(rewrite?.args[0] === 'Harbor not have it' && rewrite.args[1] === undefined && rewrite.args[2] === 'workspace', 'Reply uses the assigned workspace without a fabricated GitHub repository');
      assert(calls.filter(call => call.name === 'comment').length === 1, 'Rewriting a reply does not post it');
      assert(replyForm.scrollWidth <= replyForm.clientWidth + 1, 'Reply controls fit inside the input box');
      button('Reply', replyForm).click(); await until(() => document.querySelector('.jira-reply-form') === null);
      assert(calls.filter(call => call.name === 'comment').at(-1)?.args[0] === improved, 'Reply submits the rewritten draft');

      const own = document.querySelector('[aria-label="Comment by Alex Chen"]'); assert(own !== null, 'Sent comment exists');
      button('Edit comment', own).click(); await settle();
      const editForm = own.querySelector<HTMLFormElement>('.jira-comment-edit'); const editor = editForm?.querySelector('textarea');
      assert(editForm != null && editor != null, 'Own comment editor exists');
      edit(editor, 'still not available'); await settle(); button('Better words', editForm).click(); await until(() => editor.value === improved);
      assert(!calls.some(call => call.name === 'edit'), 'Rewriting an edit does not save it');
      button('Save', editForm).click(); await until(() => calls.some(call => call.name === 'edit'));
      assert(calls.find(call => call.name === 'edit')?.args[1] === improved, 'Save uses the rewritten edit');
    },
    async 'keeps Jira drafts after rewrite failures and ignores changed or closed replies'() {
      await mountPreview();
      button('Reply to Sam Rivera').click(); await settle();
      const form = document.querySelector<HTMLFormElement>('.jira-reply-form'); const reply = form?.querySelector('textarea');
      assert(form != null && reply != null, 'Reply editor exists');
      edit(reply, 'keep my draft'); await settle();
      window.bridge.betterWords = async () => { throw new Error('The selected model is unavailable.'); };
      button('Better words', form).click(); await until(() => document.querySelector('[role="alert"]')?.textContent === 'The selected model is unavailable.');
      assert(reply.value === 'keep my draft' && !button('Better words', form).disabled, 'A failed rewrite keeps the draft available');
      let finish: (value: string) => void = () => { throw new Error('No pending rewrite'); };
      window.bridge.betterWords = () => new Promise(resolve => { finish = resolve; });
      button('Better words', form).click(); await settle(); assert(button('Improving…', form).disabled, 'Pending rewrites cannot be repeated');
      edit(reply, 'newer draft'); await settle(); finish('outdated rewrite'); await until(() => form.querySelector('.pr-better-words')?.textContent === 'Better words');
      assert(String(reply.value) === 'newer draft', 'Late rewrite cannot overwrite typing');
      button('Better words', form).click(); await settle(); button('Cancel', form).click(); await until(() => document.querySelector('.jira-reply-form') === null);
      button('Reply to Sam Rivera').click(); await settle();
      finish('closed draft rewrite'); await settle();
      assert(document.querySelector<HTMLTextAreaElement>('.jira-reply-form textarea')?.value === '', 'Late rewrite cannot enter a reopened reply');
      assert(!calls.some(call => call.name === 'comment' || call.name === 'edit'), 'Rewrite failures and cancellation never write to Jira');
    },
  })) {
    try { await test(); results.push({ name }); } catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
    root?.unmount(); root = null;
  }
  return results;
}
