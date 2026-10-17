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

/** The parts of one Electron key press that tell the Assistant shortcut from the rest. */
export type ShortcutInput = { type: string; key: string; meta: boolean; shift: boolean; alt: boolean; control: boolean };

/** Whether a press is ⌘J alone, which opens and closes the Assistant panel from any page, web page or editor. */
export function isAssistantShortcut(input: ShortcutInput): boolean {
  return input.type === 'keyDown' && input.meta && !input.shift && !input.alt && !input.control && input.key.toLowerCase() === 'j';
}
