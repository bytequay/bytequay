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
        author: 'octocat',
        authorAvatarUrl: 'https://avatars.example/octocat',
        authoredAt: '2026-08-22T00:00:00Z',
        pullRequestNumber: 42,
        pullRequestTitle: 'Improve commit browsing',
      }]}
      loading={false}
      checked={new Set()}
      pullRequests={[]}
      onChangeChecked={() => {}}
      onHistoryRewritten={() => {}}
      onOpenPullRequest={() => {}}
    />);

    expect(html).toContain('PR #42');
    expect(html).toContain('Improve commit browsing');
    expect(html).toContain('Open PR #42');
    expect(html).toContain('src="https://avatars.example/octocat"');
  });
});
