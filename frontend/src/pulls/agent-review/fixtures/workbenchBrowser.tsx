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
import { useState } from 'react';
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import type { AgentTaskDto, Bridge } from '../../../types';
import { parseAgentTask } from '../../../agents/contracts';
import taskFixture from '../../../agents/fixtures/agent-contracts-v1.json';
import { assert, changeInput, settle } from '../../../fixtures/harness';
import TaskReviewSummary from '../TaskReviewSummary';
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
    await scenario('Starts a separate local reviewer only after an explicit click, before PR creation', async () => {
      const starts: string[] = [];
      install({ findTaskAgentReview: async () => null, readWorktreeChanges: async () => changes,
        startTaskAgentReview: async (workspaceId, taskId) => { starts.push(`${workspaceId}/${taskId}`); return review; },
        subscribeAgentReview: async () => () => {}, getAgentReview: async () => review });
      flushSync(() => root.render(<TaskReviewSummary task={task} workspaceId={task.workspaceId} onNavigateToChanges={() => {}} />));
      await settle();
      assert(starts.length === 0 && host.textContent?.includes('Uncommitted changes are excluded.') === true,
        'Opening Summary must not review or include dirty work');
      button('Review changes').click(); await settle();
      assert(Number(starts.length) === 1 && starts[0] === `${task.workspaceId}/${task.id}`, 'Review must target the source developer task');
      assert(host.textContent?.includes('Reviewer') === true && host.textContent.includes('Change intention'),
        'The existing review cards must render without PR details');
      assert(!host.textContent?.includes('Post comment now'), 'A local-only review must not offer GitHub publication');
    });
    await scenario('Reviewer questions and explicit developer fixes stay in separate conversations', async () => {
      const questions: string[] = [];
      const fixes: Parameters<Bridge['startAgentTurn']>[0][] = [];
      let fail = true;
      install({ askAgentReview: async (reviewId) => { questions.push(reviewId); return review; },
        getAgentTask: async () => task,
        startAgentTurn: async request => { fixes.push(request); if (fail) { fail = false; throw new Error('Try again'); }
          return { id: 'fix-turn' } as Awaited<ReturnType<Bridge['startAgentTurn']>>; } });
      function Surface() {
        const [value, setValue] = useState(review);
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
      button('View changes').click(); await settle();
      const request = requests.at(-1);
      assert(request?.scope === 'COMMIT' && request.commit === head && request.head === head && request.base === base,
        'The commit card must open the saved review range even when the branch has moved');
    });
    await scenario('Reassessed findings open the validated revision instead of their original run', async () => {
      const requests: Parameters<Bridge['readWorktreeChanges']>[1][] = [];
      const finding = { ...review.findings[0], revision: 2, validatedHeadSha: head };
      const updatedReview = { ...review, findings: [finding], runs: [...review.runs, { ...review.runs[0],
        id: 'updated-run', ordinal: 2, headSha: head, mergeBaseSha: base, steps: [{ number: 2, kind: 'VALIDATE' as const,
          repair: false, investigation: null, validation: { investigationStep: 1, candidates: [{ candidate: 1,
            outcome: 'SUPPORTED' as const, conclusion: 'Still present', evidenceRefs: [], counterevidence: '', findingId: finding.id,
            findingRevision: finding.revision, request: null }], coverage: { complete: true, omissions: [], warnings: [], recordedAt: '' }, newCandidates: [] } }],
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
