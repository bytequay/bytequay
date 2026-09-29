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
import type { AttentionItem } from '../home/attention';
import type { TeamCircle, TeamMember } from './types';

export type TeamAttentionMatch = { circle: TeamCircle; member: TeamMember; reason: 'author' | 'review-requester' };

export type TeamAttentionPreference = {
  circles: TeamCircle[];
  viewerLogin: string | null;
  onlyTeam?: boolean;
};

/** Local circles match GitHub identities, never a Jira display name or an agent lane. */
export function teamAttentionMatch(item: AttentionItem, circles: TeamCircle[], viewerLogin: string | null): TeamAttentionMatch | null {
  // ponytail: attention projections expose logins; match numeric IDs when those projections carry author/requester IDs.
  if (item.task || item.jira) return null;
  const activity = item.pr?.latestActivity;
  const requester = item.kind === 'review' && activity?.event === 'review_requested'
    && viewerLogin !== null && activity.requestedReviewer?.toLowerCase() === viewerLogin.toLowerCase() ? activity.actor : null;
  for (const circle of circles) {
    if (circle.repositories.length > 0 && !circle.repositories.some(repo => repo.toLowerCase() === item.repo.toLowerCase())) continue;
    const author = circle.members.find(member => member.login.toLowerCase() === item.author?.toLowerCase());
    if (author) return { circle, member: author, reason: 'author' };
    const member = circle.members.find(person => person.login.toLowerCase() === requester?.toLowerCase());
    if (member) return { circle, member, reason: 'review-requester' };
  }
  return null;
}

export type CircleNeed = { item: AttentionItem; match: TeamAttentionMatch; action: 'review' | 'assigned' };

/** Needs you: review requests (new commits since a review included) and assigned issues from circle people; other updates stay in Activity. */
export function circleNeeds(items: AttentionItem[], circles: TeamCircle[], viewerLogin: string | null): CircleNeed[] {
  return items.flatMap(item => {
    const action = item.type === 'issue' ? item.kind === 'assigned' ? 'assigned' : null
      : item.type === 'pr' && (item.kind === 'review' || item.kind === 'commits') ? 'review' : null;
    const match = action === null ? null : teamAttentionMatch(item, circles, viewerLogin);
    return action === null || match === null ? [] : [{ item, match, action }];
  });
}
