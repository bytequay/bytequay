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
import type { TeamActivityEvent, TeamCircle, TeamEventKind, TeamMember } from './types';

export const TEAM_EVENT_LABELS: Record<TeamEventKind, string> = {
  PR_OPENED: 'opened a pull request', PR_MERGED: 'merged a pull request', PR_REVIEWED: 'reviewed a pull request',
  PR_COMMENT: 'commented on a pull request', PR_REVIEW_COMMENT: 'left a review comment', REVIEW_REQUESTED: 'requested a review',
  ISSUE_OPENED: 'opened an issue', ISSUE_CLOSED: 'closed an issue', ISSUE_COMMENT: 'commented on an issue',
};

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
