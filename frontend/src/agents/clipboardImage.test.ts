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
import { describe, expect, it, vi } from 'vitest';
import { clipboardImagePng, MAX_ATTACHMENT_BYTES } from './clipboardImage';

describe('native clipboard image', () => {
  it('ignores text-only clipboards and validates dimensions before encoding', () => {
    const image = { isEmpty: () => true, getSize: vi.fn(() => ({ width: 10000, height: 10000 })), toPNG: vi.fn(() => Buffer.from('png')) };
    expect(clipboardImagePng(image)).toBeNull();
    expect(image.getSize).not.toHaveBeenCalled();
    expect(() => clipboardImagePng({ ...image, isEmpty: () => false })).toThrow('30 million pixels');
    expect(image.toPNG).not.toHaveBeenCalled();
  });

  it('returns PNG bytes and rejects images larger than the upload limit', () => {
    const bytes = Buffer.from('native PNG bytes');
    const image = { isEmpty: () => false, getSize: () => ({ width: 800, height: 600 }), toPNG: () => bytes };
    expect(clipboardImagePng(image)).toBe(bytes);
    expect(() => clipboardImagePng({ ...image, toPNG: () => Buffer.alloc(MAX_ATTACHMENT_BYTES + 1) })).toThrow('10 MiB');
    expect(() => clipboardImagePng({ ...image, toPNG: () => Buffer.alloc(0) })).toThrow('could not be read');
  });
});
