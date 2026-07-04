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
import { useLayoutEffect, useRef } from 'react';

/**
 * Grows a `<textarea>` to fit its content — up to `maxHeight`, then it
 * scrolls — so a long or pasted comment isn't squeezed into a few clipped
 * rows. Pass the controlled `value` so it re-fits on every change (typing
 * AND paste both flow through `onChange`), including the reset to '' after
 * submit. Returns the ref to attach to the textarea.
 *
 * Runs in a layout effect so the resize lands before paint (no flicker).
 * The inline `height` it sets overrides any CSS `min-height`; a `resize:
 * vertical` rule still lets the user drag taller.
 */
export function useAutoGrow(value: string, maxHeight = 320) {
  const ref = useRef<HTMLTextAreaElement>(null);
  useLayoutEffect(() => {
    const el = ref.current;
    if (el === null) {
      return;
    }
    // Collapse first so shrinking (e.g. after a delete) is measured too.
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, maxHeight)}px`;
    el.style.overflowY = el.scrollHeight > maxHeight ? 'auto' : 'hidden';
  }, [value, maxHeight]);
  return ref;
}
