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
import type { CSSProperties, MouseEvent, ReactNode } from 'react';
import { CircleDot, GitPullRequest, LoaderCircle, LockKeyhole, SquareCheck } from 'lucide-react';
import JiraIcon from '../jira/JiraIcon';
import { relativeTime } from '../relativeTime';
import type { AttentionSource, AttentionTone } from '../home/attention';
import '../css/cards.css';

/*
 * The one base every card and card row draws with: Needs attention, Circles, the Tasks page, a workspace's
 * open tasks and a goal's items. A surface keeps only its own layout; the box, its keyboard behaviour, its
 * hover actions, pills and badges come from here.
 */

type ClickableProps = {
  className?: string;
  /** What a screen reader calls the whole card or row. */
  label?: string;
  title?: string;
  onOpen: () => void;
  children: ReactNode;
};

/** A card or row that opens on a click, Enter or Space, leaving keys meant for a control inside it alone. */
export function Clickable({ className, label, title, onOpen, children }: ClickableProps): React.JSX.Element {
  return (
    <div className={className} role="button" tabIndex={0} aria-label={label} title={title} onClick={onOpen}
      onKeyDown={(event) => {
        if (event.target !== event.currentTarget || (event.key !== 'Enter' && event.key !== ' ')) return;
        event.preventDefault();
        onOpen();
      }}>
      {children}
    </div>
  );
}

/** A card: the box every list of things to open draws. Compact ones are the small cards on Home and the goal board. */
export function Card({ compact = false, selected = false, className = '', ...props }: ClickableProps & { compact?: boolean; selected?: boolean }): React.JSX.Element {
  return <Clickable {...props} className={`card${compact ? ' is-compact' : ''}${selected ? ' is-selected' : ''} ${className}`.trim()} />;
}

/** When a card's item last moved. A card's actions take its place while the pointer or focus is inside. */
export function CardAge({ at, now, title }: { at: string; now?: number; title?: string }): React.JSX.Element {
  return <time className="card-age" dateTime={at} title={title ?? new Date(at).toLocaleString()}>{relativeTime(at, { suffix: false, now })}</time>;
}

/**
 * A row in a box of rows, the way Needs attention and Circles list items: the source's icon, the title
 * and what sits beside it, a line of who and where, then whatever the surface adds below; its actions
 * take the age's place while the pointer or focus is inside.
 */
export function ItemRow({ className = '', label, onOpen, icon, title, marks, meta, children, actions, age }: {
  className?: string;
  label: string;
  onOpen: () => void;
  icon: ReactNode;
  title: string;
  /** Beside the title: its key, and badges such as where the item stands. */
  marks?: ReactNode;
  meta: ReactNode;
  children?: ReactNode;
  actions?: ReactNode;
  age: ReactNode;
}): React.JSX.Element {
  return (
    <Clickable className={`card-row item-row ${className}`.trim()} label={label} onOpen={onOpen}>
      <span className="item-row__icon">{icon}</span>
      <div className="item-row__body">
        <div className="item-row__title"><span title={title}>{title}</span>{marks}</div>
        <div className="item-row__meta">{meta}</div>
        {children}
      </div>
      {actions}
      {age}
    </Clickable>
  );
}

/** The actions a card or row shows while the pointer or focus is inside it, in place of an age that follows them. */
export function CardActions({ label, className = '', children }: { label?: string; className?: string; children: ReactNode }): React.JSX.Element {
  return <span className={`card-actions ${className}`.trim()} role="group" aria-label={label}>{children}</span>;
}

/** A small action on a card; it never opens the card it sits on. */
export function IconButton({ label, title = label, tone, pressed, disabled, onClick, children }: {
  label: string;
  title?: string;
  /** Ack turns green and danger red under the pointer; active is an on toggle. */
  tone?: 'ack' | 'danger' | 'active';
  pressed?: boolean;
  disabled?: boolean;
  onClick: (event: MouseEvent<HTMLButtonElement>) => void;
  children: ReactNode;
}): React.JSX.Element {
  return (
    <button type="button" className={`card-iconbtn${tone === undefined ? '' : ` is-${tone}`}`} title={title} aria-label={label}
      aria-pressed={pressed} disabled={disabled} onClick={(event) => { event.stopPropagation(); onClick(event); }}>
      {children}
    </button>
  );
}

/** A short label in a tone, or a plain outline for where an item stands; a running state spins. */
export function Pill({ tone = 'neutral', outline = false, spinning = false, className = '', style, title, children }: {
  tone?: AttentionTone;
  outline?: boolean;
  spinning?: boolean;
  /** `is-square` and `is-struck` draw a ticket's status as it moved. */
  className?: string;
  style?: CSSProperties;
  title?: string;
  children: ReactNode;
}): React.JSX.Element {
  return (
    <span className={`pill ${outline ? 'is-outline' : `is-${tone}`} ${className}`.trim()} style={style} title={title}>
      {spinning && <LoaderCircle className="agt-spin" aria-hidden="true" />}
      {children}
    </span>
  );
}

/** A goal: a target of two rings. */
export function GoalIcon({ size = 14 }: { size?: number }): React.JSX.Element {
  return (
    <svg className="goal-icon" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" aria-hidden="true">
      <circle cx="12" cy="12" r="9" />
      <circle cx="12" cy="12" r="4.5" />
    </svg>
  );
}

/** The item is in its workspace's pinned goal. */
export function InGoalBadge(): React.JSX.Element {
  return <span className="card-goal" title="In goal"><GoalIcon size={11} />Goal</span>;
}

/** The work is Local: only on this Mac until it is pushed to GitHub or Jira. */
export function LocalBadge({ title = 'Local: private until you push it to GitHub or Jira' }: { title?: string }): React.JSX.Element {
  return <span className="card-local" title={title}><LockKeyhole size={11} aria-hidden="true" />Local</span>;
}

/** The icon each source is drawn with; tinted, it takes the source's own colour instead of the text's. */
export function SourceIcon({ source, size = 14, tinted = false, ended = null }: {
  source: AttentionSource;
  size?: number;
  tinted?: boolean;
  /** Colours a tinted icon for a merged or closed item. */
  ended?: 'merged' | 'closed' | null;
}): React.JSX.Element {
  const Icon = source === 'pr' ? GitPullRequest : source === 'issue' ? CircleDot : SquareCheck;
  const icon = source === 'jira' ? <JiraIcon size={size} /> : <Icon size={size} strokeWidth={1.9} aria-hidden="true" />;
  return tinted ? <span className={`card-src is-${source}${ended === null ? '' : ` is-${ended}`}`}>{icon}</span> : icon;
}

/** A linked item named by its source's icon and its key. */
export function LinkKey({ source, name, title, size = 14 }: { source: AttentionSource; name: string; title?: string; size?: number }): React.JSX.Element {
  return <span className="card-link" title={title}><SourceIcon source={source} size={size} tinted /><span className="card-key">{name}</span></span>;
}
