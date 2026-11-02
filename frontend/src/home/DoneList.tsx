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
import { useEffect, useState } from 'react';
import { ChevronLeft, ChevronRight, CircleCheck, CloudOff, Eye, GitMerge, ListChecks, SquareCheck, type LucideIcon } from 'lucide-react';
import CopyLinkButton from '@bytequay/ui/CopyLinkButton';
import { plural } from '@bytequay/ui/displayText';
import { IconButton, ItemRow } from '../cards/Card';
import { getCached, setCached } from '../dataCache';
import { errorMessage } from '../errorMessage';
import { isoDay, shiftDay } from '../recent/model';
import type { AgendaTargetDto, DoneActivityDto, DoneItemDto } from '../types';
import { openTarget } from './TodayList';
import { standupText } from './done';
import { clockTime, dayText, todayRef } from './today';
import type { WorkItem } from './work';
import '../css/done.css';

type Group = 'merged' | 'reviewed' | 'closed' | 'done';

const KINDS: Record<DoneItemDto['kind'], { icon: LucideIcon; text: string; group: Group }> = {
  MERGED: { icon: GitMerge, text: 'Merged', group: 'merged' },
  MERGED_BY_YOU: { icon: GitMerge, text: 'You merged it', group: 'merged' },
  APPROVED: { icon: Eye, text: 'You approved it', group: 'reviewed' },
  CHANGES_REQUESTED: { icon: Eye, text: 'You requested changes', group: 'reviewed' },
  REVIEWED: { icon: Eye, text: 'You reviewed it', group: 'reviewed' },
  ISSUE_CLOSED: { icon: CircleCheck, text: 'Issue closed', group: 'closed' },
  TASK_DONE: { icon: SquareCheck, text: 'Task done', group: 'done' },
  WORK_DONE: { icon: ListChecks, text: 'Work item done', group: 'done' },
};

/** The header's counts: an icon and a number each, the sentence on hover, and nothing for a zero. */
const GROUPS: { group: Group; icon: LucideIcon; title: (count: number) => string }[] = [
  { group: 'merged', icon: GitMerge, title: count => `${plural(count, 'pull request')} merged` },
  { group: 'reviewed', icon: Eye, title: count => `${plural(count, 'pull request')} reviewed` },
  { group: 'closed', icon: CircleCheck, title: count => `${plural(count, 'issue')} closed` },
  { group: 'done', icon: SquareCheck, title: count => `${plural(count, 'task')} done` },
];

/** A day lists this many rows until asked for the rest, so a busy day does not push the rail's activity out of reach. */
const ROW_LIMIT = 8;

/** Where a row opens: its pull request, issue, task or work item, as Today's rows open theirs. */
export function doneTarget(item: DoneItemDto): AgendaTargetDto {
  const kind = item.kind === 'TASK_DONE' ? 'TASK' : item.kind === 'WORK_DONE' ? 'WORK' : item.kind === 'ISSUE_CLOSED' ? 'ISSUE' : 'PULL';
  return { kind, workspaceId: item.workspaceId, repo: item.repo, number: item.number, id: item.id, connectionId: null, cloudId: null };
}

/** One day's finished work; the last answer per day paints at once while it is read again. */
function useDone(day: string): { activity: DoneActivityDto | null; error: string | null } {
  const key = `home:done:${day}`;
  const [read, setRead] = useState<{ day: string; activity: DoneActivityDto | null; error: string | null } | null>(null);
  useEffect(() => {
    let active = true;
    // ponytail: read on open and on a day change only; the backend keeps today's GitHub answer five minutes anyway.
    void Promise.resolve().then(() => window.bridge.fetchDone(day, day)).then(activity => {
      setCached(key, activity);
      if (active) setRead({ day, activity, error: null });
    }, (failure: unknown) => {
      if (active) setRead({ day, activity: getCached<DoneActivityDto>(key) ?? null, error: errorMessage(failure) });
    });
    return () => { active = false; };
  }, [day, key]);
  return read?.day === day ? read : { activity: getCached<DoneActivityDto>(key) ?? null, error: null };
}

/**
 * What the viewer finished on one day: pull requests merged and reviewed, issues closed, and tasks and work items done. The arrows
 * step through days, a heatmap day opens its own, and the date goes back to today. Copy takes the day as stand-up notes, with My work
 * as what is in progress.
 */
export default function DoneList({ day, onDay, work }: { day: string; onDay: (day: string) => void; work: WorkItem[] }): React.JSX.Element {
  const [all, setAll] = useState(false);
  const { activity, error } = useDone(day);
  const today = isoDay(new Date());
  const items = activity?.items ?? [];
  const rows = all ? items : items.slice(0, ROW_LIMIT);
  const count = (group: Group) => items.filter(item => KINDS[item.kind].group === group).length;

  return (
    <section className="today done" aria-label="Done">
      <div className="today-head">
        <span className="today-head__label"><span className="today-head__title">Done</span></span>
        <span className="done-nav">
          <IconButton label="Previous day" onClick={() => { setAll(false); onDay(shiftDay(day, -1)); }}><ChevronLeft size={13} aria-hidden="true" /></IconButton>
          {day === today
            ? <span className="today-head__date">Today</span>
            : <button type="button" className="today-head__date done-nav__day" title="Back to today" onClick={() => { setAll(false); onDay(today); }}>{dayText(day)}</button>}
          {day < today && (
            <IconButton label="Next day" onClick={() => { setAll(false); onDay(shiftDay(day, 1)); }}><ChevronRight size={13} aria-hidden="true" /></IconButton>
          )}
        </span>
        <span className="today-head__spacer" />
        <span className="today-head__facts">
          {GROUPS.map(({ group, icon: Icon, title }) => {
            const shown = count(group);
            return shown === 0 ? null : (
              <span key={group} className="today-fact" title={title(shown)} aria-label={title(shown)}><Icon size={13} aria-hidden="true" />{shown}</span>
            );
          })}
          {activity !== null && !activity.complete && (
            <span className="today-fact today-fact--icon is-dim" title="Some GitHub activity could not be read" aria-label="Some GitHub activity could not be read">
              <CloudOff size={13} aria-hidden="true" />
            </span>
          )}
          {activity !== null && <CopyLinkButton label="stand-up notes" value={standupText(day, today, items, work)} />}
        </span>
      </div>
      {activity === null
        ? error !== null && <div className="today-line" role="status">{error}</div>
        : items.length === 0
          ? <div className="today-line">{day === today ? 'Nothing finished yet' : 'Nothing finished'}</div>
          : (
            <div className="item-list done-list" role="list">
              {rows.map(item => <DoneRow key={`${item.kind}:${item.repo ?? ''}#${item.number ?? item.id ?? ''}`} item={item} />)}
            </div>
          )}
      {items.length > ROW_LIMIT && (
        <button type="button" className="today-link done-more" aria-expanded={all} onClick={() => setAll(value => !value)}>
          {all ? 'Show less' : `Show ${items.length - ROW_LIMIT} more`}
        </button>
      )}
    </section>
  );
}

function DoneRow({ item }: { item: DoneItemDto }) {
  const { icon: Icon, text } = KINDS[item.kind];
  const target = doneTarget(item);
  const reference = item.repo === null ? null : todayRef(target, 'global');
  return (
    <div role="listitem">
      <ItemRow className={`done-row is-compact is-${item.kind.toLowerCase().replace(/_/g, '-')}`} label={`${text}: ${item.title}`}
        onOpen={() => openTarget(target)}
        icon={<span className="done-row__icon" title={text}><Icon size={15} aria-hidden="true" /></span>}
        title={item.title}
        marks={reference === null ? null : <span className="card-key">{reference}</span>}
        meta={null}
        age={<time className="card-age" dateTime={item.at} title={new Date(item.at).toLocaleString()}>{clockTime(item.at)}</time>} />
    </div>
  );
}
