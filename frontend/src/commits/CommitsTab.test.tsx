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
import { readFileSync, readdirSync } from 'node:fs';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { workspaceFixture } from '../workspaces/fixtures/workspace';
import type { CommitRefreshStatusDto, WorkspaceDto, WorkspaceRelationshipsDto, WorkspaceSettingsDto } from '../types';
import CommitsTab, {
  CommitsBrowser,
  commitRefreshChanged,
  commitRefreshRefs,
  commitSources,
  commitSyncLabel,
  commitTargetRef,
  firstCommitSource,
} from './CommitsTab';
import { lineRepos } from './model';
import { typedPullRequestNumber } from './PullRequestsView';

function renderCommits(props: Partial<Parameters<typeof CommitsBrowser>[0]> = {}): string {
  return renderToStaticMarkup(<CommitsBrowser workspace={workspace()} settings={directSettings} repos={lineRepos(workspace(), noRelations, [])}
    onSourceSelectionChange={() => {}} onOpenPullRequest={() => {}} onOpenSettings={() => {}} {...props} />);
}

describe('workspace commits tab', () => {
  it('waits for the saved target before reading history or offering cherry-pick', () => {
    const html = renderToStaticMarkup(<CommitsTab workspace={workspace()}
      onSourceSelectionChange={() => {}} onOpenPullRequest={() => {}} onOpenSettings={() => {}} />);

    expect(html).toContain('Loading commits target…');
    expect(html).not.toContain('cmt-line');
    expect(html).not.toContain('Cherry pick');
  });

  it('shows this workspace alone and points to Related workspaces when nothing is upstream', () => {
    const html = renderCommits();

    expect(html).toContain('aria-label="Repo line"');
    expect(html).toMatch(/class="cmt-line-repo is-active" aria-pressed="true" title="example\/project · this workspace"/);
    expect(html).toContain('No upstream repository to pick commits from');
    expect(html).toContain('Link one in Related workspaces');
    expect(html).not.toContain('cmt-pill__caption');
    expect(html).not.toContain('aria-label="List"');
  });

  it('browses an upstream repository of the line while keeping a separate destination', () => {
    const html = renderCommits({ repos: line(), initialSourceSelection: { source: 'backport-1' } });

    expect(html).toMatch(/class="cmt-line-repo is-active" aria-pressed="true" title="Pick commits from trino\/oss">/);
    expect(html).toContain('title="Read history from trino/oss · master"');
    expect(html).toContain('cmt-pill__label cmt-mono">master</span>');
    expect(html).toContain('<span class="cmt-pill__caption">into</span><span class="cmt-pill__label cmt-mono">project · main</span>');
    expect(html).toContain('title="Product takes changes from example/project · open its Commits"');
    expect(html).toContain('No commits selected');
    expect(html).toContain('Select range');
    expect(html).not.toContain('Ranges are picked on the source repo');
    expect(html).toContain('aria-label="List"');
    expect(html).toContain('>Pull requests</button>');
  });

  it('reads a typed pull request number or a link to the browsed repository', () => {
    expect(typedPullRequestNumber('#28411', 'trino/oss')).toBe(28411);
    expect(typedPullRequestNumber(' 12 ', 'trino/oss')).toBe(12);
    expect(typedPullRequestNumber('https://github.com/Trino/OSS/pull/7/files', 'trino/oss')).toBe(7);
    expect(typedPullRequestNumber('https://github.com/other/repo/pull/7', 'trino/oss')).toBeNull();
    expect(typedPullRequestNumber('fix #12', 'trino/oss')).toBeNull();
  });

  it('opens the repository just upstream when asked to pick commits', () => {
    const html = renderCommits({ repos: line(), initialRepo: 'upstream' });

    expect(html).toMatch(/class="cmt-line-repo is-active" aria-pressed="true" title="Pick commits from acme\/internal">/);
  });

  it('reads from the line, then another branch of this repository', () => {
    const repos = line();

    expect(commitSources(repos, true)).toEqual(['backport-1', 'backport-2', 'upstream']);
    expect(firstCommitSource(repos, true)).toBe('backport-2');
    expect(firstCommitSource(lineRepos(workspace(), noRelations, []), true)).toBe('upstream');
    expect(firstCommitSource(lineRepos(workspace(), noRelations, []), false)).toBeNull();
    // A remembered source the line no longer names shows this workspace instead.
    expect(renderCommits({ repos, initialSourceSelection: { source: 'removed' } }))
      .toMatch(/class="cmt-line-repo is-active" aria-pressed="true" title="example\/project · this workspace"/);
  });

  it.each([false, true])('uses the branch control for the fork destination (override=%s)', overridden => {
    const linked: WorkspaceDto = {
      ...workspace(),
      upstreamDefaultBranch: 'master',
      upstreamRepositoryId: 200,
      upstreamOwner: 'upstream',
      upstreamRepo: 'project',
      topology: 'FORK',
    };
    const targetBranch = overridden ? 'release/479' : 'master';
    const html = renderCommits({
      workspace: linked,
      repos: lineRepos(linked, noRelations, []),
      settings: { commitsTarget: { remote: 'upstream', branch: targetBranch, overridden } },
    });

    expect(html).toContain(`title="Cherry-pick target: upstream/project · upstream/${targetBranch}"`);
    // The pill names the destination repo, so the branch needs no remote prefix.
    expect(html).toContain(`cmt-pill__label cmt-mono">project · ${targetBranch}</span>`);
    expect(html).toContain('Ranges are picked on the source repo');
    expect(html).toContain('Pick commits from another branch');
    expect(html).not.toContain('Check out');
  });

  it('uses the existing Lucide icons throughout the commit toolbar', () => {
    const html = renderCommits({ repos: line() });

    expect(html).toContain('lucide-refresh-cw');
    expect(html).toContain('lucide-search');
    expect(html).toContain('lucide-git-branch');
    expect(html).toContain('lucide-git-fork');
    expect(html).toContain('lucide-arrow-right');
    expect(html).toContain('lucide-arrow-up-right');
    expect(html).toContain('lucide-chevron-down');
    expect(html).toContain('aria-label="Sync commit history"');
    expect(html).toContain('aria-label="Search commits by pull request title, number, or SHA"');
    expect(html).toContain('placeholder="Search title, SHA, PR title or #number"');
    // The sync state reads beside the button instead of only in its tooltip.
    expect(html).toContain('not synced yet</span>');
    // Every affordance in the bar and the strip is a Lucide icon, never a glyph.
    expect(html).not.toMatch(/[⑂⑃⇅⇲▾▼✓→]/);
  });

  it.each([
    { initialSourceSelection: { source: null } },
    { initialSourceSelection: { source: 'upstream' } },
    { initialRepo: 'workspace' as const },
  ])('keeps this workspace selected for its own branches: %j', selection => {
    const linked = { ...workspace(), upstreamRepositoryId: 200, upstreamDefaultBranch: 'master', topology: 'FORK' as const };
    const html = renderCommits({
      workspace: linked,
      repos: lineRepos(linked, noRelations, []),
      settings: { commitsTarget: { remote: 'upstream', branch: 'master', overridden: false } },
      ...selection,
    });

    expect(html).toMatch(/class="cmt-line-repo is-active" aria-pressed="true" title="example\/project · this workspace"/);
  });

  it('uses the direct repository target branch without an upstream prefix', () => {
    const html = renderCommits({
      settings: { commitsTarget: { remote: 'origin', branch: 'release/1', overridden: true } },
    });

    expect(html).toContain('title="Cherry-pick target: example/project · release/1"');
    expect(html).toContain('cmt-pill__label cmt-mono">project · release/1</span>');
    expect(html).not.toContain('upstream/');
  });

  it('picks from another branch of the linked upstream while retaining a separate destination', () => {
    const linked = { ...workspace(), upstreamRepositoryId: 200, upstreamDefaultBranch: 'master', topology: 'FORK' as const };
    const html = renderCommits({
      workspace: linked,
      repos: lineRepos(linked, noRelations, []),
      settings: { commitsTarget: { remote: 'upstream', branch: 'release/479', overridden: true } },
      initialRepo: 'upstream',
    });

    expect(html).toContain('cmt-pill__label cmt-mono">master</span>');
    expect(html).toContain('cmt-pill__label cmt-mono">example/project · release/479</span>');
    // Selection lives on the source side only, and starts empty.
    expect(html).toContain('No commits selected');
    expect(html).toContain('Select range');
    expect(html).not.toContain('Ranges are picked on the source repo');
  });

  it('reads and refreshes the target separately from the selected source', () => {
    const direct = { remote: 'origin' as const, branch: 'release/1', overridden: true };
    const fork = { remote: 'upstream' as const, branch: 'release/479', overridden: true };
    expect(commitTargetRef(direct)).toEqual({ source: 'WORKSPACE', branch: 'release/1' });
    expect(commitTargetRef(fork)).toEqual({ source: 'UPSTREAM', branch: 'release/479' });
    expect(commitRefreshRefs(direct)).toEqual([
      { source: 'WORKSPACE', branch: 'release/1' },
    ]);
    expect(commitRefreshRefs(fork, { source: 'UPSTREAM', branch: 'master' })).toEqual([
      { source: 'UPSTREAM', branch: 'release/479' },
      { source: 'UPSTREAM', branch: 'master' },
    ]);
    expect(commitRefreshRefs(fork, { source: 'REMOTE', branch: 'master', remote: 'oss' })).toEqual([
      { source: 'UPSTREAM', branch: 'release/479' },
      { source: 'REMOTE', branch: 'master', remote: 'oss' },
    ]);
    expect(commitRefreshRefs(fork, { source: 'UPSTREAM', branch: 'release/479' })).toEqual([
      { source: 'UPSTREAM', branch: 'release/479' },
    ]);
  });

  it('reports the last completed refresh status', () => {
    expect(commitSyncLabel(status('FAILED'))).toBe('sync failed');
    expect(commitSyncLabel(status('IDLE'))).toBe('not synced yet');
  });

  it('reloads history only when a background refresh attempt completes', () => {
    const previousAttempt = '2026-08-25T08:00:00Z';

    expect(commitRefreshChanged(previousAttempt, {
      ...status('IDLE'),
      lastAttemptAt: previousAttempt,
      lastSucceededAt: previousAttempt,
    })).toBe(false);
    expect(commitRefreshChanged(previousAttempt, {
      ...status('IDLE'),
      lastAttemptAt: '2026-08-25T08:05:00Z',
      lastSucceededAt: '2026-08-25T08:05:00Z',
    })).toBe(true);
    expect(commitRefreshChanged(previousAttempt, {
      ...status('FAILED'),
      lastAttemptAt: '2026-08-25T08:05:00Z',
    })).toBe(true);
  });
});

const directSettings: Pick<WorkspaceSettingsDto, 'commitsTarget'> = {
  commitsTarget: { remote: 'origin', branch: 'main', overridden: false },
};

const noRelations: WorkspaceRelationshipsDto = { links: [], related: [], line: [] };

/** trino/oss → acme/internal → this workspace → Product. */
function line() {
  const repo = (id: string, owner: string, name: string) => workspaceFixture({
    id, name, monitoredOwner: owner, monitoredRepo: id, defaultBranch: id === 'oss' ? 'master' : 'main',
  });
  return lineRepos(workspace(), {
    links: [],
    related: [
      { workspaceId: 'internal', position: 'UPSTREAM', remote: 'backport-2', unavailableReason: null },
      { workspaceId: 'oss', position: 'UPSTREAM', remote: 'backport-1', unavailableReason: null },
      { workspaceId: 'product', position: 'DOWNSTREAM', remote: null, unavailableReason: null },
    ],
    line: [
      { workspaceId: 'oss', parentWorkspaceId: null },
      { workspaceId: 'internal', parentWorkspaceId: 'oss' },
      { workspaceId: 'workspace-id', parentWorkspaceId: 'internal' },
      { workspaceId: 'product', parentWorkspaceId: 'workspace-id' },
    ],
  }, [repo('oss', 'trino', 'oss'), repo('internal', 'acme', 'internal'), repo('product', 'acme', 'Product')]);
}

function status(state: CommitRefreshStatusDto['state']): CommitRefreshStatusDto {
  return {
    workspaceId: 'workspace-id',
    state,
    lastAttemptAt: null,
    lastSucceededAt: null,
    error: state === 'FAILED' ? 'refresh failed' : null,
  };
}

function workspace(): WorkspaceDto {
  return workspaceFixture({
    name: 'example/project', selectedOwner: 'example', monitoredOwner: 'example', lastCodeSyncAt: '2026-08-24T00:00:00Z',
  });
}

describe('commits stylesheet', () => {
  // A dead-rule sweep once deleted the live `.cmt-search` block along with the
  // popover rules next to it, and every markup assertion still passed.
  it('styles every cmt- class the commits screens render', () => {
    const styled = new Set(
      [...readFileSync('src/css/commits.css', 'utf8').matchAll(/\.(cmt-[a-z0-9-]+)/g)].map(match => match[1]),
    );
    const unstyled = readdirSync('src/commits')
      .filter(name => name.endsWith('.tsx') && !name.endsWith('.test.tsx'))
      .flatMap(name => [...readFileSync(`src/commits/${name}`, 'utf8').matchAll(/cmt-[a-z0-9-]+/g)]
        .map(match => `${name}: ${match[0]}`)
        .filter(entry => !styled.has(entry.split(': ')[1])));

    expect([...new Set(unstyled)]).toEqual([]);
  });
});
