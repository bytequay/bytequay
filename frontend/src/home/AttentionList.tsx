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
import AttentionCard from './AttentionCard';
import { usePinnedItems } from './pinned';
import { useSnoozedAttention } from './useSnoozedAttention';
import { useTeams } from '../teams/useTeams';
import { teamAttentionMatch } from '../teams/priority';
import {
  ATTENTION_SOURCES,
  attentionCards,
  attentionSource,
  handledEntry,
  handledSummary,
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
  scope?: { workspaceId: string; repository: string };
  /** The pinned goal each card can join or leave; absent where no goal can hold the card. */
  goal?: (item: AttentionItem) => GoalToggle | null;
  /** Whether Jira is connected; without it there is no Jira tab unless a Jira card is listed anyway. */
  jiraConnected?: boolean;
  /** Teams opens this same list scoped to its private circle or person. */
  teamScope?: { circleId?: string; memberId?: number };
  /** The Teams backlog can show all cards; both Homes retain the default four. */
  cardLimit?: number;
};

/** A card's place in a pinned goal, and the change that flips it. */
export type GoalToggle = { inGoal: boolean; toggle: () => Promise<unknown> };

type Anchored = { item: AttentionItem; anchor: DOMRect };
type ToastState = { id: number; text: string; key: string };

export default function AttentionList({
  candidates, handled, onHandledChange, onOpen, onStartIssue, viewerLogin = null, variant = 'home', scope, goal, jiraConnected = false, teamScope, cardLimit,
}: Props) {
  const [filter, setFilter] = useState<AttentionFilter>('all');
  const [onlyTeam, setOnlyTeam] = useState(false);
  const teams = useTeams(viewerLogin);
  const circles = (teams.directory?.circles ?? []).filter(circle => teamScope?.circleId === undefined || circle.id === teamScope.circleId)
    .map(circle => teamScope?.memberId === undefined ? circle : { ...circle, members: circle.members.filter(member => member.githubId === teamScope.memberId) });
  const teamOnly = teamScope !== undefined || onlyTeam;
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
  const live = visibleAttention(reminders, handled, Math.max(now, Date.now()))
    .filter(item => !pinned.some(pin => pin.key === item.key));
  const filters = FILTERS.filter(id => id !== 'jira' || jiraConnected || live.some(item => attentionSource(item) === 'jira'));
  const { cards, counts } = attentionCards(live, filters.includes(filter) ? filter : 'all', cardLimit,
    { circles, viewerLogin, onlyTeam: teamOnly });
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

  const count = cards.length < counts.all ? `${cards.length} of ${counts.all}` : `${counts.all}`;
  const startable = (item: AttentionItem) => item.type === 'issue' && item.canStartDevelopment !== false && onStartIssue !== undefined && item.issueState !== 'closed'
    && (item.jira === undefined || item.jira.status.category !== 'done' && item.jira.task === null);

  return (
    <section className={`atn-section is-${variant}`} aria-label="Needs attention">
      <div className="atn-head">
        <span className="atn-head__label">Needs attention</span>
        <span className="atn-count">{count}</span>
        {teamScope === undefined && (circles.length > 0 || onlyTeam) && (
          <button type="button" className={`atn-filter${onlyTeam ? ' is-active' : ''}`} aria-pressed={onlyTeam} onClick={() => setOnlyTeam(value => !value)}>Team only</button>
        )}
        <div className="atn-filters" role="tablist" aria-label="Filter what needs attention">
          {filters.map(id => (
            <button key={id} type="button" role="tab" aria-selected={filter === id}
              className={`atn-filter${filter === id ? ' is-active' : ''}`} onClick={() => setFilter(id)}>
              {id === 'all' ? 'All' : ATTENTION_SOURCES[id].filter} <span className="atn-filter__count">{counts[id]}</span>
            </button>
          ))}
        </div>
      </div>

      {cards.length > 0 ? (
        <div className="atn-grid">
          {cards.map(item => {
            const membership = item.issueState === 'closed' ? null : goal?.(item) ?? null;
            return (
              <AttentionCard
                key={item.key}
                item={item}
                teamMatch={teamAttentionMatch(item, circles, viewerLogin)}
                viewerLogin={viewerLogin}
                showRepo={variant === 'home'}
                onOpen={() => onOpen(item)}
                onAcknowledge={() => acknowledge(item)}
                onSnooze={button => setSnooze({ item, anchor: button.getBoundingClientRect() })}
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
        <div className="atn-clear">{teamOnly ? 'No team items need you right now.' : 'Nothing needs you right now.'}</div>
      )}

      {summary !== '' && (
        <div className="atn-handled">
          <span>{summary} this session</span>
          <button className="atn-handled__restore" type="button" onClick={() => restore(session)}>
            Bring back
          </button>
        </div>
      )}

      {snooze && createPortal(
        <div className="atn-menu-catcher" onClick={() => setSnooze(null)}>
          <div
            className="atn-menu"
            style={anchorPanel(
              snooze.anchor,
              MENU_WIDTH,
              MENU_HEIGHT,
              snooze.anchor.right - MENU_WIDTH,
            )}
            onClick={event => event.stopPropagation()}
          >
            <div className="atn-menu__label">SNOOZE UNTIL</div>
            {SNOOZE_OPTIONS.map(option => (
              <button
                className="atn-menu__item"
                key={option.id}
                type="button"
                onClick={() => applySnooze(snooze.item, option.id, option.label)}
              >
                <span className="atn-menu__icon">{option.icon}</span>
                <span className="atn-menu__text">{option.label}</span>
                <span className="atn-menu__hint">{snoozeHint(option.id, Date.now())}</span>
              </button>
            ))}
          </div>
        </div>,
        document.body,
      )}

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
