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
import {
  Check,
  ChevronRight,
  CircleAlert,
  CircleDot,
  CircleX,
  Clock3,
  GitCommitHorizontal,
  GitMerge,
  ListChecks,
  type LucideIcon,
} from 'lucide-react';
import Avatar from '../Avatar';
import { relativeTime } from '../relativeTime';
import RepoAvatar from '../RepoAvatar';
import Toast from '../Toast';
import AttentionPreview from './AttentionPreview';
import {
  anchorPanel,
  ATTENTION_KINDS,
  attentionDetail,
  handledSummary,
  SNOOZE_OPTIONS,
  snoozeHint,
  snoozeUntil,
  visibleAttention,
  type AttentionItem,
  type AttentionKind,
  type Handled,
  type HandledEntry,
  type SnoozeId,
} from './attention';

/** Home stays a summary: deeper backlog belongs behind "See all". */
const HOME_MAX_ROWS = 6;
const HOVER_IN_MS = 1500;
const HOVER_OUT_MS = 90;
const MENU_WIDTH = 210;
/** Header plus four options; kept in step with .atn-menu in home.css. */
const MENU_HEIGHT = 160;

/** The tile that names the reason, drawn with the same line icons the sidebar uses. */
const KIND_ICONS: Record<AttentionKind, LucideIcon> = {
  review: ListChecks,
  assigned: CircleDot,
  changes: CircleAlert,
  ci: CircleX,
  commits: GitCommitHorizontal,
  merge: GitMerge,
};

type Props = {
  candidates: AttentionItem[];
  handled: Handled;
  onHandledChange: (handled: Handled) => void;
  onOpen: (item: AttentionItem) => void;
  onSeeAll: () => void;
  /** How many rows show before the rest waits behind "See all". */
  maxRows?: number;
  /** Home spans every watched repository; a workspace shows only its own and disappears when quiet. */
  variant?: 'home' | 'workspace';
};

type Anchored = { item: AttentionItem; anchor: DOMRect };
type ToastState = { id: number; text: string; key: string };

export default function AttentionList({
  candidates, handled, onHandledChange, onOpen, onSeeAll, maxRows = HOME_MAX_ROWS, variant = 'home',
}: Props) {
  const [hover, setHover] = useState<Anchored | null>(null);
  const [snooze, setSnooze] = useState<Anchored | null>(null);
  const [toast, setToast] = useState<ToastState | null>(null);
  const [session, setSession] = useState<string[]>([]);
  const enterTimer = useRef(0);
  const leaveTimer = useRef(0);
  const toastId = useRef(0);

  useEffect(() => () => {
    window.clearTimeout(enterTimer.current);
    window.clearTimeout(leaveTimer.current);
  }, []);

  // A card pinned to a stale rectangle is worse than no card — but scrolling
  // the card's own description must not count as moving away from it.
  useEffect(() => {
    if (hover === null) return;
    const close = (event: Event) => {
      const target = event.target;
      if (target instanceof Element && target.closest('.atn-preview')) return;
      setHover(null);
    };
    window.addEventListener('scroll', close, true);
    window.addEventListener('resize', close);
    return () => {
      window.removeEventListener('scroll', close, true);
      window.removeEventListener('resize', close);
    };
  }, [hover]);

  const live = visibleAttention(candidates, handled, Date.now());
  const visible = live.slice(0, maxRows);
  const sessionEntries = session
    .map(key => handled[key])
    .filter((entry): entry is HandledEntry => entry !== undefined);
  const summary = handledSummary(sessionEntries);

  function closePreview() {
    window.clearTimeout(enterTimer.current);
    window.clearTimeout(leaveTimer.current);
    setHover(null);
  }

  function handle(item: AttentionItem, entry: HandledEntry, text: string) {
    const key = item.key;
    closePreview();
    setSnooze(null);
    onHandledChange({ ...handled, [key]: entry });
    setSession(previous => previous.includes(key) ? previous : [...previous, key]);
    toastId.current += 1;
    setToast({ id: toastId.current, text, key });
  }

  function acknowledge(item: AttentionItem) {
    handle(
      item,
      { kind: 'ack', until: null, seenUpdatedAt: item.updatedAt, label: 'acknowledged' },
      `Acknowledged #${item.number} — cleared from your list`,
    );
  }

  function applySnooze(item: AttentionItem, option: SnoozeId, label: string) {
    handle(
      item,
      {
        kind: 'snooze',
        until: snoozeUntil(option, Date.now()),
        seenUpdatedAt: item.updatedAt,
        label: label.toLowerCase(),
      },
      `Snoozed #${item.number} · ${label.toLowerCase()}`,
    );
  }

  function restore(keys: string[]) {
    const next = { ...handled };
    for (const key of keys) delete next[key];
    onHandledChange(next);
    setSession(previous => previous.filter(key => !keys.includes(key)));
    setToast(null);
  }

  function enterRow(item: AttentionItem, element: HTMLElement) {
    window.clearTimeout(leaveTimer.current);
    window.clearTimeout(enterTimer.current);
    const anchor = element.getBoundingClientRect();
    enterTimer.current = window.setTimeout(() => setHover({ item, anchor }), HOVER_IN_MS);
  }

  function leaveRow() {
    window.clearTimeout(enterTimer.current);
    leaveTimer.current = window.setTimeout(() => setHover(null), HOVER_OUT_MS);
  }

  function openSnoozeMenu(item: AttentionItem, button: HTMLElement) {
    closePreview();
    setSnooze({ item, anchor: button.getBoundingClientRect() });
  }

  // A quiet workspace shows no section at all; Home keeps its place and says so.
  if (variant === 'workspace' && visible.length === 0) return null;
  const count = visible.length < live.length ? `${visible.length} of ${live.length}` : `${live.length}`;

  return (
    <section className={variant === 'workspace' ? 'atn-section is-workspace' : 'home-section'}>
      {variant === 'workspace' ? (
        <div className="atn-head">
          <span className="atn-head__label">Needs attention</span>
          <span className="atn-count">{count}</span>
          <div className="atn-spacer" />
          <button className="atn-head__link" type="button" onClick={onSeeAll}>See all</button>
        </div>
      ) : (
        <div className="home-section__header">
          <span className="home-section__title">Needs attention</span>
          <span className="atn-count">{count}</span>
          <div className="atn-spacer" />
          <button className="home-section__action" type="button" onClick={onSeeAll}>See all</button>
        </div>
      )}

      <div className="atn-list">
        {visible.map(item => {
          const kind = ATTENTION_KINDS[item.kind];
          const Icon = KIND_ICONS[item.kind];
          const [owner, repo] = item.repo.split('/');
          return (
            <div
              className="atn-row"
              key={item.key}
              role="button"
              tabIndex={0}
              onClick={() => onOpen(item)}
              onKeyDown={event => {
                if (event.target !== event.currentTarget) return;
                if (event.key !== 'Enter' && event.key !== ' ') return;
                event.preventDefault();
                onOpen(item);
              }}
              onMouseEnter={event => enterRow(item, event.currentTarget)}
              onMouseLeave={leaveRow}
            >
              <span className="atn-row__icon" style={{ color: kind.color, background: kind.background }} aria-hidden="true">
                <Icon size={16} strokeWidth={1.9} />
              </span>
              <span className="atn-row__body">
                <span className="atn-row__subject">
                  <span className="atn-row__title">{item.title}</span>
                  <span className="atn-row__meta">#{item.number}</span>
                </span>
                <span className="atn-row__detail">
                  <span className="atn-row__kind" style={{ color: kind.color }}>{kind.label}</span>
                  <span className="atn-row__sep" aria-hidden="true">·</span>
                  <span className="atn-row__what">{attentionDetail(item)}</span>
                </span>
              </span>
              {variant === 'home' && <RepoAvatar owner={owner} repo={repo ?? ''} size={18} />}
              <Avatar login={item.author ?? ''} avatarUrl={item.authorAvatarUrl} size={22} />
              <span className="atn-row__age">{relativeTime(item.updatedAt, { suffix: false })}</span>
              <div className="atn-row__actions" role="group" aria-label={`Actions for #${item.number}`}>
                <button
                  className="atn-iconbtn is-ack"
                  type="button"
                  title="Acknowledge — clears it from this list"
                  aria-label={`Acknowledge #${item.number}`}
                  onClick={event => { event.stopPropagation(); acknowledge(item); }}
                >
                  <Check size={15} strokeWidth={2} aria-hidden="true" />
                </button>
                <button
                  className="atn-iconbtn"
                  type="button"
                  title="Snooze"
                  aria-label={`Snooze #${item.number}`}
                  onClick={event => { event.stopPropagation(); openSnoozeMenu(item, event.currentTarget); }}
                >
                  <Clock3 size={15} strokeWidth={1.8} aria-hidden="true" />
                </button>
                <ChevronRight className="atn-row__chev" size={14} aria-hidden="true" />
              </div>
            </div>
          );
        })}

        {visible.length === 0 && (
          <div className="atn-clear">
            <span className="atn-clear__mark">✓</span>
            All caught up — nothing needs you right now.
          </div>
        )}

        {summary !== '' && (
          <div className="atn-handled">
            <span>{summary} this session</span>
            <div className="atn-spacer" />
            <button className="atn-handled__restore" type="button" onClick={() => restore(session)}>
              Bring back
            </button>
          </div>
        )}
      </div>

      {hover && (
        <AttentionPreview
          key={hover.item.key}
          item={hover.item}
          anchor={hover.anchor}
          onHold={() => window.clearTimeout(leaveTimer.current)}
          onRelease={leaveRow}
          onOpen={() => { closePreview(); onOpen(hover.item); }}
          onAck={() => acknowledge(hover.item)}
          onSnooze={button => openSnoozeMenu(hover.item, button)}
        />
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
          text={toast.text}
          onUndo={() => restore([toast.key])}
          onDismiss={() => setToast(null)}
        />
      )}
    </section>
  );
}
