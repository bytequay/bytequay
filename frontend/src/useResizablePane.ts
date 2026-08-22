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
import { useCallback, useState, type MouseEvent as ReactMouseEvent } from 'react';

export function useResizablePane(initialWidth: number, minimumWidth: number, maximumWidth: number) {
  const [width, setWidth] = useState(initialWidth);
  const [resizing, setResizing] = useState(false);

  const startResize = useCallback((event: ReactMouseEvent) => {
    event.preventDefault();
    const startX = event.clientX;
    const startWidth = width;
    const move = (next: globalThis.MouseEvent) => {
      setWidth(Math.max(minimumWidth, Math.min(maximumWidth, startWidth + next.clientX - startX)));
    };
    const stop = () => {
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', stop);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
      setResizing(false);
    };
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    setResizing(true);
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', stop);
  }, [maximumWidth, minimumWidth, width]);

  return { width, resizing, startResize };
}
