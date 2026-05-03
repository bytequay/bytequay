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
import type { MouseEvent as ReactMouseEvent } from 'react';

type Props = {
  /** Called on every mousemove while dragging, with the viewport clientX. */
  onResize: (clientX: number) => void;
  className?: string;
  ariaLabel?: string;
};

/**
 * A 5px-wide invisible drag target between two panels. Global mousemove
 * listeners are used so the drag keeps going if the user's pointer leaves
 * the handle. We also swap the body cursor + disable user-select for the
 * duration so text doesn't highlight while dragging.
 */
function ResizeHandle({ onResize, className, ariaLabel }: Props) {
  const handleMouseDown = (e: ReactMouseEvent) => {
    e.preventDefault();
    const prevCursor = document.body.style.cursor;
    const prevUserSelect = document.body.style.userSelect;
    const onMove = (ev: MouseEvent) => onResize(ev.clientX);
    const onUp = () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
      document.body.style.cursor = prevCursor;
      document.body.style.userSelect = prevUserSelect;
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
  };

  return (
    <div
      className={`resize-handle${className ? ' ' + className : ''}`}
      role="separator"
      aria-orientation="vertical"
      aria-label={ariaLabel ?? 'Resize panel'}
      onMouseDown={handleMouseDown}
    />
  );
}

export default ResizeHandle;
