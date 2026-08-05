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
import { afterEach, expect, it, vi } from 'vitest';
import { CommitAuthorPicker, CommitBranchPicker } from './CommitEditorUi';
import type { WorkspaceBranchDto } from './workspaceApi';

afterEach(cleanup);

const branches = [
  { name: 'master', remoteOnly: false },
  { name: 'topic', remoteOnly: false },
] as unknown as WorkspaceBranchDto[];

function open(props: Partial<Parameters<typeof CommitBranchPicker>[0]> = {}) {
  const onPick = vi.fn();
  render(<CommitBranchPicker branch="master" branches={branches}
    currentBranch="master" onPick={onPick} {...props} />);
  fireEvent.click(screen.getByLabelText('Branch: master'));
  return onPick;
}

it('groups the upstream refs under the upstream repo name', () => {
  const onPick = open({
    upstreamBranches: ['upstream/master', 'upstream/release-1'],
    upstreamLabel: 'trinodb/trino',
  });

  expect(screen.getByText('trinodb/trino')).toBeTruthy();
  fireEvent.click(screen.getByText('upstream/release-1'));

  expect(onPick).toHaveBeenCalledWith('upstream/release-1');
});

it('closes when the pointer goes down outside the picker', () => {
  open();
  expect(screen.getByRole('menu')).toBeTruthy();

  fireEvent.pointerDown(document.body);

  expect(screen.queryByRole('menu')).toBeNull();
});

it('closes on Escape', () => {
  open();

  fireEvent.keyDown(document, { key: 'Escape' });

  expect(screen.queryByRole('menu')).toBeNull();
});

it('closes exactly once when the trigger is clicked again', () => {
  // The dismiss ref wraps the trigger too, so the outside-click handler
  // must not fire first and let the toggle reopen the menu.
  open();

  fireEvent.pointerDown(screen.getByLabelText('Branch: master'));
  fireEvent.click(screen.getByLabelText('Branch: master'));

  expect(screen.queryByRole('menu')).toBeNull();
});

it('keeps the menu open while the pointer stays inside it', () => {
  open({ upstreamBranches: ['upstream/master'], upstreamLabel: 'trinodb/trino' });

  fireEvent.pointerDown(screen.getByText('trinodb/trino'));

  expect(screen.queryByRole('menu')).toBeTruthy();
});

it('shows the author GitHub avatar rather than initials', () => {
  render(<CommitAuthorPicker author="all" total={7} onPick={vi.fn()}
    authors={[{ name: 'Jack Chen', handle: 'chenjian2664', count: 7 }]} />);
  fireEvent.click(screen.getByRole('button'));

  // The display name is what the user filters by; the handle is only ever
  // the avatar's, and initials mean that resolution silently failed.
  expect(screen.getByAltText('chenjian2664').getAttribute('src'))
    .toContain('github.com/chenjian2664.png');
  expect(screen.getByText('Jack Chen')).toBeTruthy();
});
