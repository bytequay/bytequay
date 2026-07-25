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
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { RunMenu } from './RunMenu';

afterEach(cleanup);

describe('RunMenu', () => {
  it('opens the menu and offers Pause when running', () => {
    const onPause = vi.fn();
    render(<RunMenu onPause={onPause} onClose={() => {}} />);
    expect(screen.queryByRole('menu')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /Running/ }));
    expect(screen.getByRole('menu')).toBeTruthy();
    expect(screen.queryByRole('menuitem', { name: 'Resume' })).toBeNull();
    fireEvent.click(screen.getByRole('menuitem', { name: 'Pause' }));
    expect(onPause).toHaveBeenCalledOnce();
    // Menu closes after picking an action.
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('makes Resume the direct action when paused', () => {
    const onResume = vi.fn();
    render(<RunMenu paused statusLabel="CI fix attempts exhausted (5/5)" onResume={onResume} onPause={() => {}} />);
    expect(screen.queryByRole('menu')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Resume · CI fix attempts exhausted (5/5)' }));
    expect(onResume).toHaveBeenCalledOnce();
  });

  it('confirms an explicit CI retry before running it', () => {
    const onResume = vi.fn();
    render(<RunMenu paused statusLabel="CI fix attempts exhausted (5/5)"
      resumeLabel="Retry CI" resumeConfirmation={{
        title: 'Retry failed CI?', body: 'This reruns GitHub Actions.', confirmLabel: 'Retry CI',
      }} onResume={onResume} />);

    fireEvent.click(screen.getByRole('button', { name: 'Retry CI · CI fix attempts exhausted (5/5)' }));
    expect(onResume).not.toHaveBeenCalled();
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Retry CI' }));
    expect(onResume).toHaveBeenCalledOnce();
  });

  it('closes via the direct danger button only after confirming', () => {
    const onClose = vi.fn();
    render(<RunMenu onPause={() => {}} onClose={onClose} />);
    // Close is a direct button, not hidden in the run dropdown.
    const closeBtn = screen.getByRole('button', { name: 'Close task' });
    expect(screen.queryByRole('menu')).toBeNull();
    fireEvent.click(closeBtn);
    // A confirm dialog appears; nothing is closed yet.
    const dialog = screen.getByRole('dialog');
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Close task' }));
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('cancelling the confirm dialog does not close the task', () => {
    const onClose = vi.fn();
    render(<RunMenu onClose={onClose} />);
    fireEvent.click(screen.getByRole('button', { name: 'Close task' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }));
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('terminal tasks show a static label with no menu', () => {
    render(<RunMenu terminal statusLabel="Closed" onClose={() => {}} />);
    const btn = screen.getByRole('button', { name: 'Closed' });
    expect((btn as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(btn);
    expect(screen.queryByRole('menu')).toBeNull();
  });
});
