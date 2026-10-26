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
import { useCallback, useEffect, useRef, useState } from 'react';
import { ConfirmDialog } from '../ConfirmDialog';
import { BreakCompanion } from './BreakCompanion';
import type { FocusBreakBackground } from './contracts';
import { formatFocusTime, type BreakCompanionKind } from './model';
import './focus-breaks.css';

export function FocusBreakScreen({ companion, remainingSeconds, exiting, background = [], onLocked, onDismiss, onExited }: {
  companion: BreakCompanionKind;
  remainingSeconds: number;
  exiting: boolean;
  background?: FocusBreakBackground[];
  onLocked: () => void;
  onDismiss: () => void;
  onExited: () => void;
}) {
  const dismissButton = useRef<HTMLButtonElement>(null);
  const dialog = useRef<HTMLDialogElement>(null);
  const returnFocus = useRef<HTMLElement | null>(null);
  const [arrival, setArrival] = useState({ companion, arrived: false });
  // Reset before the new companion's effects can report a reduced-motion arrival.
  if (arrival.companion !== companion) setArrival({ companion, arrived: false });
  const arrived = arrival.companion === companion && arrival.arrived;
  const [locked, setLocked] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const companionArrived = useCallback(() => setArrival({ companion, arrived: true }), [companion]);
  const requestDismiss = () => { if (exiting) onExited(); else setConfirming(true); };

  useEffect(() => {
    const element = dialog.current;
    if (element) element.open = true;
    return () => {
      element?.close();
      if (returnFocus.current?.isConnected) returnFocus.current.focus();
    };
  }, []);

  useEffect(() => {
    const element = dialog.current;
    if (!element || !arrived || locked || exiting) return;
    const tryLock = () => {
      if (element.matches(':modal')) return;
      const otherModal = [...document.querySelectorAll('dialog:modal, [aria-modal="true"]:not([data-navigation-layer])')]
        .some(other => other !== element && other.checkVisibility());
      if (otherModal) return;
      returnFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
      element.close();
      element.showModal();
      setLocked(true);
      onLocked();
      observer.disconnect();
    };
    // A form opened during the walk keeps its turn until the user finishes it.
    const observer = new MutationObserver(tryLock);
    observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['open', 'aria-modal', 'hidden', 'style', 'class', 'data-navigation-layer'] });
    tryLock();
    return () => observer.disconnect();
  }, [arrived, exiting, locked, onLocked]);

  useEffect(() => { if (locked) dismissButton.current?.focus(); }, [locked]);

  return (
    <dialog ref={dialog} className="focus-break" data-focus-break="" data-stage={locked ? 'locked' : 'entering'} data-focus-break-entrance={locked ? undefined : ''}
      role={locked ? 'dialog' : undefined} aria-modal={locked ? true : undefined} aria-hidden={locked ? undefined : true}
      aria-label="Focus break"
      onCancel={event => { if (!locked) return; event.preventDefault(); event.stopPropagation(); requestDismiss(); }}
      onKeyDown={event => {
        if (!locked) return;
        event.stopPropagation();
        if (event.key === 'Escape') { event.preventDefault(); requestDismiss(); }
      }}>
      {background.map((pane, index) => <img key={index} className="focus-break__background" src={pane.image} alt="" draggable={false}
        style={{ left: pane.x, top: pane.y, width: pane.width, height: pane.height }} />)}
      <div className="focus-break__content">
        <BreakCompanion key={companion} companion={companion} locked={locked} exiting={exiting} onArrived={companionArrived} onExited={onExited} />
      </div>
      <span className="focus-break__timer" role="timer" aria-label="Break time remaining">{formatFocusTime(remainingSeconds)}</span>
      <button ref={dismissButton} type="button" className="bq-button focus-break__dismiss" onClick={requestDismiss}>Dismiss</button>
      {confirming && !exiting && <ConfirmDialog title="End your break?" confirmLabel="End break" cancelLabel="Keep taking a break"
        onCancel={() => setConfirming(false)} onConfirm={() => { setConfirming(false); onDismiss(); }}>
        <p><span className="focus-break__confirmation-time" role="timer">{formatFocusTime(remainingSeconds)}</span> remaining in your break.</p>
        <p>Return to your work now?</p>
      </ConfirmDialog>}
    </dialog>
  );
}
