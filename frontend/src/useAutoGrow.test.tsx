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
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { useAutoGrow } from './useAutoGrow';

// jsdom does no layout, so scrollHeight is always 0 — stub it to a value
// we control so the hook has something real to fit against.
let mockScrollHeight = 0;

beforeEach(() => {
  Object.defineProperty(HTMLTextAreaElement.prototype, 'scrollHeight', {
    configurable: true,
    get() {
      return mockScrollHeight;
    },
  });
});

afterEach(() => {
  cleanup();
});

function Harness({ value, max }: { value: string; max?: number }) {
  const ref = useAutoGrow(value, max);
  return <textarea ref={ref} value={value} readOnly aria-label="ta" />;
}

describe('useAutoGrow', () => {
  it('grows the textarea to its content height when under the cap', () => {
    mockScrollHeight = 120;
    render(<Harness value="a" max={320} />);
    const ta = screen.getByLabelText('ta') as HTMLTextAreaElement;
    expect(ta.style.height).toBe('120px');
    expect(ta.style.overflowY).toBe('hidden');
  });

  it('caps at maxHeight and switches to scroll past the cap', () => {
    mockScrollHeight = 500;
    render(<Harness value="lots of text" max={320} />);
    const ta = screen.getByLabelText('ta') as HTMLTextAreaElement;
    expect(ta.style.height).toBe('320px');
    expect(ta.style.overflowY).toBe('auto');
  });

  it('re-fits when the value changes (typing / paste)', () => {
    mockScrollHeight = 80;
    const { rerender } = render(<Harness value="short" max={320} />);
    const ta = screen.getByLabelText('ta') as HTMLTextAreaElement;
    expect(ta.style.height).toBe('80px');

    // Simulate a large paste bumping the measured content height.
    mockScrollHeight = 260;
    rerender(<Harness value="a much longer pasted body" max={320} />);
    expect(ta.style.height).toBe('260px');
  });
});
