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
import { useCallback, useEffect, useRef, useState, type Dispatch, type SetStateAction, type MouseEvent as ReactMouseEvent } from 'react';

type Pane<T extends number | null> = {
  width: T;
  setWidth: Dispatch<SetStateAction<T>>;
  resizing: boolean;
  startResize: (event: ReactMouseEvent, renderedWidth?: number) => void;
};

export function useResizablePane(initialWidth: number, minimumWidth: number, maximumWidth: number | (() => number), resizeEdge?: 'start' | 'end', axis?: 'x' | 'y'): Pane<number>;
export function useResizablePane(initialWidth: number | null, minimumWidth: number, maximumWidth: number | (() => number), resizeEdge?: 'start' | 'end', axis?: 'x' | 'y'): Pane<number | null>;
export function useResizablePane(
  initialWidth: number | null,
  minimumWidth: number,
  maximumWidth: number | (() => number),
  resizeEdge: 'start' | 'end' = 'end',
  axis: 'x' | 'y' = 'x',
): Pane<number> | Pane<number | null> {
  const [width, setWidth] = useState(initialWidth);
  const [resizing, setResizing] = useState(false);
  const stopResize = useRef<(() => void) | null>(null);

  useEffect(() => () => { stopResize.current?.(); }, []);

  const startResize = useCallback((event: ReactMouseEvent, renderedWidth = width ?? minimumWidth) => {
    if (event.button !== 0) return;
    event.preventDefault();
    stopResize.current?.();
    const startPosition = axis === 'x' ? event.clientX : event.clientY;
    const startWidth = renderedWidth;
    const { cursor, userSelect } = document.body.style;
    const move = (next: globalThis.MouseEvent) => {
      const position = axis === 'x' ? next.clientX : next.clientY;
      const distance = resizeEdge === 'start' ? startPosition - position : position - startPosition;
      setWidth(Math.max(minimumWidth, Math.min(typeof maximumWidth === 'function' ? maximumWidth() : maximumWidth, Math.round(startWidth + distance))));
    };
    const stop = () => {
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', stop);
      window.removeEventListener('blur', stop);
      document.body.style.cursor = cursor;
      document.body.style.userSelect = userSelect;
      stopResize.current = null;
      setResizing(false);
    };
    stopResize.current = stop;
    document.body.style.cursor = axis === 'x' ? 'col-resize' : 'row-resize';
    document.body.style.userSelect = 'none';
    setResizing(true);
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', stop);
    window.addEventListener('blur', stop);
  }, [maximumWidth, minimumWidth, resizeEdge, width, axis]);

  return { width, setWidth, resizing, startResize };
}
