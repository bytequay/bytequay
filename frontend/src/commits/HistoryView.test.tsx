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
import Avatar from '../Avatar';
import { DiffViewer } from '../pulls/PullChanges';
import HistoryView from './HistoryView';

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
        authoredAt: '2026-08-22T00:00:00Z',
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

  it('uses the pull-request diff viewer without review controls', () => {
    const html = renderToStaticMarkup(<DiffViewer files={[{
      filename: 'src/App.java',
      status: 'modified',
      additions: 1,
      deletions: 1,
      patch: '@@ -1 +1 @@\n-old\n+new',
    }]} />);

    expect(html).toContain('pull-file-tree');
    expect(html).toContain('pull-diff-file');
    expect(html).toContain('pull-file-tree__resize');
    expect(html).not.toContain('Leave a review comment');
    expect(html).not.toContain('Add to review');
  });

  it('shows pull-request context as a link from the commit card', () => {
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
        author: 'example-author',
        authorAvatarUrl: 'http://127.0.0.1:48765/api/avatars/abc123',
        authoredAt: '2026-08-22T00:00:00Z',
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
    expect(html).toContain('src="http://127.0.0.1:48765/api/avatars/abc123"');
    expect(html).toContain('class="cmt-view-author"');
    expect(html).toContain('aria-label="Copy full commit SHA 0123456789abcdef"');
    expect(html).not.toContain('cmt-view-summary');
    expect(html).toContain('cmt-commit-row is-selected is-viewed');
    expect(html).toContain('0123456');
  });

  it('keeps the initial fallback in the avatar slot', () => {
    const html = renderToStaticMarkup(<Avatar login="" avatarUrl={null} size={14} />);

    expect(html).toContain('avatar--fallback');
    expect(html).toContain('width:14px');
    expect(html).toContain('height:14px');
    expect(html).toContain('>?</span>');
  });

  it('synthesizes a GitHub avatar only for login-like commit authors', () => {
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
        authoredAt: '2026-08-22T00:00:00Z',
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

    expect(render('example-author')).toContain('https://github.com/example-author.png');
  });
});
