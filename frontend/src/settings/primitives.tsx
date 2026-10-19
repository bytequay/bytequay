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
import { Check, ChevronDown, Plus, X } from 'lucide-react';
import { type ReactNode, useId, useState } from 'react';
import { useModalDialog } from '../useModalDialog';
import { usePopoverDismiss } from '@bytequay/ui/usePopoverDismiss';
import { formatMinuteOfDay, MINUTE_OPTIONS } from './model';

const TIME_OPTIONS = MINUTE_OPTIONS.map(minute => ({ value: String(minute), label: formatMinuteOfDay(minute) }));

export { SettingsCard, SettingsRow, Badge, StatusDot, dotTone, Toggle } from '@bytequay/ui/settings';

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

  const wrap = usePopoverDismiss(() => setOpen(false), false, open);

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
        <ChevronDown size={11} strokeWidth={2.6} color="var(--text-3)" />
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

export function TimePill({ minute, ariaLabel, disabled, onSelect }: {
  minute: number;
  ariaLabel: string;
  disabled?: boolean;
  onSelect: (minute: number) => void;
}) {
  return <SelectPill quiet ariaLabel={ariaLabel} value={String(minute)} label={formatMinuteOfDay(minute)} disabled={disabled}
    options={TIME_OPTIONS} onSelect={value => onSelect(Number(value))} />;
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

  const dialog = useModalDialog();

  return (
    <dialog ref={dialog} className="setw-dialog" onCancel={event => { event.preventDefault(); onCancel(); }} role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <div className="setw-dialog-head">
          <span id={titleId}>{title}</span>
          <button type="button" className="setw-dialog-close" aria-label="Close" onClick={onCancel}>
            <X size={15} strokeWidth={2.2} color="var(--text-3)" />
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
    </dialog>
  );
}
