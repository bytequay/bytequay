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
import { createRoot } from 'react-dom/client';
import type { ReactNode } from 'react';
import type { Bridge, PullRequestDetailDto, PullTimelineCommentDto } from '../../types';
import { assert, settle } from '../../fixtures/harness';
import PullOverview from '../PullOverview';
import { remotePullRequest } from '../PullsScreen';
import ReviewCommentIndex, { commentIndexTarget } from '../ReviewCommentIndex';
import AgentReviewTab from '../agent-review/AgentReviewTab';
import { reviewFixture } from '../agent-review/fixtures/review';
import type { LocalReviewThreadDto } from '../local-review/types';
import { EMPTY_TIMELINE_PAYLOAD } from '../model';
import '../../css/base.css';
import '../../css/agent-review.css';
import '../../css/commits.css';

const base = reviewFixture();
const timestamp = base.createdAt;
const local: LocalReviewThreadDto = {
  id: 'local-index', taskId: base.task.id, taskVersion: base.task.version, workspaceId: base.workspaceId,
  repo: base.repo, number: base.number, path: 'src/Example.java', side: 'RIGHT', startLine: 12, line: 13,
  baseSha: null, headSha: base.observedHeadSha, code: 'return fieldId;', resolved: true, version: 1,
  createdAt: timestamp, updatedAt: timestamp,
  messages: (['USER', 'AGENT'] as const).map((author, index): LocalReviewThreadDto['messages'][number] => ({ id: `local-${index}`, author,
    body: index === 0 ? 'Private question about the field ID' : 'Private agent answer about the field ID',
    createdAt: timestamp, turnId: null, queuedInputId: null, messageEventId: null, deliveryState: null, deliveryError: null })),
};
const localReview = { threads: [local], onReply: async () => {}, onResolve: async () => {}, onRetry: async () => {} };
const comment = (githubId: number, actor: string, actorBot: boolean, body: string): PullTimelineCommentDto => ({
  githubId, actor, actorBot, body, actorAvatarUrl: null, timestamp, htmlUrl: null, reviewId: 2,
  inReplyToId: githubId === 4 ? 3 : null, reactions: [],
});
const detail: PullRequestDetailDto = {
  id: 1, repo: base.repo, number: base.number, title: 'Review nested field lookup', htmlUrl: null, state: 'open', draft: false,
  mergeable: null, mergeableState: null, author: 'alice', authorAvatarUrl: null, createdAt: timestamp, updatedAt: timestamp,
  mergedAt: null, baseRef: 'master', headRef: 'feature', body: 'Resolve nested fields safely.\n\n'.repeat(12), labels: [], assignees: [], milestone: null,
  reviewers: [], additions: 12, deletions: 4, changedFiles: 1, approvalCount: 0, ciStatus: null, checkRuns: [], unavailableSections: [], viewerCanWrite: false,
  recentActivity: [
    { actor: 'alice', eventType: 'commented', timestamp, body: 'Please check the nested field lookup.', githubId: 1, payload: EMPTY_TIMELINE_PAYLOAD },
    { actor: 'reviewer[bot]', eventType: 'reviewed', timestamp, body: 'One issue needs attention.', githubId: 2,
      payload: { ...EMPTY_TIMELINE_PAYLOAD, actorBot: true, state: 'COMMENTED' } },
    { actor: 'reviewer[bot]', eventType: 'review_thread', timestamp, body: null, githubId: 30,
      payload: { ...EMPTY_TIMELINE_PAYLOAD, resolved: true, outdated: true, path: 'src/Example.java', line: 12, side: 'RIGHT',
        comments: [comment(3, 'reviewer[bot]', true, 'Preserve the leaf field ID.'), comment(4, 'alice', false, 'Fixed the lookup.')] } },
  ],
};
const review = { ...base, findings: base.findings.map((finding, index) => ({ ...finding, state: index === 1 ? 'RESOLVED' as const : 'OPEN' as const })),
  messages: base.messages.map(message => ({ ...message, findingId: base.findings[0].id })) };

function Surface({ children }: { children: ReactNode }) {
  return <div style={{ height: 650, width: '100%', display: 'flex', flexDirection: 'column', overflow: 'auto' }}>{children}</div>;
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const previous = window.bridge;
  let writes = 0;
  const bridge = {
    getUserProfile: async () => ({ login: 'alice', name: null, avatarUrl: '', htmlUrl: '', publicRepos: 0,
      followers: 0, following: 0, bio: null, location: null, company: null, email: null }),
    saveRecentDraft: async () => {},
  } satisfies Pick<Bridge, 'getUserProfile' | 'saveRecentDraft'>;
  Object.defineProperty(window, 'bridge', { configurable: true, value: new Proxy(bridge, {
    get(target, property) {
      if (property in target) return Reflect.get(target, property);
      return async () => { writes++; throw new Error(`Unexpected bridge call: ${String(property)}`); };
    },
  }) });
  const host = document.createElement('main');
  document.body.append(host);
  const root = createRoot(host);
  const mount = async (node: ReactNode) => { flushSync(() => root.render(node)); await settle(); await settle(); };
  const entries = () => Array.from(host.querySelectorAll<HTMLButtonElement>('.review-comment-index button'));
  const marker = (text: string) => {
    const found = entries().find(button => button.getAttribute('aria-label')?.includes(text));
    assert(found !== undefined, `Missing index marker: ${text}`);
    return found;
  };
  const checkRailPosition = () => {
    const pane = host.firstElementChild?.getBoundingClientRect();
    const rail = host.querySelector('.review-comment-index')?.getBoundingClientRect();
    assert(pane !== undefined && rail !== undefined, 'The pane and its index must exist');
    assert(Math.abs(rail.left - pane.left - 6) < 1, 'Index must sit at the pane boundary, outside the centered content column');
    assert(Math.abs(rail.top + rail.height / 2 - pane.top - pane.height / 2) < 1, 'Index must stay vertically centered in the visible pane');
  };
  const results: { name: string; error?: string }[] = [];
  try {
    for (const [name, test] of [
      ['PR comments and collapsed replies', async () => {
        await mount(<Surface><PullOverview pr={remotePullRequest(detail)} detail={detail} localReview={localReview} onRefresh={async () => {}} /></Surface>);
        assert(entries().length === 6, 'Index must include human, bot, thread replies and local messages, excluding description and events');
        assert(entries().filter(button => button.classList.contains('is-agent')).length === 3, 'Agent authors must be distinct from human authors');
        checkRailPosition();
        const firstMarker = marker('Please check');
        firstMarker.dispatchEvent(new MouseEvent('mouseover', { bubbles: true })); await settle();
        const preview = host.querySelector<HTMLElement>('[role="tooltip"]');
        assert(preview !== null && preview.querySelector('strong')?.textContent === 'alice'
          && preview.querySelector('p')?.textContent === 'Please check the nested field lookup.', 'Hover must show the author and comment body');
        assert(firstMarker.getAttribute('aria-describedby') === preview.id && !firstMarker.hasAttribute('title'),
          'The custom preview must be accessible without a duplicate native tooltip');
        firstMarker.dispatchEvent(new MouseEvent('mouseout', { bubbles: true, relatedTarget: preview })); await settle();
        assert(host.querySelector('[role="tooltip"]') !== null, 'Moving onto the preview must keep it visible');
        preview.dispatchEvent(new MouseEvent('mouseout', { bubbles: true, relatedTarget: host })); await settle();
        assert(host.querySelector('[role="tooltip"]') === null, 'Leaving the marker and preview must dismiss it');
        firstMarker.focus();
        // Hidden Electron windows set activeElement without emitting native focus events.
        firstMarker.dispatchEvent(new FocusEvent('focusin', { bubbles: true })); await settle();
        assert(host.querySelector('[role="tooltip"]') !== null, 'Keyboard focus must show the same preview');
        window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); await settle();
        assert(host.querySelector('[role="tooltip"]') === null, 'Escape must dismiss the preview');
        const thread = host.querySelector<HTMLDetailsElement>('.pr-thread-row');
        assert(thread !== null && !thread.open, 'Resolved thread starts collapsed');
        marker('Fixed the lookup.').click(); await settle();
        assert(thread.open && document.activeElement?.textContent?.includes('Fixed the lookup.') === true,
          'Navigation must open and focus the selected reply');
        assert(document.activeElement !== null && getComputedStyle(document.activeElement).outlineStyle === 'none',
          'Jumping to a comment must not add a blue outline to the card');
        assert(marker('Preserve the leaf field ID.').getAttribute('aria-label')?.includes('Outdated') === true, 'Index must retain thread status');
        assert((host.querySelector('.review-comment-scroll')?.scrollTop ?? 0) > 0, 'Navigation must scroll the PR container');
        checkRailPosition();
        const pane = host.firstElementChild;
        assert(pane instanceof HTMLElement, 'The pane must exist');
        pane.style.width = '500px'; pane.style.height = '420px'; await settle();
        checkRailPosition();
        marker('Private agent answer').focus();
        marker('Private agent answer').dispatchEvent(new FocusEvent('focusin', { bubbles: true })); await settle();
        const narrowPreview = host.querySelector('[role="tooltip"]')?.getBoundingClientRect();
        const paneBounds = pane.getBoundingClientRect();
        assert(narrowPreview !== undefined && narrowPreview.right < paneBounds.right && narrowPreview.bottom < paneBounds.bottom,
          'The preview must stay inside a narrow pane');
        const updated = { ...detail, recentActivity: detail.recentActivity.slice(1) };
        await mount(<Surface><PullOverview pr={remotePullRequest(updated)} detail={updated} localReview={localReview} onRefresh={async () => {}} /></Surface>);
        assert(entries().length === 5 && !entries().some(button => button.getAttribute('aria-label')?.includes('Please check')), 'Refresh must remove stale destinations');
        await mount(<Surface><PullOverview pr={remotePullRequest(detail)} detail={{ ...detail, recentActivity: [] }} onRefresh={async () => {}} /></Surface>);
        assert(host.querySelector('.review-comment-index') === null, 'Empty pages must not reserve a rail');
        await mount(<Surface><div className="cmt-prog-pr" style={{ flex: 1 }}><div className="cmt-pr-overview">
          <PullOverview pr={remotePullRequest(detail)} detail={detail} onRefresh={async () => {}} />
          <div className="cmt-pr-footnote">Pull request for the selected commit</div>
        </div></div></Surface>);
        const embeddedPane = host.querySelector('.cmt-prog-pr')?.getBoundingClientRect();
        const embeddedRail = host.querySelector('.review-comment-index')?.getBoundingClientRect();
        const footer = host.querySelector('.cmt-pr-footnote')?.getBoundingClientRect();
        assert(embeddedPane !== undefined && embeddedRail !== undefined && footer !== undefined
          && Math.abs(embeddedRail.left - embeddedPane.left - 6) < 1 && footer.bottom <= embeddedPane.bottom
          && embeddedRail.bottom < footer.top, 'Embedded PR index and footer must remain inside the visible pane');
      }],
      ['Latest markers and scrolling earlier comments', async () => {
        const render = (count: number, hidden = false) => <Surface><ReviewCommentIndex className="pr-overview" hidden={hidden}>
          {Array.from({ length: count }, (_, index) => <article key={index}
            {...commentIndexTarget(index % 2 === 0 ? 'human' : 'agent', 'Reviewer', `Review comment ${index + 1}.`)}>
            <p>Review comment {index + 1}.</p>
          </article>)}
        </ReviewCommentIndex></Surface>;
        const list = () => {
          const element = host.querySelector<HTMLOListElement>('.review-comment-index ol');
          assert(element !== null, 'The scrollable marker list must exist');
          return element;
        };
        const visible = () => {
          const bounds = list().getBoundingClientRect();
          return entries().filter(button => {
            const marker = button.getBoundingClientRect();
            return marker.top >= bounds.top - 1 && marker.bottom <= bounds.bottom + 1;
          });
        };
        await mount(render(65));
        assert(visible().length === 30 && visible()[0].getAttribute('aria-label')?.startsWith('36.') === true
          && visible()[29].getAttribute('aria-label')?.startsWith('65.') === true, 'Only the latest 30 markers should be initially visible');
        checkRailPosition();
        await mount(render(66));
        assert(visible().length === 30 && visible()[29].getAttribute('aria-label')?.startsWith('66.') === true,
          'New comments should remain visible when following the latest markers');
        const page = host.querySelector('.review-comment-scroll');
        const pageScroll = page?.scrollTop;
        list().scrollTop = 0; await settle();
        assert(visible().length === 30 && visible()[0].getAttribute('aria-label')?.startsWith('1.') === true
          && page?.scrollTop === pageScroll, 'Scrolling up in the index must expose older markers without moving the review page');
        marker('Review comment 1.').dispatchEvent(new MouseEvent('mouseover', { bubbles: true })); await settle();
        assert(host.querySelector('[role="tooltip"] p')?.textContent === 'Review comment 1.', 'Older markers must retain their previews');
        list().scrollTop = 120; await settle();
        const earlierScroll = list().scrollTop;
        await mount(render(67));
        assert(list().scrollTop === earlierScroll, 'New comments must not interrupt browsing earlier markers');
        marker('Review comment 11.').click(); await settle();
        assert(document.activeElement?.textContent?.includes('Review comment 11.') === true, 'Earlier markers must navigate to their own comments');
        await mount(render(12));
        assert(visible().length === 12 && list().scrollTop === 0, 'Short lists must show all available markers without empty space');
        await mount(render(65, true));
        await mount(render(65));
        assert(visible().length === 30 && visible()[29].getAttribute('aria-label')?.startsWith('65.') === true,
          'An index loaded while hidden must show the latest markers when opened');
        list().scrollTop = 120; await settle();
        await mount(render(0));
        await mount(render(65));
        assert(visible()[29].getAttribute('aria-label')?.startsWith('65.') === true, 'Repopulating an empty index must start at the latest comments');
      }],
      ['Summary findings, filtering and scoped navigation', async () => {
        const render = (extra = false, hidden = false) => <Surface><ReviewCommentIndex hidden={hidden}>
          <AgentReviewTab workspaceId={base.workspaceId} viewer={null} review={review} onReviewChange={() => {}}
            onNavigateToChanges={() => {}} focusFindingId={null} ownStream={false}
            localReview={{ ...localReview, threads: [{ ...local, messages: extra ? [...local.messages, { ...local.messages[1], id: 'live-reply', body: 'New streamed reply' }] : local.messages }] }} />
        </ReviewCommentIndex></Surface>;
        await mount(render());
        assert(host.querySelectorAll('.review-comment-index').length === 1 && entries().length === 7,
          'Nested review panels must share one index for findings and human/agent replies');
        checkRailPosition();
        marker(review.findings[0].title).dispatchEvent(new MouseEvent('mouseover', { bubbles: true })); await settle();
        assert(host.querySelector('[role="tooltip"] p')?.textContent
          === '`resolveNestedFieldId` descends by the first path segment, then falls back to matching the remaining path by leaf name anywhere below.…',
        'Finding previews must show the first 20 words of the body');
        const resolved = host.querySelector<HTMLDetailsElement>(`[data-finding-id="${review.findings[1].id}"] details`);
        assert(resolved !== null && !resolved.open, 'Resolved finding starts collapsed');
        marker(review.findings[1].title).click(); await settle();
        assert(host.querySelector('[role="tooltip"]') === null, 'Jumping to a comment must close its preview');
        assert(resolved.open && document.activeElement?.textContent?.includes(review.findings[1].title) === true,
          'Index must open and focus the selected finding');
        const filter = (text: string) => {
          const button = Array.from(host.querySelectorAll<HTMLButtonElement>('.agr-findings__filters button')).find(item => item.textContent?.startsWith(text));
          assert(button !== undefined, `Missing ${text} filter`); button.click();
        };
        filter('Open'); await settle();
        assert(entries().length === 6 && !entries().some(button => button.getAttribute('aria-label')?.includes(review.findings[1].title)),
          'Filtered findings must not leave unreachable index entries');
        filter('All'); await settle();
        await mount(render(true));
        assert(entries().length === 8, 'New replies must join the index without remounting');
        marker('New streamed reply').click(); await settle();
        assert(document.activeElement?.textContent?.includes('New streamed reply') === true, 'New reply must have its own destination');
        await mount(render(true, true));
        const page = host.querySelector<HTMLElement>('.review-comment-layout');
        assert(page !== null && getComputedStyle(page).display === 'none', 'Kept Summary must stay hidden on another tab');
        await mount(render(true));
        assert(getComputedStyle(page).display === 'flex' && entries().length === 8, 'Reopened Summary must retain its destinations');
        checkRailPosition();
        assert(writes === 0, 'Index navigation must not write to GitHub or start agent work');
      }],
    ] as const) {
      try { await test(); results.push({ name }); }
      catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
      await mount(null);
    }
    return results;
  }
  finally { flushSync(() => root.unmount()); host.remove(); Object.defineProperty(window, 'bridge', { configurable: true, value: previous }); }
}
