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
import { useEffect, useState } from 'react';
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import AppSidebar from '../../AppSidebar';
import { assert, settle } from '../../fixtures/harness';
import type { Bridge, ModelSettingsDto, PullRequestDto, WorkspaceDto } from '../../types';
import AttentionList from '../AttentionList';
import HomePage from '../HomePage';
import { ATTENTION_SEED, attentionActivityVersion, handledEntry, loadHandled, pullRequestItem, saveHandled, type AttentionItem } from '../attention';
import { getCached, setCached } from '../../dataCache';
import { useWorkspaceAttention } from '../../workspaces/workspaceAttention';
import { loadPinned } from '../pinned';
import { useAttentionVisit, type AttentionAction } from '../useAttentionVisit';
import '../../css/base.css';
import '../../css/home.css';

const noOp = () => {};
const workspace = { id: 'ws-1', name: 'octo/app', monitoredOwner: 'octo', monitoredRepo: 'app', localRepositoryPath: '/managed/ws-1' } as WorkspaceDto;

function item(number: number): AttentionItem {
  const pr: PullRequestDto = {
    id: number, repo: 'octo/app', number, title: `Review change ${number}`, author: 'alice',
    htmlUrl: `https://github.com/octo/app/pull/${number}`, createdAt: null, updatedAt: '2026-09-01T00:00:00Z',
    origin: 'REVIEW_REQUESTED', labels: [], labelColors: null, draft: false, viewedAt: null, reviewedAt: null,
    handledAction: null, requestedReviewers: ['viewer'], ciStatus: null, additions: 0, deletions: 0, commentCount: 1,
    state: 'open', mergeable: null, mergeableState: null, headPushedAt: null, headRef: 'branch',
  };
  return pullRequestItem(pr, 'review');
}

function button(host: ParentNode, label: string): HTMLButtonElement {
  const found = [...host.querySelectorAll<HTMLButtonElement>('button')]
    .find(candidate => candidate.getAttribute('aria-label') === label || candidate.textContent?.trim() === label
      || candidate.querySelector('.atn-menu__text')?.textContent === label);
  assert(found !== undefined, `Missing button: ${label}`);
  return found;
}

/** The detail contract records an action only once its write resolves. */
function Detail({ value, perform }: { value: AttentionItem; perform: () => Promise<void> }) {
  const { status, onAction } = useAttentionVisit(value);
  const [error, setError] = useState('');
  const submit = (action: AttentionAction, count = 1) => {
    setError('');
    void perform().then(() => onAction(action, count)).catch(() => setError('Write failed'));
  };
  return <section className="fixture-detail">
    <output className="fixture-status">{status}</output>
    {(['review', 'prompt', 'update', 'comment'] as const).map(action => <button type="button" key={action} onClick={() => submit(action)}>{action}</button>)}
    <button type="button" onClick={() => submit('comment', 2)}>post two comments</button>
    <output className="fixture-error">{error}</output>
  </section>;
}

function mount(initial: AttentionItem[]) {
  window.localStorage.clear();
  window.bridge = { setPrAttentionHandled: async () => {}, setPrAttentionPinned: async () => {} } as unknown as Bridge;
  const host = document.createElement('div');
  document.body.append(host);
  let root = createRoot(host);
  let items = initial;
  let selected: string | null = null;
  let readers = 0;
  let currentWorkspace: WorkspaceDto | null = null;
  let perform = async () => {};
  let opens = 0;

  function Surface() {
    const [handled, setHandled] = useState(loadHandled);
    useEffect(() => {
      const changed = () => setHandled(loadHandled());
      window.addEventListener('bytequay:attention-handled-changed', changed);
      return () => window.removeEventListener('bytequay:attention-handled-changed', changed);
    }, []);
    const viewed = items.find(candidate => candidate.key === selected);
    return <>
      <AppSidebar page={currentWorkspace === null ? 'home' : 'workspaces'} workspace={currentWorkspace} workspaceTab="home"
        onWorkspaceTab={noOp} onNavigate={noOp} canGoBack={false} canGoForward={false} onBack={noOp} onForward={noOp}
        browserRailVisible={false} onToggleBrowserRail={noOp} />
      <AttentionList candidates={items} handled={handled} viewerLogin="viewer" onHandledChange={saveHandled}
        onOpen={value => { opens++; selected = value.key; readers = 1; render(); }} />
      {viewed !== undefined && Array.from({ length: readers }, (_, index) => <Detail key={index} value={viewed} perform={() => perform()} />)}
    </>;
  }

  const render = () => flushSync(() => root.render(<Surface />));
  render();
  return {
    host,
    get opens() { return opens; },
    cards: () => [...host.querySelectorAll<HTMLElement>('.atn-card')],
    async open() { host.querySelector<HTMLElement>('.atn-card')?.click(); await settle(); },
    async visit(key: string) { selected = key; readers = 1; render(); await settle(); },
    async close() { readers = 0; render(); await settle(); },
    async readers(value: number) { readers = value; render(); await settle(); },
    async items(value: AttentionItem[]) { items = value; render(); await settle(); },
    async workspace(value: WorkspaceDto | null) { currentWorkspace = value; render(); await settle(); },
    perform(value: () => Promise<void>) { perform = value; },
    async remount() { flushSync(() => root.unmount()); await settle(); root = createRoot(host); render(); await settle(); },
    async dispose() { flushSync(() => root.unmount()); await settle(); host.remove(); },
  };
}

async function viewing(): Promise<void> {
  const original = item(41);
  const f = mount([original]);
  try {
    await settle();
    await f.open();
    assert(f.cards().length === 1 && !f.cards()[0].textContent?.includes('Viewed'), 'Viewing keeps the card without a Viewed label until detail closes');
    assert(loadHandled()[original.key] === undefined, 'Opening a card must not acknowledge it early');
    await f.readers(2);
    await f.readers(1);
    assert(loadHandled()[original.key] === undefined && f.cards().length === 1, 'Closing one of two detail hosts must keep the shared visit open');
    await f.close();
    assert(f.cards().length === 0 && loadHandled()[original.key]?.label === 'Viewed', 'Closing the last unchanged detail must consume the viewed notification');
    const changed = { ...original, title: 'Updated after viewing', updatedAt: '2026-09-02T00:00:00Z',
      pr: original.pr === null ? null : { ...original.pr, title: 'Updated after viewing', updatedAt: '2026-09-02T00:00:00Z' } };
    await f.items([changed]);
    assert(f.cards().length === 1 && !f.cards()[0].textContent?.includes('Viewed'), 'New activity must bring back the card without the prior visit status');
    button(f.host, 'Acknowledge #41').click(); await settle();
    assert(f.cards().length === 0 && loadHandled()[original.key]?.label === 'acknowledged', 'Explicit Ack must remain just acknowledgment');
  }
  finally { await f.dispose(); }
}

async function actions(): Promise<void> {
  const original = item(42);
  const f = mount([original]);
  try {
    await settle(); await f.open();
    let finish = noOp;
    f.perform(() => new Promise<void>(resolve => { finish = resolve; }));
    button(f.host, 'review').click(); await settle();
    assert(!f.cards()[0].textContent?.includes('Viewed') && !f.cards()[0].textContent?.includes('Reviewed'), 'An unfinished write must not show a visit action');
    finish(); await settle();
    assert(f.cards()[0].textContent?.includes('Reviewed') === true, 'A successful review must update the visible card');
    f.perform(async () => { throw new Error('Rejected'); });
    button(f.host, 'comment').click(); await settle();
    assert(f.host.textContent?.includes('Write failed') === true && !f.cards()[0].textContent?.includes('Posted'), 'A failed comment must not count as posted');
    f.perform(async () => {});
    for (const action of ['prompt', 'update', 'post two comments']) { button(f.host, action).click(); await settle(); }
    const status = 'Reviewed · Prompted · Updated state · Posted 2 comments';
    assert(f.cards().length === 1 && f.cards()[0].textContent?.includes(status) === true, 'Successful actions must retain the card and show their distinct status and comment count');
    await f.close();
    assert(f.cards().length === 0 && loadHandled()[original.key]?.label === status, 'Finishing the visit must acknowledge with the successful action summary');
  }
  finally { await f.dispose(); }
}

async function pinning(): Promise<void> {
  const original = item(43);
  const f = mount([original]);
  try {
    await settle();
    button(f.host, `Pin ${original.title}`).click(); await settle();
    assert(f.cards().length === 0 && f.opens === 0 && loadPinned()[0]?.key === original.key, 'Pin must suppress attention without opening the card');
    for (const destination of [workspace, { ...workspace, id: 'ws-2', name: 'another/project' }, null]) {
      await f.workspace(destination);
      assert(f.host.querySelector('[aria-label="Pinned navigation"]')?.textContent?.includes(original.title) === true, 'Global pin must remain visible when changing workspace');
    }
    await f.remount();
    assert(f.cards().length === 0 && f.host.querySelector('[aria-label="Pinned navigation"]')?.textContent?.includes(original.title) === true,
      'Persisted pins must survive a renderer remount and still suppress attention');
    button(f.host, `Unpin ${original.title}`).click(); await settle();
    assert(loadPinned().length === 0 && f.cards().length === 1 && f.opens === 0, 'Unpin must restore an otherwise unhandled card');
  }
  finally { await f.dispose(); }
}

async function canonicalSnapshot(): Promise<void> {
  const original = item(46);
  assert(original.pr !== null, 'Fixture must be a pull request');
  const updated = pullRequestItem({ ...original.pr, activityAt: '2026-09-02T00:00:00Z', commentCount: 3 }, 'review');
  const f = mount([original]);
  let publish = noOp;
  let reads = 0;
  let refreshed = false;
  window.bridge.refreshPullRequestDetailByRef = async () => {
    await new Promise<void>(resolve => { publish = resolve; });
    refreshed = true;
    return { full: false, detail: null, status: { mergeable: null, mergeableState: null, ciStatus: null, checkRuns: [], unavailableSections: [] } };
  };
  window.bridge.fetchAttentionPrs = async () => {
    reads++;
    const row = refreshed ? updated.pr : original.pr;
    return row === null ? [] : [row];
  };
  try {
    await settle(); await f.open();
    button(f.host, 'post two comments').click(); await settle();
    assert(reads === 1, 'A write must refresh the source before reading its canonical local PR snapshot');
    await f.close();
    assert(loadHandled()[original.key] === undefined, 'Closing after a write must wait for the canonical snapshot before acknowledgment');
    publish(); await settle();
    assert(Number(reads) === 2, 'Successful refresh must read the canonical local PR snapshot');
    assert(loadHandled()[original.key]?.seenActivityVersion === attentionActivityVersion(updated), 'Acknowledgment must consume the successful write’s canonical activity version');
    await f.items([updated]);
    assert(f.cards().length === 0, 'The user’s own published comments must stay handled when the list catches up');
    assert(updated.pr !== null, 'Updated fixture must be a pull request');
    await f.items([pullRequestItem({ ...updated.pr, activityAt: '2026-09-03T00:00:00Z', commentCount: 4 }, 'review')]);
    assert(f.cards().length === 1, 'Later remote activity must return after the canonical post-write snapshot was handled');
  }
  finally { publish(); await f.dispose(); }
}

async function snoozing(): Promise<void> {
  const original = item(44);
  const unchanged = item(45);
  const f = mount([original, unchanged]);
  try {
    await settle();
    button(f.host, 'Snooze #44').click(); await settle();
    assert(!document.querySelector('.atn-menu')?.textContent?.includes('Until new activity'), 'Snooze must offer only a timed return');
    button(document, '1 hour').click(); await settle();
    assert(f.cards().length === 1 && loadHandled()[original.key]?.kind === 'snooze', 'Snooze action must hide only its own card');
    const deadline = Date.now() + 500;
    saveHandled({ [original.key]: handledEntry(original, 'snooze', deadline, 'soon'), [unchanged.key]: handledEntry(unchanged, 'snooze', deadline, 'soon') });
    await settle();
    const changed: AttentionItem = { ...original, title: 'Latest title while snoozed', updatedAt: '2026-09-02T00:00:00Z', commentCount: 2,
      pr: original.pr === null ? null : { ...original.pr, title: 'Latest title while snoozed', updatedAt: '2026-09-02T00:00:00Z', commentCount: 2,
        latestActivity: { event: 'issue_comment', actor: 'bob', timestamp: '2026-09-02T00:00:00Z', state: null, label: null, requestedReviewer: null, excerpt: 'Latest comment while snoozed' } } };
    await f.items([changed, unchanged]);
    assert(f.cards().length === 0, 'Changes while snoozed must not wake a timed reminder early');
    await f.visit(original.key);
    await f.close();
    assert(loadHandled()[original.key]?.kind === 'snooze', 'Reading elsewhere must preserve a pending timed reminder');
    await new Promise<void>(resolve => window.setTimeout(resolve, Math.max(0, deadline - Date.now()) + 80));
    await settle();
    assert(f.cards().length === 2, 'Timed snoozes must return automatically whether or not an item changed');
    assert(f.cards()[0].textContent?.includes('Latest title while snoozed') === true
      && f.cards()[0].textContent?.includes('Latest comment while snoozed') === true, 'Woken cards must display the latest title and comment');
  }
  finally { await f.dispose(); }
}

async function retainedReminder(): Promise<void> {
  const original = item(50);
  const f = mount([original]);
  window.bridge.findStoredPullRequest = async () => original.pr === null ? null : { ...original.pr, title: 'Closed while snoozed', state: 'closed' };
  try {
    await settle();
    const deadline = Date.now() + 200;
    saveHandled({ [original.key]: handledEntry(original, 'snooze', deadline, 'soon') });
    await f.items([]);
    assert(f.cards().length === 0, 'A snoozed item that leaves ordinary eligibility must stay quiet before its deadline');
    await new Promise<void>(resolve => window.setTimeout(resolve, Math.max(0, deadline - Date.now()) + 100));
    await settle();
    assert(f.cards().length === 1 && f.cards()[0].textContent?.includes('Closed while snoozed') === true,
      'An expired reminder must return with its latest closed state even after leaving the candidate query');
    button(f.host, 'Acknowledge #50').click(); await settle();
    assert(f.cards().length === 0, 'Acknowledging a retained reminder must remove it without ordinary eligibility');
  }
  finally { await f.dispose(); }
}

async function latestSummary(): Promise<void> {
  window.localStorage.clear();
  const pending: ((rows: PullRequestDto[]) => void)[] = [];
  window.bridge = {
    fetchAttentionPrs: () => new Promise<PullRequestDto[]>(resolve => pending.push(resolve)),
    getUserProfile: async () => { throw new Error('Profile unavailable'); },
    listWorkspaces: async (): ReturnType<Bridge['listWorkspaces']> => [],
    listJiraConnections: async (): ReturnType<Bridge['listJiraConnections']> => [],
    listJiraAttention: async (): ReturnType<Bridge['listJiraAttention']> => [],
    getModelSettings: async (): Promise<Pick<ModelSettingsDto, 'providers'>> => ({ providers: [] }),
    fetchPrs: async (): ReturnType<Bridge['fetchPrs']> => ({ pullRequests: [], page: 0, pageSize: 50, totalPullRequests: 0, openPullRequests: 0, hasMore: false }),
    onPullRequestsSynced: () => noOp,
  } as unknown as Bridge;
  setCached(ATTENTION_SEED.prs, []);
  const newest = item(51).pr;
  assert(newest !== null, 'Summary fixture is a pull request');
  function Workspace() {
    const { candidates } = useWorkspaceAttention('ws-1', 'octo/app', { items: [], tasks: [], activity: {}, refresh: noOp });
    return <output className="workspace-attention">{candidates.map(candidate => candidate.title).join(', ')}</output>;
  }
  const host = document.createElement('div'); document.body.append(host);
  const root = createRoot(host);
  try {
    flushSync(() => root.render(<><HomePage onSelectRepo={noOp} onOpenWorkspace={noOp} /><Workspace /></>));
    await settle();
    assert(pending.length === 2, 'Both Homes start their local attention reads');
    window.dispatchEvent(new CustomEvent('bytequay:pr-attention-updated', { detail: [newest] }));
    await settle();
    assert(host.querySelector('.atn-card')?.textContent?.includes(newest.title) === true
      && host.querySelector('.workspace-attention')?.textContent === newest.title, 'A fresh summary immediately reaches both Homes');
    pending.forEach(resolve => resolve([])); await settle();
    assert(host.querySelector('.atn-card')?.textContent?.includes(newest.title) === true
      && host.querySelector('.workspace-attention')?.textContent === newest.title
      && getCached<PullRequestDto[]>(ATTENTION_SEED.prs)?.[0]?.title === newest.title,
    'Older reads completing after a broadcast cannot erase its review request or cache');
  }
  finally { flushSync(() => root.unmount()); await settle(); host.remove(); }
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  for (const [name, scenario] of [['viewed until close', viewing], ['successful action status', actions], ['canonical post-write snapshot', canonicalSnapshot],
    ['global pins', pinning], ['timed snoozes', snoozing], ['retained reminders', retainedReminder], ['latest summary wins', latestSummary]] as const) {
    try { await scenario(); results.push({ name }); }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
  }
  return results;
}

let preview: ReturnType<typeof mount> | null = null;

export async function showPreview(): Promise<void> {
  await preview?.dispose();
  const pinned = { ...item(47), title: 'Fix checkpoint deletion-vector identity' };
  preview = mount([pinned, { ...item(48), title: 'Support the updated connector behavior' }, item(49)]);
  preview.host.style.cssText = 'display:flex;height:100vh;';
  const attention = preview.host.querySelector<HTMLElement>('.atn-section');
  assert(attention !== null, 'Attention preview must render');
  attention.style.cssText = 'flex:1;min-width:0;padding:24px;';
  await settle();
  button(preview.host, `Pin ${pinned.title}`).click(); await settle();
  await preview.open();
  for (const action of ['review', 'post two comments']) { button(preview.host, action).click(); await settle(); }
  preview.host.querySelectorAll<HTMLElement>('.fixture-detail').forEach(detail => { detail.style.display = 'none'; });
  await settle();
}
