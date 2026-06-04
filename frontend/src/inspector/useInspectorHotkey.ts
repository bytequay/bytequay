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
import { useEffect } from 'react';

/** Binds ⌘⇧I (mac) / Ctrl⇧I (everywhere else) to a toggle on the
 *  given setter. Used by the trunk + task pages to open the
 *  PromptContextInspector with the same shortcut the chip-trigger
 *  spells out. Mirrors Esc — pressing the hotkey while the
 *  inspector is already open closes it.
 *
 *  <p>Suppresses the binding when a text input has focus so the
 *  shortcut never steals a keystroke from typing. Standard
 *  guards: `<input>`, `<textarea>`, and any element with
 *  `contenteditable` somewhere up the tree. */
export function useInspectorHotkey(setOpen: (next: boolean | ((prev: boolean) => boolean)) => void): void {
  useEffect(() => {
    const onKey = (ev: KeyboardEvent) => {
      if (!ev.shiftKey) return;
      if (!(ev.metaKey || ev.ctrlKey)) return;
      // KeyboardEvent.key for shifted "i" comes through as "I"
      // in the standard layouts; ev.code is the layout-stable
      // form. Honour both so a non-QWERTY layout still wins.
      if (ev.key !== 'I' && ev.key !== 'i' && ev.code !== 'KeyI') return;
      if (isTypingTarget(ev.target)) return;
      ev.preventDefault();
      setOpen(prev => !prev);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [setOpen]);
}

function isTypingTarget(target: EventTarget | null): boolean {
  if (target === null) return false;
  if (!(target instanceof HTMLElement)) return false;
  const tag = target.tagName;
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true;
  return target.isContentEditable;
}
