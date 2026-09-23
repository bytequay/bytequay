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
import { afterEach, describe, expect, it, vi } from 'vitest';
import { openGitHubReference, parseGitHubReference, pullRequestFromRoute, pullRequestRoute, resolveGitHubReference, ZOOM_REFERENCE } from './githubReference';

const issuePreview = {
  title: 'Kerberos Hive container startup times out',
  body: 'The container never starts.',
  author: 'octocat',
  labels: ['bug'],
  updatedAt: '2026-09-05T12:00:00Z',
  state: 'closed',
  pullRequest: false,
};

afterEach(() => { vi.unstubAllGlobals(); });

/** What a click asked the app to show over the page it came from. */
function zoomed(dispatchEvent: ReturnType<typeof vi.fn>) {
  return dispatchEvent.mock.calls
    .filter(([event]) => (event as Event).type === ZOOM_REFERENCE)
    .map(([event]) => (event as CustomEvent).detail);
}

describe('GitHub references', () => {
  it('recognizes issue and pull request links, including query strings', () => {
    expect(parseGitHubReference('https://github.com/owner/repo/issues/42?notification_referrer_id=1')).toMatchObject({
      kind: 'issue', owner: 'owner', repo: 'repo', number: 42,
    });
    expect(parseGitHubReference('https://github.com/owner/repo/pull/7#discussion')).toMatchObject({
      kind: 'pull', owner: 'owner', repo: 'repo', number: 7,
    });
    expect(parseGitHubReference('https://example.com/owner/repo/issues/42')).toBeNull();
  });

  it('round-trips a pull request detail route', () => {
    const route = pullRequestRoute({ owner: 'owner', repo: 'repo', number: 7 });
    expect(route).toBe('#/pulls/owner/repo/7');
    expect(pullRequestFromRoute(route)).toEqual({ repo: 'owner/repo', number: 7 });
  });

  it.each([false, true])('recognizes an issue URL for a PR when its preview is available: %s', async available => {
    const openInAppBrowser = vi.fn();
    const dispatchEvent = vi.fn();
    vi.stubGlobal('window', {
      bridge: {
        listWorkspaces: vi.fn().mockResolvedValue([]),
        fetchGitHubIssuePreview: available
          ? vi.fn().mockResolvedValue({ ...issuePreview, pullRequest: true })
          : vi.fn().mockRejectedValue(new Error('Offline')),
        fetchPullRequestDetailByRef: vi.fn().mockResolvedValue({ title: 'Stored pull request' }),
        openInAppBrowser,
      },
      dispatchEvent,
    });
    const reference = parseGitHubReference('https://github.com/owner/repo/issues/42');
    if (!reference) throw new Error('Expected a GitHub reference');

    expect(await resolveGitHubReference(reference)).toMatchObject({
      kind: 'pull', detail: { title: 'Stored pull request' },
    });
    await openGitHubReference(reference);

    expect(zoomed(dispatchEvent)).toEqual([{ kind: 'pull', repo: 'owner/repo', number: 42 }]);
    expect(openInAppBrowser).not.toHaveBeenCalled();
  });

  it('resolves a pull URL pointing to an issue and opens the issue URL', async () => {
    const bridge = {
      fetchPullRequestDetailByRef: vi.fn().mockRejectedValue(new Error('Not a pull request')),
      fetchGitHubIssuePreview: vi.fn().mockResolvedValue(issuePreview),
      openInAppBrowser: vi.fn(),
    };
    const dispatchEvent = vi.fn();
    vi.stubGlobal('window', { bridge, dispatchEvent });
    const reference = parseGitHubReference('https://github.com/trinodb/trino/pull/31252');
    if (!reference) throw new Error('Expected a GitHub reference');

    expect(await resolveGitHubReference(reference)).toMatchObject({
      kind: 'issue', issue: { title: issuePreview.title },
    });
    await openGitHubReference(reference);

    expect(bridge.fetchPullRequestDetailByRef).toHaveBeenCalledWith('trinodb/trino', 31252);
    expect(bridge.fetchGitHubIssuePreview).toHaveBeenCalledWith('trinodb', 'trino', 31252);
    expect(bridge.openInAppBrowser).toHaveBeenCalledWith('https://github.com/trinodb/trino/issues/31252');
    expect(zoomed(dispatchEvent)).toEqual([]);
  });

  it.each([[], [{ id: 'workspace-1', monitoredOwner: 'trinodb', monitoredRepo: 'trino' }]])(
    'loads an uncached issue independently of watched workspaces: %j', async workspaces => {
      const bridge = {
        listWorkspaces: vi.fn().mockResolvedValue(workspaces),
        fetchWorkspaceIssue: vi.fn().mockRejectedValue(new Error('Issue is not cached')),
        fetchGitHubIssuePreview: vi.fn().mockResolvedValue(issuePreview),
        fetchPullRequestDetailByRef: vi.fn(),
        openInAppBrowser: vi.fn(),
      };
      const dispatchEvent = vi.fn();
      vi.stubGlobal('window', { bridge, dispatchEvent });
      const reference = parseGitHubReference('https://github.com/trinodb/trino/issues/30611');
      if (!reference) throw new Error('Expected a GitHub reference');

      expect(await resolveGitHubReference(reference)).toEqual({
        kind: 'issue', workspaceId: null, issue: issuePreview,
      });
      expect(bridge.fetchGitHubIssuePreview).toHaveBeenCalledWith('trinodb', 'trino', 30611);
      expect(bridge.fetchPullRequestDetailByRef).not.toHaveBeenCalled();

      await openGitHubReference(reference);

      expect(bridge.openInAppBrowser).toHaveBeenCalledWith(reference.href);
      expect(zoomed(dispatchEvent)).toEqual([]);
    },
  );

  it('zooms a cached issue instead of opening its list', async () => {
    const bridge = {
      listWorkspaces: vi.fn().mockResolvedValue([{ id: 'workspace-1', monitoredOwner: 'Owner', monitoredRepo: 'Repo' }]),
      fetchWorkspaceIssue: vi.fn().mockResolvedValue({ ...issuePreview, state: undefined }),
      fetchGitHubIssuePreview: vi.fn(),
      fetchPullRequestDetailByRef: vi.fn(),
      openInAppBrowser: vi.fn(),
    };
    const dispatchEvent = vi.fn();
    vi.stubGlobal('window', { bridge, dispatchEvent });
    const reference = parseGitHubReference('https://github.com/owner/repo/issues/42');
    if (!reference) throw new Error('Expected a GitHub reference');

    expect(await resolveGitHubReference(reference)).toMatchObject({
      kind: 'issue', workspaceId: 'workspace-1', issue: { state: 'open' },
    });
    await openGitHubReference(reference);

    expect(zoomed(dispatchEvent)).toEqual([
      { kind: 'issue', repo: 'owner/repo', number: 42, workspaceId: 'workspace-1' },
    ]);
    expect(bridge.fetchGitHubIssuePreview).not.toHaveBeenCalled();
    expect(bridge.fetchPullRequestDetailByRef).not.toHaveBeenCalled();
    expect(bridge.openInAppBrowser).not.toHaveBeenCalled();
  });

  it('leaves unavailable issue links usable', async () => {
    const bridge = {
      listWorkspaces: vi.fn().mockRejectedValue(new Error('Workspace lookup failed')),
      fetchGitHubIssuePreview: vi.fn().mockRejectedValue(new Error('Issue unavailable')),
      fetchPullRequestDetailByRef: vi.fn().mockRejectedValue(new Error('Not a pull request')),
      openInAppBrowser: vi.fn(),
    };
    const dispatchEvent = vi.fn();
    vi.stubGlobal('window', { bridge, dispatchEvent });
    const reference = parseGitHubReference('https://github.com/owner/repo/issues/42');
    if (!reference) throw new Error('Expected a GitHub reference');

    expect(await resolveGitHubReference(reference)).toBeNull();
    expect(bridge.fetchGitHubIssuePreview).toHaveBeenCalledWith('owner', 'repo', 42);
    await openGitHubReference(reference);

    expect(bridge.openInAppBrowser).toHaveBeenCalledWith(reference.href);
    expect(zoomed(dispatchEvent)).toEqual([]);
  });
});
