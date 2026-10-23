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
import { assert, settle, element } from './harness';
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
      state: 'open', pullRequest: false,
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
    return true;
  }
  finally {
    disposeRoot();
  }
}
