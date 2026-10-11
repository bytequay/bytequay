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
import type { AgentTurnDto, Bridge, PullRequestDetailDto } from '../../../types';
import { parseAgentEventSequence, type AgentEventEnvelope } from '../../../agents/events';
import eventFixture from '../../../agents/fixtures/agent-events-v2.json';
import contracts from '../../../agents/fixtures/agent-contracts-v1.json';
import { createPreviewBridge } from '../../../agents/fixtures/previewBridge';
import PullsScreen from '../../PullsScreen';
import type { ReviewMessageDto, ReviewRunDto } from '../contracts';
import type { LocalReviewThreadDto } from '../../local-review/types';
import { reviewFixture } from './review';
import '../../../css/base.css';
import '../../../css/agents.css';
import '../../../css/agent-review.css';
import { assert, settle } from '../../../fixtures/harness';

const internal = 'Review frozen scope. Use review_get_context and review_save_summary, review_save_finding and review_finish. Reply privately with review_reply. Selected card: internal context.';
const base = reviewFixture();
const original = base.runs[0];
assert(original.summary !== null, 'Review fixture needs a saved summary');
const commitMessageBody = 'Represent a dotted column reference as ordered path segments.\n\nKeep schema lookup in IcebergUtil so parsing does not depend on table metadata.';
const current: ReviewRunDto = { ...original, id: 'run-2', ordinal: 2, headSha: 'b'.repeat(40), turnIds: ['turn-review'],
  commits: original.commits.map((entry, index) => index === 0 ? { ...entry, message: `${entry.message}\n\n${commitMessageBody}` } : entry),
  summary: { ...original.summary, revision: 2, purpose: 'Current review explains the updated nested field lookup.',
    commits: original.summary.commits.map((entry, index) => index === 0 ? { ...entry,
      explanation: '**Design:** Keep syntax parsing separate from schema lookup.\n\n**Implementation:** Split dotted field names into path segments before resolving the exact field ID.',
    } : entry) } };
const question = (id: string, cardId: string | null, run = current, findingId: string | null = null): ReviewMessageDto => ({
  id, author: 'USER', body: `Explain ${id} in detail. ` + 'Keep the complete original question available. '.repeat(8),
  findingId, runId: run.id, cardId, targetRevision: run.summary?.revision ?? 1,
  turnId: `turn-${id}`, queuedInputId: null, createdAt: run.createdAt,
});
const purpose = question('purpose', `${current.id}:purpose`);
const reply = question('reply', `${current.id}:purpose`);
const commit = question('commit', `${current.id}:commit:${current.commits[0].sha}`);
const finding = question('finding', null, current, base.findings[0].id);
const older = question('older', `${original.id}:purpose`, original);
const limitations = question('limitations', `${original.id}:limitations`, original);
const review = { ...base, state: 'CLOSED' as const, runs: [original, current], latestCompletedRunId: current.id,
  observedHeadSha: current.headSha, byteQuayTurnIds: current.turnIds, messages: [purpose, reply, commit, finding, older, limitations],
  findings: [{ ...base.findings[0], runId: current.id, state: 'RESOLVED' as const, validatedHeadSha: current.headSha }],
};
const local: LocalReviewThreadDto = {
  id: 'local-navigation', taskId: review.task.id, taskVersion: review.task.version, workspaceId: review.workspaceId,
  repo: review.repo, number: review.number, path: 'src/Example.java', side: 'RIGHT', startLine: 12, line: 13,
  baseSha: current.baseSha, headSha: current.headSha, code: 'return fieldId;', resolved: true, version: 1,
  createdAt: current.createdAt, updatedAt: current.createdAt,
  messages: [{ id: 'local-question', author: 'USER', body: 'Why is this field ID returned?', createdAt: current.createdAt,
    turnId: 'turn-local', queuedInputId: null, messageEventId: null, deliveryState: 'COMPLETED', deliveryError: null }],
};
const source = parseAgentEventSequence(eventFixture.events)[0];
assert(source.type === 'user_message', 'Fixture needs a user event');
const events: AgentEventEnvelope[] = [];
const input = (turnId: string, text: string, deliveryKind: 'TURN' | 'STEER' = 'TURN') => {
  const sequence = events.length + 1;
  events.push({ ...source, taskId: review.task.id, turnId, sequence, eventId: `navigation-event-${sequence}`,
    source: { ...source.source, providerTurnId: turnId, providerItemId: `input-${sequence}`, providerEventId: `event-${sequence}` },
    payload: { text, deliveryKind, attachments: [] } });
};
input('turn-review', internal);
input('turn-review', 'Also check cancellation separately.', 'STEER');
for (const message of review.messages) {
  assert(message.turnId !== null, 'Saved questions need a delivered turn');
  input(message.turnId, internal);
}
input('turn-local', internal);
input('turn-ordinary', 'Please keep my ordinary question visible.');
const detail: PullRequestDetailDto = {
  id: 42, repo: review.repo, number: review.number, title: 'Resolve nested sort fields in Iceberg', htmlUrl: null, state: 'open', draft: true,
  mergeable: null, mergeableState: null, author: 'alice', authorAvatarUrl: null, createdAt: null, updatedAt: null,
  mergedAt: null, baseRef: 'master', headRef: 'nested-fields', body: 'Support nested struct fields in the sort order.', labels: [], assignees: [], milestone: null,
  reviewers: [], additions: 12, deletions: 3, changedFiles: 2, approvalCount: 0, ciStatus: null, recentActivity: [],
  checkRuns: [], unavailableSections: [], viewerCanWrite: false,
};

export async function run(): Promise<string[]> {
  const agent = createPreviewBridge(review.workspaceId);
  const bridge = {
    getUserProfile: async () => ({ login: 'reader', name: null, avatarUrl: '', htmlUrl: '', publicRepos: 0, followers: 0,
      following: 0, bio: null, location: null, company: null, email: null }),
    getModelSettings: agent.getModelSettings,
    getAgentTurn: async id => ({ ...contracts.turn, id, taskId: review.task.id,
      createdAt: current.createdAt, startedAt: current.createdAt, endedAt: current.endedAt } as AgentTurnDto),
    getAgentReview: async id => { assert(id === review.id, 'Navigation must retain the selected review'); return review; },
    readAgentReviewHistory: async (id, taskId) => {
      assert(id === review.id && taskId === review.task.id, 'Replay must keep the review and task identity');
      return { events, beforeCursor: null, afterCursor: null, latestSequence: events.length };
    },
    fetchAttentionPrs: async () => [],
    fetchPullRequestDetailByRef: async () => detail,
    refreshPullRequestDetailByRef: async () => ({ full: true as const, detail, status: null }),
    listLocalReviewComments: async () => [local],
    fetchPrDiffFiles: async () => [],
    fetchPrDiffCommits: async () => [],
    readWorktreeChanges: async (_host, request) => ({ scope: request.scope, selectedCommit: request.commit ?? null,
      sourceFingerprint: 'review-head', baseSha: 'base', headSha: 'head', baseLabel: 'main', branch: 'review',
      files: [], commits: [], staged: [], unstaged: [], untracked: [], truncated: false }),
    recordRecentTouch: async () => {},
  } satisfies Pick<Bridge, 'getUserProfile' | 'getModelSettings' | 'getAgentTurn' | 'getAgentReview' | 'readAgentReviewHistory'
    | 'fetchAttentionPrs' | 'fetchPullRequestDetailByRef' | 'refreshPullRequestDetailByRef' | 'listLocalReviewComments'
    | 'fetchPrDiffFiles' | 'fetchPrDiffCommits' | 'readWorktreeChanges' | 'recordRecentTouch'>;
  const previous = window.bridge;
  Object.assign(window, { bridge });
  const host = document.createElement('main');
  host.style.cssText = 'height: 850px; width: 1500px; max-width: 100vw';
  document.body.append(host);
  const root = createRoot(host);
  const results: string[] = [];
  const button = (scope: Element, text: string) => {
    const found = [...scope.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.includes(text));
    assert(found !== undefined, `Missing ${text} button`);
    return found;
  };
  try {
    flushSync(() => root.render(<PullsScreen initialPr={{ repo: review.repo, number: review.number }} initialReviewId={review.id}
      repositoryScope={{ workspaceId: review.workspaceId, repository: review.repo }} reviewWorkspace={{ onBack: () => {} }} />));
    for (let attempt = 0; attempt < 20 && host.querySelectorAll('.agt-review-request').length < 8; attempt++) await settle();
    const conversation = host.querySelector<HTMLElement>('.agr-workspace-conversation');
    const pane = host.querySelector<HTMLElement>('.pull-detail');
    assert(conversation !== null && pane !== null && conversation.parentElement === pane.parentElement,
      'Review requests and destinations must render in the actual sibling conversation and PR panes');
    assert(conversation.querySelectorAll('.agt-review-request').length === 8, 'Replay must compact the generated request, six discussions and local question');
    assert(!conversation.innerText.includes('review_get_context') && !conversation.innerText.includes('review_reply')
      && !conversation.innerText.includes('Selected card:'), 'Generated review instructions must remain folded and saved questions must omit copied context');
    assert(conversation.textContent?.includes('Also check cancellation separately.') === true
      && conversation.textContent?.includes('Please keep my ordinary question visible.') === true,
    'Ordinary user text and steering must remain visible');
    const request = (turnId: string) => {
      const card = conversation.querySelector<HTMLElement>(`[data-history-anchor="${turnId}"] .agt-review-request`);
      assert(card !== null, `Missing compact request for ${turnId}`);
      return card;
    };
    for (const card of conversation.querySelectorAll<HTMLElement>('.agt-review-request')) {
      assert(card.getBoundingClientRect().height < 115, 'Long requests must collapse to a compact card');
      const toggle = card.querySelector<HTMLButtonElement>('.agt-review-request__toggle');
      const open = card.querySelector<HTMLButtonElement>('.agt-review-request__open');
      assert(toggle !== null && open !== null && toggle !== open, 'Opening the review and expanding text must be separate keyboard actions');
      const header = open.parentElement;
      assert(header !== null, 'The navigation control must belong to a header');
      const background = getComputedStyle(header).backgroundColor;
      const rgb = background.match(/[\d.]+/g)?.map(Number);
      assert(rgb !== undefined && rgb.length >= 3 && rgb[1] > rgb[0] && rgb[1] >= rgb[2]
        && (rgb.length < 4 || rgb[3] > 0), `Review request header must have a green background, got ${background}`);
    }
    assert(request('turn-review').textContent?.includes('Review pull request') === true,
      'The initial generated request must use the same compact presentation');
    assert(request('turn-reply').textContent?.includes('Reply to review') === true, 'Repeated discussion input must retain its reply label');
    results.push('Actual review workspace replays compact green cards and preserves ordinary input');

    button(pane, 'Summary').click(); await settle();
    assert(pane.querySelector(`[data-review-card="${original.id}:purpose"]`) !== null,
      'The default explanation must belong to the first complete report');
    request('turn-review').querySelector<HTMLButtonElement>('.agt-review-request__open')?.click(); await settle();
    const intention = pane.querySelector<HTMLElement>(`[data-review-card="${current.id}:purpose"]`);
    const rows = [...pane.querySelectorAll<HTMLElement>('.agr-explanation.is-commit .lrc__head')];
    const commitCards = current.commits.map(commit => pane.querySelector<HTMLElement>(`[data-review-card="${current.id}:commit:${commit.sha}"]`));
    assert(intention?.querySelector('.lrc.is-purpose') !== null && intention?.textContent?.includes('Change intention') === true,
      'The report must begin with a distinct Change intention card');
    assert(rows.length === current.commits.length && rows.every((row, index) => row.querySelector('.agr-explanation__ordinal')?.textContent === `${index + 1}`),
      'Every commit must have its own numbered timeline event in source order');
    assert(pane.querySelectorAll('[data-review-card]').length === current.commits.length + 1,
      'A report without earlier discussions must contain only the Change intention and per-commit entries');
    for (const section of ['combined-result', 'evidence', 'coverage', 'critical-issues', 'limitations']) {
      assert(pane.querySelector(`[data-review-card="${current.id}:${section}"]`) === null,
        `The normal report must not render a redundant ${section} card`);
    }
    for (let index = 0; index < rows.length; index++) {
      const row = rows[index];
      const card = commitCards[index];
      const title = row.querySelector<HTMLElement>('.agr-commit__title');
      assert(card !== null && card.querySelector('.lrc.is-commit') !== null, 'Each commit must have its own review card');
      assert(title !== null && title.textContent === current.commits[index].message.split('\n')[0],
        'Each numbered event must show its Git commit title');
      assert(card.contains(row),
        'Each compact commit card must contain its title row');
      const more = row.querySelector<HTMLButtonElement>('.agr-commit__more');
      if (index === 0) {
        assert(more !== null && pane.querySelector('.agr-explanation__message') === null, 'The Git commit message body must start folded behind its toggle');
        more.click();
        await settle();
        const body = row.nextElementSibling;
        assert(body?.classList.contains('agr-explanation__message') === true
          && body.textContent?.includes('Represent a dotted column reference as ordered path segments.') === true
          && body.textContent.includes('Keep schema lookup in IcebergUtil'),
        'The toggle must show the Git commit message below the compact title');
        assert(card.textContent?.includes('Design:') === true && card.textContent?.includes('Implementation:') === true,
          'The agent design and implementation explanation must remain inside the separate review card');
        assert(!card.querySelector('.lrc-message__body')?.textContent?.includes('Represent a dotted column reference as ordered path segments.'),
          'The review card must not duplicate the Git commit message body');
        more.click();
        await settle();
      }
      else assert(more === null, 'A commit without a Git body must not offer a message toggle');
      const shell = card.querySelector('details');
      assert(shell !== null && parseFloat(getComputedStyle(shell).borderTopWidth) > 0,
        'The review discussion below each commit must retain its visible card boundary');
      shell.open = false;
      assert(title.getBoundingClientRect().height > 0, 'Collapsing review discussion must leave the Git commit title visible');
      shell.open = true;
      const previous = commitCards[index - 1];
      if (previous) assert(row.getBoundingClientRect().top > previous.getBoundingClientRect().bottom,
        'The next numbered commit must follow the previous commit and its review discussion');
    }
    const findingsHeading = [...pane.querySelectorAll('h2')].find(heading => heading.textContent === 'Findings');
    const lastCommit = commitCards.at(-1);
    assert(findingsHeading !== undefined && lastCommit != null && Boolean(lastCommit.compareDocumentPosition(findingsHeading)
      & Node.DOCUMENT_POSITION_FOLLOWING), 'Findings must follow the commit list');
    results.push('Change intention and numbered Git commits with separate review discussion cards lead directly to findings');

    const target = (selector: string) => {
      const element = pane.querySelector<HTMLElement>(selector);
      assert(element !== null, `Missing right-pane destination ${selector}`);
      return element;
    };
    const verifyTarget = async (selector: string) => {
      await settle();
      const destination = target(selector);
      const disclosure = destination.matches('details') ? destination as HTMLDetailsElement : destination.querySelector<HTMLDetailsElement>('details');
      assert(disclosure?.open === true, `Navigation must expand destination ${selector}`);
      let ancestor = destination.parentElement;
      while (ancestor !== null && ancestor !== pane) {
        if (ancestor instanceof HTMLDetailsElement) assert(ancestor.open, 'Navigation must expand the earlier-discussions container');
        ancestor = ancestor.parentElement;
      }
      assert(document.activeElement === destination || destination.contains(document.activeElement), `Navigation must focus ${selector} in the right pane; active ${document.activeElement?.outerHTML.slice(0, 200)}`);
      const summary = disclosure.querySelector('summary') ?? destination;
      const viewport = pane.getBoundingClientRect();
      for (let attempt = 0; attempt < 60; attempt++) {
        const rect = summary.getBoundingClientRect();
        if (rect.top >= viewport.top && rect.bottom <= viewport.bottom) return;
        await settle();
      }
      throw new Error(`Navigation must scroll ${selector} into the visible right pane`);
    };
    const open = async (turnId: string, selector: string) => {
      const card = request(turnId);
      const control = card.querySelector<HTMLButtonElement>('.agt-review-request__open');
      assert(control !== null && /^Open .+ in Summary$/.test(control.getAttribute('aria-label') ?? ''),
        'Request navigation must have an explicit accessible destination');
      control.click();
      await verifyTarget(selector);
    };
    button(pane, 'Changes').click(); await settle();
    assert(pane.querySelector('[aria-label="Changes source"]') === null, 'Changes must stay on the published PR without a source selector');
    await open('turn-review', `[data-review-card="${current.id}:purpose"]`);
    button(pane, `PR #${review.number}`).click(); await settle();
    await open('turn-commit', `[data-review-card="${commit.cardId}"]`);
    const commitDestination = target(`[data-review-card="${commit.cardId}"]`);
    const commitDisclosure = commitDestination.querySelector<HTMLDetailsElement>('details');
    assert(commitDisclosure !== null, 'Commit destination must be expandable');
    commitDisclosure.open = false;
    pane.querySelector<HTMLElement>('.agr-summary > .review-comment-scroll')?.scrollTo(0, 0);
    await open('turn-commit', `[data-review-card="${commit.cardId}"]`);
    await open('turn-finding', '[data-finding-id="finding-1"]');
    await open('turn-local', '[data-local-review-thread="local-navigation"]');
    results.push('Clicking cards changes PR tabs, opens, focuses and scrolls summary, commit, finding and local discussions');

    await open('turn-older', `[data-review-card="${original.id}:purpose"]`);
    assert(target(`[data-review-card="${original.id}:purpose"]`).textContent?.includes('Lets') === true,
      'Older questions must display their original report instead of the newest purpose');
    const earlierDiscussions = pane.querySelector<HTMLDetailsElement>('.agr-learning-history');
    assert(earlierDiscussions !== null && !earlierDiscussions.open && earlierDiscussions.textContent?.includes('Earlier discussions') === true,
      'Saved removed-section discussions must start under a collapsed Earlier discussions disclosure');
    await open('turn-limitations', `[data-review-card="${original.id}:limitations"]`);
    assert(target(`[data-review-card="${original.id}:limitations"]`).textContent?.includes(limitations.body.trim()) === true,
      'Saved limitations questions must open their exact original discussion card');
    await open('turn-reply', `[data-review-card="${current.id}:purpose"]`);
    assert(current.summary !== null, 'Current review needs a saved summary');
    assert(target(`[data-review-card="${current.id}:purpose"]`).textContent?.includes(current.summary.purpose) === true,
      'Returning to the current reply must restore its own report');
    const replyCard = request('turn-reply');
    const toggle = replyCard.querySelector<HTMLButtonElement>('.agt-review-request__toggle');
    assert(toggle !== null, 'Reply must have its own disclosure');
    toggle.click(); await settle();
    assert(toggle.getAttribute('aria-expanded') === 'true' && replyCard.innerText.includes(reply.body),
      'The separate disclosure must expose the complete user question');
    assert(!replyCard.innerText.includes('review_reply'), 'Expanding the request must keep generated instructions hidden');
    results.push('Repeated clicks, older-run identity and complete user-text disclosure work');
    const chip = conversation.querySelector<HTMLButtonElement>('.agt-pr-chip');
    assert(chip !== null && chip.textContent?.includes(`#${review.number}`) === true && chip.getAttribute('aria-pressed') === 'false',
      'The reviewer conversation must name its pull request above the composer, unpressed while WorkBench shows');
    chip.click(); await settle();
    assert(chip.getAttribute('aria-pressed') === 'true' && pane.querySelector('.pull-detail__pr-tab')?.getAttribute('aria-pressed') === 'true',
      'The pull request chip must show the pull request in the panel');
    results.push('The pull request chip above the composer opens the pull request');
    return results;
  }
  finally { flushSync(() => root.unmount()); host.remove(); Object.assign(window, { bridge: previous }); }
}
