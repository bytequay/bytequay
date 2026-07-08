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

const MIN = 360;
// Generous on purpose — the PR/Changes pane (esp. the embedded Changes tab's
// file-tree + diff) benefits from real width. The drag itself is bounded by
// the body's actual rect, so this ceiling only matters on very wide windows.
const MAX = 1600;
const DEFAULT = 520;

/**
 * Drives the draggable boundary between the conversation column and the
 * right pane on the brain / stage surfaces. Returns a width (persisted to
 * {@code localStorage} under {@code key}), a ref to put on the grid body,
 * and an {@code onResize(clientX)} for a {@link ResizeHandle} sitting just
 * left of the pane. The pane is right-anchored, so its width is the body's
 * right edge minus the pointer, clamped to a sane range.
 */
export function usePaneWidth(key = 'bq.brainPaneWidth'): {
  paneWidth: number;
  bodyRef: RefObject<HTMLDivElement>;
  onResize: (clientX: number) => void;
} {
  const [paneWidth, setPaneWidth] = useState<number>(() => {
    try {
      const stored = typeof localStorage !== 'undefined' ? Number(localStorage.getItem(key)) : NaN;
      return Number.isFinite(stored) && stored >= MIN && stored <= MAX ? stored : DEFAULT;
    }
    catch {
      return DEFAULT;
    }
  });
  const bodyRef = useRef<HTMLDivElement>(null);

  const onResize = useCallback((clientX: number) => {
    const rect = bodyRef.current?.getBoundingClientRect();
    if (!rect) {
      return;
    }
    const next = Math.max(MIN, Math.min(MAX, rect.right - clientX));
    setPaneWidth(next);
    try {
      localStorage.setItem(key, String(Math.round(next)));
    }
    catch {
      /* storage unavailable — in-memory only */
    }
  }, [key]);

  return { paneWidth, bodyRef, onResize };
}
