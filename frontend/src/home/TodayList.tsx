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
import { useMemo, useState, useSyncExternalStore } from 'react';
import {
  ArrowUpRight, CalendarCheck, Check, ChevronDown, ChevronRight, Eye, EyeOff, GitMerge, ListChecks, LoaderCircle, MessageCircleQuestion,
  MessageSquareReply, Play, RotateCw, TriangleAlert, Wrench, type LucideIcon,
} from 'lucide-react';
import { CardAge, Clickable, IconButton } from '../cards/Card';
import { zoomReference } from '../githubReference';
import { remoteErrorMessage } from '../remoteError';
import type { AgendaAction, AgendaDto, AgendaItemDto, AgendaTargetDto } from '../types';
import { workspaceAgentTaskRoute, workspaceReviewTaskRoute } from '../workspaces/model';
import type { AttentionItem } from './attention';
import {
  allDone, attentionKey, calendarFact, clockTime, dayText, doneRatio, hiddenCount, minuteText, recapLabel, showsToday, todayChips, todayRef, todayRows,
  type TodayChip, type TodayScope,
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

/** The kinds of item a row's Start begins a task for, as the item's own Start button does. */
const STARTS: readonly AgendaTargetDto['kind'][] = ['WORK', 'ISSUE', 'JIRA'];

const OPEN_LABELS: Record<AgendaTargetDto['kind'], string> = {
  PULL: 'Open PR', ISSUE: 'Open issue', JIRA: 'Open ticket', TASK: 'Open task', REVIEW: 'Open review', WORK: 'Open work item',
};

/** From this window width a Home's Today sits in its own column on the right; below it, Today joins the main column. */
const WIDE_QUERY = '(min-width: 1440px)';

function subscribeWide(notify: () => void): () => void {
  const media = window.matchMedia?.(WIDE_QUERY);
  media?.addEventListener('change', notify);
  return () => media?.removeEventListener('change', notify);
}

/** Whether the window is wide enough for a Home's right column. */
export function useWideWindow(): boolean {
  return useSyncExternalStore(subscribeWide, wideNow, () => true);
}

const wideNow = () => window.matchMedia?.(WIDE_QUERY).matches ?? true;

/** Opens an item where its work already lives; nothing starts or runs from here. */
export function openTarget(target: AgendaTargetDto): void {
  const { kind, workspaceId, repo, number, id } = target;
  if ((kind === 'PULL' || kind === 'ISSUE') && repo !== null && number !== null) {
    if (kind === 'PULL') zoomReference({ kind: 'pull', repo, number, workspaceId: workspaceId ?? undefined });
    else if (workspaceId !== null) zoomReference({ kind: 'issue', repo, number, workspaceId });
    // Only watched repositories have an issue view; another repository's issue is its GitHub page.
    else void window.bridge.openInAppBrowser(`https://github.com/${repo}/issues/${number}`);
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
 * Today's agenda: a header with the day's progress, then rows of a title and reason chips that open to the model's reason and what to
 * do about it. Home and the Assistant show it as a section, and a workspace Home as a card it can fold; a workspace Home lists only its
 * own rows.
 */
export default function TodayList({ today, scope, onOpen = openTarget, facts, variant = 'section' }: {
  today: TodayAgenda;
  scope: TodayScope;
  /** The Assistant panel shows the app before opening, since it can sit beside an open web page. */
  onOpen?: (target: AgendaTargetDto) => void;
  /** What the page already holds about the rows' items, by attention key, which the chips are made from. */
  facts?: AttentionItem[];
  variant?: 'section' | 'card';
}): React.JSX.Element | null {
  const availability = useAgentAvailability();
  const { agenda, error, makeAgain, mark, start, showHidden } = today;
  const [shown, setShown] = useState<boolean | null>(null);
  const [recapOpen, setRecapOpen] = useState(false);
  const [openRef, setOpenRef] = useState<string | null>(null);
  const byKey = useMemo(() => new Map((facts ?? []).map(item => [item.key.toLowerCase(), item])), [facts]);
  if (!showsToday(agenda, scope) || !availability.development && agenda.items.length === 0) return null;

  const items = agenda.items;
  const rows = todayRows(items);
  const { done, total } = doneRatio(items);
  const hidden = hiddenCount(items);
  const finished = allDone(items);
  const card = variant === 'card';
  // A card folds on its title; a section only once everything is done, when it starts folded.
  const collapsed = shown === null ? finished : !shown;
  const making = agenda.state === 'RUNNING';
  const before = agenda.state === 'NONE';
  const ready = agenda.state === 'READY' || making && items.length > 0;
  const failure = error ?? agenda.failure;
  const global = scope === 'global';
  const calendar = calendarFact(agenda.calendar);
  const now = Date.now();
  const made = !before && agenda.generatedAt !== null ? `Made at ${clockTime(agenda.generatedAt)}${agenda.model ? ` with ${agenda.model}` : ''} · ` : '';

  const label = card ? <span className="today-head__name">Today</span> : <span className="today-head__title">Today</span>;
  const chevron = collapsed ? <ChevronRight size={12} aria-hidden="true" /> : <ChevronDown size={12} aria-hidden="true" />;

  return (
    <section className={`today today--${scope}${card ? ' today--card' : ''}`} aria-label="Today">
      <div className="today-head">
        {card || finished
          ? <button type="button" className="today-head__toggle" aria-expanded={!collapsed} onClick={() => setShown(collapsed)}>{label}{chevron}</button>
          : <span className="today-head__label">{label}</span>}
        <span className="today-head__date" title={calendar === null ? undefined : `${calendar.text === '' ? '' : `${calendar.text} booked · `}${calendar.title}`}>
          {dayText(agenda.day)}
        </span>
        <span className="today-head__spacer" />
        <span className="today-head__facts">
          {hidden > 0 && (
            <button type="button" className="today-fact today-fact--button" title={`Show ${hidden} set aside for today`} onClick={showHidden}>
              <EyeOff size={13} aria-hidden="true" />{hidden}
            </button>
          )}
          {ready && total > 0 && (
            <span className="today-fact" title={`${done} of ${total} done`}>
              <span className="today-progress" aria-hidden="true"><span style={{ width: `${Math.round(done / total * 100)}%` }} /></span>
              {done}/{total}
            </span>
          )}
          {before && <span className="today-fact">at {minuteText(agenda.at)}</span>}
          {making
            ? <span className="today-fact today-fact--icon" title="Making today's agenda"><span className="bq-spinner is-spinning"><LoaderCircle className="today-spin" aria-hidden="true" /></span></span>
            : availability.development && (
              <IconButton label={before ? 'Make now' : 'Make again'} title={`${made}${before ? 'Make now' : 'Make again'}`} onClick={makeAgain}>
                <RotateCw size={13} aria-hidden="true" />
              </IconButton>
            )}
        </span>
      </div>

      {!availability.development && <p className="today-line">Reconnect your agent in Settings to update Today.</p>}
      {!collapsed && <>
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
            {rows.map(item => {
              const target = todayRef(item.target, scope, item.where);
              const fact = byKey.get(attentionKey(item.target)?.toLowerCase() ?? '');
              return (
                <TodayRow key={item.ref} item={item} reference={target} chips={todayChips(item, fact, now)} open={openRef === item.ref}
                  onToggle={() => setOpenRef(openRef === item.ref ? null : item.ref)}
                  onMark={mark} onStart={availability.development ? start : undefined} onOpen={onOpen} />
              );
            })}
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

/**
 * One row of the agenda: its check, title and reference over its reason chips. Opened, it says the model's reason and offers its action,
 * Done, and Tomorrow, which sets it aside for today; a row that suggests starting work offers Start, and what refuses it shows below.
 * A done row has nothing left to offer, so a click opens its item instead.
 */
function TodayRow({ item, reference, chips, open, onToggle, onMark, onStart, onOpen }: {
  item: AgendaItemDto;
  reference: string;
  chips: TodayChip[];
  open: boolean;
  onToggle: () => void;
  onMark: (ref: string, mark: 'DONE' | 'DISMISSED' | null) => void;
  /** Starts the task the row suggests; absent while the development agent is unavailable. */
  onStart?: (ref: string) => Promise<void>;
  onOpen: (target: AgendaTargetDto) => void;
}) {
  const [starting, setStarting] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);
  const isDone = item.status === 'done';
  const startable = onStart !== undefined && !isDone && item.action === 'start' && STARTS.includes(item.target.kind);
  const expanded = open && !isDone;
  const start = () => {
    if (onStart === undefined) return;
    setStarting(true);
    setRefusal(null);
    void onStart(item.ref).catch((cause: unknown) => setRefusal(remoteErrorMessage(cause))).finally(() => setStarting(false));
  };
  const act = (run: () => void) => (event: React.MouseEvent) => { event.stopPropagation(); run(); };
  return (
    <div role="listitem">
      <Clickable className={`card-row today-row${isDone ? ' is-done' : ''}${expanded ? ' is-open' : ''}`} label={`${item.title}: ${item.why}`}
        onOpen={isDone ? () => onOpen(item.target) : onToggle}>
        <button type="button" className="today-check" aria-label={isDone ? `Reopen “${item.title}”` : `Mark “${item.title}” done`}
          title={isDone ? (item.byYou ? 'You marked it done · reopen' : 'Closed by what happened since · reopen') : 'Done'}
          onClick={act(() => onMark(item.ref, isDone ? null : 'DONE'))}>
          {isDone && <Check size={10} strokeWidth={3} aria-hidden="true" />}
        </button>
        <div className="today-row__body">
          <div className="today-row__line">
            <span className="today-row__title" title={item.title}>{item.title}</span>
            <span className="today-row__ref" title={reference}>{reference}</span>
          </div>
          <div className="today-row__chips">
            {chips.map(chip => <span key={chip.text} className={`today-reason is-${chip.tone}`}>{chip.text}</span>)}
          </div>
          {expanded && (
            <div className="today-row__more">
              <p className="today-row__why">{item.why}</p>
              <div className="today-row__actions">
                {startable
                  ? <button type="button" className="bq-button bq-button--primary" disabled={starting} onClick={act(start)}>
                    {starting ? <span className="bq-spinner is-spinning"><LoaderCircle className="today-spin" aria-hidden="true" /></span> : <Play aria-hidden="true" />}Start task
                  </button>
                  : <button type="button" className="bq-button bq-button--primary" onClick={act(() => onOpen(item.target))}>
                    {item.action === 'review' && item.target.kind === 'PULL' ? 'Open review' : OPEN_LABELS[item.target.kind]}
                  </button>}
                <button type="button" className="bq-button" onClick={act(() => onMark(item.ref, 'DONE'))}>Done</button>
                <button type="button" className="bq-button" title="Set it aside for today" onClick={act(() => onMark(item.ref, 'DISMISSED'))}>Tomorrow</button>
              </div>
            </div>
          )}
          {refusal !== null && <div className="today-row__refusal" role="alert"><TriangleAlert size={12} aria-hidden="true" /><span>{refusal}</span></div>}
        </div>
        {!isDone && item.at !== null ? <CardAge at={item.at} /> : <span />}
      </Clickable>
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
