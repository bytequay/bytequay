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
import { createFixtureRoot } from '../../fixtures/reactFixture';
import CreateEpicDialog, { type CreatedEpic } from '../CreateEpicDialog';
import type { EpicDraftDto, EpicDraftMessage, EpicDraftReply, UnifiedWorkItem } from '../../workitems/contracts';
import { jiraDetail, jiraIssue } from '../../jira/fixtures/jira';
import type { Bridge, HostingServerDto } from '../../types';
import { PullRequestWorkspaceProvider } from '../../pulls/PullRequestWorkspace';
import { assert, changeInput, settle, until, element } from '../../fixtures/harness';
import '../../css/base.css';
import '../../css/agents.css';

const DRAFT: EpicDraftDto = { title: 'Cache Iceberg REST catalog metadata', description: 'Cache table metadata per catalog.', context: 'Hive keeps its own cache.' };
const remote = (id: string, source: 'GITHUB' | 'JIRA', key: string, label: string, title: string, closed = false): UnifiedWorkItem => ({
  id, source, title, description: '', status: closed ? 'Closed' : 'Open', closed, updatedAt: '2026-10-01T08:00:00Z',
  reference: { kind: source === 'GITHUB' ? 'ISSUE' : 'JIRA', key, label, title, addedAt: '2026-10-01T08:00:00Z' }, work: null,
});
const CATALOG = [
  remote('ISSUE:github.com:500', 'GITHUB', 'owner/repository#12', '#12', 'Iceberg metadata cache'),
  remote('ISSUE:github.com:501', 'GITHUB', 'owner/repository#13', '#13', 'Closed issue', true),
  remote('ISSUE:github.com:502', 'GITHUB', 'owner/repository#14', '#14', 'Already an Epic'),
  remote('JIRA:5:cloud:77', 'JIRA', 'conn:cloud:77', 'ENG-7', 'Parquet reader'),
];


function button(text: string): HTMLButtonElement {
  const found = [...document.querySelectorAll<HTMLButtonElement>('dialog button')].find(candidate => candidate.textContent?.trim() === text);
  assert(found !== undefined, `Missing the ${text} button`);
  return found;
}
async function say(text: string): Promise<void> {
  changeInput(element<HTMLTextAreaElement>('[aria-label="Message the agent about the Epic"]'), text);
  await settle();
  element<HTMLButtonElement>('[aria-label="Send to the agent"]').click();
  await settle();
}
const agentSaid = (text: string) => () => [...document.querySelectorAll('.create-epic__message.is-agent')].some(message => message.textContent?.includes(text) === true);

export async function run(): Promise<boolean> {
  const { root, dispose: disposeRoot } = createFixtureRoot();
  const asked: EpicDraftMessage[][] = [];
  const replies: (EpicDraftReply | Error)[] = [];
  const createdLocal: { id: string; title: string; description: string; context: string }[] = [];
  const markedIssues: number[] = [];
  const markedTickets: string[] = [];
  const created: CreatedEpic[] = [];
  let closes = 0;
  let catalog = CATALOG;
  window.bridge = {
    draftEpic: async (_workspaceId: string, messages: EpicDraftMessage[]) => {
      asked.push(messages);
      const reply = replies.shift();
      assert(reply !== undefined, 'The fixture scripted a reply');
      if (reply instanceof Error) throw reply;
      return reply;
    },
    createLocalEpic: async (_workspaceId: string, request: { id: string; title: string; description: string; context: string }) => { createdLocal.push(request); },
    listUnifiedWorkItems: async (workspaceId: string) => {
      assert(workspaceId === 'workspace', 'The catalog uses the current workspace');
      return catalog;
    },
    setWorkspaceIssueEpic: async (workspaceId: string, number: number) => {
      assert(workspaceId === 'workspace', 'The issue is marked in the current workspace');
      markedIssues.push(number); return {};
    },
    setJiraEpic: async (location: { connectionId: string; cloudId: string; issueId: string }) => {
      markedTickets.push(`${location.connectionId}|${location.cloudId}|${location.issueId}`);
      return { ...jiraDetail(), issue: { ...jiraIssue, isEpic: true } };
    },
  } as unknown as Bridge;
  const render = (jiraConnected = false, server?: HostingServerDto): void => flushSync(() => root.render(
    <PullRequestWorkspaceProvider workspaceId="workspace" server={server}>
      <CreateEpicDialog workspaceId="workspace" marked={new Set(['ISSUE:github.com:502'])} jiraConnected={jiraConnected}
        onClose={() => { closes++; }} onCreated={value => { created.push(value); }} />
    </PullRequestWorkspaceProvider>));
  const close = (): void => flushSync(() => root.render(<></>));
  try {
    render(); await settle();
    assert(element<HTMLDialogElement>('dialog.create-epic').open && button('Talk it through').getAttribute('aria-pressed') === 'true',
      'Create Epic opens on talking it through');
    assert(document.querySelector('.create-epic__hint')?.textContent?.includes('drafts the Epic for you to approve') === true, 'An empty conversation says what happens');

    // The agent asks first; closing the dialog by accident keeps the conversation.
    replies.push({ message: 'Which catalogs should cache metadata?', draft: null, create: false });
    await say('Cache Iceberg catalog metadata');
    await until(agentSaid('Which catalogs'), 'The agent asks what it needs to know');
    assert(element('.create-epic__message.is-user').textContent === 'Cache Iceberg catalog metadata', 'The user\'s words show as sent');
    element<HTMLButtonElement>('[aria-label="Close Create Epic"]').click();
    assert(closes === 1, 'Close asks to close');
    close(); render(); await settle();
    assert(document.querySelectorAll('.create-epic__message').length === 2, 'Reopening keeps the conversation');

    // A failed reply can be asked again with the same conversation.
    replies.push(new Error('The selected model could not answer: timed out'));
    await say('Only the Iceberg REST catalog');
    await until(() => document.querySelector('.create-epic__retry') !== null, 'A failed reply offers Ask again');
    assert(element('.create-epic__retry').textContent?.includes('timed out') === true, 'The failure says why');
    replies.push({ message: 'Here is a draft. Create it?', draft: DRAFT, create: false });
    button('Ask again').click();
    await until(() => document.querySelector('.create-epic__draft') !== null, 'The agent offers a draft on its own');
    assert(JSON.stringify(asked[2]) === JSON.stringify(asked[1]), 'Ask again sends the same conversation');
    const draft = element('.create-epic__draft');
    assert(draft.textContent?.includes(DRAFT.title) === true && draft.textContent.includes('Hive keeps its own cache.'), 'The draft shows its title, description and context');

    // Edit moves the draft into the form; the conversation waits on its own tab.
    button('Edit').click(); await settle();
    assert(element<HTMLInputElement>('.create-epic__form input').value === DRAFT.title && button('Local').getAttribute('aria-pressed') === 'true',
      'Edit opens the Local form with the draft');
    assert(document.querySelector('.create-epic__sources')?.textContent?.includes('Jira') === false, 'Without Jira there is no Jira source');
    button('Talk it through').click(); await settle();
    assert(document.querySelector('.create-epic__draft') !== null, 'The conversation is still there');

    // A yes in words creates the draft the user saw, even when the reply changed it, and ends the conversation.
    replies.push({ message: 'Creating it.', draft: { ...DRAFT, title: 'Something else' }, create: true });
    await say('yes, create it');
    await until(() => created.length === 1, 'Approving in words creates the Epic');
    const sent = asked.at(-1) ?? [];
    assert(sent.length === 5 && JSON.stringify(sent[3].draft) === JSON.stringify(DRAFT), 'The model reads the draft it showed');
    assert(createdLocal.length === 1 && createdLocal[0].title === DRAFT.title && createdLocal[0].context === DRAFT.context, `The shown draft is created: ${JSON.stringify(createdLocal)}`);
    assert(created[0].kind === 'local' && created[0].itemId === createdLocal[0].id, 'The new Local Epic opens by its id');
    close(); render(); await settle();
    assert(document.querySelectorAll('.create-epic__message').length === 0, 'Creating the Epic ends its conversation');

    // Created by hand: Local, a GitHub issue, or a Jira ticket.
    button('Create yourself').click(); await settle();
    const [title, description, context] = [...document.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>('.create-epic__form :is(input, textarea)')];
    assert(button('Create Epic').disabled, 'A Local Epic needs a title');
    changeInput(title, 'Pin product-test images'); changeInput(description, 'Every image has a digest.'); changeInput(context, '');
    await settle();
    button('Create Epic').click();
    await until(() => created.length === 2, 'Create Epic makes the Local Epic');
    assert(JSON.stringify(createdLocal[1]) === JSON.stringify({ id: createdLocal[1].id, title: 'Pin product-test images', description: 'Every image has a digest.', context: '' }),
      `The form's fields are sent: ${JSON.stringify(createdLocal[1])}`);

    close(); render(); await settle();
    button('Create yourself').click(); await settle();
    button('GitHub issue').click();
    await until(() => document.querySelectorAll('.create-epic__result').length > 0, 'Open issues load');
    const issues = [...document.querySelectorAll('.create-epic__result')].map(row => row.querySelector('.create-epic__ref')?.textContent);
    assert(JSON.stringify(issues) === JSON.stringify(['#12']), `Only open issues that are not Epics are offered: ${JSON.stringify(issues)}`);
    changeInput(element<HTMLInputElement>('.create-epic__search input'), 'nothing like it'); await settle();
    assert(document.querySelectorAll('.create-epic__result').length === 0 && element('.create-epic__results').textContent?.includes('Nothing matches') === true, 'Search narrows the list');
    changeInput(element<HTMLInputElement>('.create-epic__search input'), '#12'); await settle();
    button('Mark as Epic').click();
    await until(() => created.length === 3, 'Marking an issue creates the Epic');
    assert(JSON.stringify(markedIssues) === JSON.stringify([12]) && JSON.stringify(created[2]) === JSON.stringify({ kind: 'remote', epicId: 'ISSUE:github.com:500' }),
      'The issue is marked by its number and opens by its Epic identity');

    close(); render(true); await settle();
    button('Create yourself').click(); await settle();
    button('Jira ticket').click();
    await until(() => document.querySelector('.create-epic__result') !== null, 'Jira tickets load');
    assert(element('.create-epic__ref').textContent === 'ENG-7', 'A connected Jira offers the workspace\'s tickets');
    button('Mark as Epic').click();
    await until(() => created.length === 4, 'Marking a ticket creates the Epic');
    assert(JSON.stringify(markedTickets) === JSON.stringify(['conn|cloud|77']) && JSON.stringify(created[3]) === JSON.stringify({ kind: 'remote', epicId: 'JIRA:5:cloud:77' }),
      `The ticket is marked by its location and opens by its Epic identity: ${JSON.stringify(markedTickets)}`);
    // GitLab uses the shared issue catalog and marking endpoint, while Jira remains independently available.
    catalog = [remote('ISSUE:https://gitlab.example.com:600', 'GITHUB', 'group/subgroup/repository#21', '#21', 'GitLab request'), CATALOG[3]];
    const server: HostingServerDto = { provider: 'GITLAB', webBaseUrl: 'https://gitlab.example.com', apiBaseUrl: 'https://gitlab.example.com/api/v4' };
    close(); render(true, server); await settle();
    button('Create yourself').click(); await settle();
    assert(!element('.create-epic__sources').textContent?.includes('GitHub'), 'A GitLab workspace does not offer GitHub issues');
    button('GitLab issue').click();
    await until(() => document.querySelector('.create-epic__result') !== null, 'GitLab issues load');
    assert(element('.create-epic__ref').textContent === '#21', 'The GitLab issue is offered');
    assert(element('.create-epic__note').textContent?.includes('issue on GitLab stays unchanged') === true, 'The note names GitLab');
    button('Jira ticket').click(); await settle();
    assert(element('.create-epic__ref').textContent === 'ENG-7', 'Jira tickets remain available in a GitLab workspace');
    button('GitLab issue').click(); await settle();
    button('Mark as Epic').click();
    await until(() => created.length === 5, 'Marking a GitLab issue creates the Epic');
    assert(markedIssues.at(-1) === 21 && JSON.stringify(created[4]) === JSON.stringify({ kind: 'remote', epicId: 'ISSUE:https://gitlab.example.com:600' }),
      'The GitLab issue is marked by its number and opens by its server-scoped Epic identity');
    return true;
  }
  finally { disposeRoot(); }
}
