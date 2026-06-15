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
 * Shell-style ↑/↓ recall for a composer textarea. Pass the thread's
 * previous user prompts **newest-first**; the returned {@code onKeyDown}
 * walks them on ArrowUp (older) / ArrowDown (newer):
 *
 * - ArrowUp only fires when the caret sits on the first line, so a
 *   multi-line draft can still be edited with the arrows.
 * - ArrowDown only fires when the caret is on the last line, and at the
 *   newest entry it restores the draft the user was typing.
 *
 * Call {@code reset} from the textarea's onChange so that typing breaks
 * out of history navigation (the next ArrowUp starts fresh), and after a
 * send so the next recall begins from the newest prompt again.
 */
export type PromptHistory = {
  /** Returns true when it consumed the key (the caller should stop). */
  onKeyDown: (e: React.KeyboardEvent<HTMLTextAreaElement>) => boolean;
  reset: () => void;
};

export function usePromptHistory(
  prompts: string[],
  value: string,
  setValue: (next: string) => void,
): PromptHistory {
  // -1 = not navigating (showing the live draft). 0 = newest prompt.
  const [index, setIndex] = useState(-1);
  const draftRef = useRef('');

  // A new prompt landed (the user sent one) — restart navigation from the
  // newest. Keyed on length, not identity, so a poll that re-fetches the
  // same messages doesn't yank the user out of mid-recall.
  useEffect(() => { setIndex(-1); }, [prompts.length]);

  const apply = useCallback((textarea: HTMLTextAreaElement, text: string) => {
    setValue(text);
    // Park the caret at the end so the next ArrowUp keeps walking back
    // (the caret stays on the first line for short prompts).
    requestAnimationFrame(() => {
      textarea.selectionStart = textarea.selectionEnd = text.length;
    });
  }, [setValue]);

  const onKeyDown = useCallback((e: React.KeyboardEvent<HTMLTextAreaElement>): boolean => {
    if (prompts.length === 0) return false;
    const ta = e.currentTarget;
    if (e.key === 'ArrowUp') {
      // Only hijack the arrow when the caret is on the first line.
      if (ta.value.slice(0, ta.selectionStart).includes('\n')) return false;
      if (index === -1) draftRef.current = value;
      const next = Math.min(index + 1, prompts.length - 1);
      if (next === index) {
        e.preventDefault();
        return true;
      }
      setIndex(next);
      e.preventDefault();
      apply(ta, prompts[next]);
      return true;
    }
    if (e.key === 'ArrowDown') {
      if (index === -1) return false;
      // Only hijack when the caret is on the last line.
      if (ta.value.slice(ta.selectionEnd).includes('\n')) return false;
      const next = index - 1;
      setIndex(next);
      e.preventDefault();
      apply(ta, next === -1 ? draftRef.current : prompts[next]);
      return true;
    }
    return false;
  }, [prompts, index, value, apply]);

  const reset = useCallback(() => setIndex(-1), []);

  return { onKeyDown, reset };
}
