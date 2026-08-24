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
import type { PullRequestDetailDto, RepositoryIssueSnapshotDto } from './types';
import { workspaceIssueRoute } from './workspaces/model';

export type GitHubReference = {
  kind: 'issue' | 'pull';
  owner: string;
  repo: string;
  number: number;
  href: string;
};

export type LocalGitHubReference =
  | { kind: 'pull'; detail: PullRequestDetailDto }
  | { kind: 'issue'; workspaceId: string; issue: RepositoryIssueSnapshotDto['issues'][number] };

export function parseGitHubReference(href: string): GitHubReference | null {
  try {
    const url = new URL(href);
    if (!['github.com', 'www.github.com'].includes(url.hostname.toLowerCase())) return null;
    const match = /^\/([^/]+)\/([^/]+)\/(issues|pull)\/(\d+)\/?$/.exec(url.pathname);
    if (!match || Number(match[4]) < 1) return null;
    return {
      kind: match[3] === 'pull' ? 'pull' : 'issue',
      owner: decodeURIComponent(match[1]),
      repo: decodeURIComponent(match[2]),
      number: Number(match[4]),
      href: url.toString(),
    };
  }
  catch {
    return null;
  }
}

export function pullRequestRoute(reference: Pick<GitHubReference, 'owner' | 'repo' | 'number'>): string {
  return `#/pulls/${encodeURIComponent(reference.owner)}/${encodeURIComponent(reference.repo)}/${reference.number}`;
}

export function pullRequestFromRoute(hash: string): { repo: string; number: number } | null {
  const match = /^#\/pulls\/([^/]+)\/([^/]+)\/(\d+)$/.exec(hash);
  if (!match || Number(match[3]) < 1) return null;
  try {
    return { repo: `${decodeURIComponent(match[1])}/${decodeURIComponent(match[2])}`, number: Number(match[3]) };
  }
  catch {
    return null;
  }
}

export async function findLocalIssue(reference: GitHubReference): Promise<{
  workspaceId: string;
  issue: RepositoryIssueSnapshotDto['issues'][number];
} | null> {
  const slug = `${reference.owner}/${reference.repo}`.toLowerCase();
  const workspaces = await window.bridge.listWorkspaces();
  const workspace = workspaces.find(candidate =>
    `${candidate.monitoredOwner}/${candidate.monitoredRepo}`.toLowerCase() === slug);
  if (!workspace) return null;
  try {
    return {
      workspaceId: workspace.id,
      issue: await window.bridge.fetchWorkspaceIssue(workspace.id, reference.number),
    };
  }
  catch {
    return null;
  }
}

export async function resolveLocalGitHubReference(reference: GitHubReference): Promise<LocalGitHubReference | null> {
  if (reference.kind === 'issue') {
    const localIssue = await findLocalIssue(reference);
    if (localIssue) return { kind: 'issue', ...localIssue };
  }
  try {
    return {
      kind: 'pull',
      detail: await window.bridge.fetchPullRequestDetailByRef(`${reference.owner}/${reference.repo}`, reference.number),
    };
  }
  catch {
    return null;
  }
}

export async function openGitHubReference(reference: GitHubReference): Promise<void> {
  if (reference.kind === 'pull') {
    window.location.hash = pullRequestRoute(reference);
    return;
  }
  const local = await resolveLocalGitHubReference(reference).catch((): null => null);
  if (local?.kind === 'pull') {
    window.location.hash = pullRequestRoute(reference);
    return;
  }
  if (local?.kind === 'issue') {
    window.location.hash = workspaceIssueRoute(local.workspaceId, reference.number);
    return;
  }
  await window.bridge.openInAppBrowser(reference.href);
}
