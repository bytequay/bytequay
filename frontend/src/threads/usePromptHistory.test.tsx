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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { usePromptHistory } from './usePromptHistory';

afterEach(cleanup);

function Harness({ prompts }: { prompts: string[] }) {
  const [value, setValue] = useState('');
  const history = usePromptHistory(prompts, value, setValue);
  return (
    <textarea
      aria-label="composer"
      value={value}
      onChange={e => { setValue(e.target.value); history.reset(); }}
      onKeyDown={e => { history.onKeyDown(e); }}
    />
  );
}

describe('usePromptHistory', () => {
  it('walks older on ArrowUp, newer on ArrowDown, and restores the draft', () => {
    render(<Harness prompts={['newest', 'mid', 'oldest']} />);
    const ta = screen.getByLabelText('composer') as HTMLTextAreaElement;
    const up = () => fireEvent.keyDown(ta, { key: 'ArrowUp' });
    const down = () => fireEvent.keyDown(ta, { key: 'ArrowDown' });

    up(); expect(ta.value).toBe('newest');
    up(); expect(ta.value).toBe('mid');
    up(); expect(ta.value).toBe('oldest');
    up(); expect(ta.value).toBe('oldest');   // capped at the oldest entry
    down(); expect(ta.value).toBe('mid');
    down(); expect(ta.value).toBe('newest');
    down(); expect(ta.value).toBe('');       // back to the (empty) draft
  });

  it('keeps a typed draft and restores it past the newest', () => {
    render(<Harness prompts={['p1']} />);
    const ta = screen.getByLabelText('composer') as HTMLTextAreaElement;
    fireEvent.change(ta, { target: { value: 'draft text' } });
    fireEvent.keyDown(ta, { key: 'ArrowUp' });
    expect(ta.value).toBe('p1');
    fireEvent.keyDown(ta, { key: 'ArrowDown' });
    expect(ta.value).toBe('draft text');
  });

  it('does nothing with no prior prompts', () => {
    render(<Harness prompts={[]} />);
    const ta = screen.getByLabelText('composer') as HTMLTextAreaElement;
    fireEvent.keyDown(ta, { key: 'ArrowUp' });
    expect(ta.value).toBe('');
  });

  it('does not hijack ArrowUp from a lower line of a multi-line draft', () => {
    render(<Harness prompts={['p1']} />);
    const ta = screen.getByLabelText('composer') as HTMLTextAreaElement;
    fireEvent.change(ta, { target: { value: 'line one\nline two' } });
    ta.setSelectionRange(12, 12); // caret inside "line two"
    fireEvent.keyDown(ta, { key: 'ArrowUp' });
    expect(ta.value).toBe('line one\nline two'); // unchanged; textarea keeps the arrow
  });
});
