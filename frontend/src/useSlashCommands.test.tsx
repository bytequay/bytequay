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
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useSlashCommands, type SlashCommand } from './useSlashCommands';

function Harness({ commands, initial = '' }: { commands: SlashCommand[]; initial?: string }) {
  const [value, setValue] = useState(initial);
  const ref = useRef<HTMLTextAreaElement>(null);
  const slash = useSlashCommands({ value, onChange: setValue, commands, textareaRef: ref });
  return (
    <div>
      {slash.dropdown}
      <textarea
        ref={ref}
        aria-label="composer"
        value={value}
        onChange={slash.onChange}
        onKeyDown={slash.onKeyDown}
      />
    </div>
  );
}

describe('useSlashCommands', () => {
  afterEach(cleanup);
  const cmd = (run: () => void): SlashCommand => ({ name: 'model', desc: 'Switch AI model', run });

  it('shows the menu for a start-anchored /token', () => {
    render(<Harness commands={[cmd(vi.fn())]} />);
    fireEvent.change(screen.getByLabelText('composer'), { target: { value: '/mo' } });
    expect(screen.getByRole('listbox')).toBeTruthy();
  });

  it('does not trigger mid-message', () => {
    render(<Harness commands={[cmd(vi.fn())]} />);
    fireEvent.change(screen.getByLabelText('composer'), { target: { value: 'ship it /model' } });
    expect(screen.queryByRole('listbox')).toBeNull();
  });

  it('runs the command and clears the /token on Enter', () => {
    const run = vi.fn();
    render(<Harness commands={[cmd(run)]} />);
    const ta = screen.getByLabelText('composer') as HTMLTextAreaElement;
    fireEvent.change(ta, { target: { value: '/model' } });
    fireEvent.keyDown(ta, { key: 'Enter' });
    expect(run).toHaveBeenCalledOnce();
    expect(ta.value).toBe('');
  });

  it('is inert with no commands', () => {
    render(<Harness commands={[]} />);
    fireEvent.change(screen.getByLabelText('composer'), { target: { value: '/model' } });
    expect(screen.queryByRole('listbox')).toBeNull();
  });
});
