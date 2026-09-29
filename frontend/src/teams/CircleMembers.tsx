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
import { useState } from 'react';
import { ChevronRight, Search, UserMinus, UserPlus } from 'lucide-react';
import Avatar from '../Avatar';
import { CardActions, Clickable, IconButton } from '../cards/Card';
import { relativeTime } from '../relativeTime';
import { contributionSummary } from './activity';
import type { TeamCircle, TeamMember, TeamPersonStatistics } from './types';

/** The circle's people with what they did in the period; a row opens the person, and removal can be undone. */
export default function CircleMembers({ circle, circles, members, statistics, periodLabel, inspected, busy, onInspect, onRemove, onAddPeople }: {
  circle: TeamCircle | null;
  circles: TeamCircle[];
  members: TeamMember[];
  statistics: TeamPersonStatistics[];
  periodLabel: string;
  inspected: number | null;
  busy: boolean;
  onInspect: (memberId: number) => void;
  onRemove: (member: TeamMember) => void;
  onAddPeople: () => void;
}) {
  const [query, setQuery] = useState('');
  if (members.length === 0) return <div className="cir-empty">
    <span className="cir-empty__badge" aria-hidden="true"><UserPlus size={18} strokeWidth={1.8} /></span>
    <h2>No one in this circle yet</h2>
    <p>Add people from contributors cached across your watched repositories. They won’t be notified, and nothing changes on GitHub.</p>
    {circle && <button type="button" className="bq-button bq-button--primary" onClick={onAddPeople}>Add people</button>}
  </div>;

  const normalized = query.trim().replace(/^@/, '').toLowerCase();
  const shown = members.filter(member => !normalized || member.login.toLowerCase().includes(normalized) || member.name.toLowerCase().includes(normalized));
  return <div className="cir-members">
    <div className="cir-members__tools">
      <label className="cir-search"><Search size={14} aria-hidden="true" />
        <input value={query} placeholder="Filter people" aria-label="Filter people" onChange={event => setQuery(event.target.value)} />
      </label>
      <span className="cir-muted">Summaries cover the {periodLabel.toLowerCase()}</span>
    </div>
    <div className="cir-table" role="list" aria-label="Members">
      <div className="cir-member cir-member--head" aria-hidden="true">
        <span>Person</span><span className="cir-member__circles">Circles</span><span>Contributions</span>
        <span title="Direct interactions with you">With you</span><span className="cir-member__last">Last update</span><span />
      </div>
      {shown.map(member => {
        const stats = statistics.find(person => person.memberId === member.githubId);
        const others = circles.filter(item => item.id !== circle?.id && item.members.some(person => person.githubId === member.githubId));
        const summary = stats ? contributionSummary(stats.statistics) : '';
        const named = member.name !== '' && member.name !== member.login;
        return <div role="listitem" key={member.githubId}>
          <Clickable className={`card-row cir-member${inspected === member.githubId ? ' is-selected' : ''}`} label={`Open ${member.login}`} onOpen={() => onInspect(member.githubId)}>
            <span className="cir-member__person">
              <Avatar login={member.login} avatarUrl={member.avatarUrl} size={28} />
              <span className="cir-stack"><span className="cir-member__name" title={member.name || member.login}>{named ? member.name : member.login}</span>
                <span className="cir-muted">{named ? `@${member.login}` : 'No display name'}</span></span>
            </span>
            <span className="cir-member__circles cir-muted">{others.length > 0 ? `${circle ? 'Also in ' : ''}${others.map(item => item.name).join(', ')}` : circle ? '—' : ''}</span>
            <span className={summary ? 'cir-member__summary' : 'cir-member__summary cir-muted'}>{summary || 'No recorded updates'}</span>
            <span className="cir-num">{stats?.statistics.directInteractions ? stats.statistics.directInteractions : '—'}</span>
            <span className="cir-member__last card-age">{stats?.lastActiveAt ? relativeTime(stats.lastActiveAt, { suffix: false }) : '—'}</span>
            <span className="cir-member__actions">
              {circle && <CardActions label="Member actions">
                <IconButton label={`Remove ${member.login} from ${circle.name}`} tone="danger" disabled={busy} onClick={() => onRemove(member)}>
                  <UserMinus size={15} aria-hidden="true" />
                </IconButton>
              </CardActions>}
              <ChevronRight size={14} strokeWidth={2} aria-hidden="true" />
            </span>
          </Clickable>
        </div>;
      })}
    </div>
  </div>;
}
