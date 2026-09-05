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
import type { NativeImage } from 'electron';

export const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;
const MAX_ATTACHMENT_PIXELS = 30_000_000;

/** Checks native dimensions before PNG encoding; the backend verifies the resulting bytes again. */
export function clipboardImagePng(image: Pick<NativeImage, 'isEmpty' | 'getSize' | 'toPNG'>): Buffer | null {
  if (image.isEmpty()) return null;
  const { width, height } = image.getSize(1);
  if (width <= 0 || height <= 0 || width * height > MAX_ATTACHMENT_PIXELS) {
    throw new Error('Clipboard image exceeds 30 million pixels');
  }
  const bytes = image.toPNG({ scaleFactor: 1 });
  if (bytes.length === 0) throw new Error('Clipboard image could not be read');
  if (bytes.length > MAX_ATTACHMENT_BYTES) throw new Error('Clipboard image is larger than 10 MiB');
  return bytes;
}
