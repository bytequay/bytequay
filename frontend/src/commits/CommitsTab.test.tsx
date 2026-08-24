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
import type { CommitRefreshStatusDto } from '../types';
import type { Workspace } from '../workspaces/model';
import CommitsTab, {
  commitRefreshRefs,
  commitSyncLabel,
  selectableCommitSources,
} from './CommitsTab';

describe('workspace commits tab', () => {
  it('keeps the workspace branch separate from the remote source switcher', () => {
    const html = renderToStaticMarkup(<CommitsTab
      workspace={workspace()}
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

  it('opens on the upstream browser when asked to start there', () => {
    const linked: Workspace = {
      ...workspace(),
      upstreamDefaultBranch: 'main',
      upstreamRemote: 'upstream',
      upstreamRepositoryId: 200,
      upstreamOwner: 'upstream',
      upstreamRepo: 'project',
      topology: 'FORK',
    };
    const html = renderToStaticMarkup(<CommitsTab
      workspace={linked}
      initialRepo="upstream"
      onOpenPullRequest={() => {}}
      onOpenSettings={() => {}}
    />);

    expect(html).toContain('cmt-split-chip is-active');
    expect(html).toContain('title="View the selected remote source"');
    expect(html).toContain('cmt-repo-chip" title="This workspace');
    expect(html).toContain('Source: upstream');
    expect(html).toContain('title="Branch of the selected source"');
    expect(html).toContain('read-only');
  });

  it('refreshes the workspace, a linked upstream, and the browsed remote source', () => {
    expect(commitRefreshRefs('feature', 'main', false)).toEqual([
      { source: 'WORKSPACE', branch: 'feature' },
    ]);
    expect(commitRefreshRefs('feature', 'release', true)).toEqual([
      { source: 'WORKSPACE', branch: 'feature' },
      { source: 'UPSTREAM', branch: 'release' },
    ]);
    expect(commitRefreshRefs('feature', 'release', true, { name: 'oss', branch: 'master' })).toEqual([
      { source: 'WORKSPACE', branch: 'feature' },
      { source: 'UPSTREAM', branch: 'release' },
      { source: 'REMOTE', branch: 'master', remote: 'oss' },
    ]);
  });

  it('reports the last completed refresh status', () => {
    expect(commitSyncLabel(status('FAILED'))).toBe('sync failed');
    expect(commitSyncLabel(status('IDLE'))).toBe('not synced yet');
  });
});

function status(state: CommitRefreshStatusDto['state']): CommitRefreshStatusDto {
  return {
    workspaceId: 'workspace-id',
    state,
    lastAttemptAt: null,
    lastSucceededAt: null,
    error: state === 'FAILED' ? 'refresh failed' : null,
  };
}

function workspace(): Workspace {
  return {
    id: 'workspace-id',
    name: 'example/project',
    path: '/managed/workspace-id/repository',
    defaultBranch: 'main',
    upstreamDefaultBranch: null,
    originRemote: 'origin',
    upstreamRemote: null,
    selectedRepositoryId: 100,
    selectedOwner: 'example',
    selectedRepo: 'project',
    upstreamRepositoryId: null,
    upstreamOwner: null,
    upstreamRepo: null,
    monitoredOwner: 'example',
    monitoredRepo: 'project',
    topology: 'DIRECT',
    lastCodeSyncAt: '2026-08-24T00:00:00Z',
    lastMetadataSyncAt: null,
    displayOrder: 0,
  };
}
