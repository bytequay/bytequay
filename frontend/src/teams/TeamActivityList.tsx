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
import { ArrowUpRight, CircleDot, GitPullRequest } from 'lucide-react';
import Avatar from '../Avatar';
import { openActivityLink } from '../githubReference';
import { relativeTime } from '../relativeTime';
import { groupTeamActivity, TEAM_EVENT_LABELS } from './activity';
import type { TeamActivityEvent, TeamMember } from './types';

export default function TeamActivityList({ events, members, compact = false, onPerson }: {
  events: TeamActivityEvent[];
  members: TeamMember[];
  compact?: boolean;
  onPerson?: (id: number) => void;
}) {
  const groups = groupTeamActivity(events);
  if (groups.length === 0) return <div className="teams-empty">No recorded activity in this view.</div>;
  return <div className={`teams-activity${compact ? ' is-compact' : ''}`}>
    {(compact ? groups.slice(0, 4) : groups).map(group => {
      const event = group[0];
      const person = members.find(member => member.githubId === event.actorId);
      return <article className="teams-activity-row" key={event.id}>
        {onPerson && person
          ? <button type="button" className="teams-avatar" aria-label={`View ${person.login}`} onClick={() => onPerson(person.githubId)}>
            <Avatar login={event.actorLogin} avatarUrl={person.avatarUrl} size={28} />
          </button>
          : event.actorId === 0 ? <GitPullRequest size={24} aria-hidden="true" /> : <Avatar login={event.actorLogin} avatarUrl={person?.avatarUrl} size={compact ? 24 : 28} />}
        <div className="teams-activity-body">
          <div className="teams-activity-meta">{event.actorId === 0 ? 'Pull request merged · merger unavailable' : <><strong>{event.actorLogin}</strong> {TEAM_EVENT_LABELS[event.kind]}</>}</div>
          <button type="button" className="teams-work-link" onClick={() => openActivityLink(event.url)}>
            {!compact && (event.kind.startsWith('ISSUE_') ? <CircleDot size={15} /> : <GitPullRequest size={15} />)}
            <span>{event.title}</span><ArrowUpRight size={12} aria-hidden="true" />
          </button>
          <div className="teams-activity-meta">{event.repository} #{event.number}
            {event.viewerInteractionMemberIds.length > 0 && <span className="teams-badge">With you</span>}
          </div>
          {group.length > 1 && !compact && <details className="teams-event-details">
            <summary>{group.length} recorded updates</summary>
            {group.map(item => <div key={item.id}><span>{item.actorId === 0 ? 'Pull request merged · merger unavailable' : <><strong>{item.actorLogin}</strong> {TEAM_EVENT_LABELS[item.kind]}</>}</span>
              <button type="button" onClick={() => openActivityLink(item.url)} aria-label={`Open ${item.actorLogin}'s update`}>View</button>
              <time dateTime={item.occurredAt}>{relativeTime(item.occurredAt)}</time></div>)}
          </details>}
        </div>
        <time dateTime={event.occurredAt} title={new Date(event.occurredAt).toLocaleString()}>{relativeTime(event.occurredAt, { suffix: false })}</time>
      </article>;
    })}
  </div>;
}
