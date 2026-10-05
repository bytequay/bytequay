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
import { jiraIssueLocation, jiraTicketLink, type JiraLocation } from './jira/contracts';
import type { GitHubIssuePreviewDto, PullRequestDetailDto } from './types';

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

async function findIssueWorkspace(reference: GitHubReference): Promise<string | null> {
  const slug = `${reference.owner}/${reference.repo}`.toLowerCase();
  const workspaces = await window.bridge.listWorkspaces();
  const workspace = workspaces.find(candidate =>
    `${candidate.monitoredOwner}/${candidate.monitoredRepo}`.toLowerCase() === slug);
  return workspace?.id ?? null;
}

export async function resolveGitHubReference(reference: GitHubReference): Promise<GitHubReferencePreview | null> {
  if (reference.kind === 'issue') {
    const workspaceId = await findIssueWorkspace(reference).catch((): null => null);
    const localIssue = workspaceId === null ? null : await window.bridge.fetchWorkspaceIssue(workspaceId, reference.number).catch((): null => null);
    if (localIssue) return { kind: 'issue', workspaceId, issue: { ...localIssue, state: 'open' } };
    try {
      const issue = await window.bridge.fetchGitHubIssuePreview(reference.owner, reference.repo, reference.number);
      if (!issue.pullRequest) return { kind: 'issue', workspaceId, issue };
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
        if (!issue.pullRequest) return { kind: 'issue', workspaceId: await findIssueWorkspace(reference).catch((): null => null), issue };
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
  | { kind: 'issue'; repo: string; number: number; workspaceId: string }
  /**
   * `start` also opens the development composer over the ticket, with `draft` as its instruction when given; `replaces` names
   * the ticket's ended task that composer starts another attempt after.
   */
  | ({ kind: 'jira'; start?: boolean; draft?: string; replaces?: string } & JiraLocation);

/**
 * Shows one issue, pull request or Jira ticket over the page or layer that asked for it. Every
 * surface that opens a reference goes through here, so a reference never replaces the page with a
 * list to find it in.
 */
export function zoomReference(target: ZoomedReference): void {
  window.dispatchEvent(new CustomEvent(ZOOM_REFERENCE, { detail: target }));
}

/** Activity opens a stored PR over Home; every other target stays a web page. */
export async function openActivityLink(href: string): Promise<void> {
  const reference = parseGitHubReference(href);
  const stored = reference === null ? null : await window.bridge
    .findStoredPullRequest(`${reference.owner}/${reference.repo}`, reference.number).catch((): null => null);
  if (stored) zoomReference({ kind: 'pull', repo: stored.repo, number: stored.number });
  else await window.bridge.openInAppBrowser(href);
}

export async function openGitHubReference(reference: GitHubReference, preview?: GitHubReferencePreview | null): Promise<void> {
  const repo = `${reference.owner}/${reference.repo}`;
  const resolved = preview ?? await resolveGitHubReference(reference).catch((): null => null);
  const kind = resolved?.kind ?? reference.kind;
  if (kind === 'pull') {
    zoomReference({ kind: 'pull', repo, number: reference.number });
    return;
  }
  // Watched repositories can show cached and uncached issues in the same zoom.
  if (resolved?.kind === 'issue' && resolved.workspaceId) {
    zoomReference({ kind: 'issue', repo, number: reference.number, workspaceId: resolved.workspaceId });
    return;
  }
  await window.bridge.openInAppBrowser(referenceHref(reference, kind));
}

/**
 * Opens a link from rendered text. A ticket on a connected Jira site opens natively over the page;
 * anything else, including a ticket no connected account can read, stays a web page.
 */
export async function openWebLink(href: string): Promise<void> {
  const ticket = jiraTicketLink(href);
  const location = ticket === null ? null : await jiraTicketLocation(ticket.origin, ticket.key).catch((): null => null);
  if (location !== null) zoomReference({ kind: 'jira', ...location });
  else await window.bridge.openInAppBrowser(href);
}

async function jiraTicketLocation(origin: string, key: string): Promise<JiraLocation | null> {
  for (const connection of await window.bridge.listJiraConnections()) {
    const site = connection.state === 'connected' ? connection.sites.find(candidate => new URL(candidate.url).origin === origin) : undefined;
    if (site) return jiraIssueLocation(await window.bridge.refreshJiraIssueByKey(connection.id, site.id, key));
  }
  return null;
}
