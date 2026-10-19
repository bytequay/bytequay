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
import { LoaderCircle } from 'lucide-react';
import './cards.css';

export type CardTone = 'blue' | 'amber' | 'red' | 'green' | 'neutral' | 'orange' | 'purple';

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


/** A small action on a card; it never opens the card it sits on. */
export function IconButton({ label, title = label, tone, pressed, disabled, onClick, children }: {
  label: string;
  title?: string;
  /** Ack turns green and danger red under the pointer; active is an on toggle, and selected the chosen one of a set. */
  tone?: 'ack' | 'danger' | 'active' | 'selected';
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
  tone?: CardTone;
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
