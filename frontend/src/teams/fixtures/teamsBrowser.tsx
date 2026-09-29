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
import { createRoot, type Root } from 'react-dom/client';
import AppSidebar, { type AppPage } from '../../AppSidebar';
import { assert, settle } from '../../fixtures/harness';
import { pr } from '../../pulls/fixtures/pullRequest';
import type { Bridge, PullRequestDto } from '../../types';
import TeamsPage from '../TeamsPage';
import { membersOf } from '../activity';
import type { TeamActivityEvent, TeamActivityQuery, TeamActivitySnapshot, TeamDirectory, TeamMember, TeamStatistics } from '../types';
import { GITHUB_ACCOUNT_CHANGED, updateTeams } from '../useTeams';
import '../../css/base.css';
import '../../css/home.css';
import '../../css/pulls.css';

const noOp = () => {};
const alice: TeamMember = { githubId: 101, login: 'alice', name: 'Alice Chen', avatarUrl: '' };
const bob: TeamMember = { githubId: 202, login: 'bob', name: 'Bob Rivera', avatarUrl: '' };
const cara: TeamMember = { githubId: 303, login: 'cara', name: 'Cara Patel', avatarUrl: '' };
const knownPeople = [alice, bob, cara];
const ago = (hours: number) => new Date(Date.now() - hours * 3_600_000).toISOString();

function seededDirectory(): TeamDirectory {
  return { accountLogin: 'viewer', circles: [
    { id: 'trusted-oss', name: 'Trusted OSS', description: 'Trusted open-source collaborators', avatar: { color: 'teal', shape: 'circle' }, repositories: ['trinodb/trino'], members: [alice, bob] },
    { id: 'platform', name: 'Platform team', description: 'Our internal platform group', avatar: { color: 'gray', shape: 'square' }, repositories: ['acme/query-engine'], members: [alice, cara] },
  ] };
}

function event(id: string, kind: TeamActivityEvent['kind'], actor: { githubId: number; login: string }, number: number, title: string, hours: number,
  extra: Partial<TeamActivityEvent> = {}): TeamActivityEvent {
  return { id, kind, actorId: actor.githubId, actorLogin: actor.login, repository: 'trinodb/trino', number, title,
    url: `https://github.com/trinodb/trino/${kind.startsWith('ISSUE_') ? 'issues' : 'pull'}/${number}#${id}`,
    occurredAt: ago(hours), subjectAuthorId: alice.githubId, subjectAuthorLogin: alice.login,
    source: 'REPOSITORY', viewerInteractionMemberIds: [], ...extra };
}

function seededEvents(): TeamActivityEvent[] {
  const title = 'Avoid repeated metadata calls when planning large queries';
  return [
    event('review-1', 'PR_REVIEWED', bob, 24118, title, 1),
    event('comment-1', 'PR_COMMENT', alice, 24118, title, 2, { viewerInteractionMemberIds: [alice.githubId] }),
    event('outsider-1', 'PR_COMMENT', { githubId: 777, login: 'mallory' }, 24118, title, 2.5),
    event('merge-1', 'PR_MERGED', alice, 24105, 'Improve predicate pushdown for nested Parquet columns', 3),
    event('issue-1', 'ISSUE_OPENED', alice, 24122, 'Document retry behavior for long-running transactions', 4),
    event('review-2', 'PR_REVIEWED', alice, 24090, 'Handle connection resets during query cancellation', 5,
      { subjectAuthorId: 999, subjectAuthorLogin: 'viewer', viewerInteractionMemberIds: [alice.githubId] }),
    event('platform-1', 'PR_OPENED', cara, 318, 'Reduce coordinator memory use during deployment rollouts', 6,
      { repository: 'acme/query-engine', url: 'https://github.com/acme/query-engine/pull/318', subjectAuthorId: cara.githubId, subjectAuthorLogin: cara.login }),
    event('unknown-merge', 'PR_MERGED', { githubId: 0, login: 'unknown' }, 24095, 'Remove deprecated hive.s3 properties', 8,
      { subjectAuthorId: bob.githubId, subjectAuthorLogin: bob.login }),
  ];
}

function statistics(person: TeamMember): TeamStatistics {
  return { prsOpened: person === alice ? 12 : 0, authoredPrsMerged: person === alice ? 10 : 3, mergesPerformed: 2, prsReviewed: person === alice ? 8 : 4,
    issuesOpened: 3, issuesClosed: 2, comments: 24, directInteractions: person === alice ? 50 : 7, sharedPullRequests: 8, sharedIssues: 3 };
}

const alicePr = (patch: Partial<PullRequestDto> = {}) => pr({ id: 9001, repo: 'trinodb/trino', number: 24830, title: 'Add dynamic filtering for Iceberg MERGE',
  author: 'alice', origin: 'REVIEW_REQUESTED', requestedReviewers: ['viewer'], reviewRequestedAt: ago(50), updatedAt: ago(3),
  latestActivity: { event: 'commented', actor: 'mallory', timestamp: ago(3), label: null, state: null, requestedReviewer: null,
    excerpt: `**Walkthrough** ${Array.from({ length: 130 }, (_, index) => `word${index}`).join(' ')}` }, ...patch });

function button(label: string, host: ParentNode = document): HTMLButtonElement {
  const found = [...host.querySelectorAll<HTMLButtonElement>('button, [role="button"]')]
    .find(candidate => candidate.getAttribute('aria-label') === label || candidate.textContent?.trim() === label || candidate.title === label);
  assert(found !== undefined, `Missing button: ${label}`);
  return found;
}

const settled = async () => { await settle(); await settle(); };
async function click(label: string, host: ParentNode = document) { button(label, host).click(); await settled(); }
const text = () => document.body.textContent ?? '';

async function input(label: string, value: string): Promise<void> {
  const element = document.querySelector<HTMLInputElement | HTMLTextAreaElement>(`[aria-label="${label}"]`);
  assert(element !== null, `Missing input: ${label}`);
  const prototype = element instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(prototype, 'value')?.set?.call(element, value);
  element.dispatchEvent(new Event('input', { bubbles: true }));
  await settled();
}

async function press(label: string, key: string, modifiers: KeyboardEventInit = {}): Promise<void> {
  const target = label === '' ? document.body : document.querySelector<HTMLElement>(`[aria-label="${label}"]`);
  assert(target !== null, `Missing key target: ${label}`);
  target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...modifiers }));
  await settled();
}

async function openTab(label: string): Promise<void> {
  const tab = [...document.querySelectorAll<HTMLButtonElement>('[role="tab"]')].find(candidate => candidate.firstChild?.textContent === label);
  assert(tab !== undefined, `Missing tab: ${label}`);
  tab.click();
  await settled();
}

const selectedTab = () => document.querySelector('.bq-page-tabs [aria-selected="true"]')?.textContent?.replace(/\d+$/, '') ?? '';
const pageTitle = () => document.querySelector('.bq-page-header__title')?.textContent ?? '';

let root: Root | null = null;
async function dispose() { if (root) { flushSync(() => root?.unmount()); root = null; await settled(); } }

async function mount(initial = seededDirectory(), { home = false, observations = seededEvents(), prs = [] as PullRequestDto[], keepStorage = false } = {}) {
  await dispose();
  if (!keepStorage) window.localStorage.clear();
  const directory = structuredClone(initial);
  const queries: TeamActivityQuery[] = [];
  const opened: string[] = [];
  const created: unknown[] = [];
  const removed: number[] = [];
  const added: { id: string; githubId: number }[] = [];
  let refreshes = 0;
  let failRefresh = false;
  let delayRefresh = false;
  let finishRefresh = noOp;
  let createFailure: string | null = null;
  const copy = () => structuredClone(directory);
  function snapshot(query: TeamActivityQuery = {}): TeamActivitySnapshot {
    queries.push({ ...query });
    const circles = directory.circles.filter(circle => !query.circleId || circle.id === query.circleId);
    const people = membersOf(circles).filter(person => !query.memberId || person.githubId === query.memberId);
    const ids = new Set(people.map(person => person.githubId));
    const involves = (item: TeamActivityEvent, id: number) => item.actorId === id || item.subjectAuthorId === id || item.viewerInteractionMemberIds.includes(id);
    const events = observations.filter(item => circles.some(circle => (circle.repositories.length === 0 || circle.repositories.includes(item.repository))
      && circle.members.some(person => ids.has(person.githubId) && involves(item, person.githubId))))
      .filter(item => {
        switch (query.filter ?? 'all') {
          case 'merged': return item.kind === 'PR_MERGED';
          case 'reviews': return item.kind === 'PR_REVIEWED' || item.kind === 'PR_REVIEW_COMMENT';
          case 'issues': return item.kind.startsWith('ISSUE_');
          case 'interactions': return item.viewerInteractionMemberIds.some(id => ids.has(id));
          case 'reviews-performed': return item.kind === 'PR_REVIEWED' && ids.has(item.actorId);
          case 'authored-merges': return item.kind === 'PR_MERGED' && item.subjectAuthorId !== null && ids.has(item.subjectAuthorId);
          case 'all': return true;
          default: return false;
        }
      }).sort((left, right) => Date.parse(right.occurredAt) - Date.parse(left.occurredAt));
    const days = query.days ?? 7;
    const total = people.map(person => statistics(knownPeople.find(known => known.githubId === person.githubId) ?? person))
      .reduce((sum, each) => Object.fromEntries(Object.entries(sum).map(([key, value]) => [key, value + each[key as keyof TeamStatistics]])) as TeamStatistics,
        { prsOpened: 0, authoredPrsMerged: 0, mergesPerformed: 0, prsReviewed: 0, issuesOpened: 0, issuesClosed: 0, comments: 0, directInteractions: 0, sharedPullRequests: 0, sharedIssues: 0 });
    return {
      accountLogin: directory.accountLogin, events: events.slice(0, query.limit ?? 100), updates: events.length,
      workItems: new Set(events.map(item => `${item.repository}#${item.number}`)).size,
      people: people.map(person => ({ memberId: person.githubId, login: person.login,
        statistics: statistics(knownPeople.find(known => known.githubId === person.githubId) ?? person), lastActiveAt: ago(2) })),
      totals: total,
      days: Array.from({ length: days }, (_, index) => ({ date: new Date(Date.now() - (days - 1 - index) * 86_400_000).toLocaleDateString('en-CA'),
        updates: index % 3, collected: index >= 5 })),
      coverage: { partial: false, observedSince: ago(168), lastRefreshedAt: ago(0.2), errors: [], memberCount: people.length, membersScanned: people.length,
        repositoryCount: circles.length, repositoriesScanned: circles.length },
    };
  }
  const bridge = {
    getTeams: async () => copy(),
    fetchTeamContributors: async () => knownPeople.map(person => ({ id: person.githubId, login: person.login, avatarUrl: person.avatarUrl, bot: false })),
    createTeam: async value => {
      created.push(value);
      if (createFailure !== null) throw new Error(createFailure);
      directory.circles.push({ id: `circle-${directory.circles.length + 1}`, ...value,
        members: knownPeople.filter(person => value.members.some(selected => selected.githubId === person.githubId)) });
      return copy();
    },
    updateTeam: async (id, value) => { directory.circles = directory.circles.map(circle => circle.id === id ? { ...circle, ...value, members: circle.members } : circle); return copy(); },
    removeTeam: async id => { directory.circles = directory.circles.filter(circle => circle.id !== id); return copy(); },
    addTeamMember: async (id, selected) => {
      added.push({ id, githubId: selected.githubId });
      const person = knownPeople.find(candidate => candidate.githubId === selected.githubId);
      if (!person) throw new Error('GitHub user not found');
      directory.circles = directory.circles.map(circle => circle.id !== id || circle.members.some(member => member.githubId === person.githubId)
        ? circle : { ...circle, members: [...circle.members, person] });
      return copy();
    },
    removeTeamMember: async (id, memberId) => {
      removed.push(memberId);
      directory.circles = directory.circles.map(circle => circle.id === id ? { ...circle, members: circle.members.filter(person => person.githubId !== memberId) } : circle);
      return copy();
    },
    getTeamActivity: async query => snapshot(query),
    refreshTeamActivity: async query => {
      refreshes++;
      if (failRefresh) throw new Error('GitHub rate limit reached');
      const result = snapshot(query);
      if (delayRefresh) await new Promise<void>(resolve => { finishRefresh = resolve; });
      return result;
    },
    fetchAttentionPrs: async () => prs,
    listWorkspaces: async () => [],
    onPullRequestsSynced: () => noOp,
    findStoredPullRequest: async () => null,
    openInAppBrowser: async url => { opened.push(url); },
  } satisfies Partial<Bridge>;
  window.bridge = bridge as unknown as Bridge;
  updateTeams(copy());
  window.location.hash = home ? '#/home' : '#/teams';
  document.body.innerHTML = '<div id="root"></div>';
  function Surface() {
    const [page, setPage] = useState<AppPage>(home ? 'home' : 'teams');
    useEffect(() => {
      const changed = () => setPage(window.location.hash === '#/home' ? 'home' : 'teams');
      window.addEventListener('hashchange', changed);
      return () => window.removeEventListener('hashchange', changed);
    }, []);
    return <div className="app-shell" style={{ display: 'flex', height: '100vh' }}>
      <AppSidebar page={page} onNavigate={next => { window.location.hash = `#/${next}`; }} workspace={null} workspaceTab="home"
        onWorkspaceTab={noOp} canGoBack={false} canGoForward={false} onBack={noOp} onForward={noOp}
        browserRailVisible={false} onToggleBrowserRail={noOp} />
      <main className="app-content">{page === 'teams' ? <TeamsPage />
        : <div style={{ padding: 36, maxWidth: 620 }}><h1>Home</h1></div>}</main>
    </div>;
  }
  const host = document.getElementById('root');
  assert(host !== null, 'Missing fixture root');
  root = createRoot(host);
  flushSync(() => root?.render(<Surface />));
  await settled();
  return { queries, opened, created, removed, added, get directory() { return directory; }, get refreshes() { return refreshes; },
    failRefresh() { failRefresh = true; }, delayRefresh() { delayRefresh = true; }, finishRefresh() { finishRefresh(); },
    failCreate(message: string | null) { createFailure = message; },
    async switchAccount(next: TeamDirectory) {
      directory.accountLogin = next.accountLogin; directory.circles = structuredClone(next.circles);
      window.dispatchEvent(new Event(GITHUB_ACCOUNT_CHANGED));
      await settled();
    } };
}

async function firstUse() {
  const f = await mount({ accountLogin: 'viewer', circles: [] });
  assert(text().includes('Keep the people you review close') && document.querySelector('.cir-pane') === null, 'No circles must explain circles without a list');
  await click('Trusted OSS');
  assert(document.querySelector<HTMLInputElement>('[aria-label="Circle name"]')?.value === 'Trusted OSS', 'A suggested name must prefill the dialog');
  await input('Circle name', ' ');
  await click('Create circle');
  assert(text().includes('Give the circle a name.') && f.created.length === 0, 'A blank name must be refused before saving');
  await input('Circle name', 'Review friends');
  await input('Circle description', 'People whose reviews I trust');
  await input('Filter cached contributors', '@ali');
  await press('Filter cached contributors', 'Enter');
  assert(document.querySelector('.cir-token')?.textContent?.includes('alice') === true, 'Return must select the highlighted contributor');
  await input('Add repository to scope', 'TrinoDB/Trino');
  await press('Add repository to scope', 'Enter');
  await click('Create circle');
  const saved = f.directory.circles[0];
  assert(saved?.name === 'Review friends' && saved.repositories[0] === 'trinodb/trino' && saved.members[0]?.login === 'alice' && f.added.length === 0,
    'Create must save the name, normalized scope and selected people in one creation');
  assert(pageTitle() === 'Review friends' && selectedTab() === 'Needs you', 'A circle created with people must open on Needs you');
  assert(text().includes('Created “Review friends” with 1 person'), 'Creation must confirm what was saved');
  assert(f.refreshes === 0, 'Managing circles must not start a collection pass');
}

async function saveFailure() {
  const f = await mount();
  await click('New circle');
  await input('Circle name', 'Gateway reviewers');
  await input('Filter cached contributors', 'bob'); await press('Filter cached contributors', 'Enter');
  await input('Filter cached contributors', 'cara'); await press('Filter cached contributors', 'Enter');
  f.failCreate("cara couldn't be verified: the cached contributor no longer matches their GitHub account");
  await click('Create circle');
  assert(document.querySelector('.cir-alert')?.textContent?.includes('Circle not saved.') === true && f.directory.circles.length === 2,
    'A member who cannot be verified must leave nothing saved');
  assert(document.querySelector('.cir-token.is-bad')?.textContent?.includes('cara') === true, 'The unverified person must be marked');
  await click('Remove cara');
  assert(document.querySelector('.cir-alert') === null && document.querySelectorAll('.cir-token').length === 1, 'Removing them must clear the failure');
  f.failCreate(null);
  await click('Create circle');
  assert(Number(f.directory.circles.length) === 3 && f.directory.circles[2].members.map(person => person.login).join() === 'bob', 'Retrying must save the rest');
}

async function membersAndUndo() {
  const f = await mount();
  await click('Trusted OSS', document.querySelector('.cir-pane') ?? document);
  await openTab('Members');
  assert(document.querySelectorAll('.cir-table [role="listitem"]').length === 2, 'Members must list the circle’s people');
  assert(document.querySelector('.cir-table [role="listitem"] .cir-member__circles')?.textContent === 'Also in Platform team', 'Members must name a person’s other circles');
  await click('Remove bob from Trusted OSS');
  assert(f.removed[0] === bob.githubId && document.querySelectorAll('.cir-table [role="listitem"]').length === 1, 'Removal must update the list');
  assert(document.querySelector('.bq-toast')?.textContent?.includes('Removed bob from Trusted OSS') === true, 'Removal must say what changed');
  await click('Undo');
  assert(f.added.at(-1)?.githubId === bob.githubId && document.querySelectorAll('.cir-table [role="listitem"]').length === 2, 'Undo must re-add the stored GitHub ID');
  await click('Add people');
  assert(document.querySelector('.cir-add [aria-label="@alice"], .cir-add [aria-label="Alice Chen · @alice"]')?.hasAttribute('disabled') === true,
    'Existing members must not be picked again');
  await input('Search contributors', 'car');
  await click('Cara Patel · @cara');
  assert(f.directory.circles[0].members.some(person => person.login === 'cara'), 'A pick must add the person at once');
}

async function activityWording() {
  const f = await mount();
  await openTab('Activity');
  const group = [...document.querySelectorAll('.cir-group')].find(row => row.textContent?.includes('Avoid repeated metadata calls'));
  assert(group !== undefined && group.querySelector('.cir-actor')?.textContent === 'bob', 'A work item must be headed by its latest update’s actor');
  await click('+2 more', group);
  const updates = [...group.querySelectorAll('.cir-update')].map(row => row.textContent ?? '');
  assert(updates.some(row => row.includes('mallory') && row.includes('(not in your circles)')), 'Someone outside every circle must be labeled');
  assert(updates.some(row => row.includes('alice') && row.includes('mentioned you') && row.includes('With you')), 'A mention must read as a direct interaction');
  await click('Open this update', group.querySelector('.cir-update') ?? group);
  assert(f.opened[0]?.includes('#comment-1') === true || f.opened[0]?.includes('#outsider-1') === true, 'An update must open its own source');
  assert(text().includes('reviewed your PR') && text().includes('Merged · merger not recorded'), 'Verbs must name the viewer’s work and unidentified mergers');
  assert(document.querySelector('.cir-unknown') !== null, 'An unidentified account must draw a dashed avatar');
  await click('Merged', document.querySelector('[aria-label="Activity type"]') ?? document);
  assert(f.queries.at(-1)?.filter === 'merged', 'A type must filter before the limit');
}

async function insightsDrill() {
  const f = await mount();
  await click('Trusted OSS', document.querySelector('.cir-pane') ?? document);
  await openTab('Insights');
  const tile = [...document.querySelectorAll<HTMLButtonElement>('.cir-tile')].find(item => item.textContent?.includes('PRs reviewed'));
  assert(tile?.querySelector('.cir-tile__value')?.textContent === '12', 'The scope tile must total its people');
  assert(document.querySelectorAll('.cir-bar.is-gap').length >= 5, 'Uncollected days must be marked apart from quiet days');
  tile.click(); await settled();
  assert(selectedTab() === 'Activity' && document.querySelector('.cir-metric-chip')?.textContent?.includes('PRs reviewed') === true,
    'A count must open Activity with its metric');
  assert(f.queries.at(-1)?.filter === 'reviews-performed' && f.queries.at(-1)?.circleId === 'trusted-oss', 'The drill-down must keep the circle and exact metric');
  await click('Clear metric filter');
  assert(document.querySelector('[aria-label="Activity type"]') !== null && f.queries.at(-1)?.filter === 'all', 'Clearing the metric must restore the type chips');
  await openTab('Insights');
  const cell = [...document.querySelectorAll<HTMLButtonElement>('.cir-person-row')].find(row => row.textContent?.includes('bob'))?.querySelectorAll<HTMLButtonElement>('.cir-cell')[0];
  assert(cell?.textContent === '–' && cell.title === 'No recorded observation', 'A zero must read as no observation');
  cell.click(); await settled();
  assert(f.queries.at(-1)?.memberId === bob.githubId && f.queries.at(-1)?.filter === 'prs-opened', 'A person cell must drill into that person’s metric');
}

async function personPanel() {
  const f = await mount();
  await click('Trusted OSS', document.querySelector('.cir-pane') ?? document);
  await openTab('Members');
  await click('Open alice');
  const panel = document.querySelector('.cir-person');
  assert(panel?.querySelector('.cir-person__name')?.textContent === 'Alice Chen', 'A row must open the person');
  assert(f.queries.some(query => query.memberId === alice.githubId && query.limit === 50), 'The panel must read that person’s own activity');
  await click('Circles…', panel);
  assert(document.querySelector('[role="menuitemcheckbox"][aria-checked="true"]') !== null, 'The circle menu must show current membership');
  await click('Trusted OSS', document.querySelector('.cir-person__circles [role="menu"]') ?? document);
  assert(!f.directory.circles[0].members.some(person => person.githubId === alice.githubId), 'Unchecking a circle must remove the person from it');
  await press('', 'Escape');
  await press('', 'Escape');
  assert(document.querySelector('.cir-person') === null || document.querySelector('.cir-person__name')?.textContent !== 'Alice Chen', 'Esc must close the panel');
}

async function needsYou() {
  await mount(seededDirectory(), { prs: [alicePr(), pr({ id: 9002, repo: 'trinodb/trino', number: 24831, title: 'Someone else’s change', author: 'stranger',
    origin: 'REVIEW_REQUESTED', requestedReviewers: ['viewer'] })] });
  await settled();
  const rows = document.querySelectorAll('.cir-needs .cir-row');
  assert(rows.length === 1 && rows[0].textContent?.includes('alice') === true && rows[0].textContent.includes('authored'),
    'Needs you must list a member’s review request and leave others out');
  assert(rows[0].textContent.includes('Latest: mallory (not in your circles) commented'), 'The latest actor must be labeled apart from the member');
  const comment = rows[0].querySelector('.cir-row__comment');
  assert(comment !== null && getComputedStyle(comment).display === 'none', 'The comment must stay folded until the line is hovered');
  const words = comment.textContent?.trim().split(/\s+/) ?? [];
  assert(words.length === 100 && words[99].endsWith('…'), 'The unfolded comment must stop at a hundred words');
  assert(document.querySelector('.bq-page-tabs [aria-selected="true"] .bq-page-tabs__count')?.textContent === '1', 'The tab must count the backlog');
  await click('Acknowledge', rows[0]);
  assert(document.querySelector('.cir-needs') === null && text().includes('Nothing needs you right now'), 'Acknowledging must clear the row');
  assert(Object.keys(JSON.parse(window.localStorage.getItem('bytequay.attention.handled.v1') ?? '{}')).includes('trinodb/trino#24830'), 'Handling must reach Home too');
  await click('Undo');
  assert(document.querySelectorAll('.cir-needs .cir-row').length === 1, 'Undo must bring the row back');
}

async function refreshFailure() {
  const f = await mount();
  await openTab('Activity');
  const before = document.querySelector('.cir-feed')?.textContent;
  f.failRefresh(); await click('Refresh activity');
  assert(f.refreshes === 1 && document.querySelector('.cir-banner[role="alert"]')?.textContent?.includes('GitHub rate limit reached') === true,
    'A failed refresh must say why');
  assert(document.querySelector('.cir-status')?.textContent === 'Refresh failed', 'The status must turn to failed');
  assert(before !== undefined && document.querySelector('.cir-feed')?.textContent === before, 'A failed refresh must keep what was shown');
  assert(!button('Refresh activity').disabled && button('Try again') !== null, 'Retry must stay available');
}

async function accountSwitchDuringRefresh() {
  const f = await mount();
  await click('Trusted OSS', document.querySelector('.cir-pane') ?? document);
  await openTab('Activity');
  f.delayRefresh(); await click('Refresh activity');
  assert(button('Refresh activity').disabled, 'The delayed refresh must be in flight');
  await f.switchAccount({ accountLogin: 'second-viewer', circles: [
    { id: 'second-circle', name: 'Second account circle', description: '', avatar: { color: 'gray', shape: 'square' }, repositories: ['acme/query-engine'], members: [cara] },
  ] });
  await settled();
  assert(pageTitle() === 'All circles', 'An account change must reset the circle');
  await openTab('Activity');
  const actors = () => [...document.querySelectorAll('.cir-group .cir-actor')].map(item => item.textContent).join();
  assert(actors() === 'cara', 'The new account must show only its own activity');
  f.finishRefresh(); await settled();
  assert(actors() === 'cara', 'The old account’s delayed refresh must not overwrite the new account');
}

async function rememberedView() {
  await mount();
  await click('Platform team', document.querySelector('.cir-pane') ?? document);
  await press('', '4', { metaKey: true });
  assert(selectedTab() === 'Insights', '⌘4 must open Insights');
  await mount(seededDirectory(), { keepStorage: true });
  assert(pageTitle() === 'Platform team' && selectedTab() === 'Insights', 'The last circle and view must reopen');
}

async function avatarChoice() {
  const f = await mount();
  await click('New circle');
  assert(document.querySelector('.cir-swatch[aria-pressed="true"]')?.getAttribute('aria-label') === 'Blue', 'A new circle must start on a color no circle uses');
  await input('Circle name', 'Reviewers');
  await click('Pink');
  await click('Circle', document.querySelector('[aria-label="Avatar shape"]') ?? document);
  const preview = document.querySelector('.cir-dialog .cir-avatar');
  assert(preview?.classList.contains('is-pink') === true && preview.classList.contains('is-circle') && preview.textContent === '', 'The preview must follow the pick');
  await click('Create circle');
  assert(JSON.stringify(f.directory.circles[2]?.avatar) === '{"color":"pink","shape":"circle"}', 'Create must save the avatar');
  const tile = document.querySelector('.cir-pane [title="Reviewers"] .cir-avatar');
  assert(tile?.classList.contains('is-pink') === true && tile.classList.contains('is-circle'), 'The circle list must draw the avatar');
  await click('Edit');
  await click('Green');
  await click('Square', document.querySelector('[aria-label="Avatar shape"]') ?? document);
  await click('Save changes');
  assert(JSON.stringify(f.directory.circles[2]?.avatar) === '{"color":"green","shape":"square"}', 'Edit must save a changed avatar');
  assert(document.querySelector('.cir-pane [title="Reviewers"] .cir-avatar')?.classList.contains('is-green') === true, 'The list must redraw the changed avatar');
}

async function homeNavigation() {
  const f = await mount(seededDirectory(), { home: true });
  await click('Circles', document.querySelector('.app-sidebar') ?? document);
  assert(window.location.hash === '#/teams' && pageTitle() === 'All circles', 'Sidebar Circles must reach the circles page');
  await click('Home', document.querySelector('.app-sidebar') ?? document);
  assert(document.querySelector('.app-content h1')?.textContent === 'Home', 'Sidebar Home must return to Home');
  assert(f.refreshes === 0, 'Opening circles must use local data without remote collection');
}

export async function run() {
  const results: { name: string; error?: string }[] = [];
  const scenarios = { firstUse, saveFailure, membersAndUndo, activityWording, insightsDrill, personPanel, needsYou, refreshFailure,
    accountSwitchDuringRefresh, rememberedView, avatarChoice, homeNavigation };
  for (const [name, scenario] of Object.entries(scenarios)) {
    try { await scenario(); results.push({ name }); }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
    finally { await dispose(); }
  }
  return results;
}

/** A real pointer is moved here by the test's main process, since a script cannot fake :hover. */
export async function hoverTarget(): Promise<{ x: number; y: number }> {
  await mount(seededDirectory(), { prs: [alicePr()] });
  await settled();
  const line = document.querySelector('.cir-row__latest-line')?.getBoundingClientRect();
  assert(line !== undefined, 'Missing latest line');
  return { x: Math.round(line.left + 40), y: Math.round(line.top + line.height / 2) };
}

export function commentShown(): boolean {
  const box = document.querySelector('.cir-row__comment');
  return box !== null && getComputedStyle(box).display !== 'none';
}

export async function showPreview(view: 'needs' | 'activity' | 'members' | 'insights' | 'person' | 'create' | 'edit' | 'empty' | 'remove' = 'needs') {
  if (view === 'empty') { await mount({ accountLogin: 'viewer', circles: [] }); return; }
  await mount(seededDirectory(), { prs: [alicePr()] });
  if (view === 'create') { await click('New circle'); await input('Circle name', 'Review friends'); await input('Filter cached contributors', 'a'); return; }
  await click('Trusted OSS', document.querySelector('.cir-pane') ?? document);
  if (view === 'edit') { await click('Edit'); return; }
  if (view === 'activity') {
    await openTab('Activity');
    const group = document.querySelector('.cir-group');
    if (group) await click('+2 more', group);
  }
  if (view === 'members' || view === 'person' || view === 'remove') await openTab('Members');
  if (view === 'remove') { document.querySelector<HTMLElement>('[aria-label="Open bob"]')?.focus(); await settled(); }
  if (view === 'person') await click('Open alice');
  if (view === 'insights') await openTab('Insights');
}
