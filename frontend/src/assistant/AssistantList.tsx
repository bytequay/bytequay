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
import {
  Ban, Check, CheckCheck, CircleCheck, CircleDashed, CircleOff, CircleX, FileDiff, Flag, GitCommitVertical, GitMerge, GitPullRequestClosed, LayoutGrid, List,
  ListTodo, LoaderCircle, MessageSquare, ThumbsUp, TriangleAlert,
} from 'lucide-react';
import { Card, CardAge, Clickable, IconButton, Pill, SourceIcon } from '../cards/Card';
import type { AttentionSource } from '../home/attention';
import { clockTime } from '../home/today';
import { commentExcerpt } from '../pulls/PrivateThread';
import { remoteErrorMessage } from '../remoteError';
import type { AgendaTargetDto, AssistantItemDto, AssistantListStyle, AssistantRowDto } from '../types';
import { dayLabel, refLabel, rowState, rowTitle, timelineDays } from './model';
import type { PullAction } from './pullActions';

const STYLES: { style: AssistantListStyle; label: string; icon: ReactNode }[] = [
  { style: 'ROWS', label: 'Rows', icon: <List size={14} aria-hidden="true" /> },
  { style: 'CARDS', label: 'Cards', icon: <LayoutGrid size={14} aria-hidden="true" /> },
  { style: 'TIMELINE', label: 'Timeline', icon: <GitCommitVertical size={14} aria-hidden="true" /> },
];

const SOURCES: Partial<Record<AssistantRowDto['kind'], AttentionSource>> = { PULL: 'pr', ISSUE: 'issue', JIRA: 'jira', TASK: 'task', REVIEW: 'task' };

const CHECKS: Record<string, { icon: ReactNode; title: string }> = {
  passing: { icon: <CircleCheck size={13} aria-hidden="true" />, title: 'Checks passing' },
  failing: { icon: <CircleX size={13} aria-hidden="true" />, title: 'Checks failing' },
  pending: { icon: <CircleDashed size={13} aria-hidden="true" />, title: 'Checks running' },
};

const PHASES: Record<string, { icon: ReactNode; title: string }> = {
  running: { icon: <span className="assistant-spinner" />, title: 'Running' },
  waiting: { icon: <span className="assistant-dot" />, title: 'Waiting for your answer' },
  blocked: { icon: <span className="assistant-dot" />, title: 'Blocked' },
  completed: { icon: <CircleCheck size={13} aria-hidden="true" />, title: 'Completed' },
  failed: { icon: <TriangleAlert size={13} aria-hidden="true" />, title: 'Failed' },
};

type Open = (target: AgendaTargetDto) => void;
/** Completes a task by the user's click; rejects with why it could not. */
type Complete = (target: AgendaTargetDto) => Promise<void>;
/** Acts on a pull request by the user's click; rejects the same way. */
type Pull = (target: AgendaTargetDto, action: PullAction) => Promise<void>;

/** The phases of a task that is still open; only an idle one can be completed. */
const OPEN_PHASES = ['running', 'waiting', 'blocked', 'idle'];

/**
 * A list the assistant showed, drawn from the app's own data: its title and how many items ByteQuay still holds, a switch between rows,
 * cards and a timeline, and each ref, which the assistant never writes. It picks the first style; the switch changes only how this list
 * draws.
 */
export default function AssistantList({ item, rows, onOpen, onComplete, onPull }: {
  item: AssistantItemDto;
  rows: Record<string, AssistantRowDto>;
  onOpen: Open;
  onComplete: Complete;
  onPull: Pull;
}): React.JSX.Element | null {
  const [style, setStyle] = useState<AssistantListStyle>(item.list?.style ?? 'ROWS');
  if (item.list === null) return null;
  const { refs } = item.list;
  const held = refs.filter(ref => rows[ref] !== undefined).length;
  return (
    <section className="assistant-items" aria-label={item.text}>
      <div className="assistant-items__head">
        <span className="assistant-items__title" title={item.text}>{item.text}</span>
        {held > 0 && <span className="assistant-items__count" title={`${held} ${held === 1 ? 'item' : 'items'}`}>{held}</span>}
        <span className="assistant-spacer" />
        {STYLES.map(each => (
          <IconButton key={each.style} label={`Show as ${each.label.toLowerCase()}`} title={each.label} tone={each.style === style ? 'selected' : undefined}
            pressed={each.style === style} onClick={() => setStyle(each.style)}>{each.icon}</IconButton>
        ))}
      </div>
      {style === 'CARDS' && (
        <div className="assistant-items__cards">{refs.map(ref => <ItemCard key={ref} id={ref} row={rows[ref]} onOpen={onOpen} onComplete={onComplete} onPull={onPull} />)}</div>
      )}
      {style === 'TIMELINE' && timelineDays(refs, rows).map(day => (
        <div key={day.day ?? 'untimed'} className={`assistant-items__day${day.day === null ? ' is-untimed' : ''}`}>
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
    <Clickable className="card-row assistant-row" label={label(row)} title={rowTitle(row)} onOpen={() => onOpen(row.target)}>
      {timed && <span className="assistant-row__time">{row.at === null ? '' : clockTime(row.at)}</span>}
      <RowIcon row={row} size={15} />
      {row.key !== null && <span className="card-key">{row.key}</span>}
      <span className="assistant-row__title">{row.title}</span>
      <Facts row={row} card={false} />
      {!timed && row.at !== null && <CardAge at={row.at} />}
    </Clickable>
  );
}

/** A button on a card, which acts at once; `busy` is what it reads while it acts. */
type CardAction = { text: string; busy: string; label: string; title?: string; icon: ReactNode; danger?: boolean; disabled?: boolean; run: () => Promise<void> };

/**
 * The user's own buttons on a card, each doing what the item's own page does: a task's Complete task while it is open, enabled once it
 * is idle, and a pull request's Approve, Merge, Comment and Close while it is open. Approve skips the user's own pull requests, which
 * GitHub refuses, and those the user already approved; Close shows on the user's own only, as the page's Close does without write access.
 */
function cardActions(row: AssistantRowDto, onComplete: Complete, onPull: Pull): CardAction[] | null {
  if (row.kind === 'TASK' && row.state !== null && OPEN_PHASES.includes(row.state)) {
    const idle = row.state === 'idle';
    return [{ text: 'Complete task', busy: 'Completing…', label: `Complete task “${row.title}”`, title: idle ? undefined : 'Available when the task is idle',
      icon: <CircleCheck aria-hidden="true" />, disabled: !idle, run: () => onComplete(row.target) }];
  }
  if (row.kind !== 'PULL' || (row.state !== 'open' && row.state !== 'draft')) return null;
  const name = row.key ?? row.title;
  const pull = (action: PullAction) => () => onPull(row.target, action);
  const actions: CardAction[] = [];
  if (row.state === 'open' && !row.mine && !row.youApproved) {
    actions.push({ text: 'Approve', busy: 'Approving…', label: `Approve ${name}`, icon: <Check aria-hidden="true" />, run: pull('approve') });
  }
  if (row.state === 'open') actions.push({ text: 'Merge', busy: 'Merging…', label: `Merge ${name}`, icon: <GitMerge aria-hidden="true" />, run: pull('merge') });
  actions.push({ text: 'Comment', busy: 'Comment', label: `Comment on ${name}`, icon: <MessageSquare aria-hidden="true" />, run: pull('comment') });
  if (row.mine) {
    actions.push({ text: 'Close', busy: 'Closing…', label: `Close ${name}`, title: 'Close pull request', icon: <GitPullRequestClosed aria-hidden="true" />, danger: true,
      run: pull('close') });
  }
  return actions;
}

/**
 * One item as a card: where it stands in the strip, what happened last in the body, then the user's own buttons, or else what the strip
 * and the body do not already say. A button that cannot act says why in their place, with the way to the item's page.
 */
function ItemCard({ id, row, onOpen, onComplete, onPull }: { id: string; row: AssistantRowDto | undefined; onOpen: Open; onComplete: Complete; onPull: Pull }) {
  const [busy, setBusy] = useState<string | null>(null);
  const [refusal, setRefusal] = useState<string | null>(null);
  if (row === undefined) return <Gone id={id} timed={false} />;
  const state = rowState(row);
  const actions = cardActions(row, onComplete, onPull);
  const at = row.latest?.at ?? row.at;
  // A task's strip names its agent, so the agent's own messages need no name in the body.
  const by = row.latest === null || row.latest.by === row.agent ? null : row.latest.by;
  // A comment or an agent's message is Markdown, which one line cannot draw; a quote that was only markup leaves just what happened.
  const said = row.latest === null ? null : commentExcerpt(row.latest.text).replace(/:$/, '');
  const act = (action: CardAction) => {
    setBusy(action.text);
    setRefusal(null);
    void action.run().catch((cause: unknown) => setRefusal(remoteErrorMessage(cause))).finally(() => setBusy(null));
  };
  return (
    <Card compact className="assistant-item-card" label={label(row)} title={rowTitle(row)} onOpen={() => onOpen(row.target)}>
      <div className="assistant-item-card__strip">
        <RowIcon row={row} size={14} />
        {row.key !== null && <span className="card-key">{row.key}</span>}
        {row.agent !== null && <span className="assistant-item-card__lane">{row.agent}</span>}
        <span className="assistant-spacer" />
        {actions !== null && at !== null && <CardAge at={at} />}
        {state !== null && <Pill outline>{state}</Pill>}
      </div>
      <div className="assistant-item-card__body">
        <div className="assistant-item-card__title" title={row.title}>{row.title}</div>
        {said !== null && (
          <div className="assistant-item-card__latest" title={said}>
            {by !== null && <span className="assistant-item-card__by">{by}</span>}
            <span className="assistant-item-card__text">{said}</span>
          </div>
        )}
      </div>
      {refusal !== null ? (
        <div className="assistant-item-card__refusal" role="alert">
          <TriangleAlert size={13} aria-hidden="true" />
          <span>{refusal}</span>
          <CardButton text="Open" label={`Open ${row.key ?? row.title}`} onClick={() => onOpen(row.target)} />
        </div>
      ) : actions === null ? (
        <div className="assistant-item-card__foot">
          <Facts row={row} card />
          <span className="assistant-spacer" />
          {at !== null && <CardAge at={at} />}
        </div>
      ) : (
        <div className="assistant-item-card__foot" role="group" aria-label={`Actions for ${row.title}`}>
          {actions.map(action => (
            <CardButton key={action.text} text={busy === action.text ? action.busy : action.text} label={action.label} title={action.title}
              icon={busy === action.text ? <LoaderCircle className="agt-spin" aria-hidden="true" /> : action.icon} danger={action.danger}
              disabled={action.disabled === true || busy !== null} onClick={() => act(action)} />
          ))}
        </div>
      )}
    </Card>
  );
}

/** A text button on a card; it never opens the card it sits on. */
function CardButton({ text, label, title, icon, danger = false, disabled = false, onClick }: {
  text: string;
  label: string;
  title?: string;
  icon?: ReactNode;
  danger?: boolean;
  disabled?: boolean;
  onClick: () => void;
}) {
  return (
    <button type="button" className={`bq-button bq-button--ghost assistant-card-button${danger ? ' is-danger' : ''}`} aria-label={label} title={title}
      disabled={disabled} onClick={event => { event.stopPropagation(); onClick(); }}>
      {icon}{text}
    </button>
  );
}

/** A ref that no longer resolves, such as a pull request ByteQuay no longer holds. */
function Gone({ id, timed }: { id: string; timed: boolean }) {
  return (
    <div className="assistant-row is-gone" title={`ByteQuay no longer holds ${refLabel(id)}`}>
      {timed && <span className="assistant-row__time" />}
      <span className="assistant-row__icon"><CircleOff size={15} aria-hidden="true" /></span>
      <span className="assistant-row__title">{refLabel(id)}</span>
      <span>Gone</span>
    </div>
  );
}

/** An item's kind as a plain icon; where it stands is the card's pill or the row's facts, so no state tints it. */
function RowIcon({ row, size }: { row: AssistantRowDto; size: number }) {
  const source = SOURCES[row.kind];
  const icon = source !== undefined ? <SourceIcon source={source} size={size} />
    : row.kind === 'EPIC' ? <Flag size={size} aria-hidden="true" /> : <ListTodo size={size} aria-hidden="true" />;
  return <span className="assistant-row__icon">{icon}</span>;
}

/**
 * An item's facts by kind, as icons and counts with the words on hover. A card leaves out where it stands and its agent, which its strip
 * says, and a pull request's review decision and merge block, which its state names.
 */
function Facts({ row, card }: { row: AssistantRowDto; card: boolean }) {
  const checks = row.ci === null ? undefined : CHECKS[row.ci];
  const phase = (row.kind === 'TASK' || row.kind === 'REVIEW') && row.state !== null ? PHASES[row.state] : undefined;
  return (
    <span className="assistant-row__facts">
      {checks !== undefined && <span className="assistant-fact" title={checks.title}>{checks.icon}</span>}
      {row.approvals !== null && row.approvals > 0 && (
        <span className="assistant-fact" title={`${row.approvals} ${row.approvals === 1 ? 'approval' : 'approvals'}`}><ThumbsUp size={13} aria-hidden="true" />{row.approvals}</span>
      )}
      {!card && row.review === 'approved' && <span className="assistant-fact" title="Review decision: approved"><CheckCheck size={13} aria-hidden="true" /></span>}
      {!card && row.review === 'changes_requested' && (
        <span className="assistant-fact" title="Review decision: changes requested"><FileDiff size={13} aria-hidden="true" /></span>
      )}
      {!card && row.mergeBlocked && row.state === 'open' && <span className="assistant-fact" title="Merge blocked"><Ban size={13} aria-hidden="true" /></span>}
      {!card && row.agent !== null && <span className="assistant-fact" title={`Agent: ${row.agent}`}>{row.agent}</span>}
      {!card && phase !== undefined && <span className="assistant-fact" title={phase.title}>{phase.icon}</span>}
      {!card && row.kind === 'JIRA' && row.state !== null && <span className="assistant-fact" title={`Status: ${row.state}`}>{row.state}</span>}
      {row.epic !== null && <span className="assistant-fact assistant-fact--epic" title={`Epic: ${row.epic}`}><Flag size={13} aria-hidden="true" /><span>{row.epic}</span></span>}
      {row.kind === 'EPIC' && row.done !== null && row.total !== null && (
        <span className="assistant-fact" title={`${row.done} of ${row.total} done`}><Check size={13} aria-hidden="true" />{row.done}/{row.total}</span>
      )}
    </span>
  );
}
