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
import { describe, expect, it, vi } from 'vitest';
import Avatar from '../Avatar';
import { DiffViewer } from '../pulls/PullChanges';
import type { GitCommitDto } from '../types';
import HistoryView, { nextCommitRenderLimit } from './HistoryView';

describe('workspace commit history', () => {
  it('shows the empty state without range controls or hints', () => {
    const html = renderToStaticMarkup(<HistoryView
      workspaceId="workspace-id"
      branch="main"
      logRef="main"
      isUpstream={false}
      canRewrite={true}
      commits={[]}
      loading={false}
      checked={new Set()}
      onChangeChecked={() => {}}
      onHistoryRewritten={() => {}}
      onOpenPullRequest={() => {}}
    />);

    expect(html).toContain('No commits on this ref.');
    expect(html).not.toContain('select range');
    expect(html).not.toContain('SHA not on this branch');
    expect(html).not.toContain('Click a commit to see its diff');
  });

  it('hides commits the toolbar search does not match', () => {
    const html = renderToStaticMarkup(<HistoryView
      workspaceId="workspace-id"
      branch="main"
      logRef="main"
      isUpstream={false}
      canRewrite={true}
      commits={[{
        sha: '0123456789abcdef',
        title: 'Improve commit browsing',
        body: '',
        author: 'example-author',
        authorAvatarUrl: null,
        committer: 'example-author',
        committerAvatarUrl: null,
        authoredAt: '2026-08-22T00:00:00Z',
        committedAt: '2026-08-22T00:00:00Z',
      }]}
      loading={false}
      checked={new Set()}
      filter="kafka"
      onChangeChecked={() => {}}
      onHistoryRewritten={() => {}}
      onOpenPullRequest={() => {}}
    />);

    expect(html).toContain('No commits match “kafka”.');
    expect(html).not.toContain('cmt-commit-row');
  });

  it('renders commit history in scroll-loaded batches', () => {
    const commits = Array.from({ length: 51 }, (_, index): GitCommitDto => ({
      sha: `commit-${index}`,
      title: `Commit ${index}`,
      body: '',
      author: 'example-author',
      authorAvatarUrl: null,
      committer: 'example-author',
      committerAvatarUrl: null,
      authoredAt: '2026-08-22T00:00:00Z',
      committedAt: '2026-08-22T00:00:00Z',
    }));
    const render = (checked: ReadonlySet<string>, filter?: string) => renderToStaticMarkup(<HistoryView
      workspaceId="workspace-id"
      branch="main"
      logRef="main"
      isUpstream={false}
      canRewrite={true}
      commits={commits}
      loading={false}
      checked={checked}
      filter={filter}
      onChangeChecked={() => {}}
      onHistoryRewritten={() => {}}
      onOpenPullRequest={() => {}}
    />);
    const html = render(new Set());

    expect(html).toContain('width:400px');
    expect(html).toContain('flex-basis:400px');
    expect(html).toContain('51 commits');
    expect(html).toContain('Showing 50 of 51 commits · scroll to load more');
    expect(html).toContain('Commit 49');
    expect(html).not.toContain('Commit 50');
    expect(render(new Set(['commit-50']))).toContain('Commit 50');
    expect(render(new Set(['commit-50']), 'Commit')).toContain('Commit 50');
  });

  it('loads one more commit batch near the bottom', () => {
    expect(nextCommitRenderLimit(50, 50, 120, 239)).toBe(100);
    expect(nextCommitRenderLimit(50, 50, 120, 240)).toBe(50);
    expect(nextCommitRenderLimit(100, 100, 120, 0)).toBe(120);
    expect(nextCommitRenderLimit(120, 120, 120, 0)).toBe(120);
    expect(nextCommitRenderLimit(100, 50, 120, 0)).toBe(100);
  });

  it('uses the pull-request diff viewer without review controls', () => {
    const html = renderToStaticMarkup(<DiffViewer files={[{
      filename: 'src/App.java',
      status: 'modified',
      additions: 1,
      deletions: 1,
      patch: '@@ -1 +1 @@\n-old\n+new',
    }]} />);

    expect(html).toContain('pull-changes__files-toggle');
    expect(html).not.toContain('class="pull-file-tree"');
    expect(html).toContain('pull-diff-file');
    expect(html).not.toContain('pull-file-tree__resize');
    expect(html).not.toContain('Leave a review comment');
    expect(html).not.toContain('Add to review');
  });

  it('shows pull-request context as a bottom-row link from the commit card', () => {
    const authorAvatarUrl = `http://127.0.0.1:48765/api/avatars/${'a'.repeat(64)}`;
    const committerAvatarUrl = `http://127.0.0.1:48765/api/avatars/${'b'.repeat(64)}`;
    const html = renderToStaticMarkup(<HistoryView
      workspaceId="workspace-id"
      branch="main"
      logRef="main"
      isUpstream={false}
      canRewrite={true}
      commits={[{
        sha: '0123456789abcdef',
        title: 'Improve commit browsing (#42)',
        body: '',
        author: 'percy-r',
        authorAvatarUrl,
        committer: 'ezra-b',
        committerAvatarUrl,
        authoredAt: '2026-08-10T00:00:00Z',
        committedAt: '2026-08-22T00:00:00Z',
        pullRequestNumber: 42,
        pullRequestTitle: 'Improve commit browsing',
      }]}
      loading={false}
      checked={new Set(['0123456789abcdef'])}
      onChangeChecked={() => {}}
      onHistoryRewritten={() => {}}
      onOpenPullRequest={() => {}}
    />);

    expect(html).toContain('PR #42');
    expect(html).toContain('Improve commit browsing');
    expect(html).toContain('Open PR #42');
    expect(html).toContain('class="cmt-commit-pr"');
    expect(html.indexOf('class="cmt-commit-pr"'))
      .toBeGreaterThan(html.indexOf('</div>', html.indexOf('class="cmt-commit-meta"')));
    expect(html).toContain(`src="${authorAvatarUrl}"`);
    expect(html).toContain(`src="${committerAvatarUrl}"`);
    expect(html).toContain('<strong>percy-r</strong> authored and <strong>ezra-b</strong> committed');
    expect(html).toContain('class="cmt-view-author"');
    expect(html).toContain('aria-label="Copy full commit SHA 0123456789abcdef"');
    expect(html).toContain('<button type="button" class="cmt-commit-sha"');
    expect(html).not.toContain('cmt-commit-copy');
    expect(html).not.toContain('cmt-view-summary');
    expect(html).toContain('cmt-commit-row is-selected is-viewed');
    expect(html).toContain('0123456');
  });

  it('groups by committed day and reports the committer-relative age', () => {
    const now = vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-08-25T10:00:00Z'));
    const html = renderToStaticMarkup(<HistoryView
      workspaceId="workspace-id"
      branch="main"
      logRef="main"
      isUpstream={false}
      canRewrite={true}
      commits={[{
        sha: '0123456789abcdef',
        title: 'Separate the Git timestamps',
        body: '',
        author: 'example-author',
        authorAvatarUrl: null,
        committer: 'example-committer',
        committerAvatarUrl: null,
        authoredAt: '2026-08-13T10:00:00Z',
        committedAt: '2026-08-25T09:00:00Z',
      }]}
      loading={false}
      checked={new Set()}
      onChangeChecked={() => {}}
      onHistoryRewritten={() => {}}
      onOpenPullRequest={() => {}}
    />);
    now.mockRestore();

    expect(html).toContain('Commits on Aug 25, 2026');
    expect(html).not.toContain('Commits on Aug 13, 2026');
    expect(html).toContain(
      '<strong>example-author</strong> authored and <strong>example-committer</strong> committed <time',
    );
    expect(html).toContain('>1 hour ago</time>');
    expect(html).not.toContain('committed 12 days ago');
  });

  it('keeps the initial fallback in the avatar slot', () => {
    const html = renderToStaticMarkup(<Avatar login="" avatarUrl={null} size={14} />);

    expect(html).toContain('avatar--fallback');
    expect(html).toContain('width:14px');
    expect(html).toContain('height:14px');
    expect(html).toContain('>?</span>');
  });

  it('keeps commit avatars local when no cached image exists', () => {
    const render = (author: string) => renderToStaticMarkup(<HistoryView
      workspaceId="workspace-id"
      branch="main"
      logRef="main"
      isUpstream={false}
      canRewrite={true}
      commits={[{
        sha: 'fedcba9876543210',
        title: 'Keep history local',
        body: '',
        author,
        authorAvatarUrl: null,
        committer: author,
        committerAvatarUrl: null,
        authoredAt: '2026-08-22T00:00:00Z',
        committedAt: '2026-08-22T00:00:00Z',
      }]}
      loading={false}
      checked={new Set()}
      onChangeChecked={() => {}}
      onHistoryRewritten={() => {}}
      onOpenPullRequest={() => {}}
    />);

    const displayName = render('Example Author');
    expect(displayName).toContain('avatar--fallback');
    expect(displayName).not.toContain('github.com');

    const login = render('example-author');
    expect(login).toContain('avatar--fallback');
    expect(login).not.toContain('github.com');
    expect(login).toContain('<strong>example-author</strong> committed <time');
    expect(login).not.toContain('authored and');
  });
});
