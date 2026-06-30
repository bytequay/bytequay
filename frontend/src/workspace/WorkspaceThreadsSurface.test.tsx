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
import type { ThreadDto } from '../types';
import { WorkspaceThreadsSurface } from './WorkspaceThreadsSurface';

afterEach(cleanup);

function thread(over: Partial<ThreadDto> = {}): ThreadDto {
  return {
    id: 't1', kind: 'TASK', provider: 'claude', agentSessionId: null,
    title: 'Backend cleanup review', status: 'RUNNING', flow: 'build',
    model: 'opus', costUsdMilli: 0, tokensIn: 0, tokensOut: 0,
    createdAt: '2026-06-24T00:00:00Z', updatedAt: '2026-06-24T00:00:00Z',
    endedAt: null, errorMessage: null, workspaceId: 'bq',
    workModel: null, parallelSlots: 1,
    ...over,
  } as ThreadDto;
}

describe('WorkspaceThreadsSurface', () => {
  it('renders a thread card with repo logo, title and meta', () => {
    const { container } = render(
      <WorkspaceThreadsSurface threads={[thread()]} loading={false} />,
    );
    expect(screen.getByText('Open threads')).toBeTruthy();
    expect(screen.getByText('Backend cleanup review')).toBeTruthy();
    // The thread DTO no longer projects a task, so the card shows the
    // discussion meta line.
    expect(screen.getByText('repo · discussion · no task yet')).toBeTruthy();
    expect(container.querySelector('.v3-logo')).toBeTruthy();
  });

  it('hides terminal threads and shows the empty state when none are open', () => {
    render(
      <WorkspaceThreadsSurface
        threads={[thread({ id: 't9', status: 'COMPLETED' })]}
        loading={false}
      />,
    );
    expect(screen.queryByText('Backend cleanup review')).toBeNull();
    expect(screen.getByText(/workspace is at rest/)).toBeTruthy();
  });

  it('shows the loading hint before data arrives', () => {
    render(<WorkspaceThreadsSurface threads={[]} loading />);
    expect(screen.getByText('Loading…')).toBeTruthy();
  });

  it('routes a card click to onOpenThread', () => {
    const onOpenThread = vi.fn();
    render(<WorkspaceThreadsSurface threads={[thread()]} loading={false} onOpenThread={onOpenThread} />);
    fireEvent.click(screen.getByText('Backend cleanup review'));
    expect(onOpenThread).toHaveBeenCalledWith('t1');
  });

  it('labels a 0-task discussion thread without a pill', () => {
    render(
      <WorkspaceThreadsSurface
        threads={[thread({ id: 't2', title: 'View prs' })]}
        loading={false}
      />,
    );
    expect(screen.getByText('repo · discussion · no task yet')).toBeTruthy();
  });
});
