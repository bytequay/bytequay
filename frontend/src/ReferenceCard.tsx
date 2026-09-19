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
import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties, type MouseEvent, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { CircleDot, GitPullRequest } from 'lucide-react';
import Avatar from './Avatar';
import RepoAvatar from './RepoAvatar';
import { ago } from './relativeTime';

const CARD_WIDTH = 500;
const CARD_HEIGHT = { pr: 400, issue: 460 };
/** How far from the bottom right corner the browser's own resize grip takes a press. */
const RESIZE_GRIP = 18;

export type ReferenceCardChip = { label: string; color: string; background: string };

/** The one shape every issue and pull-request hover card renders, whichever surface opened it. */
export type ReferenceCardItem = {
  type: 'pr' | 'issue';
  /** owner/repo */
  repo: string;
  number: number;
  title: string;
  author: string | null;
  authorAvatarUrl?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
  labels?: string[];
  chip?: ReferenceCardChip | null;
  /** Replaces the default "author · opened X · updated Y" line. */
  byline?: string;
  /** Stat cells between the head and the body. */
  facts?: { label: string; value: string }[];
};

const STATE_CHIPS: Record<string, ReferenceCardChip> = {
  open: { label: 'Open', color: '#1a7f37', background: '#dafbe1' },
  draft: { label: 'Draft', color: '#57606a', background: '#eceff2' },
  merged: { label: 'Merged', color: '#8250df', background: '#fbefff' },
  closed: { label: 'Closed', color: '#cf222e', background: '#ffebe9' },
};

/** The chip for a card whose surface has no reason of its own to show, such as a Markdown reference. */
export function stateChip(state: string | null | undefined, draft = false): ReferenceCardChip | null {
  const named = (state ?? '').toLowerCase();
  return STATE_CHIPS[draft && named === 'open' ? 'draft' : named] ?? null;
}

/**
 * A body ready for a card: still Markdown, minus the parts that don't belong in a few clamped
 * lines. HTML comments carry issue-template instructions, and images cannot be read at card scale.
 */
export function previewMarkdown(body: string | null | undefined): string {
  if (!body) return '';
  return body
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
    .replace(/<img\b[^>]*>/gi, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

type PanelAnchor = { top: number; bottom: number; left: number; right: number };

/**
 * Places a floating panel under its anchor, flipping it above and clamping it
 * inside the viewport when it would otherwise be cut off at an edge. Both the
 * hover card and the snooze menu open from rows that can sit anywhere in a
 * scrolled list, so neither can assume there is room below.
 */
export function anchorPanel(
  anchor: PanelAnchor,
  width: number,
  height: number,
  desiredLeft: number,
  viewport: { width: number; height: number } = {
    width: window.innerWidth,
    height: window.innerHeight,
  },
): { top: number; left: number } {
  const below = anchor.bottom + 6;
  const top = below + height > viewport.height - 10
    ? Math.max(10, anchor.top - height - 6)
    : below;
  return {
    top,
    left: Math.max(12, Math.min(desiredLeft, viewport.width - width - 12)),
  };
}

/** A moved card may leave at most half its width past the right edge and 30% of its height past the bottom. */
export function clampCardOrigin(
  left: number,
  top: number,
  card: { width: number; height: number },
  view: { width: number; height: number },
) {
  return {
    left: Math.max(0, Math.min(left, view.width - card.width / 2)),
    top: Math.max(0, Math.min(top, view.height - card.height * 0.7)),
  };
}

export type HoverCard = {
  /** The rectangle the open card is placed against; null while it is closed. */
  anchor: DOMRect | null;
  /** Set once the reader moves or resizes the card, which stops it closing on its own. */
  pinned: boolean;
  show: (element: Element, delay?: number) => void;
  hide: (delay?: number) => void;
  hold: () => void;
  close: () => void;
  pin: () => void;
  /** Focus back on the element the card was opened from. */
  restoreFocus: () => void;
};

/** The open and close timing every hover card shares, so no surface keeps its own pair of timers. */
export function useHoverCard(openDelay = 350, closeDelay = 140): HoverCard {
  const showTimer = useRef(0);
  const hideTimer = useRef(0);
  const pinnedNow = useRef(false);
  const trigger = useRef<Element | null>(null);
  const [anchor, setAnchor] = useState<DOMRect | null>(null);
  const [pinned, setPinned] = useState(false);

  useEffect(() => () => {
    window.clearTimeout(showTimer.current);
    window.clearTimeout(hideTimer.current);
  }, []);

  const hold = useCallback(() => window.clearTimeout(hideTimer.current), []);
  const close = useCallback(() => {
    window.clearTimeout(showTimer.current);
    window.clearTimeout(hideTimer.current);
    pinnedNow.current = false;
    setPinned(false);
    setAnchor(null);
  }, []);
  const pin = useCallback(() => {
    window.clearTimeout(hideTimer.current);
    pinnedNow.current = true;
    setPinned(true);
  }, []);
  const restoreFocus = useCallback(() => {
    if (trigger.current instanceof HTMLElement) trigger.current.focus();
  }, []);
  const show = useCallback((element: Element, delay = openDelay) => {
    window.clearTimeout(hideTimer.current);
    window.clearTimeout(showTimer.current);
    trigger.current = element;
    const rect = element.getBoundingClientRect();
    // A fresh hover opens a fresh card: whatever the reader did to the last one, including pinning it, is spent.
    showTimer.current = window.setTimeout(() => {
      pinnedNow.current = false;
      setPinned(false);
      setAnchor(rect);
    }, delay);
  }, [openDelay]);
  const hide = useCallback((delay = closeDelay) => {
    window.clearTimeout(showTimer.current);
    window.clearTimeout(hideTimer.current);
    // A card the reader moved or resized stays put; the pointer has to leave it to reach what it covered.
    if (pinnedNow.current) return;
    hideTimer.current = window.setTimeout(() => setAnchor(null), delay);
  }, [closeDelay]);

  return useMemo(
    () => ({ anchor, pinned, show, hide, hold, close, pin, restoreFocus }),
    [anchor, close, hide, hold, pin, pinned, restoreFocus, show],
  );
}

function defaultByline(item: ReferenceCardItem): string {
  const opened = ago(item.createdAt);
  const updated = ago(item.updatedAt);
  return [item.author, opened && `opened ${opened}`, updated && `updated ${updated}`]
    .filter(Boolean)
    .join(' · ');
}

/**
 * The hover card for one issue or pull request. The reader can drag it by its head and resize it
 * from the browser's own corner grip; either one pins it until Escape or a press outside.
 */
export default function ReferenceCard({ id, hover, item, body, actions }: {
  id?: string;
  hover: HoverCard;
  item: ReferenceCardItem;
  body: ReactNode;
  actions?: ReactNode;
}) {
  const card = useRef<HTMLDivElement>(null);
  const [moved, setMoved] = useState<CSSProperties | null>(null);
  const [sized, setSized] = useState<CSSProperties | null>(null);
  const { anchor, pinned, close, hide, hold, pin, restoreFocus } = hover;

  // Every fresh hover hands over a new rectangle, which drops whatever the reader did to the last card.
  useEffect(() => {
    setMoved(null);
    setSized(null);
  }, [anchor]);

  useEffect(() => {
    if (anchor === null) return;
    const outside = (event: Event) => !(event.target instanceof Node) || !card.current?.contains(event.target);
    // A card left on a stale rectangle is worse than no card, but a moved card has left its anchor on purpose.
    const scrolled = (event: Event) => { if (!pinned && outside(event)) close(); };
    const resized = () => { if (!pinned) close(); };
    const pressed = (event: PointerEvent) => { if (outside(event)) close(); };
    const escaped = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      // The card consumes the key; a reader inside it gets the trigger back rather than the document.
      event.stopPropagation();
      if (card.current?.contains(document.activeElement)) restoreFocus();
      close();
    };
    window.addEventListener('scroll', scrolled, true);
    window.addEventListener('resize', resized);
    document.addEventListener('keydown', escaped);
    if (pinned) window.addEventListener('pointerdown', pressed);
    return () => {
      window.removeEventListener('scroll', scrolled, true);
      window.removeEventListener('resize', resized);
      document.removeEventListener('keydown', escaped);
      window.removeEventListener('pointerdown', pressed);
    };
  }, [anchor, close, pinned, restoreFocus]);

  if (anchor === null) return null;

  const startDrag = (event: MouseEvent<HTMLElement>) => {
    const rect = card.current?.getBoundingClientRect();
    if (!rect || event.button !== 0) return;
    event.preventDefault();
    const grabX = event.clientX - rect.left;
    const grabY = event.clientY - rect.top;
    const move = (next: globalThis.MouseEvent) => setMoved(clampCardOrigin(
      next.clientX - grabX,
      next.clientY - grabY,
      rect,
      { width: window.innerWidth, height: window.innerHeight },
    ));
    const stop = () => {
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', stop);
      document.body.style.userSelect = '';
    };
    document.body.style.userSelect = 'none';
    pin();
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', stop);
  };

  const startResize = (event: MouseEvent<HTMLElement>) => {
    const box = event.currentTarget.getBoundingClientRect();
    if (box.right - event.clientX > RESIZE_GRIP || box.bottom - event.clientY > RESIZE_GRIP) return;
    pin();
    // The browser's own grip writes width and height from here, so the height that fitted the placement has to give way.
    setSized({ height: box.height, maxHeight: 'none' });
  };

  const [owner, repo] = item.repo.split('/');
  const KindIcon = item.type === 'pr' ? GitPullRequest : CircleDot;
  const byline = item.byline ?? defaultByline(item);
  const style: CSSProperties = {
    maxHeight: Math.min(CARD_HEIGHT[item.type], window.innerHeight - 20),
    ...anchorPanel(anchor, CARD_WIDTH, CARD_HEIGHT[item.type], anchor.left + 40),
    ...moved,
    ...sized,
  };

  return createPortal(
    <div
      ref={card}
      id={id}
      className="ref-card"
      style={style}
      role="dialog"
      aria-label={`${item.type === 'pr' ? 'Pull request' : 'Issue'} preview`}
      onMouseDown={startResize}
      onMouseEnter={hold}
      onMouseLeave={() => hide()}
    >
      <div className="ref-card__head" onMouseDown={startDrag}>
        <div className="ref-card__ref">
          <KindIcon
            className="ref-card__kind"
            size={15}
            strokeWidth={1.7}
            style={item.chip ? { color: item.chip.color } : undefined}
            aria-hidden="true"
          />
          <RepoAvatar owner={owner} repo={repo ?? ''} size={16} radius={4} />
          <span className="ref-card__meta">{item.repo} #{item.number}</span>
          {item.chip && (
            <span className="ref-card__chip" style={{ color: item.chip.color, background: item.chip.background }}>
              {item.chip.label}
            </span>
          )}
        </div>
        <div className="ref-card__title">{item.title}</div>
        {byline !== '' && (
          <div className="ref-card__byline">
            <Avatar login={item.author ?? ''} avatarUrl={item.authorAvatarUrl} size={19} />
            <span>{byline}</span>
          </div>
        )}
      </div>

      {item.facts && item.facts.length > 0 && (
        <div className="ref-card__grid">
          {item.facts.map(fact => (
            <div className="ref-card__cell" key={fact.label}>
              <div className="ref-card__label">{fact.label}</div>
              <div className="ref-card__value">{fact.value}</div>
            </div>
          ))}
        </div>
      )}

      <div className="ref-card__foot">
        <div className="ref-card__body">{body}</div>
        <div className="ref-card__actions" role="group" aria-label={`Actions for #${item.number}`}>
          {(item.labels ?? []).slice(0, 3).map(label => (
            <span className="ref-card__tag" key={label}>{label}</span>
          ))}
          <div className="ref-card__spacer" />
          {actions}
        </div>
      </div>
    </div>,
    document.body,
  );
}
