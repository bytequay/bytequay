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
import { setCached } from '../dataCache';
import type { WorkspaceDto } from '../types';
import WorkspacesPage from './WorkspacesPage';

describe('workspaces page', () => {
  it('paints the last lists on a return visit and no empty workspace state while the first load runs', () => {
    const cold = renderToStaticMarkup(<WorkspacesPage onOpen={() => {}} />);
    expect(cold).toContain('Loading workspaces…');
    expect(cold).not.toContain('Watch a repository');
    expect(cold).not.toContain('<span>0</span>');

    setCached('workspaces:watched', [workspace('watched-id')]);
    setCached('workspaces:retired', [workspace('retired-id')]);
    const seeded = renderToStaticMarkup(<WorkspacesPage onOpen={() => {}} />);
    expect(seeded).not.toContain('Loading workspaces…');
    expect(seeded).toContain('Watched <span>1</span>');
    expect(seeded).toContain('Retired <span>1</span>');
    expect(seeded).toContain('data-workspace-id="watched-id"');
  });
});

function workspace(id: string): WorkspaceDto {
  return {
    id,
    name: 'octocat/project',
    selectedRepositoryId: 100,
    selectedOwner: 'octocat',
    selectedRepo: 'project',
    upstreamRepositoryId: null,
    upstreamOwner: null,
    upstreamRepo: null,
    monitoredRepositoryId: 100,
    monitoredOwner: 'octocat',
    monitoredRepo: 'project',
    topology: 'DIRECT',
    defaultBranch: 'main',
    upstreamDefaultBranch: null,
    localRepositoryPath: '/managed/project',
    lastCodeSyncAt: null,
    lastMetadataSyncAt: null,
    displayOrder: 0,
  };
}
