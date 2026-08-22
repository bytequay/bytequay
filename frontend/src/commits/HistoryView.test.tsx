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
        authorAvatarUrl: 'https://avatars.example/author',
        authoredAt: '2026-08-22T00:00:00Z',
        pullRequestNumber: 42,
        pullRequestTitle: 'Improve commit browsing',
      }]}
      loading={false}
      checked={new Set(['0123456789abcdef'])}
      pullRequests={[]}
      onChangeChecked={() => {}}
      onHistoryRewritten={() => {}}
      onOpenPullRequest={() => {}}
    />);

    expect(html).toContain('PR #42');
    expect(html).toContain('Improve commit browsing');
    expect(html).toContain('Open PR #42');
    expect(html).toContain('class="cmt-commit-pr"');
    expect(html).toContain('src="https://avatars.example/author"');
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
});
