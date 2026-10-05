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

import { useCallback, useContext, useEffect, useLayoutEffect, useRef, useState, type CSSProperties, type ReactNode, type RefObject } from 'react';
import { LayerCovered, useZoom, type ZoomControls } from '../Zoom';

type Bounds = { left: number; top: number; width: number; height: number };
const MARGIN = 8;

function fit(bounds: Bounds, parent: HTMLElement): Bounds {
  const width = Math.min(bounds.width, Math.max(0, parent.clientWidth - MARGIN * 2));
  const height = Math.min(bounds.height, Math.max(0, parent.clientHeight - MARGIN * 2));
  return { width, height,
    left: Math.max(MARGIN, Math.min(bounds.left, parent.clientWidth - width - MARGIN)),
    top: Math.max(MARGIN, Math.min(bounds.top, parent.clientHeight - height - MARGIN)) };
}

/** Keep the mounted panel's drafts while the pointer closes its floating window. */
export function GoalWindow({ anchor, open, onClose, children }: {
  anchor: RefObject<HTMLElement | null>;
  open: boolean;
  onClose: () => void;
  children: (controls: ZoomControls) => ReactNode;
}): React.JSX.Element {
  const ref = useRef<HTMLDivElement>(null);
  const covered = useContext(LayerCovered);
  const close = useCallback(() => {
    if (ref.current?.contains(document.activeElement)) anchor.current?.focus({ preventScroll: true });
    onClose();
  }, [anchor, onClose]);
  const zoom = useZoom(close, open);
  const { close: closeWindow } = zoom;
  const [bounds, setBounds] = useState<Bounds>({ left: MARGIN, top: MARGIN, width: 520, height: 620 });
  const size = useRef({ width: 520, height: 620 });
  const drag = useRef<{ x: number; y: number; from: Bounds } | null>(null);
  const [moving, setMoving] = useState(false);
  const [origin, setOrigin] = useState<CSSProperties>({});

  useLayoutEffect(() => {
    const element = ref.current;
    const parent = element?.parentElement;
    const pill = anchor.current;
    if (!open || element == null || parent == null || pill === null) return;
    const area = parent.getBoundingClientRect();
    const source = pill.getBoundingClientRect();
    const next = fit({ ...size.current, left: source.left - area.left, top: source.bottom - area.top - size.current.height }, parent);
    setBounds(next);
    setOrigin({
      '--goal-zoom-x': `${source.left - area.left - next.left}px`,
      '--goal-zoom-y': `${source.top - area.top - next.top}px`,
      '--goal-zoom-scale-x': source.width / next.width,
      '--goal-zoom-scale-y': source.height / next.height,
    } as CSSProperties);
    (element.querySelector<HTMLElement>('[data-goal-focus]') ?? element.querySelector<HTMLElement>('button'))?.focus({ preventScroll: true });
  }, [anchor, open]);

  useLayoutEffect(() => {
    const element = ref.current;
    const parent = element?.parentElement;
    if (!open || element == null || parent == null) return;
    const observer = new ResizeObserver(() => {
      if (zoom.full) return;
      // Native CSS resizing changes the element's inline size; retain it for reopening.
      size.current = { width: element.offsetWidth, height: element.offsetHeight };
      setBounds(previous => {
        const next = fit({ ...previous, ...size.current }, parent);
        return next.left === previous.left && next.top === previous.top && next.width === previous.width && next.height === previous.height ? previous : next;
      });
    });
    observer.observe(element);
    observer.observe(parent);
    return () => observer.disconnect();
  }, [open, zoom.full]);

  useEffect(() => {
    if (!open || covered) return;
    let timer: number | undefined;
    const cancelClose = (): void => { window.clearTimeout(timer); timer = undefined; };
    const leave = (event: PointerEvent): void => {
      // A drag, native resize, text selection or confirmation must finish before auto-close.
      if (event.buttons !== 0 || drag.current !== null || document.querySelector('dialog[open]') !== null) {
        cancelClose();
        return;
      }
      if (event.type === 'pointerout' && event.relatedTarget !== null) return;
      const inside = [ref.current, anchor.current].some(element => {
        if (element === null) return false;
        // Use layout bounds so the opening animation cannot strand or dismiss the pointer.
        const parent = element.offsetParent?.getBoundingClientRect();
        if (parent === undefined) return false;
        const left = parent.left + element.offsetLeft;
        const top = parent.top + element.offsetTop;
        return event.clientX >= left - 6 && event.clientX <= left + element.offsetWidth + 6
          && event.clientY >= top - 6 && event.clientY <= top + element.offsetHeight + 6;
      });
      const inPreview = event.target instanceof Element && event.target.closest('.ref-card') !== null;
      if ((inside || inPreview) && event.type !== 'pointerout') cancelClose();
      else if (timer === undefined) {
        timer = window.setTimeout(() => {
          timer = undefined;
          if (drag.current === null && document.querySelector('dialog[open]') === null) closeWindow();
        }, 1_500);
      }
    };
    document.addEventListener('pointerdown', cancelClose);
    document.addEventListener('pointermove', leave);
    document.addEventListener('pointerover', leave);
    document.addEventListener('pointerout', leave);
    return () => {
      cancelClose();
      document.removeEventListener('pointerdown', cancelClose);
      document.removeEventListener('pointermove', leave);
      document.removeEventListener('pointerover', leave);
      document.removeEventListener('pointerout', leave);
    };
  }, [anchor, covered, open, closeWindow]);

  const finishDrag = (): void => { drag.current = null; setMoving(false); };
  return <div ref={ref} hidden={!open} inert={!open || covered}
    className={`goal-window${zoom.full ? ' is-expanded' : ''}${moving ? ' is-moving' : ''}`}
    style={{ ...origin, ...(zoom.full ? {} : { ...bounds, maxWidth: `calc(100% - ${bounds.left + MARGIN}px)`, maxHeight: `calc(100% - ${bounds.top + MARGIN}px)` }) }}
    onPointerDown={event => {
      if (zoom.full || event.button !== 0 || !(event.target instanceof Element)) return;
      if (event.target.closest('.goal-panel__head') === null || event.target.closest('a, button, input, textarea, select, .goal-menu, dialog') !== null) return;
      event.preventDefault();
      event.currentTarget.setPointerCapture(event.pointerId);
      drag.current = { x: event.clientX, y: event.clientY, from: bounds };
      setMoving(true);
    }}
    onPointerMove={event => {
      const parent = event.currentTarget.parentElement;
      if (drag.current === null || parent === null) return;
      const { x, y, from } = drag.current;
      setBounds(fit({ ...from, left: from.left + event.clientX - x, top: from.top + event.clientY - y }, parent));
    }}
    onPointerUp={finishDrag} onPointerCancel={finishDrag} onLostPointerCapture={finishDrag}>
    {children(zoom)}
  </div>;
}
