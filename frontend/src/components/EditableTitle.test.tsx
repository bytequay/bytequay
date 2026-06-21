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
import { useState } from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EditableTitle } from './EditableTitle';

afterEach(cleanup);

/** Stateful host so a successful rename re-renders with the new title,
 *  mirroring how the real callers feed the saved value back in. */
function Harness({ onRename }: { onRename: (next: string) => Promise<void> }) {
  const [title, setTitle] = useState('Old title');
  return (
    <EditableTitle
      title={title}
      onRename={async next => { await onRename(next); setTitle(next); }}
    />
  );
}

function enterEditMode() {
  fireEvent.click(screen.getByRole('button'));
  return screen.getByRole('textbox') as HTMLInputElement;
}

describe('EditableTitle', () => {
  it('enters edit mode, saves on Enter, and renders the new title', async () => {
    const onRename = vi.fn().mockResolvedValue(undefined);
    render(<Harness onRename={onRename} />);

    const input = enterEditMode();
    fireEvent.change(input, { target: { value: 'New title' } });
    fireEvent.keyDown(input, { key: 'Enter' });

    expect(onRename).toHaveBeenCalledWith('New title');
    await waitFor(() => expect(screen.getByText('New title')).toBeTruthy());
    // Editor closed back to the resting label.
    expect(screen.queryByRole('textbox')).toBeNull();
  });

  it('trims whitespace and skips the save when unchanged', () => {
    const onRename = vi.fn().mockResolvedValue(undefined);
    render(<Harness onRename={onRename} />);

    const input = enterEditMode();
    fireEvent.change(input, { target: { value: '  Old title  ' } });
    fireEvent.keyDown(input, { key: 'Enter' });

    expect(onRename).not.toHaveBeenCalled();
  });

  it('reverts to the original on Escape', () => {
    const onRename = vi.fn().mockResolvedValue(undefined);
    render(<Harness onRename={onRename} />);

    const input = enterEditMode();
    fireEvent.change(input, { target: { value: 'Throwaway' } });
    fireEvent.keyDown(input, { key: 'Escape' });

    expect(onRename).not.toHaveBeenCalled();
    expect(screen.getByText('Old title')).toBeTruthy();
    expect(screen.queryByRole('textbox')).toBeNull();
  });

  it('shows an inline error and keeps the typed text when the save fails', async () => {
    const onRename = vi.fn().mockRejectedValue(new Error('Failed to update title'));
    render(<Harness onRename={onRename} />);

    const input = enterEditMode();
    fireEvent.change(input, { target: { value: 'New title' } });
    fireEvent.keyDown(input, { key: 'Enter' });

    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain('Failed to update title'));
    // Editor stays open with the user's text intact for a retry.
    expect((screen.getByRole('textbox') as HTMLInputElement).value).toEqual('New title');
  });
});
