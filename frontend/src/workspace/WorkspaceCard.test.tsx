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
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import WorkspaceCard from './WorkspaceCard';
import type { WorkspaceCardDto } from '../types';

afterEach(cleanup);

function card(over: Partial<WorkspaceCardDto> = {}): WorkspaceCardDto {
  return {
    id: 'ws-1',
    name: 'ByteQuay',
    color: '#7c3aed',
    isScratch: false,
    repos: ['bytequay'],
    activeThreadCount: 2,
    tasksInFlight: 0,
    spendTodayMilliUsd: 0,
    needsAttentionCount: 0,
    memory: { decisionCount: 3, blockerCount: 0, tokensUsed: 100, tokensCap: 1000 },
    lastActivityMs: 0,
    ...over,
  };
}

describe('WorkspaceCard delete affordance', () => {
  it('renders the GitHub avatar immediately without a coloured abbreviation fallback', () => {
    const { container } = render(
      <WorkspaceCard
        card={card({
          repository: {
            owner: 'octocat',
            repo: 'hello-world',
            fullName: 'octocat/hello-world',
            defaultBaseBranch: 'main',
            clonePath: '/repos/hello-world',
            verified: true,
          },
        })}
        isCurrent={false}
        onEnter={() => {}}
      />,
    );

    const avatar = container.querySelector('.workspace-landing-card__repo-avatar');
    expect(avatar?.getAttribute('src')).toBe('https://github.com/octocat.png?size=72');
    expect(container.querySelector('.workspace-landing-card__repo-fallback')).toBeNull();

    fireEvent.error(avatar as HTMLElement);
    const fallback = container.querySelector('.workspace-landing-card__repo-fallback');
    expect(fallback?.querySelector('svg')).toBeTruthy();
    expect(fallback?.textContent).toBe('');
  });

  it('renders a delete button that fires onDelete with the workspace id', () => {
    const onDelete = vi.fn();
    render(
      <WorkspaceCard card={card()} isCurrent={false} onEnter={() => {}} onDelete={onDelete} />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Delete workspace ByteQuay' }));
    expect(onDelete).toHaveBeenCalledWith('ws-1');
  });

  it('omits the delete button when onDelete is not supplied', () => {
    render(<WorkspaceCard card={card()} isCurrent={false} onEnter={() => {}} />);
    expect(screen.queryByRole('button', { name: /Delete workspace/ })).toBeNull();
  });

  it('does not offer delete on a scratch card', () => {
    const onDelete = vi.fn();
    render(
      <WorkspaceCard
        card={card({ isScratch: true })}
        isCurrent={false}
        onEnter={() => {}}
        onDelete={onDelete}
      />,
    );
    expect(screen.queryByRole('button', { name: /Delete workspace/ })).toBeNull();
  });
});
