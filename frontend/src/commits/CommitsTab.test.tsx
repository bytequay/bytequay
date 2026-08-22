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
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Workspace } from '../workspaces/model';
import CommitsTab from './CommitsTab';

describe('workspace commits tab', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('shows the Settings control when no upstream workspace is linked', () => {
    vi.stubGlobal('localStorage', { getItem: () => null, setItem: () => {} });

    const html = renderToStaticMarkup(<CommitsTab
      workspace={workspace()}
      onOpenPullRequest={() => {}}
      onOpenSettings={() => {}}
    />);

    expect(html).toContain('No upstream linked');
    expect(html).toContain('Link another watched workspace');
    expect(html).toContain('cmt-readonly">Settings');
  });
});

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
