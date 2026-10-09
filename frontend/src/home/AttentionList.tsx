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
import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import Toast from '../Toast';
import { anchorPanel } from '../ReferenceCard';
import AttentionRow from './AttentionRow';
import { pullRequestIsMine } from '../pulls/model';
import { usePinnedItems } from './pinned';
import { useSnoozedAttention } from './useSnoozedAttention';
import { useTeams } from '../teams/useTeams';
import { teamAttentionMatch } from '../teams/priority';
import {
  ATTENTION_SOURCES,
  attentionCards,
  attentionEnded,
  attentionSource,
  handledEntry,
  handledSummary,
  myPullRequestCards,
  SNOOZE_OPTIONS,
  snoozeHint,
  snoozeUntil,
  visibleAttention,
  type AttentionFilter,
  type AttentionItem,
  type Handled,
  type HandledEntry,
  type SnoozeId,
} from './attention';

const MENU_WIDTH = 210;
/** Header plus three options; kept in step with .atn-menu in home.css. */
const MENU_HEIGHT = 130;
const FILTERS: AttentionFilter[] = ['all', 'pr', 'issue', 'jira', 'task'];
const OPEN_ONLY_KEY = 'bytequay.attention.openOnly';

function loadOpenOnly(): boolean {
  try { return window.localStorage.getItem(OPEN_ONLY_KEY) === 'true'; } catch { return false; }
}

type Props = {
  candidates: AttentionItem[];
  handled: Handled;
  onHandledChange: (handled: Handled) => void;
  onOpen: (item: AttentionItem) => void;
  onStartIssue?: (item: AttentionItem) => void;
  /** The signed-in login, so a review request can say it was yours. */
  viewerLogin?: string | null;
  /** Home spans every watched repository and names each card's; a workspace shows only its own. */
  variant?: 'home' | 'workspace';
  section?: 'attention' | 'my-prs';
  scope?: { workspaceId: string; repository: string };
  /** The pinned goal each card can join or leave; absent where no goal can hold the card. */
  goal?: (item: AttentionItem) => GoalToggle | null;
  /** Whether Jira is connected; without it there is no Jira tab unless a Jira card is listed anyway. */
  jiraConnected?: boolean;
};

/**
 * A card's place in a pinned goal, and the change that flips it.
 *
 * picked: a card under the Goal control offers this same next action.
 */
export type GoalToggle = { inGoal: boolean; toggle: () => Promise<unknown>; picked?: boolean };

// What still needs the user's own card while a Goal card offers its source as practice.
const URGENT = new Set<AttentionItem['kind']>(['approval', 'review', 'changes', 'failed', 'ci']);

type Anchored = { item: AttentionItem; anchor: DOMRect };
type ToastState = { id: number; text: string; key: string };

export default function AttentionList({
  candidates, handled, onHandledChange, onOpen, onStartIssue, viewerLogin = null, variant = 'home', section = 'attention', scope, goal, jiraConnected = false,
}: Props) {
  const myPrs = section === 'my-prs';
  const title = myPrs ? 'My PRs' : 'Needs attention';
  const [filter, setFilter] = useState<AttentionFilter>('all');
  const [onlyTeam, setOnlyTeam] = useState(false);
  const [openOnly, setOpenOnly] = useState(loadOpenOnly);
  const circles = useTeams(viewerLogin).directory?.circles ?? [];
  const [snooze, setSnooze] = useState<Anchored | null>(null);
  const [toast, setToast] = useState<ToastState | null>(null);
  const [session, setSession] = useState<string[]>([]);
  const toastId = useRef(0);
  const pinned = usePinnedItems();
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const deadlines = Object.values(handled).flatMap(entry =>
      entry.kind === 'snooze' && entry.until !== null && entry.until > Date.now() ? [entry.until] : []);
    if (deadlines.length === 0) return;
    const timer = window.setTimeout(() => setNow(Date.now()), Math.min(Math.min(...deadlines) - Date.now(), 2_147_483_647));
    return () => window.clearTimeout(timer);
  }, [handled, now]);

  // Handled items drop out before the cap, so the next eligible item takes a handled card's place.
  const reminders = useSnoozedAttention(candidates, handled, Math.max(now, Date.now()), scope);
  // A source the Goal's card offers as practice folds into that card, unless it asks for an approval, a review or a recovery.
  const live = visibleAttention(reminders, handled, Math.max(now, Date.now()))
    .filter(item => !(variant === 'workspace' && item.pr?.state === 'open' && pullRequestIsMine(item.pr, viewerLogin))
      && !pinned.some(pin => pin.key === item.key) && (!openOnly || attentionEnded(item) === null)
      && (URGENT.has(item.kind) || goal?.(item)?.picked !== true));
  const filters = FILTERS.filter(id => id !== 'jira' || jiraConnected || live.some(item => attentionSource(item) === 'jira'));
  const cards = myPrs ? myPullRequestCards(reminders, handled, viewerLogin, Math.max(now, Date.now()))
    : attentionCards(live, filters.includes(filter) ? filter : 'all', undefined, { circles, viewerLogin, onlyTeam }, Math.max(now, Date.now()));
  const sessionEntries = session
    .map(key => handled[key])
    .filter((entry): entry is HandledEntry => entry !== undefined);
  const summary = handledSummary(sessionEntries);

  function handle(item: AttentionItem, entry: HandledEntry, text: string) {
    const key = item.key;
    setSnooze(null);
    onHandledChange({ ...handled, [key]: entry });
    setSession(previous => previous.includes(key) ? previous : [...previous, key]);
    toastId.current += 1;
    setToast({ id: toastId.current, text, key });
  }

  function acknowledge(item: AttentionItem) {
    handle(
      item,
      handledEntry(item, 'ack', null, 'acknowledged'),
      `Acknowledged ${item.reference ?? `#${item.number}`} — cleared from your list`,
    );
  }

  function applySnooze(item: AttentionItem, option: SnoozeId, label: string) {
    handle(
      item,
      handledEntry(item, 'snooze', snoozeUntil(option, Date.now()), label.toLowerCase()),
      `Snoozed ${item.reference ?? `#${item.number}`} · ${label.toLowerCase()}`,
    );
  }

  function restore(keys: string[]) {
    const next = { ...handled };
    for (const key of keys) delete next[key];
    onHandledChange(next);
    setSession(previous => previous.filter(key => !keys.includes(key)));
    setToast(null);
  }

  const startable = (item: AttentionItem) => item.type === 'issue' && item.canStartDevelopment !== false && onStartIssue !== undefined && item.issueState !== 'closed'
    && (item.jira === undefined || item.jira.status.category !== 'done' && item.jira.task === null);

  return (
    <section className={`atn-section is-${variant}`} aria-label={title}>
      <div className="atn-head">
        <span className="atn-head__label">{title}</span>
        {!myPrs && <>
          {(circles.length > 0 || onlyTeam) && (
            <button type="button" className={`atn-toggle${onlyTeam ? ' is-active' : ''}`} aria-pressed={onlyTeam} onClick={() => setOnlyTeam(value => !value)}>Circles</button>
          )}
          <button type="button" className={`atn-toggle${openOnly ? ' is-active' : ''}`} aria-pressed={openOnly} title="Hide merged, closed and completed items"
            onClick={() => {
              const next = !openOnly;
              setOpenOnly(next);
              try { window.localStorage.setItem(OPEN_ONLY_KEY, String(next)); } catch { /* the toggle still works for this visit */ }
            }}>Open only</button>
          <div className="atn-filters" role="tablist" aria-label="Filter what needs attention">
            {filters.map(id => (
              <button key={id} type="button" role="tab" aria-selected={filter === id}
                className={`atn-filter${filter === id ? ' is-active' : ''}`} onClick={() => setFilter(id)}>
                {id === 'all' ? 'All' : ATTENTION_SOURCES[id].filter}
              </button>
            ))}
          </div>
        </>}
      </div>

      {cards.length > 0 ? (
        <div className="item-list" role="list">
          {cards.map(item => {
            const membership = item.issueState === 'closed' ? null : goal?.(item) ?? null;
            const match = teamAttentionMatch(item, circles, viewerLogin);
            return (
              <AttentionRow
                key={item.key}
                item={item}
                circle={match === null ? null : { match, circles: [match.circle] }}
                viewerLogin={viewerLogin}
                showRepo={variant === 'home'}
                onOpen={() => onOpen(item)}
                onAcknowledge={myPrs ? undefined : () => acknowledge(item)}
                onSnooze={anchor => setSnooze({ item, anchor })}
                onStart={startable(item) ? () => onStartIssue?.(item) : undefined}
                inGoal={membership?.inGoal ?? false}
                onToggleGoal={membership === null ? undefined : () => {
                  void membership.toggle().catch((failure: unknown) => {
                    toastId.current += 1;
                    setToast({ id: toastId.current, text: `Could not change the goal: ${failure instanceof Error ? failure.message : String(failure)}`, key: '' });
                  });
                }}
              />
            );
          })}
        </div>
      ) : (
        <div className="atn-clear">{myPrs ? 'No pull requests to show right now.' : onlyTeam ? 'Nothing from your circles needs you right now.' : openOnly ? 'Nothing open needs you right now.' : 'Nothing needs you right now.'}</div>
      )}

      {summary !== '' && (
        <div className="atn-handled">
          <span>{summary} this session</span>
          <button className="atn-handled__restore" type="button" onClick={() => restore(session)}>
            Bring back
          </button>
        </div>
      )}

      {snooze && <SnoozeMenu anchor={snooze.anchor} onClose={() => setSnooze(null)} onPick={option => applySnooze(snooze.item, option.id, option.label)} />}

      {toast && (
        <Toast
          key={toast.id}
          className={variant === 'workspace' ? 'is-raised' : ''}
          text={toast.text}
          onUndo={toast.key === '' ? undefined : () => restore([toast.key])}
          onDismiss={() => setToast(null)}
        />
      )}
    </section>
  );
}

/** The snooze choices, under the button that asked for them; a click anywhere else closes them. */
export function SnoozeMenu({ anchor, onPick, onClose }: { anchor: DOMRect; onPick: (option: typeof SNOOZE_OPTIONS[number]) => void; onClose: () => void }) {
  return createPortal(
    <div className="atn-menu-catcher" onClick={onClose}>
      <div className="atn-menu" style={anchorPanel(anchor, MENU_WIDTH, MENU_HEIGHT, anchor.right - MENU_WIDTH)} onClick={event => event.stopPropagation()}>
        <div className="atn-menu__label">SNOOZE UNTIL</div>
        {SNOOZE_OPTIONS.map(option => (
          <button className="atn-menu__item" key={option.id} type="button" onClick={() => onPick(option)}>
            <span className="atn-menu__icon">{option.icon}</span>
            <span className="atn-menu__text">{option.label}</span>
            <span className="atn-menu__hint">{snoozeHint(option.id, Date.now())}</span>
          </button>
        ))}
      </div>
    </div>,
    document.body,
  );
}
