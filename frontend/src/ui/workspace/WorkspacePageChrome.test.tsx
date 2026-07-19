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
import {
  WorkspaceGlobalRows, WorkspaceSidebarFooter, WorkspaceSwitcherCard,
} from './WorkspacePageChrome';

afterEach(cleanup);

describe('locked workspace page chrome', () => {
  it('shares the exact global rows and workspace switcher behavior', () => {
    const onNavigate = vi.fn();
    const onSwitch = vi.fn();
    render(
      <>
        <WorkspaceGlobalRows onNavigate={onNavigate} />
        <WorkspaceSwitcherCard name="ByteQuay" repository="chenjian2664/ByteQuay" onSwitch={onSwitch} />
      </>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Home' }));
    fireEvent.click(screen.getByRole('button', { name: 'Workspaces' }));
    const workspace = screen.getByRole('button', { name: 'ByteQuaychenjian2664/ByteQuay' });
    expect(workspace.getAttribute('title')).toBe('Open ByteQuay Today');
    fireEvent.click(workspace);
    expect(onNavigate.mock.calls.map(call => call[0])).toEqual(['home', 'workspaces']);
    expect(onSwitch).toHaveBeenCalledOnce();
  });

  it('lets trunk and task pages choose whether Settings is present', () => {
    const { rerender } = render(<WorkspaceSidebarFooter user="chenjian2664" notificationCount={26} />);
    expect(screen.getByRole('button', { name: 'Notifications26' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Settings' })).toBeNull();

    rerender(<WorkspaceSidebarFooter user="chenjian2664" showSettings />);
    expect(screen.getByRole('button', { name: 'Settings' })).toBeTruthy();
  });
});
