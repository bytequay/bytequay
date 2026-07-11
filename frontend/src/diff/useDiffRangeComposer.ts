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
import type { AnchorSide } from './DiffFileList';

export type DiffRangeComposerSlot = {
  file?: string;
  side: AnchorSide;
  line: number;
  startLine?: number;
  startSide?: AnchorSide;
} | null;

type LineRef = {
  file?: string;
  side: AnchorSide;
  line: number;
};

type DragRange = {
  file?: string;
  side: AnchorSide;
  start: number;
  end: number;
} | null;

function sameFile(a?: string, b?: string): boolean {
  return (a ?? null) === (b ?? null);
}

export function useDiffRangeComposer() {
  const [composer, setComposer] = useState<DiffRangeComposerSlot>(null);
  const [dragRange, setDragRange] = useState<DragRange>(null);
  const dragRangeRef = useRef<DragRange>(null);
  const suppressNextClickRef = useRef(false);

  const closeComposer = useCallback(() => {
    setComposer(null);
  }, []);

  const openComposer = useCallback(({ file, side, line }: LineRef, shiftKey = false) => {
    setComposer(prev => {
      if (shiftKey && prev !== null && sameFile(prev.file, file) && prev.side === side) {
        const anchor = prev.line;
        const start = Math.min(anchor, line);
        const end = Math.max(anchor, line);
        return start === end
          ? { file, side, line: end }
          : { file, side, line: end, startLine: start, startSide: side };
      }
      return { file, side, line };
    });
  }, []);

  const isComposerAt = useCallback(({ file, side, line }: LineRef): boolean =>
    composer !== null
      && sameFile(composer.file, file)
      && composer.side === side
      && composer.line === line,
  [composer]);

  const handleRowClick = useCallback((line: LineRef, shiftKey = false, options?: { toggleActive?: boolean }) => {
    if (suppressNextClickRef.current) {
      suppressNextClickRef.current = false;
      return;
    }
    if (options?.toggleActive === true && !shiftKey && isComposerAt(line)) {
      closeComposer();
      return;
    }
    openComposer(line, shiftKey);
  }, [closeComposer, isComposerAt, openComposer]);

  const onRowPointerDown = useCallback(({ file, side, line }: LineRef) => {
    const range = { file, side, start: line, end: line };
    dragRangeRef.current = range;
    setDragRange(range);
  }, []);

  const onRowPointerEnter = useCallback(({ file, side, line }: LineRef) => {
    const cur = dragRangeRef.current;
    if (!cur || !sameFile(cur.file, file) || cur.side !== side || cur.end === line) return;
    const next = { ...cur, end: line };
    dragRangeRef.current = next;
    setDragRange(next);
  }, []);

  useEffect(() => {
    const onUp = () => {
      const drag = dragRangeRef.current;
      if (!drag) return;
      dragRangeRef.current = null;
      setDragRange(null);
      const start = Math.min(drag.start, drag.end);
      const end = Math.max(drag.start, drag.end);
      if (end === start) return;
      suppressNextClickRef.current = true;
      setComposer({ file: drag.file, side: drag.side, line: end, startLine: start, startSide: drag.side });
    };
    window.addEventListener('pointerup', onUp);
    window.addEventListener('pointercancel', onUp);
    return () => {
      window.removeEventListener('pointerup', onUp);
      window.removeEventListener('pointercancel', onUp);
    };
  }, []);

  const isInRange = useCallback(({ file, side, line }: LineRef): boolean => {
    if (dragRange && sameFile(dragRange.file, file) && dragRange.side === side) {
      const lo = Math.min(dragRange.start, dragRange.end);
      const hi = Math.max(dragRange.start, dragRange.end);
      return line >= lo && line <= hi;
    }
    if (composer === null || !sameFile(composer.file, file) || composer.side !== side) return false;
    if (composer.startLine == null) return composer.line === line;
    return line >= composer.startLine && line <= composer.line;
  }, [dragRange, composer]);

  return {
    composer,
    setComposer,
    closeComposer,
    openComposer,
    handleRowClick,
    onRowPointerDown,
    onRowPointerEnter,
    isComposerAt,
    isInRange,
  };
}
