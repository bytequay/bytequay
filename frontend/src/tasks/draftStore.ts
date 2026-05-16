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

/**
 * In-memory draft store scoped to the renderer process lifetime —
 * survives component unmount/remount (so typing in a task's reply
 * box, navigating away, and coming back restores the text) but is
 * dropped when the app window closes. Keyed by an opaque string so
 * call sites can namespace their own drafts (e.g. {@code reply:<id>}).
 *
 * sessionStorage would also fit, but a plain Map avoids JSON I/O and
 * the risk of stale data outliving the renderer if Electron ever
 * reuses session partitions across windows.
 */
const drafts = new Map<string, string>();

/** Read the saved draft for {@code key}, or empty string if none. */
export function readDraft(key: string): string {
  return drafts.get(key) ?? '';
}

/** Persist a draft. Empty strings purge the entry so the Map doesn't
 *  grow unboundedly as the user works through many tasks. */
export function writeDraft(key: string, value: string): void {
  if (value === '') {
    drafts.delete(key);
    return;
  }
  drafts.set(key, value);
}

/** React-friendly handle that mirrors {@code useState<string>} but
 *  reads its initial value from the store and writes every change
 *  back. Switching the key (e.g. opening a different task) reloads
 *  the saved value for the new key. */
export function usePersistentDraft(key: string): [string, (next: string) => void] {
  const [value, setValue] = useState<string>(() => readDraft(key));
  useEffect(() => { setValue(readDraft(key)); }, [key]);
  const update = useCallback((next: string) => {
    writeDraft(key, next);
    setValue(next);
  }, [key]);
  return [value, update];
}

/**
 * Returns a ref to attach to a {@code <textarea>} so the element
 * auto-grows with its content — soft-wrapped lines bump the height
 * too, not just explicit newlines. Capped at {@code maxHeight} px
 * (the browser's natural overflow-y kicks in once exceeded).
 *
 * Pair with {@code style={{ resize: 'none', overflowY: 'auto' }}} on
 * the textarea and drop the {@code rows} prop — it would just fight
 * the measured height. {@code minHeight} (in CSS) sets the floor.
 */
export function useAutoGrowTextarea(
  value: string,
  maxHeight: number,
): React.RefObject<HTMLTextAreaElement | null> {
  const ref = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    // Reset first so scrollHeight reflects the new content, not the
    // previously-set inline height.
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, maxHeight)}px`;
  }, [value, maxHeight]);
  return ref;
}
