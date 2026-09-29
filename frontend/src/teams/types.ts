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
export type TeamMember = { githubId: number; login: string; name: string; avatarUrl: string };
/** Keep in step with TeamCircle.Avatar in the backend. */
export const CIRCLE_COLORS = ['blue', 'green', 'purple', 'orange', 'red', 'pink', 'teal', 'gray'] as const;
export const CIRCLE_SHAPES = ['square', 'circle'] as const;
/** A circle's avatar: a solid palette color, drawn as a rounded square or a circle. */
export type CircleAvatar = { color: typeof CIRCLE_COLORS[number]; shape: typeof CIRCLE_SHAPES[number] };
export type TeamCircle = { id: string; name: string; description: string; avatar: CircleAvatar; repositories: string[]; members: TeamMember[] };
export type TeamDirectory = { accountLogin: string; circles: TeamCircle[] };
export type TeamCircleInput = { name: string; description: string; avatar: CircleAvatar; repositories: string[] };
export type TeamMemberInput = Pick<TeamMember, 'githubId' | 'login'>;
export type TeamCreateInput = TeamCircleInput & { members: TeamMemberInput[] };
/** Activity's type chips, then the drill-down metrics every count links to. */
export const TEAM_ACTIVITY_FILTERS = ['all', 'merged', 'reviews', 'issues', 'prs-opened', 'authored-merges', 'merges-performed', 'reviews-performed',
  'issues-opened', 'issues-closed', 'comments', 'interactions', 'shared'] as const;
export type TeamActivityFilter = typeof TEAM_ACTIVITY_FILTERS[number];
export type TeamActivityQuery = { circleId?: string; memberId?: number; days?: number; limit?: number; filter?: TeamActivityFilter };
export const TEAM_EVENT_KINDS = ['PR_OPENED', 'PR_MERGED', 'PR_REVIEWED', 'PR_COMMENT', 'PR_REVIEW_COMMENT', 'REVIEW_REQUESTED', 'ISSUE_OPENED', 'ISSUE_CLOSED', 'ISSUE_COMMENT'] as const;
export type TeamEventKind = typeof TEAM_EVENT_KINDS[number];

export type TeamActivityEvent = {
  id: string;
  kind: TeamEventKind;
  actorId: number;
  actorLogin: string;
  repository: string;
  number: number;
  title: string;
  url: string;
  occurredAt: string;
  subjectAuthorId: number | null;
  subjectAuthorLogin: string | null;
  source: 'PUBLIC_EVENTS' | 'REPOSITORY' | 'CACHED_DETAIL';
  viewerInteractionMemberIds: number[];
};

export type TeamStatistics = {
  prsOpened: number;
  authoredPrsMerged: number;
  mergesPerformed: number;
  prsReviewed: number;
  issuesOpened: number;
  issuesClosed: number;
  comments: number;
  directInteractions: number;
  sharedPullRequests: number;
  sharedIssues: number;
};

export type TeamPersonStatistics = { memberId: number; login: string; statistics: TeamStatistics; lastActiveAt: string | null };

/** Matching updates on one local day; outside the collected window a zero means unknown. */
export type TeamDay = { date: string; updates: number; collected: boolean };

export type TeamCoverage = {
  partial: boolean;
  observedSince: string | null;
  lastRefreshedAt: string | null;
  errors: string[];
  memberCount: number;
  membersScanned: number;
  repositoryCount: number;
  repositoriesScanned: number;
};

export type TeamActivitySnapshot = {
  accountLogin: string;
  events: TeamActivityEvent[];
  /** Every matching event and work item in the period, beyond the loaded page. */
  updates: number;
  workItems: number;
  people: TeamPersonStatistics[];
  totals: TeamStatistics;
  days: TeamDay[];
  coverage: TeamCoverage;
};
