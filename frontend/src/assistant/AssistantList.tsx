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
import { useState, type ReactNode } from 'react';
import { CalendarClock, Check, CircleCheck, CircleDashed, CircleOff, CircleX, FileDiff, LayoutGrid, ListChecks, ListTodo, ListTree, Rows3, TriangleAlert } from 'lucide-react';
import { Card, CardActions, CardAge, Clickable, IconButton, Pill, SourceIcon } from '../cards/Card';
import type { AttentionSource } from '../home/attention';
import { clockTime } from '../home/today';
import type { AgendaTargetDto, AssistantItemDto, AssistantListStyle, AssistantRowDto } from '../types';
import { dayLabel, refLabel, rowState, timelineDays } from './model';

const STYLES: { style: AssistantListStyle; label: string; icon: ReactNode }[] = [
  { style: 'ROWS', label: 'Rows', icon: <Rows3 size={14} aria-hidden="true" /> },
  { style: 'CARDS', label: 'Cards', icon: <LayoutGrid size={14} aria-hidden="true" /> },
  { style: 'TIMELINE', label: 'Timeline', icon: <CalendarClock size={14} aria-hidden="true" /> },
];

const SOURCES: Partial<Record<AssistantRowDto['kind'], AttentionSource>> = { PULL: 'pr', ISSUE: 'issue', JIRA: 'jira', TASK: 'task', REVIEW: 'task' };

const CHECKS: Record<string, { icon: ReactNode; tone: string; title: string }> = {
  passing: { icon: <CircleCheck size={13} aria-hidden="true" />, tone: 'is-green', title: 'Checks passing' },
  failing: { icon: <CircleX size={13} aria-hidden="true" />, tone: 'is-red', title: 'Checks failing' },
  pending: { icon: <CircleDashed size={13} aria-hidden="true" />, tone: 'is-amber', title: 'Checks running' },
};

type Open = (target: AgendaTargetDto) => void;
/** Completes a task by the user's click; settles once the call is done, failed or not. */
type Complete = (target: AgendaTargetDto) => Promise<void>;

/** The phases of a task that is still open; only an idle one can be completed. */
const OPEN_PHASES = ['running', 'waiting', 'blocked', 'idle'];

/**
 * A list the assistant showed: its title, a switch between rows, cards and a timeline, and each ref drawn from the app's own data,
 * which the assistant never writes. It picks the first style; the switch changes only how this list draws.
 */
export default function AssistantList({ item, rows, onOpen, onComplete }: {
  item: AssistantItemDto;
  rows: Record<string, AssistantRowDto>;
  onOpen: Open;
  onComplete: Complete;
}): React.JSX.Element | null {
  const [style, setStyle] = useState<AssistantListStyle>(item.list?.style ?? 'ROWS');
  if (item.list === null) return null;
  const { refs } = item.list;
  return (
    <section className="assistant-items" aria-label={item.text}>
      <div className="assistant-items__head">
        <span className="assistant-items__title" title={item.text}>{item.text}</span>
        <span className="assistant-items__count" title={`${refs.length} ${refs.length === 1 ? 'item' : 'items'}`}>{refs.length}</span>
        <span className="assistant-spacer" />
        {STYLES.map(each => (
          <IconButton key={each.style} label={`Show as ${each.label.toLowerCase()}`} title={each.label} tone={each.style === style ? 'active' : undefined}
            pressed={each.style === style} onClick={() => setStyle(each.style)}>{each.icon}</IconButton>
        ))}
      </div>
      {style === 'CARDS' && (
        <div className="assistant-items__cards">{refs.map(ref => <ItemCard key={ref} id={ref} row={rows[ref]} onOpen={onOpen} onComplete={onComplete} />)}</div>
      )}
      {style === 'TIMELINE' && timelineDays(refs, rows).map(day => (
        <div key={day.day ?? 'untimed'} className="assistant-items__day">
          {day.day !== null && <div className="assistant-items__date">{dayLabel(day.day)}</div>}
          {day.refs.map(ref => <Line key={ref} id={ref} row={rows[ref]} timed onOpen={onOpen} />)}
        </div>
      ))}
      {style === 'ROWS' && refs.map(ref => <Line key={ref} id={ref} row={rows[ref]} timed={false} onOpen={onOpen} />)}
    </section>
  );
}

const label = (row: AssistantRowDto): string => row.key === null ? row.title : `${row.key} ${row.title}`;

/** One item on one line; a timeline puts its time first instead of its age last. */
function Line({ id, row, timed, onOpen }: { id: string; row: AssistantRowDto | undefined; timed: boolean; onOpen: Open }) {
  if (row === undefined) return <Gone id={id} timed={timed} />;
  return (
    <Clickable className="card-row assistant-row" label={label(row)} title={row.title} onOpen={() => onOpen(row.target)}>
      {timed && <span className="assistant-row__time">{row.at === null ? '' : clockTime(row.at)}</span>}
      <RowIcon row={row} />
      {row.key !== null && <span className="card-key">{row.key}</span>}
      <span className="assistant-row__title">{row.title}</span>
      <Facts row={row} withState />
      {!timed && row.at !== null && <CardAge at={row.at} />}
    </Clickable>
  );
}

/** One item as a card: where it stands in the strip, and what happened last in the body; a task's card also carries its own Complete task. */
function ItemCard({ id, row, onOpen, onComplete }: { id: string; row: AssistantRowDto | undefined; onOpen: Open; onComplete: Complete }) {
  if (row === undefined) return <Gone id={id} timed={false} />;
  const state = rowState(row);
  const source = SOURCES[row.kind];
  return (
    <Card compact className={`assistant-item-card${source === undefined ? '' : ` is-${source}`}`} label={label(row)} onOpen={() => onOpen(row.target)}>
      <div className="assistant-item-card__strip">
        <RowIcon row={row} />
        {row.key !== null && <span className="card-key">{row.key}</span>}
        <span className="assistant-spacer" />
        {state !== null && <Pill outline>{state}</Pill>}
      </div>
      <div className="assistant-item-card__body">
        <div className="assistant-item-card__title" title={row.title}>{row.title}</div>
        <div className="assistant-item-card__latest">
          {row.latest === null
            ? <><Facts row={row} withState={false} /><span className="assistant-spacer" /></>
            : <>
              {row.latest.by !== null && <span className="assistant-item-card__by">{row.latest.by}</span>}
              <span className="assistant-item-card__text" title={row.latest.text}>{row.latest.text}</span>
            </>}
          {row.kind === 'TASK' && row.state !== null && OPEN_PHASES.includes(row.state) && <CompleteTask row={row} onComplete={onComplete} />}
          {(row.latest?.at ?? row.at) !== null && <CardAge at={row.latest?.at ?? row.at} />}
        </div>
      </div>
    </Card>
  );
}

/** Completing a task stays the user's click, as on the task's own page: it waits until the task is idle. */
function CompleteTask({ row, onComplete }: { row: AssistantRowDto; onComplete: Complete }) {
  const [busy, setBusy] = useState(false);
  const idle = row.state === 'idle';
  return (
    <CardActions label={`Actions for ${row.title}`}>
      <IconButton label={`Complete “${row.title}”`} title={idle ? 'Complete task' : 'Available when the task is idle'} tone="ack" disabled={!idle || busy}
        onClick={() => {
          setBusy(true);
          void onComplete(row.target).finally(() => setBusy(false));
        }}>
        <CircleCheck size={14} aria-hidden="true" />
      </IconButton>
    </CardActions>
  );
}

/** A ref that no longer resolves, such as a pull request ByteQuay no longer holds. */
function Gone({ id, timed }: { id: string; timed: boolean }) {
  return (
    <div className="assistant-row is-gone" title="ByteQuay no longer holds this">
      {timed && <span className="assistant-row__time" />}
      <CircleOff size={14} aria-hidden="true" />
      <span className="assistant-row__title">{refLabel(id)}</span>
      <span>Gone</span>
    </div>
  );
}

function RowIcon({ row }: { row: AssistantRowDto }) {
  const source = SOURCES[row.kind];
  if (source !== undefined) return <SourceIcon source={source} tinted ended={row.state === 'merged' || row.state === 'closed' ? row.state : null} />;
  return row.kind === 'EPIC' ? <ListTree className="assistant-row__icon" size={14} aria-hidden="true" /> : <ListTodo className="assistant-row__icon" size={14} aria-hidden="true" />;
}

/** A row's facts by kind, as icons and counts with the words on hover; a card's strip already says where it stands. */
function Facts({ row, withState }: { row: AssistantRowDto; withState: boolean }) {
  const checks = row.ci === null ? undefined : CHECKS[row.ci];
  return (
    <span className="assistant-row__facts">
      {checks !== undefined && <span className={`assistant-fact ${checks.tone}`} title={checks.title}>{checks.icon}</span>}
      {row.review === 'changes_requested'
        ? <span className="assistant-fact is-red" title="Changes requested"><FileDiff size={13} aria-hidden="true" /></span>
        : row.approvals !== null && row.approvals > 0 && (
          <span className="assistant-fact is-green" title={`${row.approvals} ${row.approvals === 1 ? 'approval' : 'approvals'}`}><Check size={13} aria-hidden="true" />{row.approvals}</span>
        )}
      {row.agent !== null && <span className="assistant-fact" title={`Runs with ${row.agent}`}>{row.agent}</span>}
      {withState && (row.kind === 'TASK' || row.kind === 'REVIEW') && <Phase phase={row.state} />}
      {row.epic !== null && <span className="assistant-fact assistant-fact--epic" title={`Epic: ${row.epic}`}><ListTree size={13} aria-hidden="true" /><span>{row.epic}</span></span>}
      {withState && (row.kind === 'ISSUE' || row.kind === 'JIRA' || row.kind === 'WORK') && row.state !== null && <Pill>{rowState(row)}</Pill>}
      {withState && row.kind === 'EPIC' && row.done !== null && row.total !== null && (
        <span className="assistant-fact" title={`${row.done} of ${row.total} done`}><ListChecks size={13} aria-hidden="true" />{row.done}/{row.total}</span>
      )}
    </span>
  );
}

function Phase({ phase }: { phase: string | null }) {
  const title = phase === null ? '' : phase.charAt(0).toUpperCase() + phase.slice(1);
  switch (phase) {
    case 'running': return <span className="assistant-fact" title={title}><span className="assistant-spinner" /></span>;
    case 'waiting': case 'blocked': return <span className="assistant-fact" title={title}><span className="assistant-dot" /></span>;
    case 'completed': return <span className="assistant-fact is-green" title={title}><CircleCheck size={13} aria-hidden="true" /></span>;
    case 'failed': return <span className="assistant-fact is-amber" title={title}><TriangleAlert size={13} aria-hidden="true" /></span>;
    default: return null;
  }
}
