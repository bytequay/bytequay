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
import type { TeamActivityEvent, TeamActivityFilter, TeamCircle, TeamMember, TeamStatistics } from './types';

/** Every count on the page links to Activity with its exact filter; these are the counts and their names. */
export const METRICS = {
  'prs-opened': 'PRs opened',
  'authored-merges': 'Authored PRs merged',
  'merges-performed': 'Merges performed',
  'reviews-performed': 'PRs reviewed',
  'issues-opened': 'Issues opened',
  'issues-closed': 'Issues closed',
  comments: 'Comments',
  interactions: 'Direct interactions with you',
  shared: 'Shared PRs and issues',
} as const satisfies Partial<Record<TeamActivityFilter, string>>;
export type Metric = keyof typeof METRICS;

export const ACTIVITY_TYPES = [['all', 'All'], ['merged', 'Merged'], ['reviews', 'Reviews'], ['issues', 'Issues'], ['interactions', 'With you']] as const;
export type ActivityType = typeof ACTIVITY_TYPES[number][0];

export function membersOf(circles: TeamCircle[]): TeamMember[] {
  return [...new Map(circles.flatMap(circle => circle.members.map(member => [member.githubId, member] as const))).values()]
    .sort((left, right) => left.login.localeCompare(right.login));
}

/** One work item per row; the original facts remain available for expansion and statistics. */
export function groupTeamActivity(events: TeamActivityEvent[]): TeamActivityEvent[][] {
  const groups = new Map<string, TeamActivityEvent[]>();
  const seen = new Set<string>();
  for (const event of [...events].sort((left, right) => Date.parse(right.occurredAt) - Date.parse(left.occurredAt))) {
    if (seen.has(event.id)) continue;
    seen.add(event.id);
    const key = `${event.repository.toLowerCase()}#${event.number}`;
    const group = groups.get(key);
    if (group) group.push(event);
    else groups.set(key, [event]);
  }
  return [...groups.values()];
}

const same = (left: string | null | undefined, right: string | null) => right !== null && left?.toLowerCase() === right.toLowerCase();

/** Who acted, as the feed names them; the source leaves a merge's merger unidentified at times. */
export type ActorRole = 'member' | 'viewer' | 'outsider' | 'unknown';

export function actorRole(event: TeamActivityEvent, memberIds: ReadonlySet<number>, viewerLogin: string | null): ActorRole {
  if (event.actorId === 0) return 'unknown';
  if (same(event.actorLogin, viewerLogin)) return 'viewer';
  return memberIds.has(event.actorId) ? 'member' : 'outsider';
}

/**
 * What the actor did, telling a direct interaction with the viewer apart from taking part in the same work.
 * A member's event lists that member as an interaction only when it mentions the viewer or reviews the viewer's PR.
 */
export function activityVerb(event: TeamActivityEvent, viewerLogin: string | null, names: ReadonlyMap<number, string>): string {
  if (event.actorId === 0) return 'Merged · merger not recorded';
  const mine = same(event.subjectAuthorLogin, viewerLogin) && !same(event.actorLogin, viewerLogin);
  const withYou = event.viewerInteractionMemberIds.includes(event.actorId);
  const others = same(event.actorLogin, viewerLogin)
    ? event.viewerInteractionMemberIds.map(id => names.get(id)).filter(Boolean).join(', ') : '';
  switch (event.kind) {
    case 'PR_OPENED': case 'ISSUE_OPENED': return 'opened';
    case 'ISSUE_CLOSED': return 'closed';
    case 'PR_MERGED': return mine ? 'merged your PR' : 'merged';
    case 'PR_REVIEWED': return mine ? 'reviewed your PR' : withYou ? 'mentioned you in a review' : 'reviewed';
    case 'PR_REVIEW_COMMENT': return mine ? 'left review comments on your PR' : withYou ? 'mentioned you' : 'left review comments';
    case 'PR_COMMENT': case 'ISSUE_COMMENT': return withYou ? 'mentioned you' : others ? `mentioned ${others}` : 'commented';
    case 'REVIEW_REQUESTED': return withYou ? 'requested your review' : others ? `requested a review from ${others}` : 'requested a review';
  }
}

/** Whose work the item is, after the update that heads it. */
export function workByline(event: TeamActivityEvent, viewerLogin: string | null): string {
  if (event.subjectAuthorLogin === null) return '';
  if (same(event.subjectAuthorLogin, viewerLogin)) return event.kind.startsWith('ISSUE_') ? 'your issue' : 'your PR';
  return `by ${event.subjectAuthorLogin}`;
}

/** An item's state as far as the recorded updates show it. */
export function workState(group: TeamActivityEvent[]): 'pr' | 'merged' | 'issue' | 'closed' {
  const issue = group[0].kind.startsWith('ISSUE_');
  if (issue) return group.some(event => event.kind === 'ISSUE_CLOSED') ? 'closed' : 'issue';
  return group.some(event => event.kind === 'PR_MERGED') ? 'merged' : 'pr';
}

export function dayHeader(at: string, now = Date.now()): string {
  const day = new Date(at);
  const yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);
  if (day.toDateString() === new Date(now).toDateString()) return 'Today';
  if (day.toDateString() === yesterday.toDateString()) return 'Yesterday';
  return day.toLocaleDateString('en-US', { weekday: 'long', month: 'short', day: 'numeric' });
}

/** Up to three non-zero contributions for a member row; never a ranking or a score. */
export function contributionSummary(statistics: TeamStatistics): string {
  const issues = statistics.issuesOpened + statistics.issuesClosed;
  return ([[statistics.prsOpened, 'opened'], [statistics.authoredPrsMerged, 'merged'], [statistics.prsReviewed, 'reviewed'],
    [issues, issues === 1 ? 'issue update' : 'issue updates']] as const)
    .filter(([count]) => count > 0).slice(0, 3).map(([count, label]) => `${count} ${label}`).join(' · ');
}

export function people(count: number): string {
  return count === 0 ? 'No people yet' : `${count} ${count === 1 ? 'person' : 'people'}`;
}

/** The text through its first `count` words, marked with an ellipsis when that cuts it short. */
export function firstWords(text: string, count: number): string {
  let words = 0;
  for (const match of text.matchAll(/\S+/g)) {
    words += 1;
    if (words === count) {
      const end = (match.index ?? 0) + match[0].length;
      return end < text.trimEnd().length ? `${text.slice(0, end)}…` : text;
    }
  }
  return text;
}
