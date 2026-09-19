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
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { CommitRefreshStatusDto, WorkspaceDto, WorkspaceSettingsDto } from '../types';
import CommitsTab, {
  CommitsBrowser,
  availableCommitSource,
  commitRefreshChanged,
  commitRefreshRefs,
  commitSyncLabel,
  commitTargetRef,
  selectableCommitSources,
} from './CommitsTab';

describe('workspace commits tab', () => {
  it('waits for the saved target before reading history or offering cherry-pick', () => {
    const html = renderToStaticMarkup(<CommitsTab workspace={workspace()}
      onSourceSelectionChange={() => {}} onOpenPullRequest={() => {}} onOpenSettings={() => {}} />);

    expect(html).toContain('Loading commits target…');
    expect(html).not.toContain('cmt-repo-chip');
    expect(html).not.toContain('Cherry pick');
  });

  it('keeps the workspace branch separate from the remote source switcher', () => {
    const html = renderToStaticMarkup(<CommitsBrowser
      workspace={workspace()}
      settings={directSettings}
      onSourceSelectionChange={() => {}}
      onOpenPullRequest={() => {}}
      onOpenSettings={() => {}}
    />);

    expect(html).toContain('Source: Select source');
    expect(html).toContain('Choose a remote source for history and cherry-pick');
    expect(html).not.toContain('Source: origin');
    expect(html).not.toContain('No upstream linked');
  });

  it('excludes origin from cherry-pick sources', () => {
    expect(selectableCommitSources([
      { name: 'origin', url: 'https://github.com/example/project.git' },
      { name: 'upstream', url: 'https://github.com/upstream/project.git' },
      { name: 'oss', url: 'https://github.com/example/project-oss.git' },
    ], 'origin')).toEqual([
      { name: 'upstream', url: 'https://github.com/upstream/project.git' },
      { name: 'oss', url: 'https://github.com/example/project-oss.git' },
    ]);
  });

  it('discards a remembered source that is no longer configured', () => {
    const remotes = ['upstream', 'oss'];

    expect(availableCommitSource('oss', remotes, true)).toBe('oss');
    expect(availableCommitSource('upstream', [], true)).toBe('upstream');
    expect(availableCommitSource('removed', remotes, true)).toBeNull();
    expect(availableCommitSource('upstream', remotes, false)).toBe('oss');
  });

  it('keeps a remembered remote source while validating it', () => {
    const html = renderToStaticMarkup(<CommitsBrowser
      workspace={workspace()}
      settings={directSettings}
      initialSourceSelection={{ source: 'oss' }}
      onSourceSelectionChange={() => {}}
      onOpenPullRequest={() => {}}
      onOpenSettings={() => {}}
    />);

    expect(html).toContain('Source: oss');
    expect(html).toContain('title="Branch of the selected source"');
    expect(html).not.toContain('cmt-split-chip is-active');
    expect(html).not.toContain('read-only');
  });

  it.each([false, true])('uses the first branch control for the fork destination (override=%s)', overridden => {
    const linked: WorkspaceDto = {
      ...workspace(),
      upstreamDefaultBranch: 'master',
      upstreamRepositoryId: 200,
      upstreamOwner: 'upstream',
      upstreamRepo: 'project',
      topology: 'FORK',
    };
    const targetBranch = overridden ? 'release/479' : 'master';
    const html = renderToStaticMarkup(<CommitsBrowser
      workspace={linked}
      settings={{ ...directSettings, commitsTarget: { remote: 'upstream', branch: targetBranch, overridden } }}
      onSourceSelectionChange={() => {}}
      onOpenPullRequest={() => {}}
      onOpenSettings={() => {}}
    />);

    expect(html).toMatch(new RegExp(`class="cmt-repo-chip is-active"[^>]*title="Cherry-pick target: upstream/project · upstream/${targetBranch}"[^>]*aria-pressed="true"`));
    expect(html).not.toContain('cmt-split-chip is-active');
    expect(html).toContain('title="View the selected remote source"');
    expect(html).toContain('Source: upstream');
    expect(html).toContain('title="Branch of the selected source"');
    expect(html).toContain(`cmt-repo-chip__label cmt-mono">upstream/${targetBranch}</span>`);
    expect(html).not.toContain(`Target · upstream/${targetBranch}`);
    expect(html).not.toContain('Check out');
  });

  it('uses the existing Lucide icons throughout the commit toolbar', () => {
    const html = renderToStaticMarkup(<CommitsBrowser
      workspace={workspace()}
      settings={directSettings}
      onSourceSelectionChange={() => {}}
      onOpenPullRequest={() => {}}
      onOpenSettings={() => {}}
    />);

    expect(html).toContain('lucide-refresh-cw');
    expect(html).toContain('lucide-search');
    expect(html).toContain('lucide-git-branch');
    expect(html).toContain('lucide-git-compare-arrows');
    expect(html).toContain('lucide-chevron-down');
    expect(html).toContain('lucide-list-checks');
    expect(html).toContain('aria-label="Sync commit history"');
    expect(html).toContain('aria-label="Search commits by pull request title, number, or SHA"');
    expect(html).not.toContain('Search — PR title, #number, or SHA');
    expect(html).not.toContain('not synced yet</span>');
    expect(html).not.toMatch(/[⑂⇅⇲▾]/);
  });

  it.each([
    { initialSourceSelection: { source: null } },
    { initialSourceSelection: { source: 'upstream' } },
    { initialRepo: 'workspace' as const },
  ])('opens the selected target when the source browser is inactive: %j', selection => {
    const html = renderToStaticMarkup(<CommitsBrowser
      workspace={{ ...workspace(), upstreamRepositoryId: 200, upstreamDefaultBranch: 'master', topology: 'FORK' }}
      settings={{ ...directSettings, commitsTarget: { remote: 'upstream', branch: 'master', overridden: false } }}
      {...selection}
      onSourceSelectionChange={() => {}}
      onOpenPullRequest={() => {}}
      onOpenSettings={() => {}}
    />);

    expect(html).not.toContain('cmt-split-chip is-active');
    expect(html).not.toContain('read-only');
    expect(html).toMatch(/class="cmt-repo-chip is-active"[^>]*aria-pressed="true"/);
  });

  it('uses the direct repository target branch without an upstream prefix', () => {
    const html = renderToStaticMarkup(<CommitsBrowser workspace={workspace()}
      settings={{ ...directSettings, commitsTarget: { remote: 'origin', branch: 'release/1', overridden: true } }}
      onSourceSelectionChange={() => {}} onOpenPullRequest={() => {}} onOpenSettings={() => {}} />);

    expect(html).toContain('title="Cherry-pick target: example/project · release/1"');
    expect(html).toContain('cmt-repo-chip__label cmt-mono">release/1</span>');
    expect(html).not.toContain('upstream/');
  });

  it('opens the source for a cherry-pick while retaining a separate configured destination', () => {
    const html = renderToStaticMarkup(<CommitsBrowser
      workspace={{ ...workspace(), upstreamRepositoryId: 200, upstreamDefaultBranch: 'master', topology: 'FORK' }}
      settings={{ ...directSettings, commitsTarget: { remote: 'upstream', branch: 'release/479', overridden: true } }}
      initialRepo="upstream" onSourceSelectionChange={() => {}} onOpenPullRequest={() => {}} onOpenSettings={() => {}} />);

    expect(html).toContain('cmt-split-chip is-active');
    expect(html).toContain('cmt-repo-chip__label cmt-mono">upstream/release/479</span>');
    expect(html).not.toContain('Upstream · read-only');
    expect(html).not.toContain('then cherry-pick the range into upstream/release/479');
  });

  it('reads and refreshes the target separately from the selected source', () => {
    const direct = { remote: 'origin' as const, branch: 'release/1', overridden: true };
    const fork = { remote: 'upstream' as const, branch: 'release/479', overridden: true };
    expect(commitTargetRef(direct)).toEqual({ source: 'WORKSPACE', branch: 'release/1' });
    expect(commitTargetRef(fork)).toEqual({ source: 'UPSTREAM', branch: 'release/479' });
    expect(commitRefreshRefs(direct)).toEqual([
      { source: 'WORKSPACE', branch: 'release/1' },
    ]);
    expect(commitRefreshRefs(fork, { source: 'UPSTREAM', branch: 'master' })).toEqual([
      { source: 'UPSTREAM', branch: 'release/479' },
      { source: 'UPSTREAM', branch: 'master' },
    ]);
    expect(commitRefreshRefs(fork, { source: 'REMOTE', branch: 'master', remote: 'oss' })).toEqual([
      { source: 'UPSTREAM', branch: 'release/479' },
      { source: 'REMOTE', branch: 'master', remote: 'oss' },
    ]);
    expect(commitRefreshRefs(fork, { source: 'UPSTREAM', branch: 'release/479' })).toEqual([
      { source: 'UPSTREAM', branch: 'release/479' },
    ]);
  });

  it('reports the last completed refresh status', () => {
    expect(commitSyncLabel(status('FAILED'))).toBe('sync failed');
    expect(commitSyncLabel(status('IDLE'))).toBe('not synced yet');
  });

  it('reloads history only when a background refresh attempt completes', () => {
    const previousAttempt = '2026-08-25T08:00:00Z';

    expect(commitRefreshChanged(previousAttempt, {
      ...status('IDLE'),
      lastAttemptAt: previousAttempt,
      lastSucceededAt: previousAttempt,
    })).toBe(false);
    expect(commitRefreshChanged(previousAttempt, {
      ...status('IDLE'),
      lastAttemptAt: '2026-08-25T08:05:00Z',
      lastSucceededAt: '2026-08-25T08:05:00Z',
    })).toBe(true);
    expect(commitRefreshChanged(previousAttempt, {
      ...status('FAILED'),
      lastAttemptAt: '2026-08-25T08:05:00Z',
    })).toBe(true);
  });
});

const directSettings: Pick<WorkspaceSettingsDto, 'commitsTarget' | 'remotes'> = {
  remotes: [],
  commitsTarget: { remote: 'origin', branch: 'main', overridden: false },
};

function status(state: CommitRefreshStatusDto['state']): CommitRefreshStatusDto {
  return {
    workspaceId: 'workspace-id',
    state,
    lastAttemptAt: null,
    lastSucceededAt: null,
    error: state === 'FAILED' ? 'refresh failed' : null,
  };
}

function workspace(): WorkspaceDto {
  return {
    id: 'workspace-id',
    name: 'example/project',
    localRepositoryPath: '/managed/workspace-id/repository',
    defaultBranch: 'main',
    upstreamDefaultBranch: null,
    selectedRepositoryId: 100,
    selectedOwner: 'example',
    selectedRepo: 'project',
    upstreamRepositoryId: null,
    upstreamOwner: null,
    upstreamRepo: null,
    monitoredRepositoryId: 100,
    monitoredOwner: 'example',
    monitoredRepo: 'project',
    topology: 'DIRECT',
    lastCodeSyncAt: '2026-08-24T00:00:00Z',
    lastMetadataSyncAt: null,
    displayOrder: 0,
  };
}
