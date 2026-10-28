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
import { flushSync } from 'react-dom';
import { createFixtureRoot } from './reactFixture';
import ReferenceCard, { type HoverCard, type ReferenceCardItem } from '../ReferenceCard';
import MarkdownBody from '../pulls/MarkdownBody';
import type { GitHubIssuePreviewDto } from '../types';
import { ZOOM_REFERENCE, type ZoomedReference } from '../githubReference';
import { prDetail } from '../pulls/fixtures/pullRequest';
import { assert, settle, element, until } from './harness';
import '../css/reference-card.css';
import '../css/pulls.css';

export async function run(): Promise<boolean> {
  const { host, root, dispose: disposeRoot } = createFixtureRoot();
  const anchor = new DOMRect(100, 700, 120, 20);
  const hover: HoverCard = {
    anchor, pinned: false,
    show: () => {}, hide: () => {}, hold: () => {}, close: () => {}, pin: () => {}, restoreFocus: () => {},
  };
  const item: ReferenceCardItem = {
    type: 'issue', repo: 'owner/repository', number: 31252, title: 'Issue', author: null,
  };
  let opened = '';
  Object.assign(window, { bridge: {
    fetchPullRequestDetailByRef: async () => { throw new Error('Not a pull request'); },
    fetchGitHubIssuePreview: async (): Promise<GitHubIssuePreviewDto> => ({
      title: 'Delta Lake checkpoint writer drops files with deletion vectors',
      body: 'Issue details', author: 'octocat', labels: [], updatedAt: '2026-09-21T00:00:00Z',
      state: 'open', pullRequest: false, htmlUrl: 'https://github.com/owner/repository/issues/1', authorAvatarUrl: '',
    }),
    openInAppBrowser: async (href: string) => { opened = href; },
  } });

  try {
    flushSync(() => root.render(<ReferenceCard hover={hover} item={item} body="Details are unavailable." />));
    await settle();
    const card = element<HTMLElement>('.ref-card', document, 'Missing hover card');
    const rect = card.getBoundingClientRect();
    assert(Math.abs(rect.bottom - (anchor.top - 6)) < 12,
      `A short hover card must sit immediately above its link (card=${rect.top}-${rect.bottom}, anchor=${anchor.top}, viewport=${window.innerHeight})`);

    flushSync(() => root.render(<MarkdownBody
      body="- Fixes https://github.com/owner/repository/pull/31252" repository="owner/repository" />));
    await settle();
    const link = element<HTMLAnchorElement>('.md-reference', host, 'Missing issue reference');
    assert(link.textContent?.includes('Delta Lake checkpoint writer drops files with deletion vectors #31252') === true,
      'The issue title and number must replace the URL label');
    assert(link.href === 'https://github.com/owner/repository/issues/31252', 'The link must point to the issue');
    link.click();
    await settle();
    assert(opened === link.href, 'Opening the reference must use the issue URL');

    const zooms: ZoomedReference[] = [];
    const zoomed = (event: Event) => { zooms.push((event as CustomEvent<ZoomedReference>).detail); };
    window.addEventListener(ZOOM_REFERENCE, zoomed);
    const issue = (title: string): GitHubIssuePreviewDto => ({
      title, body: 'Issue details', author: 'octocat', labels: [], updatedAt: '2026-09-21T00:00:00Z', state: 'open', pullRequest: false, htmlUrl: 'https://github.com/owner/repository/issues/1', authorAvatarUrl: '',
    });
    Object.assign(window, { bridge: {
      listWorkspaces: async () => ['A', 'B'].map(repo => ({ id: `workspace-${repo}`, monitoredOwner: 'owner', monitoredRepo: repo })),
      fetchWorkspaceIssue: async (workspaceId: string) => issue(`Issue in ${workspaceId}`),
    } });
    const focusReference = () => element<HTMLAnchorElement>('.md-reference', host).dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
    const closeCard = async () => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      await until(() => document.querySelector('.ref-card') === null, 'The reference card did not close');
    };
    try {
      flushSync(() => root.render(<MarkdownBody body="[related](https://github.com/owner/A/issues/1)" />));
      focusReference();
      await until(() => document.querySelector('.ref-card')?.textContent?.includes('Issue in workspace-A') === true, 'The first issue preview did not load');
      await closeCard();
      flushSync(() => root.render(<MarkdownBody body="[related](https://github.com/owner/B/issues/2)" />));
      element<HTMLAnchorElement>('.md-reference', host).click();
      await until(() => zooms.length > 0, 'The changed reference did not open');
      const target = zooms.at(-1);
      assert(target?.kind === 'issue' && target.repo === 'owner/B' && target.number === 2 && target.workspaceId === 'workspace-B',
        'A changed reference must resolve its own workspace before opening');

      let finishOld: ((value: GitHubIssuePreviewDto) => void) | undefined;
      Object.assign(window.bridge, {
        fetchWorkspaceIssue: (workspaceId: string) => workspaceId === 'workspace-A'
          ? new Promise<GitHubIssuePreviewDto>(resolve => { finishOld = resolve; })
          : Promise.resolve(issue('Current issue B')),
      });
      flushSync(() => root.render(<MarkdownBody body="https://github.com/owner/A/issues/3" />));
      await until(() => finishOld !== undefined, 'The older reference read did not start');
      flushSync(() => root.render(<MarkdownBody body="https://github.com/owner/B/issues/4" />));
      await until(() => host.textContent?.includes('Current issue B') === true, 'The current reference did not load');
      finishOld?.(issue('Obsolete issue A'));
      await settle();
      assert(host.textContent?.includes('Current issue B') && !host.textContent?.includes('Obsolete issue A'), 'A late preview must not replace the current reference');
    }
    finally { window.removeEventListener(ZOOM_REFERENCE, zoomed); }

    for (const showTitle of [false, true]) {
      const number = showTitle ? 5101 : 5100;
      const url = `https://github.com/owner/fresh/pull/${number}`;
      let detail = prDetail({ repo: 'owner/fresh', number, title: 'Before merge' });
      let reads = 0;
      Object.assign(window, { bridge: {
        fetchPullRequestDetailByRef: async () => { reads++; return detail; },
      } });
      flushSync(() => root.render(<MarkdownBody body={showTitle ? url : `[related](${url})`} />));
      if (showTitle) await until(() => host.textContent?.includes('Before merge') === true, 'The reference title did not preload');
      const beforeOpen = reads;
      focusReference();
      await until(() => reads > beforeOpen && document.querySelector('.ref-card')?.textContent?.includes('Before merge') === true, 'Opening must read the current preview');
      await closeCard();
      detail = { ...detail, title: 'After merge', state: 'closed', mergedAt: '2026-09-22T00:00:00Z' };
      const beforeReopen = reads;
      focusReference();
      await until(() => reads > beforeReopen && document.querySelector('.ref-card')?.textContent?.includes('Merged') === true, 'Reopening must refresh the merged state');
      assert(document.querySelector('.ref-card')?.textContent?.includes('After merge'), 'Reopening must refresh the title');
      await closeCard();
    }
    return true;
  }
  finally {
    disposeRoot();
  }
}
