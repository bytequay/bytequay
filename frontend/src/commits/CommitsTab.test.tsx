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
  isCommitRefreshActive,
} from './CommitsTab';

describe('workspace commits tab', () => {
  it('always shows the remote source switcher, defaulting to origin', () => {
    const html = renderToStaticMarkup(<CommitsTab
      workspace={workspace()}
      onOpenPullRequest={() => {}}
      onOpenSettings={() => {}}
    />);

    expect(html).toContain('Source: origin');
    expect(html).toContain('Remote source — history and cherry-pick read from here');
    expect(html).not.toContain('No upstream linked');
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
    expect(html).toContain('title="Remote source');
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

  it('polls only active manual refresh states and reports durable status', () => {
    expect(isCommitRefreshActive(status('QUEUED'))).toBe(true);
    expect(isCommitRefreshActive(status('RUNNING'))).toBe(true);
    expect(isCommitRefreshActive(status('IDLE'))).toBe(false);
    expect(isCommitRefreshActive(status('FAILED'))).toBe(false);
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
    codeState: 'READY',
    metadataState: 'READY',
    codeError: null,
    metadataError: null,
    lastCodeSyncAt: null,
    lastMetadataSyncAt: null,
    displayOrder: 0,
  };
}
