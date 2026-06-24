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

  it('offers Resume when paused', () => {
    const onResume = vi.fn();
    render(<RunMenu paused onResume={onResume} onPause={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: /Paused/ }));
    expect(screen.queryByRole('menuitem', { name: 'Pause' })).toBeNull();
    fireEvent.click(screen.getByRole('menuitem', { name: 'Resume' }));
    expect(onResume).toHaveBeenCalledOnce();
  });

  it('terminal tasks show a static label with no menu', () => {
    render(<RunMenu terminal statusLabel="Closed" onClose={() => {}} />);
    const btn = screen.getByRole('button', { name: 'Closed' });
    expect((btn as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(btn);
    expect(screen.queryByRole('menu')).toBeNull();
  });
});
