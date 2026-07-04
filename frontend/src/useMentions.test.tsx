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
import { useRef, useState } from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { useMentions } from './useMentions';

afterEach(() => cleanup());

function Harness({ candidates }: { candidates?: string[] }) {
  const [value, setValue] = useState('');
  const ref = useRef<HTMLTextAreaElement>(null);
  const m = useMentions({ value, onChange: setValue, candidates, textareaRef: ref });
  return (
    <div>
      <textarea
        ref={ref}
        aria-label="ta"
        value={value}
        onChange={m.onChange}
        onKeyDown={m.onKeyDown}
        onClick={m.onClick}
      />
      {m.dropdown}
    </div>
  );
}

const CANDIDATES = ['crusher', 'bob', 'carol'];

function type(text: string) {
  const ta = screen.getByLabelText('ta') as HTMLTextAreaElement;
  fireEvent.change(ta, { target: { value: text, selectionStart: text.length } });
  return ta;
}

describe('useMentions', () => {
  it('offers matching logins for the @token at the caret', () => {
    render(<Harness candidates={CANDIDATES} />);
    type('LGTM @c');
    // 'c' matches crusher + carol, not bob.
    expect(screen.getByText('@crusher')).toBeTruthy();
    expect(screen.getByText('@carol')).toBeTruthy();
    expect(screen.queryByText('@bob')).toBeNull();
  });

  it('inserts the pick as "@login " into the text', () => {
    render(<Harness candidates={CANDIDATES} />);
    type('LGTM @cru');
    // mousedown (the picker uses it so the textarea keeps its caret).
    fireEvent.mouseDown(screen.getByText('@crusher'));
    expect((screen.getByLabelText('ta') as HTMLTextAreaElement).value).toBe('LGTM @crusher ');
  });

  it('does not trigger on an email-style a@b (no separator before @)', () => {
    render(<Harness candidates={CANDIDATES} />);
    type('ping a@cr');
    expect(screen.queryByRole('listbox')).toBeNull();
  });

  it('is inert when there are no candidates', () => {
    render(<Harness candidates={[]} />);
    type('LGTM @c');
    expect(screen.queryByRole('listbox')).toBeNull();
  });
});
