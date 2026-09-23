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
import type { GitHubIssuePreviewDto, PullRequestDetailDto, RepositoryIssueSnapshotDto } from './types';

export type GitHubReference = {
  kind: 'issue' | 'pull';
  owner: string;
  repo: string;
  number: number;
  href: string;
};

export type GitHubReferencePreview =
  | { kind: 'pull'; detail: PullRequestDetailDto }
  | { kind: 'issue'; workspaceId: string | null; issue: Omit<GitHubIssuePreviewDto, 'pullRequest'> };

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

export function referenceHref(reference: GitHubReference, kind: GitHubReference['kind']): string {
  return kind === reference.kind
    ? reference.href
    : `https://github.com/${reference.owner}/${reference.repo}/${kind === 'pull' ? 'pull' : 'issues'}/${reference.number}`;
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

async function findLocalIssue(reference: GitHubReference): Promise<{
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

export async function resolveGitHubReference(reference: GitHubReference): Promise<GitHubReferencePreview | null> {
  if (reference.kind === 'issue') {
    const localIssue = await findLocalIssue(reference).catch((): null => null);
    if (localIssue) return { kind: 'issue', workspaceId: localIssue.workspaceId, issue: { ...localIssue.issue, state: 'open' } };
    try {
      const issue = await window.bridge.fetchGitHubIssuePreview(reference.owner, reference.repo, reference.number);
      if (!issue.pullRequest) return { kind: 'issue', workspaceId: null, issue };
    }
    catch {
      // An issue-form URL can still refer to a cached pull request when GitHub is unavailable.
    }
  }
  try {
    return {
      kind: 'pull',
      detail: await window.bridge.fetchPullRequestDetailByRef(`${reference.owner}/${reference.repo}`, reference.number),
    };
  }
  catch {
    // A PR-shaped URL can point at an issue. GitHub's issues endpoint identifies both kinds.
    if (reference.kind === 'pull') {
      try {
        const issue = await window.bridge.fetchGitHubIssuePreview(reference.owner, reference.repo, reference.number);
        if (!issue.pullRequest) return { kind: 'issue', workspaceId: null, issue };
      }
      catch {
        // Keep the link usable when GitHub cannot provide either preview.
      }
    }
    return null;
  }
}

export const ZOOM_REFERENCE = 'bytequay:zoom-reference';

export type ZoomedReference =
  | { kind: 'pull'; repo: string; number: number; workspaceId?: string }
  | { kind: 'issue'; repo: string; number: number; workspaceId: string };

/**
 * Shows one issue or pull request over the page that asked for it. Every surface that opens a
 * reference goes through here, so a reference never replaces the page with a list to find it in.
 */
export function zoomReference(target: ZoomedReference): void {
  window.dispatchEvent(new CustomEvent(ZOOM_REFERENCE, { detail: target }));
}

export async function openGitHubReference(reference: GitHubReference, preview?: GitHubReferencePreview | null): Promise<void> {
  const repo = `${reference.owner}/${reference.repo}`;
  const resolved = preview ?? await resolveGitHubReference(reference).catch((): null => null);
  const kind = resolved?.kind ?? reference.kind;
  if (kind === 'pull') {
    zoomReference({ kind: 'pull', repo, number: reference.number });
    return;
  }
  // Only a watched repository has an issue the app can show; anything else stays a web page.
  if (resolved?.kind === 'issue' && resolved.workspaceId) {
    zoomReference({ kind: 'issue', repo, number: reference.number, workspaceId: resolved.workspaceId });
    return;
  }
  await window.bridge.openInAppBrowser(referenceHref(reference, kind));
}
