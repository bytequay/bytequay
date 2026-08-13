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
import WorkspaceSyncFeed from './WorkspaceSyncFeed';
import { syncRun } from './syncRunFixture';
import { syncFeed } from './syncRunModel';
import type {
  UpstreamCherryPickEventDto,
  UpstreamCherryPickJobDto,
} from './workspaceApi';

afterEach(cleanup);

/** A run past phase 1, so the feed folds the picks and shows the run's own story. */
function pushedJob(over: Partial<UpstreamCherryPickJobDto> = {}): UpstreamCherryPickJobDto {
  return {
    ...syncRun().job, status: 'COMPLETED', prNumber: 4244, ...over,
  };
}

let ordinal = 0;
function event(
  kind: UpstreamCherryPickEventDto['kind'],
  title: string,
  over: Partial<UpstreamCherryPickEventDto> = {},
): UpstreamCherryPickEventDto {
  ordinal += 1;
  return {
    id: `e${ordinal}`,
    ordinal,
    pickIndex: null,
    kind,
    title,
    detail: null,
    exitCode: null,
    durationMs: null,
    at: '2026-08-09T10:00:00Z',
    ...over,
  };
}

describe('syncFeed', () => {
  it('folds consecutive program steps into one group', () => {
    const items = syncFeed([
      event('watch', 'Probing the latest GitHub Actions checks'),
      event('note', 'CI is still running'),
      event('watch', 'Probing again'),
      event('push', 'git push origin bump-trino'),
    ]);

    expect(items.map(item => item.kind)).toEqual(['activity', 'moment']);
    const activity = items[0];
    expect(activity.kind === 'activity' && activity.lines).toHaveLength(3);
  });

  it('attaches a transcript to the agent turn above it, not to the stream', () => {
    const items = syncFeed([
      event('agent', 'Resolved the conflict in maven.config'),
      event('agent_log', 'Agent transcript', { detail: '{"type":"result"}' }),
    ]);

    expect(items).toHaveLength(1);
    expect(items[0].kind).toBe('agent');
    expect(items[0].kind === 'agent' && items[0].transcript?.id).toBe('e6');
  });

  it('keeps every pick in one group regardless of how many there are', () => {
    const items = syncFeed([
      event('command', 'git cherry-pick -x aaa', { pickIndex: 0 }),
      event('command', 'git cherry-pick -x bbb', { pickIndex: 1 }),
      event('done', 'Range complete'),
    ]);

    expect(items.map(item => item.kind)).toEqual(['picks', 'moment']);
  });
});

describe('WorkspaceSyncFeed', () => {
  it('folds phase 1 once the run is past it, and opens onto the picks', () => {
    const run = syncRun();
    render(<WorkspaceSyncFeed job={pushedJob()} commits={run.commits}
      events={run.events} />);

    expect(screen.getByText('Phase 1 · Local cherry-picks')).toBeTruthy();
    expect(document.querySelectorAll('.sf-pick')).toHaveLength(0);
    fireEvent.click(screen.getByText('Phase 1 · Local cherry-picks'));
    expect(document.querySelectorAll('.sf-pick').length).toBeGreaterThan(0);
  });

  it('opens a step group onto its own lines', () => {
    render(<WorkspaceSyncFeed job={pushedJob()} commits={[]} events={[
      event('watch', 'Watching CI', { detail: 'probing every 5m' }),
      event('note', 'CI is still running'),
    ]} />);

    expect(document.querySelectorAll('.sf-step')).toHaveLength(0);
    fireEvent.click(screen.getByText('Watching CI'));
    expect(document.querySelectorAll('.sf-step')).toHaveLength(2);
  });

  it('reads an agent turn as prose, with its transcript behind a disclosure', () => {
    render(<WorkspaceSyncFeed job={pushedJob()} commits={[]} events={[
      event('agent', 'Kept the fork’s config names where upstream renamed them'),
      event('agent_log', 'Agent transcript', {
        detail: '{"type":"assistant","message":{"content":[{"type":"text","text":"checked"}]}}',
      }),
    ]} />);

    expect(screen.getByText(/Kept the fork/)).toBeTruthy();
    expect(screen.queryByText('checked')).toBeNull();
    fireEvent.click(screen.getByText('Agent transcript'));
    expect(screen.getByText('checked')).toBeTruthy();
  });

  it('shows a live agent log inline and folds it when the turn finishes', () => {
    const run = syncRun();
    const turn = {
      id: 1,
      role: 'sync' as const,
      running: true,
      entries: [{
        kind: 'tool' as const,
        name: 'Read',
        summary: 'core/trino-spi/pom.xml',
        full: 'core/trino-spi/pom.xml',
      }],
    };
    const view = render(<WorkspaceSyncFeed job={run.job} commits={run.commits}
      events={run.events} liveAgentTurns={[turn]} />);

    expect(screen.getByText('Sync agent working')).toBeTruthy();
    expect(screen.getByText('core/trino-spi/pom.xml')).toBeTruthy();

    view.rerender(<WorkspaceSyncFeed job={run.job} commits={run.commits}
      events={run.events} liveAgentTurns={[{ ...turn, running: false }]} />);
    expect(screen.getByText('Sync agent log')).toBeTruthy();
    expect(screen.queryByText('core/trino-spi/pom.xml')).toBeNull();
    fireEvent.click(screen.getByText('Sync agent log'));
    expect(screen.getByText('core/trino-spi/pom.xml')).toBeTruthy();
  });

  it('says when the live agent is waiting for a permission decision', () => {
    render(<WorkspaceSyncFeed job={syncRun().job} commits={[]} events={[]}
      agentWaitingForApproval liveAgentTurns={[{
        id: 1,
        role: 'sync',
        running: true,
        entries: [{
          kind: 'tool', name: 'Bash', summary: 'git hash-object pom.xml',
          full: 'git hash-object pom.xml',
        }],
      }]} />);

    expect(screen.getByText('Sync agent waiting for permission')).toBeTruthy();
    expect(screen.queryByText('Sync agent working')).toBeNull();
  });

  it('shows the user’s steering as theirs, not as the agent’s', () => {
    render(<WorkspaceSyncFeed job={pushedJob()} commits={[]} events={[
      event('guidance', 'prefer our config names'),
    ]} />);

    expect(screen.getByText('YOU')).toBeTruthy();
    expect(screen.getByText('prefer our config names')).toBeTruthy();
  });

  it('ends on a decision card that offers the pull request', () => {
    const onOpenPr = vi.fn();
    render(<WorkspaceSyncFeed job={pushedJob()} commits={[]} events={[]}
      onOpenPr={onOpenPr} />);

    expect(screen.getByText('Range complete — parked for your review')).toBeTruthy();
    fireEvent.click(screen.getByText('Open PR #4244'));
    expect(onOpenPr).toHaveBeenCalled();
  });

  it('shows each teardown step in the stream as its own moment', () => {
    render(<WorkspaceSyncFeed job={pushedJob()} commits={[]} events={[
      event('cleanup', 'Removed the isolated worktree'),
      event('cleanup', 'Remote bump-trino was not deleted', { detail: 'already gone' }),
    ]} />);

    expect(screen.getByText('Removed the isolated worktree')).toBeTruthy();
    expect(screen.getByText('Remote bump-trino was not deleted')).toBeTruthy();
  });

  it('offers no decision while the run is still working', () => {
    render(<WorkspaceSyncFeed job={pushedJob({ status: 'RUNNING', prNumber: null })}
      commits={[]} events={[]} />);

    expect(document.querySelector('.sf-decision')).toBeNull();
  });
});
