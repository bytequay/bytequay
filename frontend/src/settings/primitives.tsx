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
import { type ReactNode, useEffect, useId, useRef, useState } from 'react';
import { Check, ChevronDown, Plus, X } from 'lucide-react';

/** One concern. Rows inside are separated by hairlines; a footer holds the card's one add action. */
export function SettingsCard({ children, footer }: { children: ReactNode; footer?: ReactNode }) {
  return (
    <section className="setw-card">
      {children}
      {footer !== undefined && <div className="setw-card-foot">{footer}</div>}
    </section>
  );
}

/** One setting: a title, at most one short subtitle, and right-aligned controls. */
export function SettingsRow({ title, badge, subtitle, children }: {
  title: ReactNode;
  badge?: ReactNode;
  subtitle?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <div className="setw-row">
      <div className="setw-row-main">
        <div className="setw-row-title">
          {title}
          {badge}
        </div>
        {subtitle !== undefined && subtitle !== null && <div className="setw-row-sub">{subtitle}</div>}
      </div>
      {children !== undefined && <div className="setw-row-controls">{children}</div>}
    </div>
  );
}

export function Badge({ children, tone }: { children: ReactNode; tone?: 'warn' }) {
  return <span className={`setw-badge${tone === 'warn' ? ' is-warn' : ''}`}>{children}</span>;
}

export function StatusDot({ tone }: { tone: 'ok' | 'warn' | 'bad' | 'idle' }) {
  return <span className={`setw-dot${tone === 'idle' ? '' : ` is-${tone}`}`} />;
}

/** The readiness badge's tone as a dot: the prototype shows a dot, the model layer speaks in badges. */
export function dotTone(badgeTone: string): 'ok' | 'warn' | 'bad' | 'idle' {
  if (badgeTone === 'is-ok') return 'ok';
  if (badgeTone === 'is-warn') return 'warn';
  if (badgeTone === 'is-danger') return 'bad';
  return 'idle';
}

export function Toggle({ on, label, disabled, onChange }: {
  on: boolean;
  label: string;
  disabled?: boolean;
  onChange: (next: boolean) => void;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      aria-label={label}
      disabled={disabled}
      className={`setw-toggle${on ? ' is-on' : ''}`}
      onClick={() => onChange(!on)}
    >
      <span className="setw-toggle-knob" />
    </button>
  );
}

export type SegmentedOption<T extends string> = { value: T; label: string; disabled?: boolean };

export function Segmented<T extends string>({ value, options, label, onChange }: {
  value: T;
  options: SegmentedOption<T>[];
  label: string;
  onChange: (next: T) => void;
}) {
  return (
    <div className="setw-seg" role="radiogroup" aria-label={label}>
      {options.map(option => (
        <button
          type="button"
          key={option.value}
          role="radio"
          aria-checked={option.value === value}
          disabled={option.disabled}
          className={option.value === value ? 'is-current' : undefined}
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

export type PillOption = {
  value: string;
  label: string;
  /** Right-aligned muted text, used for the provider a model comes from. */
  note?: string;
  /** Non-selectable, with the reason surfaced as its title. */
  disabled?: boolean;
  disabledReason?: string;
  /** Starts a new labelled block in the menu. */
  group?: string;
};

/**
 * Button plus popover list with tick marks. Closing on any outside mousedown is
 * how every other menu in this app behaves, so the same listener is used here.
 */
export function SelectPill({ value, label, options, ariaLabel, quiet, disabled, wide, align, footer, onSelect }: {
  value: string;
  label: string;
  options: PillOption[];
  ariaLabel: string;
  quiet?: boolean;
  disabled?: boolean;
  wide?: boolean;
  align?: 'left';
  footer?: ReactNode;
  onSelect: (value: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const wrap = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const close = (event: MouseEvent) => {
      if (!wrap.current?.contains(event.target as Node)) setOpen(false);
    };
    window.addEventListener('mousedown', close);
    return () => window.removeEventListener('mousedown', close);
  }, [open]);

  let lastGroup: string | undefined;
  return (
    <div className="setw-pill-wrap" ref={wrap}>
      <button
        type="button"
        className={`setw-pill${quiet === true ? ' is-quiet' : ''}`}
        aria-label={ariaLabel}
        aria-expanded={open}
        disabled={disabled}
        onClick={() => setOpen(current => !current)}
      >
        <span className="setw-pill-label">{label}</span>
        <ChevronDown size={11} strokeWidth={2.6} color="#8e8e93" />
      </button>
      {open && (
        <div className={`setw-menu${wide === true ? ' is-wide' : ''}${align === 'left' ? ' is-left' : ''}`} role="listbox">
          {options.map(option => {
            const heading = option.group !== undefined && option.group !== lastGroup
              ? <div className="setw-menu-group" key={`${option.group}-heading`}>{option.group}</div>
              : null;
            lastGroup = option.group;
            return (
              <div key={option.value}>
                {heading}
                <button
                  type="button"
                  role="option"
                  aria-selected={option.value === value}
                  className="setw-menu-item"
                  disabled={option.disabled}
                  title={option.disabledReason}
                  onClick={() => { onSelect(option.value); setOpen(false); }}
                >
                  <span className="setw-menu-label">{option.label}</span>
                  {option.note !== undefined && <span className="setw-menu-note">{option.note}</span>}
                  <span className="setw-menu-tick">{option.value === value ? <Check size={12} /> : null}</span>
                </button>
              </div>
            );
          })}
          {footer !== undefined && <>
            <div className="setw-menu-divider" />
            <div onClick={() => setOpen(false)} role="presentation">{footer}</div>
          </>}
        </div>
      )}
    </div>
  );
}

/** The card-footer add action shared by Models, Skills and Remotes. */
export function AddAction({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick}>
      <Plus size={14} strokeWidth={2.2} />
      <span>{label}</span>
    </button>
  );
}

/**
 * Modal over the settings window. Escape cancels; the primary action is
 * disabled until the form is answerable, which is what the prototype shows.
 */
export function Dialog({ title, children, confirmLabel, confirmDisabled, destructive, onConfirm, onCancel }: {
  title: string;
  children: ReactNode;
  confirmLabel: string;
  confirmDisabled?: boolean;
  /** A destructive action for the thing being edited, kept left of Cancel and away from the primary. */
  destructive?: ReactNode;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const titleId = useId();

  useEffect(() => {
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') onCancel(); };
    window.addEventListener('keydown', escape);
    return () => window.removeEventListener('keydown', escape);
  }, [onCancel]);

  return (
    <div className="setw-dialog-backdrop">
      <div className="setw-dialog" role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <div className="setw-dialog-head">
          <span id={titleId}>{title}</span>
          <button type="button" className="setw-dialog-close" aria-label="Close" onClick={onCancel}>
            <X size={15} strokeWidth={2.2} color="#6e6e73" />
          </button>
        </div>
        <div className="setw-dialog-body">{children}</div>
        <div className="setw-dialog-foot">
          {destructive !== undefined && <div className="setw-dialog-foot-start">{destructive}</div>}
          <button type="button" className="setw-btn" onClick={onCancel}>Cancel</button>
          <button type="button" className="setw-btn-primary" disabled={confirmDisabled} onClick={onConfirm}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
