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
import { Fragment, useState } from 'react';
import { ArrowUpRight, ChevronDown, ChevronRight, CircleCheck, CircleDot, GitMerge, GitPullRequest } from 'lucide-react';
import Avatar from '../Avatar';
import { Clickable, Pill } from '../cards/Card';
import { openActivityLink } from '../githubReference';
import { relativeTime } from '../relativeTime';
import { activityVerb, actorRole, dayHeader, groupTeamActivity, workByline, workState } from './activity';
import type { TeamActivityEvent, TeamActivitySnapshot, TeamMember } from './types';

/** The most events one page may ask for; beyond it, a narrower period or person shows the rest. */
export const ACTIVITY_LIMIT_MAX = 500;

const STATE_ICONS = {
  pr: <GitPullRequest size={16} aria-hidden="true" />,
  merged: <GitMerge size={16} aria-hidden="true" />,
  issue: <CircleDot size={16} aria-hidden="true" />,
  closed: <CircleCheck size={16} aria-hidden="true" />,
};

const exact = (at: string) => new Date(at).toLocaleString('en-US', { dateStyle: 'medium', timeStyle: 'short' });

/** One actor's update: who, what, and whether it was a direct interaction with the viewer. */
export function ActorLine({ event, people, viewerLogin, names }: {
  event: TeamActivityEvent;
  people: ReadonlyMap<number, TeamMember>;
  viewerLogin: string | null;
  names: ReadonlyMap<number, string>;
}) {
  const role = actorRole(event, new Set(people.keys()), viewerLogin);
  return <>
    {role === 'unknown'
      ? <span className="cir-unknown" role="img" aria-label="Unidentified account" title="The source did not identify this account">?</span>
      : <Avatar login={event.actorLogin} avatarUrl={people.get(event.actorId)?.avatarUrl} size={16} allowGitHubFallback={role !== 'outsider'}
        className={role === 'outsider' ? 'cir-outsider' : undefined} />}
    {role !== 'unknown' && <span className={`cir-actor is-${role}`}>{role === 'viewer' ? 'You' : event.actorLogin}</span>}
    <span>{activityVerb(event, viewerLogin, names)}</span>
    {role === 'outsider' && <span className="cir-note">(not in your circles)</span>}
    {role !== 'viewer' && event.viewerInteractionMemberIds.length > 0 && <Pill tone="blue">With you</Pill>}
  </>;
}

/** One row per PR or issue, headed by its latest update, with the earlier updates a click away. */
export default function CircleActivity({ snapshot, people, viewerLogin, days, personLogin, metricLabel, filtered, canLoadMore, onLoadMore, onWiden, onClear }: {
  snapshot: TeamActivitySnapshot;
  /** Everyone in any circle, by GitHub ID. */
  people: ReadonlyMap<number, TeamMember>;
  viewerLogin: string | null;
  days: number;
  personLogin: string | null;
  metricLabel: string | null;
  /** A person, metric or type narrows the list, so clearing them could show more. */
  filtered: boolean;
  canLoadMore: boolean;
  onLoadMore: () => void;
  onWiden: () => void;
  onClear: () => void;
}) {
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set());
  const names = new Map([...people].map(([id, person]) => [id, person.login]));
  const groups = groupTeamActivity(snapshot.events);
  if (groups.length === 0) return <div className="cir-empty">
    <h2>No recorded activity in the past {days} {days === 1 ? 'day' : 'days'}</h2>
    <p>Nothing was recorded for {personLogin ?? 'these people'}{metricLabel === null ? '' : ` matching “${metricLabel}”`}. Zero means no observation, not necessarily no work.</p>
    <div className="cir-empty__actions">
      {days < 90 && <button type="button" className="bq-button" onClick={onWiden}>Show past 90 days</button>}
      {filtered && <button type="button" className="bq-button" onClick={onClear}>Clear filters</button>}
    </div>
  </div>;

  let previousDay = '';
  return <div className="cir-feed" role="feed" aria-label="Recorded activity">
    {groups.map(group => {
      const lead = group[0];
      const key = `${lead.repository}#${lead.number}`;
      const day = dayHeader(lead.occurredAt);
      const newDay = day !== previousDay;
      previousDay = day;
      const open = expanded.has(key);
      const toggle = () => setExpanded(current => {
        const next = new Set(current);
        if (!next.delete(key)) next.add(key);
        return next;
      });
      return <Fragment key={key}>
        {newDay && <div className="cir-day">{day}</div>}
        <Clickable className="card-row cir-row cir-group" label={`${lead.title}, ${lead.repository} #${lead.number}`} onOpen={() => { void openActivityLink(lead.url); }}>
          <span className={`cir-row__icon cir-state is-${workState(group)}`}>{STATE_ICONS[workState(group)]}</span>
          <div className="cir-row__body">
            <div className="cir-row__title"><span title={lead.title}>{lead.title}</span><span className="card-key">#{lead.number}</span></div>
            <div className="cir-row__meta">
              <ActorLine event={lead} people={people} viewerLogin={viewerLogin} names={names} />
              <span className="cir-sep">·</span>
              <span className="cir-muted">{lead.repository}</span>
              {!activityVerb(lead, viewerLogin, names).includes('your') && <span className="cir-muted">{workByline(lead, viewerLogin)}</span>}
              {group.length > 1 && <button type="button" className="cir-more" aria-expanded={open} onClick={event => { event.stopPropagation(); toggle(); }}>
                {open ? <ChevronDown size={12} strokeWidth={2} aria-hidden="true" /> : <ChevronRight size={12} strokeWidth={2} aria-hidden="true" />}
                {open ? `Hide ${group.length - 1} more` : `+${group.length - 1} more`}
              </button>}
            </div>
            {open && <div className="cir-updates">
              {group.slice(1).map(event => <div className="cir-update" key={event.id}>
                <ActorLine event={event} people={people} viewerLogin={viewerLogin} names={names} />
                <span className="cir-spacer" />
                <time className="card-age" dateTime={event.occurredAt} title={exact(event.occurredAt)}>{relativeTime(event.occurredAt, { suffix: false })}</time>
                <button type="button" className="cir-link" aria-label="Open this update" onClick={click => { click.stopPropagation(); void openActivityLink(event.url); }}>
                  Open<ArrowUpRight size={12} aria-hidden="true" />
                </button>
              </div>)}
            </div>}
          </div>
          <time className="card-age" dateTime={lead.occurredAt} title={exact(lead.occurredAt)}>{relativeTime(lead.occurredAt, { suffix: false })}</time>
        </Clickable>
      </Fragment>;
    })}
    <div className="cir-feed__foot">
      <span>Showing {groups.length} of {snapshot.workItems} work items · {snapshot.updates} recorded updates</span>
      {snapshot.updates > snapshot.events.length && (canLoadMore
        ? <button type="button" className="bq-button" onClick={onLoadMore}>Load more</button>
        : <span>Narrow the period or choose a person to see more.</span>)}
      <span className="cir-spacer" />
      <span>Insights count every recorded update in this period.</span>
    </div>
  </div>;
}
