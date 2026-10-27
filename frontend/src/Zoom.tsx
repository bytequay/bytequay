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
import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { Maximize2, Minimize2, X } from 'lucide-react';
import './css/zoom.css';

/**
 * Whether another layer, the Assistant, or the embedded browser is drawn above this page or layer. Only the top
 * answers Escape, and a covered page keeps its native modals closed: they paint in the browser's
 * top layer, over anything opened after them.
 */
export const LayerCovered = createContext(false);

export type ZoomControls = {
  full: boolean;
  toggle: () => void;
  close: () => void;
};

/**
 * One zoom layer over the page or layer that opened it: a card by default, the whole window when
 * full. Escape leaves the layer from either state instead of unwinding it one step at a time.
 */
export function useZoom(onClose: () => void, open = true): ZoomControls & { cardClass: string } {
  const [full, setFull] = useState(false);
  const covered = useContext(LayerCovered);
  const close = useCallback(() => { setFull(false); onClose(); }, [onClose]);
  useEffect(() => {
    // A layer closed from outside, such as by the route, must not reopen full.
    if (!open) { setFull(false); return; }
    if (covered) return;
    const leave = (event: KeyboardEvent): void => {
      if (event.defaultPrevented || event.key !== 'Escape') return;
      // An editor or a terminal answers Escape itself.
      if (event.target instanceof Element && event.target.closest('.cm-editor, .xterm') !== null) return;
      event.preventDefault();
      close();
    };
    window.addEventListener('keydown', leave);
    return () => window.removeEventListener('keydown', leave);
  }, [close, covered, open]);
  return { full, close, toggle: () => setFull(value => !value), cardClass: `zoom-card${full ? ' is-full' : ''}` };
}

/** The backdrop that dims the page under a card; full screen covers that page itself. */
export function ZoomScrim({ onClose }: { onClose: () => void }) {
  return <div className="zoom-scrim" onMouseDown={onClose} />;
}

/** The layer's own controls, wearing the button chrome of whichever header bar draws them. */
export function ZoomActions({ full, toggle, close, label, className }: ZoomControls & {
  /** What the close button says it closes, such as `task`. */
  label: string;
  className: string;
}) {
  return (
    <>
      <button className={className} type="button"
        title={full ? 'Exit full screen' : 'Enter full screen'}
        aria-label={full ? 'Exit full screen' : 'Enter full screen'}
        aria-pressed={full} onClick={toggle}>
        {full
          ? <Minimize2 size={15} strokeWidth={1.8} aria-hidden="true" />
          : <Maximize2 size={15} strokeWidth={1.8} aria-hidden="true" />}
      </button>
      <button className={className} type="button"
        title="Close (Esc)" aria-label={`Close ${label}`} onClick={close}>
        <X size={16} aria-hidden="true" />
      </button>
    </>
  );
}
