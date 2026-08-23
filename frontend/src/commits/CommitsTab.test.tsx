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
  it('shows the Settings control when no upstream workspace is linked', () => {
    const html = renderToStaticMarkup(<CommitsTab
      workspace={workspace()}
      onOpenPullRequest={() => {}}
      onOpenSettings={() => {}}
    />);

    expect(html).toContain('No upstream linked');
    expect(html).toContain('Link another watched workspace');
    expect(html).toContain('cmt-readonly">Settings');
  });

  it('opens on the upstream browser when asked to start there', () => {
    const linked: Workspace = {
      ...workspace(),
      upstreamDefaultBranch: 'main',
      upstreamRemote: 'upstream',
      upstreamWorkspaceId: 'upstream-workspace',
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

    expect(html).toContain('cmt-repo-chip is-active" title="Remote source');
    expect(html).toContain('cmt-repo-chip" title="This workspace');
  });

  it('refreshes the workspace and only an explicitly linked upstream', () => {
    expect(commitRefreshRefs('feature', 'main', false)).toEqual([
      { source: 'WORKSPACE', branch: 'feature' },
    ]);
    expect(commitRefreshRefs('feature', 'release', true)).toEqual([
      { source: 'WORKSPACE', branch: 'feature' },
      { source: 'UPSTREAM', branch: 'release' },
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
    selectedOwner: 'example',
    selectedRepo: 'project',
    upstreamWorkspaceId: null,
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
