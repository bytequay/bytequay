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
import type { Bridge } from '../../types';
import TeamsPage from '../TeamsPage';
import { membersOf } from '../activity';
import type { TeamActivityEvent, TeamActivityQuery, TeamActivitySnapshot, TeamDirectory, TeamMember, TeamPersonStatistics } from '../types';
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
    { id: 'trusted-oss', name: 'Trusted OSS', description: 'Trusted open-source collaborators', repositories: ['trinodb/trino'], members: [alice, bob] },
    { id: 'platform', name: 'Platform team', description: 'Our internal platform group', repositories: ['acme/query-engine'], members: [alice, cara] },
  ] };
}

function event(id: string, kind: TeamActivityEvent['kind'], actor: TeamMember, number: number, title: string, hours: number,
  extra: Partial<TeamActivityEvent> = {}): TeamActivityEvent {
  return { id, kind, actorId: actor.githubId, actorLogin: actor.login, repository: 'trinodb/trino', number, title,
    url: `https://github.com/trinodb/trino/${kind.startsWith('ISSUE_') ? 'issues' : 'pull'}/${number}#${id}`,
    occurredAt: ago(hours), subjectAuthorId: actor.githubId, subjectAuthorLogin: actor.login,
    source: 'REPOSITORY', viewerInteractionMemberIds: [], ...extra };
}

function seededEvents(): TeamActivityEvent[] {
  const review = event('review-1', 'PR_REVIEWED', bob, 24118, 'Avoid repeated metadata calls when planning large queries', 1,
    { subjectAuthorId: alice.githubId, subjectAuthorLogin: alice.login, viewerInteractionMemberIds: [bob.githubId] });
  return [review, { ...review },
    event('comment-1', 'PR_COMMENT', alice, 24118, review.title, 2, { viewerInteractionMemberIds: [alice.githubId] }),
    event('merge-1', 'PR_MERGED', alice, 24105, 'Improve predicate pushdown for nested Parquet columns', 3),
    event('issue-1', 'ISSUE_OPENED', alice, 24122, 'Document retry behavior for long-running transactions', 4),
    event('review-2', 'PR_REVIEWED', alice, 24090, 'Handle connection resets during query cancellation', 5,
      { subjectAuthorId: 999, subjectAuthorLogin: 'viewer', viewerInteractionMemberIds: [alice.githubId] }),
    event('platform-1', 'PR_OPENED', cara, 318, 'Reduce coordinator memory use during deployment rollouts', 6,
      { repository: 'acme/query-engine', url: 'https://github.com/acme/query-engine/pull/318' }),
  ];
}

function statistics(person: TeamMember): TeamPersonStatistics {
  return { memberId: person.githubId, login: person.login, prsOpened: 12, authoredPrsMerged: person === alice ? 10 : 3,
    mergesPerformed: 2, prsReviewed: person === alice ? 8 : 4, issuesOpened: 3, issuesClosed: 2, comments: 24,
    directInteractions: person === alice ? 50 : 7, sharedPullRequests: 8, sharedIssues: 3 };
}

function button(label: string, host: ParentNode = document): HTMLButtonElement {
  const found = [...host.querySelectorAll<HTMLButtonElement>('button')]
    .find(candidate => candidate.getAttribute('aria-label') === label || candidate.textContent?.trim() === label);
  assert(found !== undefined, `Missing button: ${label}`);
  return found;
}

async function click(label: string, host: ParentNode = document) { button(label, host).click(); await settled(); }
const settled = async () => { await settle(); await settle(); };

async function input(label: string, value: string): Promise<void> {
  const element = document.querySelector<HTMLInputElement | HTMLTextAreaElement>(`[aria-label="${label}"]`);
  assert(element !== null, `Missing input: ${label}`);
  const prototype = element instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(prototype, 'value')?.set?.call(element, value);
  element.dispatchEvent(new Event('input', { bubbles: true }));
  await settled();
}

async function select(label: string, value: string): Promise<void> {
  const element = document.querySelector<HTMLSelectElement>(`select[aria-label="${label}"]`);
  assert(element !== null, `Missing select: ${label}`);
  element.value = value;
  element.dispatchEvent(new Event('change', { bubbles: true }));
  await settled();
}

let root: Root | null = null;
async function dispose() { if (root) { flushSync(() => root?.unmount()); root = null; await settled(); } }

async function mount(initial = seededDirectory(), home = false, observations = seededEvents()) {
  await dispose();
  window.localStorage.clear();
  const directory = structuredClone(initial);
  const queries: TeamActivityQuery[] = [];
  const opened: string[] = [];
  let refreshes = 0;
  let failRefresh = false;
  let delayRefresh = false;
  let finishRefresh = noOp;
  let adds = 0;
  let contributorReads = 0;
  let failAdd = false;
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
          case 'all': return true;
          case 'merged': return item.kind === 'PR_MERGED';
          case 'reviews': return item.kind === 'PR_REVIEWED';
          case 'issues': return item.kind.startsWith('ISSUE_');
          case 'interactions': return item.viewerInteractionMemberIds.some(id => ids.has(id));
          case 'authored-merges': return item.kind === 'PR_MERGED' && item.subjectAuthorId !== null && ids.has(item.subjectAuthorId);
          case 'reviews-performed': return item.kind === 'PR_REVIEWED' && ids.has(item.actorId);
          case 'issues-opened': return item.kind === 'ISSUE_OPENED' && ids.has(item.actorId);
        }
      }).sort((left, right) => Date.parse(right.occurredAt) - Date.parse(left.occurredAt));
    return { accountLogin: directory.accountLogin, events: events.slice(0, query.limit ?? 100), people: people.map(person => statistics(knownPeople.find(known => known.githubId === person.githubId) ?? person)),
      coverage: { partial: true, description: 'Recorded GitHub activity from accessible repositories. This is an observed history, not a complete measure of someone’s work.',
        observedSince: ago(168), lastRefreshedAt: ago(0.1), errors: [], memberCount: people.length, membersScanned: people.length,
        repositoryCount: circles.length, repositoriesScanned: circles.length, hasMoreEvents: events.length > (query.limit ?? 100) } };
  }
  const bridge = {
    getTeams: async () => copy(),
    fetchTeamContributors: async () => { contributorReads++; return knownPeople.map(person => ({ id: person.githubId, login: person.login, avatarUrl: person.avatarUrl, bot: false })); },
    createTeam: async value => { directory.circles.push({ id: `circle-${directory.circles.length + 1}`, ...value,
      members: knownPeople.filter(person => value.members.some(selected => selected.githubId === person.githubId && selected.login === person.login)) }); return copy(); },
    updateTeam: async (id, value) => { directory.circles = directory.circles.map(circle => circle.id === id ? { ...circle, ...value, members: circle.members } : circle); return copy(); },
    removeTeam: async id => { directory.circles = directory.circles.filter(circle => circle.id !== id); return copy(); },
    addTeamMember: async (id, selected) => {
      adds++;
      if (failAdd) throw new Error('GitHub user lookup failed');
      const person = knownPeople.find(candidate => candidate.githubId === selected.githubId && candidate.login === selected.login);
      if (!person) throw new Error('GitHub user not found');
      directory.circles = directory.circles.map(circle => circle.id !== id || circle.members.some(member => member.githubId === person.githubId)
        ? circle : { ...circle, members: [...circle.members, person] });
      return copy();
    },
    removeTeamMember: async (id, memberId) => {
      directory.circles = directory.circles.map(circle => circle.id === id ? { ...circle, members: circle.members.filter(person => person.githubId !== memberId) } : circle);
      return copy();
    },
    getTeamActivity: async query => snapshot(query),
    refreshTeamActivity: async query => {
      refreshes++;
      if (failRefresh) throw new Error('GitHub refresh failed. Try again later.');
      const result = snapshot(query);
      if (delayRefresh) await new Promise<void>(resolve => { finishRefresh = resolve; });
      return result;
    },
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
    return <div className="app-shell">
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
  return { queries, opened, get directory() { return directory; }, get refreshes() { return refreshes; }, get adds() { return adds; }, get contributorReads() { return contributorReads; }, failAdd() { failAdd = true; },
    failRefresh() { failRefresh = true; }, delayRefresh() { delayRefresh = true; }, finishRefresh() { finishRefresh(); },
    async switchAccount(next: TeamDirectory) {
      directory.accountLogin = next.accountLogin; directory.circles = structuredClone(next.circles);
      window.dispatchEvent(new Event(GITHUB_ACCOUNT_CHANGED));
      await settled();
    } };
}

async function management() {
  const f = await mount({ accountLogin: 'viewer', circles: [] });
  await click('Create your first circle');
  assert(button('Create circle').disabled, 'A blank circle must not be submitted');
  await input('Circle name', 'Review friends'); await input('Circle description', 'People whose reviews I trust');
  await input('Circle repositories', 'TrinoDB/Trino');
  await input('Search contributors', '@ali'); await click('@alice'); await click('Create circle');
  assert(f.directory.circles[0]?.name === 'Review friends' && f.directory.circles[0].repositories[0] === 'trinodb/trino', 'Create must save and normalize the repository scope');
  assert(f.directory.circles[0].description === 'People whose reviews I trust' && f.directory.circles[0].members[0]?.login === 'alice' && f.adds === 0,
    'Create must save its description and selected people in a single creation');
  assert(document.querySelector('.teams-tabs [aria-current="page"]')?.textContent === 'People', 'Create must open the new circle’s people');
  await click('Add people'); await input('Search contributors', 'bob'); await click('@bob');
  assert(document.querySelectorAll('.teams-person').length === 2, 'Adding selected contributors must update the list immediately');
  await click('Edit Review friends'); await input('Circle name', 'Trusted reviewers');
  await input('Circle description', 'Review collaborators');
  await input('Circle repositories', 'trinodb/trino\nacme/query-engine'); await click('Save circle');
  assert(String(f.directory.circles[0].name) === 'Trusted reviewers' && f.directory.circles[0].repositories.length === 2
    && String(f.directory.circles[0].description) === 'Review collaborators' && f.directory.circles[0].members.length === 2,
    'Editing must update description and scope without changing saved people');
  await click('Remove bob from Trusted reviewers');
  assert(Number(f.directory.circles[0].members.length) === 1 && document.querySelectorAll('.teams-person').length === 1, 'Removal must update membership and rendered people');
  await click('Edit Trusted reviewers'); await click('Delete circle'); await click('Cancel');
  assert(f.directory.circles.length === 1, 'Canceling deletion must preserve the circle');
  await click('Edit Trusted reviewers'); await click('Delete circle'); await click('Delete circle');
  assert(Number(f.directory.circles.length) === 0 && document.body.textContent?.includes('Your people, in one place') === true, 'Confirmed deletion must return to the welcome view');
  assert(f.refreshes === 0, 'Membership management must not start remote activity refreshes');
}

async function validation() {
  const f = await mount();
  await select('Circle', 'trusted-oss'); await click('People'); await click('Add people');
  await input('Search contributors', 'not a username');
  assert(f.adds === 0 && document.body.textContent?.includes('No matching contributors') === true, 'Typing must only filter saved contributors, never add an arbitrary login');
  await input('Search contributors', 'Chen');
  assert(button('Alice Chen · @alice').disabled, 'Known names must match and existing members must not be added twice');
  await input('Search contributors', 'CaRa');
  assert(!button('Cara Patel · @cara').disabled && f.contributorReads === 1, 'Search must include other repositories and remain local while typing');
  f.failAdd(); await click('Cara Patel · @cara');
  assert(Number(f.adds) === 1 && document.body.textContent?.includes('GitHub user lookup failed') === true, 'Failed additions must retain existing people and display the failure');
  assert(document.querySelectorAll('.teams-person').length === 2, 'Failed additions must preserve known members');
}

async function insights() {
  const newerComments = Array.from({ length: 110 }, (_, index) => event(`recent-${index}`, 'PR_COMMENT', alice, 25000 + index, `Recent discussion ${index}`, 0.1));
  const f = await mount(seededDirectory(), false, [...newerComments, ...seededEvents(),
    event('merge-other', 'PR_MERGED', alice, 24100, 'Another author’s pull request', 7, { subjectAuthorId: bob.githubId, subjectAuthorLogin: bob.login }),
    event('closed-issue', 'ISSUE_CLOSED', alice, 24099, 'An issue closed rather than opened', 8),
  ]);
  await select('Circle', 'trusted-oss'); await click('People');
  const person = document.querySelector<HTMLButtonElement>('.teams-person-link');
  assert(person !== null, 'Missing member drilldown'); person.click(); await settled();
  assert(document.querySelector('.teams-person-heading h2')?.textContent === 'Alice Chen', 'Selecting a person must identify whose statistics are shown');
  assert(document.querySelector('.teams-stat strong')?.textContent === '10' && document.querySelectorAll('.teams-table tbody tr').length === 1, 'Person insights must use only the selected person’s statistics');
  assert(f.queries.at(-1)?.memberId === 101 && f.queries.at(-1)?.circleId === 'trusted-oss', 'Person insights must keep the chosen circle scope');
  async function inspect(label: string, filter: TeamActivityQuery['filter']) {
    await click('Insights');
    const stat = [...document.querySelectorAll<HTMLButtonElement>('.teams-stat')].find(item => item.textContent?.includes(label));
    assert(stat !== undefined, `Missing statistic: ${label}`); stat.click(); await settled();
    assert(document.querySelector<HTMLSelectElement>('[aria-label="Activity type"]')?.value === filter, 'A statistic must select its exact activity type');
    const query = f.queries.at(-1);
    assert(query?.filter === filter && query.memberId === 101 && query.circleId === 'trusted-oss' && query.limit === 100,
      'A statistic must request its exact backend filter before the limit, preserving person and circle scope');
  }
  await inspect('PRs reviewed by person', 'reviews-performed');
  assert(document.querySelectorAll('.teams-activity-row').length === 1 && document.querySelector('.teams-activity-row strong')?.textContent === 'alice', 'Review drilldown must preserve selected person identity');
  assert(document.querySelector('.teams-work-link')?.textContent?.includes('Handle connection resets') === true, 'An older matching review beyond the unfiltered cap must remain reachable');
  await inspect('Authored PRs merged', 'authored-merges');
  assert(document.querySelectorAll('.teams-activity-row').length === 1 && document.querySelector('.teams-work-link')?.textContent?.includes('Improve predicate pushdown') === true,
    'Authored merges must exclude another author’s PR even when the selected person performed that merge');
  await inspect('Issues opened', 'issues-opened');
  assert(document.querySelectorAll('.teams-activity-row').length === 1 && document.querySelector('.teams-work-link')?.textContent?.includes('Document retry behavior') === true,
    'Issues opened must exclude issue closure observations');
  await inspect('Direct interactions with you', 'interactions');
  assert(document.querySelectorAll('.teams-activity-row').length === 2, 'Direct interactions must belong to the selected person');
  await select('Period', '30');
  assert(f.queries.at(-1)?.days === 30 && f.queries.at(-1)?.filter === 'interactions' && f.refreshes === 0, 'Period changes must retain the metric filter and query local observations');
}

async function groupedIdentity() {
  const f = await mount();
  const row = document.querySelector('.teams-activity-row');
  assert(row?.querySelector('.teams-activity-meta strong')?.textContent === 'bob', 'The group header must name the latest update’s actual actor');
  const details = row.querySelector<HTMLDetailsElement>('details');
  assert(details?.querySelector('summary')?.textContent === '2 recorded updates', 'Duplicate source event IDs must not inflate grouped updates');
  details.querySelector('summary')?.click(); await settled();
  assert([...details.querySelectorAll('strong')].map(element => element.textContent).join(',') === 'bob,alice', 'Expanding a group must retain both original actors');
  await click("Open alice's update", details);
  assert(f.opened[0] === 'https://github.com/trinodb/trino/pull/24118#comment-1', 'An expanded update must open its own source link');
  await click('View bob', row);
  assert(document.querySelector<HTMLSelectElement>('[aria-label="Person"]')?.value === '202', 'The group avatar must inspect its actor, not another author in the group');
}

async function refreshFailure() {
  const f = await mount();
  const before = document.querySelector('.teams-activity')?.textContent;
  f.failRefresh(); await click('Refresh');
  assert(f.refreshes === 1 && document.querySelector('[role="alert"]')?.textContent?.includes('GitHub refresh failed') === true, 'Explicit refresh failures must be visible');
  assert(before !== undefined && document.querySelector('.teams-activity')?.textContent === before, 'A failed refresh must retain the last known activity');
  assert(!button('Refresh').disabled, 'Failed refresh must leave retry available');
}

async function homeNavigation() {
  const f = await mount(seededDirectory(), true);
  await click('Teams', document.querySelector('.app-sidebar') ?? document);
  assert(window.location.hash === '#/teams' && document.querySelector('.teams-page h1')?.textContent === 'Teams', 'Sidebar Teams must reach the Teams destination');
  await click('Home', document.querySelector('.app-sidebar') ?? document);
  assert(document.querySelector('.app-content h1')?.textContent === 'Home', 'Sidebar Home must return to Home');
  await click('Teams', document.querySelector('.app-sidebar') ?? document);
  assert(document.querySelector('.teams-page') !== null && f.refreshes === 0, 'Sidebar Teams must use local data without remote collection');
}

async function accountSwitchDuringRefresh() {
  const f = await mount();
  await select('Circle', 'trusted-oss'); await select('Person', String(alice.githubId));
  f.delayRefresh(); await click('Refresh');
  assert(button('Refreshing…').disabled, 'The delayed refresh must be in flight');
  await f.switchAccount({ accountLogin: 'second-viewer', circles: [
    { id: 'second-circle', name: 'Second account circle', description: '', repositories: ['acme/query-engine'], members: [cara] },
  ] });
  assert(document.querySelector<HTMLSelectElement>('[aria-label="Circle"]')?.value === ''
    && document.querySelector<HTMLSelectElement>('[aria-label="Person"]')?.value === '', 'Account changes must reset circle and person selection');
  assert(document.querySelectorAll('.teams-activity-row').length === 1 && document.querySelector('.teams-activity-row strong')?.textContent === 'cara',
    'The new account must load only its own circle activity');
  f.finishRefresh(); await settled();
  assert(document.querySelectorAll('.teams-activity-row').length === 1 && document.querySelector('.teams-activity-row strong')?.textContent === 'cara'
    && !document.querySelector('.teams-activity')?.textContent?.includes('alice'), 'The old account’s delayed refresh must not overwrite the new account');
}

export async function run() {
  const results: { name: string; error?: string }[] = [];
  for (const [name, scenario] of Object.entries({ management, validation, insights, groupedIdentity, refreshFailure, homeNavigation, accountSwitchDuringRefresh })) {
    try { await scenario(); results.push({ name }); }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
    finally { await dispose(); }
  }
  return results;
}

export async function showPreview(view: 'activity' | 'insights' | 'dialog' | 'create' | 'people' = 'activity') {
  await mount();
  if (view === 'insights') { await select('Person', String(alice.githubId)); await click('Insights'); }
  else if (view === 'dialog') { await select('Circle', 'trusted-oss'); await click('Edit Trusted OSS'); }
  else if (view === 'create') { await click('New circle'); await input('Circle name', 'Review friends'); await input('Circle description', 'People I review and collaborate with'); await input('Search contributors', 'alice'); await click('Alice Chen · @alice'); await input('Search contributors', ''); }
  else if (view === 'people') { await select('Circle', 'trusted-oss'); await click('People'); await click('Add people'); }
  else { document.querySelector<HTMLDetailsElement>('.teams-event-details')?.querySelector('summary')?.click(); await settled(); }
}
