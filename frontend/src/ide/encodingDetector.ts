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
import { analyse } from 'chardet';

const normalized = (encoding: string) => encoding.toLowerCase().replace(/[^a-z0-9]/g, '');

// The pinned VS Code text-file service passes Latin-1 bytes and expects jschardet's result shape.
// Use the MIT detector; an unsupported candidate yields no guess and leaves the configured encoding in effect.
export function detect(input: string, options?: { detectEncodings: string[] }): { encoding: string | null; confidence: number } {
  const bytes = Uint8Array.from(input, character => character.charCodeAt(0));
  // ASCII supplies no evidence for choosing a legacy encoding over the project's configured encoding.
  if (bytes.every(byte => byte < 0x80)) return { encoding: null, confidence: 0 };
  const candidates = options?.detectEncodings.map(normalized);
  const match = analyse(bytes)
    .find(result => !candidates || candidates.includes(normalized(result.name)));
  return { encoding: match?.name ?? null, confidence: (match?.confidence ?? 0) / 100 };
}
