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

import type { WorkspaceDto } from '../../types';

export function workspaceFixture(overrides: Partial<WorkspaceDto> = {}): WorkspaceDto {
  return {
    id: 'workspace-id',
    name: 'contributor/project',
    selectedRepositoryId: 100,
    selectedOwner: 'contributor',
    selectedRepo: 'project',
    upstreamRepositoryId: null,
    upstreamOwner: null,
    upstreamRepo: null,
    monitoredRepositoryId: 100,
    monitoredOwner: 'contributor',
    monitoredRepo: 'project',
    topology: 'DIRECT',
    defaultBranch: 'main',
    upstreamDefaultBranch: null,
    localRepositoryPath: '/managed/workspace-id/repository',
    lastCodeSyncAt: null,
    lastMetadataSyncAt: null,
    displayOrder: 0,
    ...overrides,
  };
}
