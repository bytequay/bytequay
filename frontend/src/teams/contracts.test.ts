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
import { describe, expect, it } from 'vitest';
import { groupTeamActivity, membersOf } from './activity';
import { parseTeamActivity, parseTeamContributors, parseTeamCreateInput, parseTeamInput, parseTeamMemberInput, parseTeamQuery, parseTeams, teamLogin } from './contracts';
import type { TeamActivityEvent, TeamActivitySnapshot } from './types';

const member = { githubId: 2, login: 'alice', name: 'Alice', avatarUrl: 'https://avatars.githubusercontent.com/u/2' };
const directory = { accountLogin: 'viewer', circles: [{ id: 'circle-1', name: 'Trusted OSS', description: '', repositories: ['owner/repo'], members: [member] }] };
const event: TeamActivityEvent = {
  id: 'comment:123', kind: 'PR_COMMENT', actorId: 2, actorLogin: 'alice', repository: 'owner/repo', number: 42, title: 'A change',
  url: 'https://github.com/owner/repo/pull/42#issuecomment-123', occurredAt: '2026-09-20T12:00:00Z', subjectAuthorId: 3, subjectAuthorLogin: 'bob',
  source: 'REPOSITORY', viewerInteractionMemberIds: [2],
};
const snapshot: TeamActivitySnapshot = {
  accountLogin: 'viewer', events: [event], people: [{ memberId: 2, login: 'alice', prsOpened: 0, authoredPrsMerged: 0, mergesPerformed: 0, prsReviewed: 0,
    issuesOpened: 0, issuesClosed: 0, comments: 1, directInteractions: 1, sharedPullRequests: 1, sharedIssues: 0 }],
  coverage: { partial: true, description: 'Observed activity', observedSince: null, lastRefreshedAt: null, errors: [], memberCount: 1,
    membersScanned: 0, repositoryCount: 1, repositoriesScanned: 0, hasMoreEvents: false },
};

describe('Team IPC boundary', () => {
  it('normalizes repository scopes without capping people or conflating identities', () => {
    expect(parseTeamInput({ name: ' Internal ', description: ' Colleagues ', repositories: ['Owner/Repo', 'owner/repo'] })).toEqual({ name: 'Internal', description: 'Colleagues', repositories: ['owner/repo'] });
    const many = { ...directory, circles: [{ ...directory.circles[0], members: Array.from({ length: 300 }, (_, i) => ({ ...member, githubId: i + 1, login: `user${i}` })) }] };
    expect(parseTeams(many).circles[0].members).toHaveLength(300);
    expect(teamLogin(' @Alice ')).toBe('Alice');
    expect(parseTeamCreateInput({ name: 'New circle', description: '', repositories: [], members: [{ githubId: 1, login: ' @Alice ' }, { githubId: 1, login: 'alice' }, { githubId: 2, login: 'bob' }] }))
      .toEqual({ name: 'New circle', description: '', repositories: [], members: [{ githubId: 1, login: 'alice' }, { githubId: 2, login: 'bob' }] });
    const contributor = { id: 2, login: 'alice', avatarUrl: '', bot: false };
    expect(parseTeamContributors([contributor])).toEqual([contributor]);
    const bot = { ...contributor, id: 3, login: 'github-actions[bot]', bot: true };
    expect(parseTeamContributors([contributor, bot])).toEqual([contributor, bot]);
    expect(parseTeamCreateInput({ name: 'Automation', description: '', repositories: [], members: [{ githubId: bot.id, login: bot.login }] }).members).toEqual([{ githubId: bot.id, login: bot.login }]);
    expect(teamLogin(bot.login)).toBe(bot.login);
    expect(parseTeamMemberInput({ githubId: 42, login: ' @Alice ' })).toEqual({ githubId: 42, login: 'Alice' });
    expect(() => parseTeamMemberInput({ login: 'alice' })).toThrow();
    expect(() => parseTeamMemberInput({ githubId: 0, login: 'alice' })).toThrow();
    expect(() => parseTeamContributors([{ ...contributor, id: 0 }])).toThrow();
    expect(() => parseTeamContributors([{ ...contributor, bot: 'false' }])).toThrow();

    expect(membersOf([directory.circles[0], { ...directory.circles[0], id: 'circle-2' }])).toEqual([member]);
  });

  it('rejects invalid scope, identity, period and unsafe activity destinations', () => {
    expect(() => parseTeamInput({ name: ' ', description: '', repositories: [] })).toThrow();
    expect(() => parseTeamInput({ name: 'Team', description: '', repositories: ['../private'] })).toThrow();
    expect(() => parseTeamInput({ name: 'Team', description: '', repositories: ['owner/..'] })).toThrow();
    expect(() => parseTeamInput({ name: 'Team', description: '', repositories: ['https://github.com/a/b'] })).toThrow();
    expect(() => teamLogin('alice/../bob')).toThrow();
    expect(() => parseTeamCreateInput({ name: 'Team', description: '', repositories: [], members: [{ githubId: 1, login: 'alice/../bob' }] })).toThrow();
    expect(() => parseTeamInput({ name: 'Team', description: 'a'.repeat(2001), repositories: [] })).toThrow();
    expect(() => parseTeamQuery({ days: 91 })).toThrow();
    expect(() => parseTeamQuery({ filter: 'anything' })).toThrow();
    expect(() => parseTeamQuery({ memberId: -2 })).toThrow();
    expect(() => parseTeamActivity({ ...snapshot, events: [{ ...event, url: 'https://github.com.evil.test/repo' }] })).toThrow();
    expect(() => parseTeamActivity({ ...snapshot, events: [{ ...event, occurredAt: 'yesterday' }] })).toThrow();
    expect(parseTeamQuery()).toEqual({ days: 7, limit: 100 });
    expect(parseTeamActivity(snapshot)).toEqual(snapshot);
  });
});

describe('Team activity grouping', () => {
  it('groups work after deduplication while retaining every actor and source fact', () => {
    const bob: TeamActivityEvent = { ...event, id: 'comment:124', actorId: 3, actorLogin: 'bob', occurredAt: '2026-09-21T12:00:00Z', viewerInteractionMemberIds: [] };
    const groups = groupTeamActivity([event, bob, event]);
    expect(groups).toEqual([[bob, event]]);
    expect(event.actorLogin).toBe('alice');
  });
});
