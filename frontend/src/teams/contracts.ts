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
import type { RepositoryMetadataDto } from '../types';
import { parseList } from '../goals/contracts';
import { object, oneOf, optionalText, text } from '../pulls/wireParse';
import { TEAM_ACTIVITY_FILTERS, TEAM_EVENT_KINDS, type TeamActivityQuery, type TeamActivitySnapshot, type TeamCircleInput, type TeamCreateInput, type TeamDirectory, type TeamMemberInput } from './types';

function integer(value: unknown, field: string, min = 0, max = Number.MAX_SAFE_INTEGER): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < min || value > max) throw new Error(`Invalid ${field}`);
  return value;
}

export function teamId(value: unknown): string {
  const id = text(value, 'circle ID');
  if (!/^[a-zA-Z0-9-]{1,100}$/.test(id)) throw new Error('Invalid circle ID');
  return id;
}

export function teamMemberId(value: unknown): number { return integer(value, 'GitHub user ID', 1); }

export function teamLogin(value: unknown): string {
  const login = text(value, 'GitHub username').trim().replace(/^@/, '');
  if (!/^[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,37}[a-zA-Z0-9])?(?:\[bot\])?$/.test(login)) throw new Error('Enter a valid GitHub username');
  return login;
}

export function parseTeamInput(value: unknown): TeamCircleInput {
  const input = object(value, 'circle');
  const name = text(input.name, 'circle name').trim();
  if (name.length < 1 || name.length > 100) throw new Error('Circle name must contain 1–100 characters');
  const description = text(input.description, 'circle description').trim();
  if (description.length > 2000) throw new Error('Circle description must contain at most 2000 characters');
  const repositories = parseList(input.repositories, value => {
    const repo = text(value, 'repository').trim().toLowerCase();
    if (!/^[a-z0-9_-]{1,100}\/[a-z0-9_.-]{1,100}$/.test(repo) || repo.endsWith('/.') || repo.endsWith('/..')) throw new Error('Use owner/repository for each repository');
    return repo;
  }, 'repositories');
  return { name, description, repositories: [...new Set(repositories)] };
}

export function parseTeamMemberInput(value: unknown): TeamMemberInput {
  const member = object(value, 'member');
  return { githubId: teamMemberId(member.githubId), login: teamLogin(member.login) };
}

export function parseTeamCreateInput(value: unknown): TeamCreateInput {
  const input = object(value, 'circle');
  const members = parseList(input.members, parseTeamMemberInput, 'members');
  return { ...parseTeamInput(input), members: [...new Map(members.map(member => [member.githubId, member])).values()] };
}

export function parseTeamContributors(value: unknown): RepositoryMetadataDto['contributors'] {
  return parseList(value, value => {
    const person = object(value, 'contributor');
    if (typeof person.bot !== 'boolean') throw new Error('Invalid contributor bot flag');
    return { id: teamMemberId(person.id), login: teamLogin(person.login), avatarUrl: text(person.avatarUrl, 'avatar'), bot: person.bot };
  }, 'contributors');
}

export function parseTeamQuery(value?: unknown): TeamActivityQuery {
  const query = object(value ?? {}, 'team activity query');
  return {
    ...(query.circleId === undefined ? {} : { circleId: teamId(query.circleId) }),
    ...(query.memberId === undefined ? {} : { memberId: teamMemberId(query.memberId) }),
    ...(query.filter === undefined ? {} : { filter: oneOf(query.filter, TEAM_ACTIVITY_FILTERS, 'activity filter') }),
    days: query.days === undefined ? 7 : integer(query.days, 'period', 1, 90),
    limit: query.limit === undefined ? 100 : integer(query.limit, 'activity limit', 1, 500),
  };
}

export function parseTeams(value: unknown): TeamDirectory {
  const directory = object(value, 'teams');
  return {
    accountLogin: text(directory.accountLogin, 'account login'),
    circles: parseList(directory.circles, value => {
      const circle = object(value, 'circle');
      return {
        id: teamId(circle.id), ...parseTeamInput(circle),
        members: parseList(circle.members, value => {
          const member = object(value, 'member');
          return { githubId: teamMemberId(member.githubId), login: text(member.login, 'login'), name: text(member.name, 'name'), avatarUrl: text(member.avatarUrl, 'avatar') };
        }, 'members'),
      };
    }, 'circles'),
  };
}

function time(value: unknown, field: string): string {
  const result = text(value, field);
  if (!Number.isFinite(Date.parse(result))) throw new Error(`Invalid ${field}`);
  return result;
}

export function parseTeamActivity(value: unknown): TeamActivitySnapshot {
  const snapshot = object(value, 'team activity');
  const coverage = object(snapshot.coverage, 'coverage');
  if (typeof coverage.partial !== 'boolean' || typeof coverage.hasMoreEvents !== 'boolean') throw new Error('Invalid team coverage');
  return {
    accountLogin: text(snapshot.accountLogin, 'account login'),
    events: parseList(snapshot.events, value => {
      const event = object(value, 'team event');
      const url = new URL(text(event.url, 'event URL'));
      if (url.origin !== 'https://github.com' || url.username || url.password) throw new Error('Invalid GitHub event URL');
      return {
        id: text(event.id, 'event ID'), kind: oneOf(event.kind, TEAM_EVENT_KINDS, 'event kind'),
        actorId: integer(event.actorId, 'actor ID'), actorLogin: text(event.actorLogin, 'actor login'),
        repository: text(event.repository, 'repository'), number: integer(event.number, 'item number', 1),
        title: text(event.title, 'title'), url: url.toString(), occurredAt: time(event.occurredAt, 'event time'),
        subjectAuthorId: event.subjectAuthorId == null ? null : teamMemberId(event.subjectAuthorId),
        subjectAuthorLogin: optionalText(event.subjectAuthorLogin),
        source: oneOf(event.source, ['PUBLIC_EVENTS', 'REPOSITORY', 'CACHED_DETAIL'] as const, 'event source'),
        viewerInteractionMemberIds: parseList(event.viewerInteractionMemberIds, teamMemberId, 'interaction members'),
      };
    }, 'events'),
    people: parseList(snapshot.people, value => {
      const person = object(value, 'person statistics');
      return {
        memberId: teamMemberId(person.memberId), login: text(person.login, 'login'),
        prsOpened: integer(person.prsOpened, 'PR count'), authoredPrsMerged: integer(person.authoredPrsMerged, 'merged PR count'),
        mergesPerformed: integer(person.mergesPerformed, 'merge count'), prsReviewed: integer(person.prsReviewed, 'review count'),
        issuesOpened: integer(person.issuesOpened, 'issue count'), issuesClosed: integer(person.issuesClosed, 'closed issue count'),
        comments: integer(person.comments, 'comment count'), directInteractions: integer(person.directInteractions, 'interaction count'),
        sharedPullRequests: integer(person.sharedPullRequests, 'shared PR count'), sharedIssues: integer(person.sharedIssues, 'shared issue count'),
      };
    }, 'people'),
    coverage: {
      partial: coverage.partial, description: text(coverage.description, 'coverage description'),
      observedSince: coverage.observedSince == null ? null : time(coverage.observedSince, 'observation start'),
      lastRefreshedAt: coverage.lastRefreshedAt == null ? null : time(coverage.lastRefreshedAt, 'last refresh'),
      errors: parseList(coverage.errors, value => text(value, 'collection error'), 'errors'),
      memberCount: integer(coverage.memberCount, 'member count'), membersScanned: integer(coverage.membersScanned, 'members scanned'),
      repositoryCount: integer(coverage.repositoryCount, 'repository count'), repositoriesScanned: integer(coverage.repositoriesScanned, 'repositories scanned'),
      hasMoreEvents: coverage.hasMoreEvents,
    },
  };
}
