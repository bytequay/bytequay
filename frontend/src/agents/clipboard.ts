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

/** The native process reads image bytes; text stays with the input's ordinary paste behavior. */
export function pasteImage(
  event: Pick<ClipboardEvent, 'clipboardData' | 'preventDefault'>,
  enabled: boolean,
  attach?: () => void,
): void {
  if (!enabled || attach === undefined || event.clipboardData === null) return;
  const clipboard = event.clipboardData;
  if (!Array.from(clipboard.items).some((item) => item.kind === 'file' && item.type.startsWith('image/'))) return;
  if (clipboard.getData('text/plain') === '') event.preventDefault();
  attach();
}
