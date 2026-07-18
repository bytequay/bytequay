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
import type { ClipboardEvent } from 'react';

function readDataUrl(file: File): Promise<string | null> {
  return new Promise(resolve => {
    const reader = new FileReader();
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : null);
    reader.onerror = () => resolve(null);
    reader.readAsDataURL(file);
  });
}

/** Append pasted clipboard images as data URLs, leaving normal text paste alone. */
export function pasteClipboardImages(
  event: ClipboardEvent<HTMLTextAreaElement>,
  current: string[],
  onChange: (next: string[]) => void,
) {
  const files = Array.from(event.clipboardData.items)
    .filter(item => item.type.startsWith('image/'))
    .map(item => item.getAsFile())
    .filter((file): file is File => file !== null);
  if (files.length === 0) return;

  event.preventDefault();
  void Promise.all(files.map(readDataUrl)).then(results => {
    const added = results.filter((result): result is string => result !== null);
    if (added.length > 0) onChange([...current, ...added]);
  });
}
