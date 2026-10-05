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
import { openActivityLink, openGitHubReference, openWebLink, parseGitHubReference, pullRequestFromRoute, pullRequestRoute, resolveGitHubReference, ZOOM_REFERENCE } from './githubReference';
import { jiraTicketLink } from './jira/contracts';

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

  it.each([false, true])(
    'zooms an uncached issue when its repository has a workspace: %s', async watched => {
      const workspaces = watched ? [{ id: 'workspace-1', monitoredOwner: 'trinodb', monitoredRepo: 'trino' }] : [];
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
        kind: 'issue', workspaceId: workspaces[0]?.id ?? null, issue: issuePreview,
      });
      expect(bridge.fetchGitHubIssuePreview).toHaveBeenCalledWith('trinodb', 'trino', 30611);
      expect(bridge.fetchPullRequestDetailByRef).not.toHaveBeenCalled();

      await openGitHubReference(reference);

      if (workspaces.length > 0) {
        expect(bridge.openInAppBrowser).not.toHaveBeenCalled();
        expect(zoomed(dispatchEvent)).toEqual([{ kind: 'issue', repo: 'trinodb/trino', number: 30611, workspaceId: 'workspace-1' }]);
      }
      else {
        expect(bridge.openInAppBrowser).toHaveBeenCalledWith(reference.href);
        expect(zoomed(dispatchEvent)).toEqual([]);
      }
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

describe('Home activity links', () => {
  it.each(['pull', 'issues'])('zooms a local PR from a %s link without leaving Home', async kind => {
    const bridge = {
      findStoredPullRequest: vi.fn().mockResolvedValue({ repo: 'owner/repo', number: 42 }),
      fetchPullRequestDetailByRef: vi.fn(),
      openInAppBrowser: vi.fn(),
    };
    const dispatchEvent = vi.fn();
    const location = { hash: '#/home' };
    vi.stubGlobal('window', { bridge, dispatchEvent, location });

    await openActivityLink(`https://github.com/owner/repo/${kind}/42?notification=1#discussion`);

    expect(bridge.findStoredPullRequest).toHaveBeenCalledWith('owner/repo', 42);
    expect(zoomed(dispatchEvent)).toEqual([{ kind: 'pull', repo: 'owner/repo', number: 42 }]);
    expect(location.hash).toBe('#/home');
    expect(bridge.openInAppBrowser).not.toHaveBeenCalled();
    expect(bridge.fetchPullRequestDetailByRef).not.toHaveBeenCalled();
  });

  it.each([
    'https://github.com/owner/repo',
    'https://github.com/owner/repo/commits',
    'https://github.com/owner/repo/commit/abc123',
    'https://github.com/owner/repo/pull/42',
    'https://github.com/owner/repo/issues/42',
    'https://github.com/owner',
  ])('opens an activity without a local PR in the browser: %s', async href => {
    const bridge = { findStoredPullRequest: vi.fn().mockResolvedValue(null), openInAppBrowser: vi.fn() };
    const dispatchEvent = vi.fn();
    const location = { hash: '#/home' };
    vi.stubGlobal('window', { bridge, dispatchEvent, location });

    await openActivityLink(href);

    expect(bridge.openInAppBrowser).toHaveBeenCalledWith(href);
    expect(bridge.findStoredPullRequest).toHaveBeenCalledTimes(parseGitHubReference(href) ? 1 : 0);
    expect(zoomed(dispatchEvent)).toEqual([]);
    expect(location.hash).toBe('#/home');
  });

  it('opens the original link when the local lookup fails', async () => {
    const bridge = { findStoredPullRequest: vi.fn().mockRejectedValue(new Error('Unavailable')), openInAppBrowser: vi.fn() };
    const dispatchEvent = vi.fn();
    vi.stubGlobal('window', { bridge, dispatchEvent });
    const href = 'https://github.com/owner/repo/pull/42#discussion';

    await openActivityLink(href);

    expect(bridge.openInAppBrowser).toHaveBeenCalledWith(href);
    expect(zoomed(dispatchEvent)).toEqual([]);
  });
});

describe('Jira ticket links', () => {
  const site = { id: 'cloud', name: 'Engineering', url: 'https://acme.atlassian.net/' };
  const connection = { id: 'connection', state: 'connected', sites: [site] };

  it('recognizes a browse link by its site and key', () => {
    expect(jiraTicketLink('https://acme.atlassian.net/browse/eng-7?focusedCommentId=3')).toEqual({ origin: 'https://acme.atlassian.net', key: 'ENG-7' });
    expect(jiraTicketLink('https://acme.atlassian.net/browse/ENG')).toBeNull();
    expect(jiraTicketLink('http://acme.atlassian.net/browse/ENG-7')).toBeNull();
    expect(jiraTicketLink('https://acme.atlassian.net/jira/software/projects/ENG/boards/1')).toBeNull();
  });

  it('opens a connected-site ticket natively by the issue id its key names now', async () => {
    const bridge = {
      listJiraConnections: vi.fn().mockResolvedValue([{ ...connection, id: 'pending', state: 'pending' }, connection]),
      refreshJiraIssueByKey: vi.fn().mockResolvedValue({ id: '10042', key: 'ENG-7', connectionId: 'connection', cloudId: 'cloud' }),
      openInAppBrowser: vi.fn(),
    };
    const dispatchEvent = vi.fn();
    vi.stubGlobal('window', { bridge, dispatchEvent });

    await openWebLink('https://acme.atlassian.net/browse/ENG-7');

    expect(bridge.refreshJiraIssueByKey).toHaveBeenCalledWith('connection', 'cloud', 'ENG-7');
    expect(zoomed(dispatchEvent)).toEqual([{ kind: 'jira', connectionId: 'connection', cloudId: 'cloud', issueId: '10042' }]);
    expect(bridge.openInAppBrowser).not.toHaveBeenCalled();
  });

  it.each([
    ['another site', 'https://other.atlassian.net/browse/ENG-7', vi.fn()],
    ['an unreadable ticket', 'https://acme.atlassian.net/browse/ENG-7', vi.fn().mockRejectedValue(new Error('Not found'))],
    ['any other page', 'https://example.com/docs', vi.fn()],
  ])('keeps %s a web page', async (_name, href, refreshJiraIssueByKey) => {
    const bridge = { listJiraConnections: vi.fn().mockResolvedValue([connection]), refreshJiraIssueByKey, openInAppBrowser: vi.fn() };
    const dispatchEvent = vi.fn();
    vi.stubGlobal('window', { bridge, dispatchEvent });

    await openWebLink(href);

    expect(bridge.openInAppBrowser).toHaveBeenCalledWith(href);
    expect(zoomed(dispatchEvent)).toEqual([]);
  });
});
