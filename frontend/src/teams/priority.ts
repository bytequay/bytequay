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
  /** Due reminders already passed the same handling and pin checks as other cards. */
  protectedKeys?: ReadonlySet<string>;
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

/** Select from already eligible, handled, scoped, and newest-first cards without changing their timestamps. */
export function preferTeamAttention(sorted: AttentionItem[], preference: TeamAttentionPreference | undefined, limit: number): AttentionItem[] {
  if (preference === undefined || preference.onlyTeam) return sorted.slice(0, limit);
  const trusted = sorted.filter(item => teamAttentionMatch(item, preference.circles, preference.viewerLogin) !== null);
  if (trusted.length === 0) return sorted.slice(0, limit);
  const selected: AttentionItem[] = [];
  const keys = new Set<string>();
  const add = (item: AttentionItem) => {
    if (selected.length < limit && !keys.has(item.key)) { selected.push(item); keys.add(item.key); }
  };
  sorted.filter(item => preference.protectedKeys?.has(item.key) || item.task && (item.kind === 'approval' || item.kind === 'failed')).forEach(add);
  const trustedKeys = new Set(trusted.map(item => item.key));
  const otherRequests = sorted.filter(item => !keys.has(item.key) && !trustedKeys.has(item.key) && item.kind !== 'updated' && item.kind !== 'finished');
  // Assignment time is not available for issues. Use their source update as the honest fallback, not an invented waiting age.
  const requestTime = (item: AttentionItem) => Date.parse(item.pr?.reviewRequestedAt ?? item.updatedAt) || 0;
  const oldest = otherRequests.reduce<AttentionItem | null>((previous, item) => previous === null || requestTime(item) < requestTime(previous) ? item : previous, null);
  const available = limit - selected.length;
  trusted.filter(item => !keys.has(item.key)).slice(0, Math.max(0, Math.min(3, available - (oldest === null ? 0 : 1)))).forEach(add);
  if (oldest !== null) add(oldest);
  sorted.forEach(add);
  return selected;
}
