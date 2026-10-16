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
import type { AgentReviewDto, ReviewDraftItemDto } from '../contracts';
import { useState } from 'react';
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import type { AgentTaskDto, Bridge } from '../../../types';
import { parseAgentTask } from '../../../agents/contracts';
import taskFixture from '../../../agents/fixtures/agent-contracts-v1.json';
import { assert, changeInput, settle } from '../../../fixtures/harness';
import DevelopmentLearningPanel from '../../../agents/DevelopmentLearningPanel';
import { useLocalReviewThreads } from '../../local-review/useLocalReviewThreads';
import ReviewSummaryCard from '../ReviewSummaryCard';
import ReviewFindingCard from '../ReviewFindingCard';
import { useFindingActions } from '../useFindingActions';
import { reviewFixture } from './review';
import WorktreeChanges from '../../code-tests/WorktreeChanges';
import type { WorktreeChangesDto } from '../../code-tests/contracts';
import '../../../css/base.css';
import '../../../css/markdown-editor.css';
import '../../../css/pulls.css';
import '../../../css/agent-review.css';

const base = 'a'.repeat(40);
const head = 'b'.repeat(40);
const changes: WorktreeChangesDto = {
  branch: 'developer-branch', baseSha: base, headSha: head, baseLabel: 'main', scope: 'ALL', selectedCommit: null,
  sourceFingerprint: 'working-copy', truncated: false, staged: ['staged.ts'], unstaged: ['dirty.ts'], untracked: ['new.ts'],
  commits: [{ sha: head, parentSha: base, message: 'Handle missing configuration\n\nKeep the existing default.', author: 'Developer', authoredAt: null }],
  files: [{ filename: 'working.ts', status: 'modified', additions: 1, deletions: 1, patch: '@@ -1 +1 @@\n-old\n+new' }],
};

export async function run(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  const previous = window.bridge;
  const host = document.createElement('div');
  host.style.cssText = 'width:620px;height:700px;display:flex;flex-direction:column;background:white';
  document.body.append(host);
  const root = createRoot(host);
  const task: AgentTaskDto = { ...parseAgentTask(taskFixture.task), id: 'developer-task', state: 'OPEN', interactive: true,
    mutationClaim: null, checkoutRemovedAt: null, developmentContinuationUnfinished: false };
  const review = reviewFixture({ number: null, sourceTaskId: task.id, draft: null, pullRequest: null, messages: [] });
  const button = (label: string) => {
    const node = [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent === label);
    assert(node !== undefined, `Missing ${label}`);
    return node;
  };
  const install = (bridge: Partial<Bridge>) => Object.defineProperty(window, 'bridge', { configurable: true, value: bridge });
  const scenario = async (name: string, run: () => Promise<void>) => {
    try { await run(); results.push({ name }); }
    catch (cause) { results.push({ name, error: cause instanceof Error ? cause.message : String(cause) }); }
    finally { flushSync(() => root.render(null)); }
  };
  try {
    await scenario('Starts a local reviewer for uncommitted work only after an explicit click', async () => {
      const starts: string[] = [];
      const scopes: string[] = [];
      install({ findTaskAgentReview: async () => null, readWorktreeChanges: async (_host, selection) => {
        scopes.push(selection?.scope ?? 'ALL'); return { ...changes, commits: [] }; },
        startTaskAgentReview: async (workspaceId, taskId) => { starts.push(`${workspaceId}/${taskId}`); return review; },
        subscribeAgentReview: async () => () => {}, getAgentReview: async () => review });
      function Summary() {
        const localReview = useLocalReviewThreads(null);
        return <DevelopmentLearningPanel task={task} repository="" refreshKey={0} refining={false} disabledReason={null} localReview={localReview}
          bridge={window.bridge} review={{ workspaceId: task.workspaceId, onNavigateToChanges: () => {} }} />;
      }
      flushSync(() => root.render(<Summary />));
      await settle();
      const scope = host.querySelector('.agr-summary-strip__note');
      assert(starts.length === 0 && scope?.textContent === `main → ${head.slice(0, 7)}`
        && scope.getAttribute('title') === 'Includes committed, staged, unstaged, and new files in this working copy.' && scopes[0] === 'ALL',
        'Opening Summary must include local edits without starting a review until the user clicks');
      assert(button('Review changes').closest('.agr-summary-strip') !== null, 'Review changes must sit in the Summary strip, not in a box below the report');
      button('Review changes').click(); await settle();
      assert(Number(starts.length) === 1 && starts[0] === `${task.workspaceId}/${task.id}`, 'Review must target the source developer task');
      assert(host.querySelector('.agr-event')?.textContent?.includes('Agent review') === true
        && host.querySelectorAll('.agr-finding .lrc.is-local').length === review.findings.length && host.querySelector('[data-review-card]') === null,
        'The review must join the timeline as local finding cards without repeating the reviewer\'s own summary');
      assert(!host.textContent?.includes('Post comment now'), 'A local-only review must not offer GitHub publication');
    });
    await scenario('Reviewer questions and explicit developer fixes stay in separate conversations', async () => {
      const discussionReview = { ...review, messages: reviewFixture().messages.map(message => ({ ...message, findingId: review.findings[0].id })) };
      const questions: string[] = [];
      const fixes: Parameters<Bridge['startAgentTurn']>[0][] = [];
      let fail = true;
      install({ askAgentReview: async (reviewId) => { questions.push(reviewId); return discussionReview; },
        getAgentTask: async () => task,
        startAgentTurn: async request => { fixes.push(request); if (fail) { fail = false; throw new Error('Try again'); }
          return { id: 'fix-turn' } as Awaited<ReturnType<Bridge['startAgentTurn']>>; } });
      function Surface() {
        const [value, setValue] = useState(discussionReview);
        const { actions, busy } = useFindingActions({ review: value, onReviewChange: setValue, onNavigate: () => {} });
        return <ReviewFindingCard review={value} finding={value.findings[0]} projection="review" viewerLogin="me" viewerAvatarUrl={null}
          actions={actions} busy={busy} />;
      }
      flushSync(() => root.render(<Surface />)); await settle();
      button('Ask reviewer').click(); await settle();
      const question = host.querySelector<HTMLTextAreaElement>('textarea');
      assert(question !== null, 'Reviewer question composer must open');
      changeInput(question, 'Why does this fail?'); await settle(); button('Ask reviewer').click(); await settle();
      assert(questions[0] === review.id && fixes.length === 0, 'A question must go only to the reviewer');
      button('Request fix').click(); await settle(); button('Send to developer').click(); await settle();
      assert(host.textContent?.includes('Try again') === true, 'A failed fix request must preserve its composer');
      button('Send to developer').click(); await settle();
      assert(Number(fixes.length) === 2 && fixes[0].taskId === task.id && fixes[1].idempotencyKey === fixes[0].idempotencyKey,
        'Fixes must go to the developer and retain their identity across retry');
      assert(fixes[0].message.includes(review.findings[0].validatedHeadSha) && host.textContent?.includes('Sent to developer') === true,
        'The fix must retain its reviewed revision and confirm accepted delivery');
      assert(fixes[0].message.includes(review.findings[0].id) && fixes[0].message.includes('list_local_review_comments')
        && discussionReview.messages.every(message => !fixes[0].message.includes(message.body))
        && !fixes[0].message.includes(review.findings[0].evidence[1].excerpt), 'The developer must receive a lookup for the finding instead of a discussion and evidence dump');
    });
    await scenario('A failed GitHub post reloads its unconfirmed delivery and preserves the comment', async () => {
      let current = reviewFixture({ draft: null });
      let posts = 0;
      install({
        addReviewDraftFinding: async () => {
          const finding = current.findings[0];
          const draft: NonNullable<AgentReviewDto['draft']> = { repo: current.repo, number: 30304, body: '', event: 'COMMENT', reviewPublication: null, postedSummaryFindingIds: [], version: 1,
            updatedAt: finding.updatedAt, items: [{ id: 'outgoing', source: 'AGENT_FINDING', findingId: finding.id, findingRevision: finding.revision,
              commitId: finding.validatedHeadSha, path: finding.path, side: finding.side, line: finding.endLine, startLine: finding.startLine,
              body: `${finding.title}\n\n${finding.body}`, included: true, published: false, publication: null }] };
          current = { ...current, draft };
          return draft;
        },
        publishReviewDraftComment: async () => {
          posts++;
          assert(current.draft !== null, 'Posting must first save the outgoing comment');
          current = { ...current, draft: { ...current.draft, version: 3, items: current.draft.items.map((item): ReviewDraftItemDto => ({ ...item, publication: {
            state: 'UNCERTAIN', remoteReviewId: null, remoteCommentId: null, url: null, at: current.createdAt,
          } })) } };
          throw new Error('Network connection lost');
        },
        getAgentReview: async () => current,
      });
      function Surface() {
        const [value, setValue] = useState(current);
        const { actions, busy } = useFindingActions({ review: value, onReviewChange: setValue, onNavigate: () => {} });
        return <ReviewFindingCard review={value} finding={value.findings[0]} projection="review" viewerLogin="me" viewerAvatarUrl={null}
          actions={actions} busy={busy} />;
      }
      flushSync(() => root.render(<Surface />)); await settle();
      button('Post comment now').click(); await settle();
      button('Post now').click(); await settle();
      assert(host.textContent?.includes('Network connection lost') === true && host.textContent.includes('GitHub did not confirm this comment'),
        'The network error and saved uncertain delivery must both be visible immediately');
      assert(!host.textContent?.includes('Posted to GitHub') && host.querySelector('.agr-notice.is-published') === null,
        'A failed post must never claim success');
      assert(button('Post now').disabled && !button('Cancel').disabled, 'An uncertain post must block resending while allowing the composer to close');
      button('Cancel').click(); await settle();
      assert(button('Edit').disabled && button('Remove').disabled && !button('Ask reviewer').disabled,
        'Uncertain outgoing comments must retain their text and private discussion');
      assert(posts === 1 && host.querySelector('.agr-draft__body')?.textContent?.includes(current.findings[0].title)
        && current.draft?.items[0].body === `${current.findings[0].title}\n\n${current.findings[0].body}`,
        'Failure must preserve the outgoing text without retrying the GitHub write');
    });
    await scenario('Published and withdrawn findings keep their private reviewer conversation open', async () => {
      const finding = { ...review.findings[0], state: 'WITHDRAWN' as const, outdated: true, stateReason: 'Fixed by the latest commit' };
      const published = reviewFixture({ findings: [finding], messages: reviewFixture().messages.map(message => ({ ...message, findingId: finding.id })), draft: {
        repo: review.repo, number: 30304, body: '', event: 'COMMENT', reviewPublication: null, postedSummaryFindingIds: [], version: 1, updatedAt: finding.updatedAt,
        items: [{ id: 'published-item', source: 'AGENT_FINDING', findingId: finding.id, findingRevision: finding.revision,
          commitId: finding.validatedHeadSha, path: finding.path, side: finding.side, line: finding.endLine, startLine: finding.startLine,
          body: finding.body, included: true, published: true, publication: null }],
      } });
      const questions: Parameters<Bridge['askAgentReview']>[] = [];
      install({ askAgentReview: async (...args) => { questions.push(args); return published; } });
      function Surface() {
        const [value, setValue] = useState(published);
        const { actions, busy } = useFindingActions({ review: value, onReviewChange: setValue, onNavigate: () => {} });
        return <ReviewFindingCard review={value} finding={value.findings[0]} projection="review" viewerLogin="me" viewerAvatarUrl={null}
          actions={actions} busy={busy} />;
      }
      flushSync(() => root.render(<Surface />)); await settle();
      assert(host.textContent?.includes('Posted to GitHub as a review comment') === true, 'The finding must retain its publication status');
      for (const message of ['Why was this withdrawn?', 'Which check confirmed the fix?']) {
        assert(!button('Ask reviewer').disabled, 'Publication and withdrawal must not disable private questions');
        button('Ask reviewer').click(); await settle();
        const input = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Ask the reviewer about this finding"]');
        assert(input !== null, 'The private question composer must open');
        changeInput(input, message); await settle(); button('Ask reviewer').click(); await settle();
        assert(JSON.stringify(questions.at(-1)) === JSON.stringify([published.id, finding.id, finding.revision, message]),
          'Each question must keep the original private finding identity and revision');
        assert(host.querySelector('textarea') === null, 'Sending must close the composer and allow another question');
      }
      assert(questions.length === 2 && host.querySelectorAll('.agr-thread .lrc-message').length === published.messages.length,
        'Repeated questions must preserve the existing private conversation');
      assert(!host.textContent?.includes('Post comment now') && !host.textContent?.includes('Add to my review'),
        'Continuing discussion must not offer to repost the finding');
    });
    await scenario('Local diff selection rejects stale responses and preserves commit inspection', async () => {
      const pending: Array<{ scope: string; resolve: (value: WorktreeChangesDto) => void }> = [];
      install({ readWorktreeChanges: async (_host, request) => {
        if (request.scope === 'ALL') return changes;
        return new Promise(resolve => pending.push({ scope: request.scope, resolve }));
      } });
      flushSync(() => root.render(<WorktreeChanges host={{ kind: 'task', taskId: task.id, workspaceId: task.workspaceId }} />));
      await settle();
      const select = host.querySelector<HTMLSelectElement>('select');
      assert(select !== null && host.textContent?.includes('1 staged · 1 unstaged · 1 new files') === true,
        'The task diff must expose all uncommitted categories');
      select.value = 'UNCOMMITTED'; select.dispatchEvent(new Event('change', { bubbles: true })); await settle();
      select.value = `commit:${head}`; select.dispatchEvent(new Event('change', { bubbles: true })); await settle();
      pending.find(item => item.scope === 'COMMIT')?.resolve({ ...changes, scope: 'COMMIT', selectedCommit: head, sourceFingerprint: null,
        files: [{ ...changes.files[0], filename: 'committed.ts' }] }); await settle();
      pending.find(item => item.scope === 'UNCOMMITTED')?.resolve({ ...changes, scope: 'UNCOMMITTED',
        files: [{ ...changes.files[0], filename: 'stale-dirty.ts' }] }); await settle();
      assert(host.textContent?.includes('committed.ts') === true && !host.textContent?.includes('stale-dirty.ts'),
        'A late dirty response must not replace the selected commit');
      assert(host.textContent?.includes('Keep the existing default.') === true, 'The selected commit body must be visible');
      const details = host.querySelector<HTMLDetailsElement>('.wb-changes__details');
      const scroll = host.querySelector<HTMLElement>('.pull-changes__scroll');
      assert(details !== null && !details.open && scroll !== null, 'Commit metadata must start folded in the toolbar');
      const top = scroll.getBoundingClientRect().top;
      assert(top - host.getBoundingClientRect().top < 90, 'The compact toolbar must leave the panel height for changed files');
      details.querySelector('summary')?.click(); await settle();
      assert(details.open && scroll.getBoundingClientRect().top === top, 'Opening commit details must not push files down');
      details.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); await settle();
      assert(!details.open, 'Escape must dismiss the metadata disclosure');
      assert(host.scrollWidth <= host.clientWidth + 1, 'The controls and diff must fit the panel');
    });
    await scenario('Historical commit inspection retains the reviewed head and comparison base', async () => {
      const requests: Parameters<Bridge['readWorktreeChanges']>[1][] = [];
      const run = { ...review.runs[0], headSha: head, mergeBaseSha: base, commits: changes.commits.map(commit => ({
        ...commit, message: commit.message ?? '', author: commit.author ?? '',
      })) };
      install({ readWorktreeChanges: async (_host, request) => { requests.push(request); return {
        ...changes, scope: request.scope, selectedCommit: request.commit ?? null,
      }; } });
      function Surface() {
        const [selection, setSelection] = useState<{ commitSha: string; token: number; headSha?: string; baseSha?: string } | null>(null);
        return <><ReviewSummaryCard review={review} run={run} findings={[]} viewerLogin="me" viewerAvatarUrl={null}
          busy={false} onAsk={async () => {}} onSelectFinding={() => {}}
          onSelectCommit={(sha, snapshot) => setSelection({ commitSha: sha, token: 1, ...snapshot })} />
          <WorktreeChanges host={{ kind: 'review', reviewId: review.id }} selection={selection} /></>;
      }
      flushSync(() => root.render(<Surface />)); await settle();
      button('Diff').click(); await settle();
      const request = requests.at(-1);
      assert(request?.scope === 'COMMIT' && request.commit === head && request.head === head && request.base === base,
        'The commit card must open the saved review range even when the branch has moved');
    });
    await scenario('Reassessed findings open the validated revision instead of their original run', async () => {
      const requests: Parameters<Bridge['readWorktreeChanges']>[1][] = [];
      const finding = { ...review.findings[0], revision: 2, validatedHeadSha: head };
      const updatedReview: AgentReviewDto = { ...review, findings: [finding], runs: [...review.runs, { ...review.runs[0],
        id: 'updated-run', ordinal: 2, headSha: head, mergeBaseSha: base, steps: [{ number: 2, kind: 'VALIDATE' as const,
          repair: false, investigation: null, validation: { investigationStep: 1, candidates: [{ candidate: 1,
            outcome: 'SUPPORTED' as const, conclusion: 'Still present', evidenceRefs: [], counterevidence: '', findingId: finding.id,
            findingRevision: finding.revision, request: null }], coverage: { complete: true, omissions: [], warnings: [], recordedAt: '' }, newCandidates: [], selectedFindingIds: [finding.id], selectionExplanation: 'Still present.' } }],
      }] };
      install({ readWorktreeChanges: async (_host, request) => { requests.push(request); return { ...changes, scope: request.scope }; } });
      flushSync(() => root.render(<WorktreeChanges host={{ kind: 'review', reviewId: review.id }} review={updatedReview}
        reviewNavigation={{ findingId: finding.id, path: finding.path ?? '', side: 'RIGHT', startLine: 1, endLine: 1, token: 1 }} />));
      await settle();
      assert(requests.at(-1)?.head === head && requests.at(-1)?.base === base,
        'The finding must use the later validation snapshot while retaining its original identity');
    });
    return results;
  }
  finally {
    flushSync(() => root.unmount()); host.remove(); Object.defineProperty(window, 'bridge', { configurable: true, value: previous });
  }
}
