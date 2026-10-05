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

import { useEffect, useId, useRef, type ReactNode } from 'react';
import { LoaderCircle } from 'lucide-react';
import './css/confirm-dialog.css';

interface ConfirmDialogProps {
  title: string;
  children: ReactNode;
  confirmLabel: string;
  cancelLabel?: string;
  busy?: boolean;
  confirmDisabled?: boolean;
  destructive?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export function ConfirmDialog({ title, children, confirmLabel, cancelLabel = 'Cancel', busy = false, confirmDisabled = false, destructive = false, onConfirm, onCancel }: ConfirmDialogProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const id = useId();

  useEffect(() => {
    const dialog = dialogRef.current;
    const previousFocus = document.activeElement;
    dialog?.showModal();
    cancelRef.current?.focus();
    return () => {
      dialog?.close();
      if (previousFocus instanceof HTMLElement && previousFocus.isConnected) previousFocus.focus();
    };
  }, []);

  return (
    <dialog
      ref={dialogRef}
      className="bq-confirm-dialog"
      aria-labelledby={`${id}-title`}
      aria-describedby={`${id}-body`}
      aria-modal="true"
      aria-busy={busy}
      onCancel={(event) => {
        // React hands a dialog's cancel to its ancestors; a confirm inside another dialog must not close that one too.
        event.preventDefault();
        event.stopPropagation();
        if (!busy) onCancel();
      }}
      onKeyDown={(event) => event.stopPropagation()}
    >
      <h2 id={`${id}-title`} className="bq-confirm-dialog__title">{title}</h2>
      <div id={`${id}-body`} className="bq-confirm-dialog__body">{children}</div>
      <div className="bq-confirm-dialog__actions">
        <button ref={cancelRef} type="button" className="bq-button" disabled={busy} onClick={onCancel}>{cancelLabel}</button>
        <button type="button" className={`bq-button ${destructive ? 'bq-confirm-dialog__confirm is-destructive' : 'bq-button--primary'}`} disabled={busy || confirmDisabled} onClick={onConfirm}>
          {busy && <LoaderCircle size={14} className="bq-confirm-dialog__spinner" aria-hidden="true" />}
          {confirmLabel}
        </button>
      </div>
    </dialog>
  );
}
