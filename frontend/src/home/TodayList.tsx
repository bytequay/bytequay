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
import { useAgentAvailability } from '../agents/availability';
import { useState } from 'react';
import {
  ArrowUpRight, CalendarCheck, Check, ChevronDown, ChevronRight, Clock, Eye, EyeOff, GitMerge, ListChecks, LoaderCircle, MessageCircleQuestion,
  MessageSquareReply, Play, RotateCw, TriangleAlert, Wrench, X, type LucideIcon,
} from 'lucide-react';
import { CardActions, CardAge, Clickable, IconButton, ItemRow } from '../cards/Card';
import { zoomReference } from '../githubReference';
import type { AgendaAction, AgendaDto, AgendaItemDto, AgendaTargetDto } from '../types';
import { workspaceAgentTaskRoute, workspaceReviewTaskRoute } from '../workspaces/model';
import {
  allDone, calendarFact, clockTime, dayText, doneRatio, doneText, hiddenCount, minuteText, recapLabel, showsToday, todayRows, whereText, type TodayScope,
} from './today';
import type { TodayAgenda } from './useTodayAgenda';
import '../css/today.css';

export const ACTION_ICONS: Record<AgendaAction, LucideIcon> = {
  start: Play,
  reply: MessageSquareReply,
  review: Eye,
  fix: Wrench,
  merge: GitMerge,
  answer: MessageCircleQuestion,
  decide: ListChecks,
  follow_up: ArrowUpRight,
};

/** Opens an item where its work already lives; nothing starts or runs from here. */
export function openTarget(target: AgendaTargetDto): void {
  const { kind, workspaceId, repo, number, id } = target;
  if ((kind === 'PULL' || kind === 'ISSUE') && repo !== null && number !== null) {
    if (kind === 'PULL') zoomReference({ kind: 'pull', repo, number, workspaceId: workspaceId ?? undefined });
    else if (workspaceId !== null) zoomReference({ kind: 'issue', repo, number, workspaceId });
    return;
  }
  if (kind === 'JIRA' && id !== null && target.connectionId !== null && target.cloudId !== null) {
    zoomReference({ kind: 'jira', connectionId: target.connectionId, cloudId: target.cloudId, issueId: id });
    return;
  }
  if (id === null || workspaceId === null) return;
  if (kind === 'TASK') window.location.hash = workspaceAgentTaskRoute(workspaceId, id);
  else if (kind === 'REVIEW') window.location.hash = workspaceReviewTaskRoute(workspaceId, id);
  else if (kind === 'WORK') zoomReference({ kind: 'work', workspaceId, itemId: id });
}

/**
 * Today's agenda above Needs attention, in its row language: a header of counts, the agent's one-line summary on global Home, then
 * at most five rows that open where the work happens. A workspace Home shows only its own rows.
 */
export default function TodayList({ today, scope, onOpen = openTarget }: {
  today: TodayAgenda;
  scope: TodayScope;
  /** The Assistant panel shows the app before opening, since it can sit beside an open web page. */
  onOpen?: (target: AgendaTargetDto) => void;
}): React.JSX.Element | null {
  const availability = useAgentAvailability();
  const { agenda, error, makeAgain, mark, showHidden } = today;
  const [expanded, setExpanded] = useState(false);
  const [recapOpen, setRecapOpen] = useState(false);
  if (!showsToday(agenda, scope) || !availability.development && agenda.items.length === 0) return null;

  const items = agenda.items;
  const rows = todayRows(items);
  const { done, total } = doneRatio(items);
  const hidden = hiddenCount(items);
  const finished = allDone(items);
  const collapsed = finished && !expanded;
  const making = agenda.state === 'RUNNING';
  const before = agenda.state === 'NONE';
  const ready = agenda.state === 'READY' || making && items.length > 0;
  const failure = error ?? agenda.failure;
  const global = scope === 'global';
  const toggle = () => setExpanded(value => !value);
  const calendar = calendarFact(agenda.calendar);

  const head = (
    <span className="today-head__label">
      <span className="today-head__title">Today</span>
      <span className="today-head__date">{dayText(agenda.day)}</span>
      {finished && (collapsed ? <ChevronRight size={12} aria-hidden="true" /> : <ChevronDown size={12} aria-hidden="true" />)}
    </span>
  );

  return (
    <section className={`today today--${scope}`} aria-label="Today">
      <div className="today-head">
        {finished
          ? <button type="button" className="today-head__toggle" aria-expanded={!collapsed} onClick={toggle}>{head}</button>
          : head}
        <span className="today-head__spacer" />
        <span className="today-head__facts">
          {hidden > 0 && (
            <button type="button" className="today-fact today-fact--button" title={`Show ${hidden} set aside for today`} onClick={showHidden}>
              <EyeOff size={13} aria-hidden="true" />{hidden}
            </button>
          )}
          {calendar !== null && (
            <span className={`today-fact${calendar.dim ? ' is-dim' : ''}`} title={calendar.title}>
              <Clock size={13} aria-hidden="true" />{calendar.text}
            </span>
          )}
          {ready && total > 0 && <span className="today-fact" title={`${done} of ${total} done`}><Check size={13} aria-hidden="true" />{done}/{total}</span>}
          {!before && agenda.generatedAt !== null && (
            <span className="today-fact" title={`Made at ${clockTime(agenda.generatedAt)}${agenda.model ? ` with ${agenda.model}` : ''}`}>{clockTime(agenda.generatedAt)}</span>
          )}
          {before && <span className="today-fact">at {minuteText(agenda.at)}</span>}
          {making
            ? <span className="today-fact today-fact--icon" title="Making today's agenda"><LoaderCircle className="today-spin" aria-hidden="true" /></span>
            : availability.development && (
              <IconButton label={before ? 'Make now' : 'Make again'} onClick={makeAgain}>
                <RotateCw size={13} aria-hidden="true" />
              </IconButton>
            )}
        </span>
      </div>

      {!availability.development && <p className="today-line">Reconnect your agent in Settings to update Today.</p>}
      {!collapsed && <>
        {global && ready && agenda.summary && <p className="today-summary">{agenda.summary}</p>}
        {making && items.length === 0 && <div className="today-line">Making today&apos;s agenda…</div>}
        {failure !== null && (
          <div className="today-failed" role="status">
            <TriangleAlert size={14} aria-hidden="true" />
            <span>{failure}</span>
            {!making && availability.development && <button type="button" className="today-link" onClick={makeAgain}>Make again</button>}
          </div>
        )}
        {agenda.state === 'READY' && items.length === 0 && <div className="today-line">Nothing pressing today</div>}
        {ready && rows.length > 0 && (
          <div className="item-list today-list" role="list">
            {rows.map(item => <TodayRow key={item.ref} item={item} scope={scope} onMark={mark} onOpen={onOpen} />)}
            {global && agenda.recap !== null && (
              <div role="listitem">
                <Clickable className="card-row today-recap" label="This week" onOpen={() => setRecapOpen(value => !value)}>
                  <CalendarCheck size={15} className="today-row__icon" aria-hidden="true" />
                  <span className="today-recap__title">This week</span>
                  {recapOpen ? <ChevronDown size={14} aria-hidden="true" /> : <ChevronRight size={14} aria-hidden="true" />}
                </Clickable>
                {recapOpen && <Recap recap={agenda.recap} onOpen={onOpen} />}
              </div>
            )}
          </div>
        )}
      </>}
    </section>
  );
}

function TodayRow({ item, scope, onMark, onOpen }: {
  item: AgendaItemDto;
  scope: TodayScope;
  onMark: (ref: string, mark: 'DONE' | 'DISMISSED' | null) => void;
  onOpen: (target: AgendaTargetDto) => void;
}) {
  const Icon = ACTION_ICONS[item.action];
  const isDone = item.status === 'done';
  const where = whereText(item.target, scope, item.where);
  return (
    <div role="listitem">
      <ItemRow className={`today-row${isDone ? ' is-done' : ''}`} label={`${item.title}: ${item.why}`} onOpen={() => onOpen(item.target)}
        icon={<Icon size={15} className="today-row__icon" aria-hidden="true" />}
        title={item.title}
        meta={<>
          <span className="today-row__where">{where}</span>
          <span className="item-row__sep">·</span>
          {isDone
            ? <span className="today-row__done" title={item.byYou ? 'You marked it done' : 'Closed by what happened since'}><Check size={12} aria-hidden="true" />{doneText(item)}</span>
            : <span className="today-row__why" title={item.why}>{item.why}</span>}
        </>}
        actions={isDone ? undefined : (
          <CardActions label={`Actions for ${item.title}`}>
            <IconButton label={`Mark “${item.title}” done`} title="Done" tone="ack" onClick={() => onMark(item.ref, 'DONE')}>
              <Check size={14} aria-hidden="true" />
            </IconButton>
            <IconButton label={`Set “${item.title}” aside for today`} title="Not today" onClick={() => onMark(item.ref, 'DISMISSED')}>
              <X size={14} aria-hidden="true" />
            </IconButton>
          </CardActions>
        )}
        age={isDone
          ? (
            <span className="today-row__reopen">
              <IconButton label={`Reopen “${item.title}”`} title="Reopen" onClick={() => onMark(item.ref, null)}>
                <Check size={14} aria-hidden="true" />
              </IconButton>
            </span>
          )
          : item.at === null ? null : <CardAge at={item.at} />} />
    </div>
  );
}

function Recap({ recap, onOpen }: { recap: NonNullable<AgendaDto['recap']>; onOpen: (target: AgendaTargetDto) => void }) {
  const links = [...recap.merged, ...recap.slipped];
  return (
    <div className="today-recap__body">
      {recap.text && <p className="today-recap__text">{recap.text}</p>}
      {([['Merged', recap.merged], ['Slipped', recap.slipped]] as const).filter(([, list]) => list.length > 0).map(([name, list]) => (
        <div key={name} className="today-recap__line">
          <span className="today-recap__name">{name}</span>
          {list.map(link => (
            <button key={link.ref} type="button" className="today-chip" onClick={() => onOpen(link.target)}>{recapLabel(link, links)}</button>
          ))}
        </div>
      ))}
    </div>
  );
}
