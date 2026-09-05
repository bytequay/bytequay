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
import { pasteImage } from './clipboard';

function pasteEvent(items: { kind: string; type: string }[], text = '') {
  return {
    clipboardData: { items, getData: () => text } as unknown as DataTransfer,
    preventDefault: vi.fn(),
  };
}

describe('image paste', () => {
  it('leaves ordinary text paste alone without reading the native clipboard', () => {
    const event = pasteEvent([{ kind: 'string', type: 'text/plain' }], 'Fix this test');
    const attach = vi.fn();
    pasteImage(event, true, attach);
    expect(attach).not.toHaveBeenCalled();
    expect(event.preventDefault).not.toHaveBeenCalled();
  });

  it('handles image-only paste once through the native clipboard', () => {
    const event = pasteEvent([{ kind: 'file', type: 'image/png' }, { kind: 'file', type: 'image/jpeg' }]);
    const attach = vi.fn();
    pasteImage(event, true, attach);
    expect(attach).toHaveBeenCalledOnce();
    expect(event.preventDefault).toHaveBeenCalledOnce();
  });

  it('keeps the default text insertion when the clipboard also contains an image', () => {
    const event = pasteEvent([{ kind: 'file', type: 'image/png' }, { kind: 'string', type: 'text/plain' }], 'Screenshot details');
    const attach = vi.fn();
    pasteImage(event, true, attach);
    expect(attach).toHaveBeenCalledOnce();
    expect(event.preventDefault).not.toHaveBeenCalled();
  });

  it('does not read the clipboard when the composer cannot accept images', () => {
    const event = pasteEvent([{ kind: 'file', type: 'image/png' }]);
    const attach = vi.fn();
    pasteImage(event, false, attach);
    pasteImage(event, true);
    expect(attach).not.toHaveBeenCalled();
    expect(event.preventDefault).not.toHaveBeenCalled();
  });

  it('ignores nonimage files and missing clipboard data', () => {
    const event = pasteEvent([{ kind: 'file', type: 'application/pdf' }, { kind: 'string', type: 'image/png' }]);
    const attach = vi.fn();
    pasteImage(event, true, attach);
    pasteImage({ clipboardData: null, preventDefault: event.preventDefault }, true, attach);
    expect(attach).not.toHaveBeenCalled();
    expect(event.preventDefault).not.toHaveBeenCalled();
  });
});
