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
import { useCallback, useRef, useState } from 'react';
import type { RefObject } from 'react';

export const SIDEBAR_MIN_WIDTH = 200;
export const SIDEBAR_MAX_WIDTH = 460;
export const SIDEBAR_DEFAULT_WIDTH = 250;
export const SIDEBAR_WIDTH_KEY = 'bq.rail-width';

/**
 * Drives the draggable boundary between the sidebar and the conversation
 * column on the shell. Returns a width (persisted to {@code localStorage}
 * under {@code key}, one preference for every surface), a ref to put on the
 * grid root, and an {@code onResize(clientX)} for a {@link ResizeHandle}
 * sitting on the sidebar's right edge. The sidebar is left-anchored, so its
 * width is the pointer minus the shell's left edge, clamped to a sane range.
 */
export function useSidebarWidth<T extends HTMLElement = HTMLDivElement>(
  key = SIDEBAR_WIDTH_KEY,
  defaultWidth = SIDEBAR_DEFAULT_WIDTH,
): {
  sidebarWidth: number;
  shellRef: RefObject<T>;
  onResize: (clientX: number) => void;
} {
  const [sidebarWidth, setSidebarWidth] = useState<number>(() => {
    try {
      const stored = typeof localStorage !== 'undefined' ? Number(localStorage.getItem(key)) : NaN;
      return Number.isFinite(stored) && stored >= SIDEBAR_MIN_WIDTH && stored <= SIDEBAR_MAX_WIDTH
        ? stored
        : defaultWidth;
    }
    catch {
      return defaultWidth;
    }
  });
  const shellRef = useRef<T>(null);

  const onResize = useCallback((clientX: number) => {
    const rect = shellRef.current?.getBoundingClientRect();
    if (!rect) {
      return;
    }
    const next = Math.max(SIDEBAR_MIN_WIDTH, Math.min(SIDEBAR_MAX_WIDTH, clientX - rect.left));
    setSidebarWidth(next);
    try {
      localStorage.setItem(key, String(Math.round(next)));
    }
    catch {
      /* storage unavailable — in-memory only */
    }
  }, [key]);

  return { sidebarWidth, shellRef, onResize };
}
