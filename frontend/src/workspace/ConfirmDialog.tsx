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
import { useEffect } from 'react';
import { createPortal } from 'react-dom';
import { WS_DIALOG_OVERLAY, WS_DIALOG_PANEL, dialogStyles } from './dialogStyles';

type Props = {
  title: string;
  /** Body copy — newlines split into paragraphs so the caller can use
   *  the same string they would have passed to {@code window.confirm}. */
  body: string;
  confirmLabel: string;
  /** Defaults to "Cancel". */
  cancelLabel?: string;
  /** Style the confirm button as destructive (red) instead of the
   *  workspace primary purple. Use for delete / drop / discard flows. */
  destructive?: boolean;
  /** Disables the confirm button — used while a request is in flight
   *  so a double-click can't fire two deletes. */
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
};

/** In-app replacement for {@code window.confirm} that matches the
 *  workspace dialog visual language (glass card on a soft backdrop,
 *  same buttons as the new-thread / new-workspace flows). Rendered
 *  into a portal on {@code document.body} so it escapes any
 *  transformed ancestor and overlays the whole window. */
export function ConfirmDialog({
  title, body, confirmLabel, cancelLabel = 'Cancel',
  destructive = false, busy = false, onConfirm, onCancel,
}: Props) {
  // Escape closes the dialog; Enter confirms. Matches the muscle
  // memory the native confirm() built up before this replaced it.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        onCancel();
      }
      else if (e.key === 'Enter' && !busy) {
        e.preventDefault();
        onConfirm();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [busy, onCancel, onConfirm]);

  const paragraphs = body.split('\n\n');

  return createPortal(
    <div
      style={WS_DIALOG_OVERLAY}
      onClick={onCancel}
      role="presentation"
    >
      <div
        style={{ ...WS_DIALOG_PANEL, width: 460 }}
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirm-dialog-title"
        onClick={e => e.stopPropagation()}
      >
        <header style={dialogStyles.header}>
          <h2 id="confirm-dialog-title" style={dialogStyles.title}>{title}</h2>
        </header>
        <div style={bodyStyle}>
          {paragraphs.map((p, i) => (
            <p key={i} style={paragraphStyle}>{p}</p>
          ))}
        </div>
        <div style={dialogStyles.footer}>
          <span style={dialogStyles.footerNote} />
          <div style={dialogStyles.footerButtons}>
            <button
              type="button"
              className="ui-hand"
              style={dialogStyles.secondaryBtn}
              onClick={onCancel}
            >
              {cancelLabel}
            </button>
            <button
              type="button"
              className="ui-hand"
              style={destructive
                ? (busy ? destructiveBtnDisabledStyle : destructiveBtnStyle)
                : (busy ? dialogStyles.primaryBtnDisabled : dialogStyles.primaryBtn)}
              onClick={onConfirm}
              disabled={busy}
              autoFocus
            >
              {confirmLabel}
            </button>
          </div>
        </div>
      </div>
    </div>,
    document.body,
  );
}

const bodyStyle: React.CSSProperties = {
  fontSize: 13,
  lineHeight: 1.55,
  color: 'var(--ws-text-2)',
};

const paragraphStyle: React.CSSProperties = {
  margin: '0 0 10px',
};

const destructiveBtnStyle: React.CSSProperties = {
  padding: '7px 13px',
  fontSize: 12,
  fontWeight: 600,
  border: 'none',
  borderRadius: 8,
  background: '#dc2626',
  color: '#fff',
  cursor: 'pointer',
  transition: 'background var(--ws-fast)',
};

const destructiveBtnDisabledStyle: React.CSSProperties = {
  ...destructiveBtnStyle,
  background: 'rgba(220, 38, 38, 0.5)',
  cursor: 'not-allowed',
};
