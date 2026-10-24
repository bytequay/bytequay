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
import { createFixtureRoot } from '../../fixtures/reactFixture';
import { timelineActivity, timelineComment, prDetail } from './pullRequest';
import type { Bridge, DiffCommitDto, DiffFileDto, PullRequestDetailDto } from '../../types';
import PullsScreen, { remotePullRequest, ReviewDialog } from '../PullsScreen';
import { createPreviewBridge } from '../../agents/fixtures/previewBridge';
import { reviewDraftFixture, reviewFixture } from '../agent-review/fixtures/review';
import '../../css/base.css';
import { textButton, element as fixtureElement, assert, changeInput, settle } from '../../fixtures/harness';
import { DETAIL_REFRESH_INTERVAL } from '../usePullRequestDetail';
import type { ReviewDraftDto, ReviewDraftItemDto } from '../agent-review/contracts';

const review = reviewFixture({ id: 'pending-review', state: 'CLOSED', closedAt: '2026-09-07T00:00:00Z' });
const stored = prDetail({
  id: 9001, repo: review.repo, number: review.number, title: 'Sort by nested fields', htmlUrl: null,
  mergeable: null, mergeableState: null, author: 'alice', updatedAt: '2026-01-01T00:00:00Z',
  baseRef: 'master', body: 'Implementation',
  reviewers: [{ login: 'bob', avatarUrl: null, state: 'PENDING' }], deletions: 1,
  recentActivity: [timelineActivity({ githubId: 61, actor: 'alice', timestamp: null, body: 'Existing discussion' }, { path: 'src/Example.java', line: 1, originalLine: 1, side: 'RIGHT',
      comments: [timelineComment({ githubId: 61, actor: 'alice', timestamp: null, body: 'Existing discussion', reviewId: 50 })] })],
});
const files: DiffFileDto[] = [{ filename: 'src/Example.java', status: 'modified', additions: 1, deletions: 1, patch: '@@ -1 +1 @@\n-old\n+new' }];
const commits: DiffCommitDto[] = [
  { sha: 'one', parentSha: 'base', message: 'one', author: null, authoredAt: null },
  { sha: 'two', parentSha: 'one', message: 'two', author: null, authoredAt: null },
];

/**
 * A staged review lasts as long as the pull request page.
 *
 * A background refresh used to drop it: the staged comments were keyed to the diff revision, and
 * GitHub restates a busy pull request's totals within seconds of opening it, so the comments were
 * gone by the time the reader came back from another tab.
 */
export async function run(): Promise<{ name: string; error?: string }[]> {
  const agent = createPreviewBridge(review.workspaceId, { replayThrough: Number.MAX_SAFE_INTEGER });
  let detail = stored;
  let reviewedReads = 0;
  // The review has no draft yet, so the sheet loads one; every review leaves through it.
  let saved: ReviewDraftDto = reviewDraftFixture({ repo: stored.repo, number: stored.number, version: 0, updatedAt: '2026-09-07T00:00:00Z' });
  const added: Parameters<Bridge['addReviewDraftComment']>[3][] = [];
  const submissions: Parameters<Bridge['submitReviewDraft']>[] = [];
  let polls: (() => void)[] = [];
  const originalTimeout = window.setTimeout;
  window.setTimeout = ((handler: TimerHandler, delay?: number, ...args: unknown[]) => {
    if (delay === DETAIL_REFRESH_INTERVAL && typeof handler === 'function') {
      polls.push(() => handler(...args));
      return 1_000_000;
    }
    return originalTimeout(handler, delay, ...args);
  }) as typeof window.setTimeout;
  const stubs = {
    getUserProfile: async () => ({ login: 'bob', name: null, avatarUrl: '', htmlUrl: '', publicRepos: 0, followers: 0,
      following: 0, bio: null, location: null, company: null, email: null }),
    readAgentReviewHistory: async (_id, taskId, before, after) => agent.readAgentHistory(taskId, before, after),
    fetchAttentionPrs: async () => [],
    fetchPullRequestDetailByRef: async () => detail,
    refreshPullRequestDetailByRef: async () => ({ full: true as const, detail, status: null }),
    refreshAllPullRequestDetailByRef: async () => detail,
    fetchPrDiffFiles: async () => files,
    fetchPrDiffCommits: async () => commits,
    refreshPrDiffFiles: async () => files,
    readWorktreeChanges: async (host, request) => {
      assert(host.kind === 'review' && host.reviewId === review.id, 'Reviewed changes must read the separate reviewer worktree');
      reviewedReads++;
      return { branch: 'reviewer', baseSha: 'base', headSha: 'local-only', baseLabel: 'master', scope: request.scope,
        selectedCommit: null, sourceFingerprint: 'local-only', truncated: false, staged: [], unstaged: ['local.txt'], untracked: [],
        files: [{ filename: 'local.txt', status: 'modified', additions: 1, deletions: 0, patch: '@@ -0,0 +1 @@\n+Unpublished reviewer edit' }], commits: [] };
    },
    getAgentReview: async () => review,
    findAgentReview: async () => review,
    findPullRequestTask: async () => null,
    listWorkspaces: async () => [],
    listLocalReviewComments: async () => [],
    listCodeFiles: async () => ({ source: 'WORKING_COPY', revision: 'one', truncated: false,
      sourceFingerprint: null, savedExperiment: false, files: [] }),
    listCodeRuns: async () => [],
    listAgentReviews: async () => [review],
    subscribeAgentReview: async () => () => {},
    fetchMergeMode: async () => ({ mergeQueue: false }),
    getReviewDraft: async () => saved,
    saveReviewDraft: async (_repo, _number, version, body, event) => (saved = { ...saved, body, event, version: version + 1 }),
    addReviewDraftComment: async (_repo, _number, version, comment) => { added.push(comment); return (saved = { ...saved, version: version + 1 }); },
    submitReviewDraft: async (...request) => { submissions.push(request); },
    recordRecentTouch: async () => {},
  } satisfies Pick<Bridge, 'getUserProfile' | 'readAgentReviewHistory' | 'fetchAttentionPrs' | 'fetchPullRequestDetailByRef'
    | 'refreshPullRequestDetailByRef' | 'refreshAllPullRequestDetailByRef' | 'fetchPrDiffFiles' | 'fetchPrDiffCommits'
    | 'refreshPrDiffFiles' | 'readWorktreeChanges' | 'getAgentReview' | 'findAgentReview' | 'findPullRequestTask' | 'listWorkspaces'
    | 'listLocalReviewComments' | 'listCodeFiles' | 'listCodeRuns' | 'listAgentReviews' | 'subscribeAgentReview'
    | 'fetchMergeMode' | 'getReviewDraft' | 'saveReviewDraft' | 'addReviewDraftComment' | 'submitReviewDraft' | 'recordRecentTouch'>;
  const previous = window.bridge;
  Object.assign(window, { bridge: { ...agent, ...stubs } });
  const { host, root, dispose } = createFixtureRoot('main');
  host.style.cssText = 'height: 900px; width: 1600px';
  const button = (text: string) => {
    const found = [...host.querySelectorAll<HTMLButtonElement>('button')].find(entry => entry.textContent?.includes(text));
    assert(found !== undefined, `Missing ${text} button`);
    return found;
  };
  const named = (text: string) => [...host.querySelectorAll<HTMLButtonElement>('button')].some(entry => entry.textContent?.includes(text));
  /** The staged comment counted in the toolbar and drawn on the line it was written on. */
  const staged = async (): Promise<boolean> => {
    for (let attempt = 0; attempt < 30; attempt++) {
      if (host.querySelector('[aria-label^="Submit review, 1 pending"]') !== null
        && host.textContent?.includes('Keep this pending review') === true) return true;
      await settle();
    }
    return false;
  };
  // The detail poll, answered the way a busy pull request answers it.
  const refreshed = async (value: PullRequestDetailDto) => {
    detail = value;
    assert(polls.length > 0, 'Detail polling must schedule its next refresh');
    const due = polls;
    polls = [];
    for (const callback of due) callback();
    for (let attempt = 0; attempt < 10 && polls.length === 0; attempt++) await settle();
    assert(polls.length > 0, 'The detail refresh must finish and schedule the next poll');
  };
  try {
    flushSync(() => root.render(<PullsScreen initialPr={{ repo: review.repo, number: review.number }} initialReviewId={review.id}
      repositoryScope={{ workspaceId: review.workspaceId, repository: review.repo }} reviewWorkspace={{ onBack: () => {} }} />));
    for (let attempt = 0; attempt < 60 && !named('WorkBench'); attempt++) await settle();
    button('WorkBench').click(); await settle();
    button('Changes').click(); await settle();
    assert(host.querySelector('[aria-label="Changes source"]') === null,
      'A linked PR must expose a single Changes view without a source selector');
    for (let attempt = 0; attempt < 30 && host.querySelector('[aria-label="Add a comment on new line 1"]') === null; attempt++) await settle();
    const add = fixtureElement<HTMLButtonElement>('[aria-label="Add a comment on new line 1"]', host, 'The diff must offer the inline comment gutter');
    add.click();
    await settle();
    const input = fixtureElement<HTMLTextAreaElement>('.pull-diff-review textarea', host, 'Selecting a line must open a composer');
    changeInput(input, 'Keep this pending review');
    await settle();
    button('Add to review').click();
    await settle();
    assert(await staged(), 'Adding an inline comment must stage it on the diff');

    assert(reviewedReads === 0 && !host.textContent?.includes('Unpublished reviewer edit'),
      'A different reviewer working copy must not replace the published diff');

    button('PR #').click();
    await settle();
    button('WorkBench').click(); await settle();
    button('Tears of time').click();
    for (let attempt = 0; attempt < 30 && host.querySelector('.pull-detail__code') === null; attempt++) await settle();
    button('WorkBench').click(); await settle();
    button('Changes').click(); await settle();
    assert(await staged(), 'A trip through the other panel tabs must keep the staged review');

    // GitHub restates a busy pull request's totals on the next refresh, which used to reach the
    // staged review through the diff revision.
    await refreshed({ ...detail, additions: 2, updatedAt: '2026-01-02T00:00:00Z' });
    assert(await staged(), 'A background detail refresh must keep the staged review');

    const range = fixtureElement<HTMLInputElement>('[aria-label="Select two: two"]', host, 'The diff must offer its commit picker');
    range.click();
    for (let attempt = 0; attempt < 30 && host.querySelector('[aria-label="Deselect two: two"]') === null; attempt++) await settle();
    assert(await staged(), 'Narrowing the diff to selected commits must keep the staged review');

    button('PR #').click(); await settle();
    button('Reply').click(); await settle();
    const reply = fixtureElement<HTMLTextAreaElement>('[aria-label="Reply to review thread"]', host, 'Existing review threads must offer a reply editor');
    changeInput(reply, 'Include this reply in my review'); await settle();
    button('Add to review').click(); await settle();
    assert(submissions.length === 0 && host.textContent?.includes('Include this reply in my review'),
      'Adding a reply must keep it local and visible in its thread');
    assert(host.querySelector('[aria-label^="Submit review, 2 pending"]') !== null, 'Replies must count toward the pending review');
    const thread = host.querySelector('.pr-inline-thread');
    assert(thread?.querySelector(':scope > summary .pull-diff-pending__badge')?.textContent === 'Pending',
      'Pending replies must mark the existing thread header');
    const pendingMessage = thread?.querySelector('.lrc-message:last-of-type');
    assert(pendingMessage?.querySelector('.user-avatar-trigger strong')?.textContent === 'bob'
      && pendingMessage.querySelector('.lrc-message__menu') === null, 'Pending replies must use the normal author row without a Remove menu');
    button('Reply').click(); await settle();
    const extraReply = fixtureElement<HTMLTextAreaElement>('[aria-label="Reply to review thread"]', host, 'The thread can stage another reply');
    changeInput(extraReply, 'Remove only this reply'); await settle();
    button('Add to review').click(); await settle();
    const remove = host.querySelector<HTMLButtonElement>('.lrc__foot [aria-label="Remove pending reply 2"]');
    assert(remove !== null && remove.nextElementSibling?.textContent?.trim() === 'Resolve', 'Remove must sit beside Resolve');
    remove.click(); await settle();
    assert(host.textContent?.includes('Include this reply in my review') && !host.textContent?.includes('Remove only this reply')
      && submissions.length === 0, 'Removing a pending reply must preserve the other reply without publishing');
    button('WorkBench').click(); await settle();
    button('Summary').click(); await settle();
    button('Submit review').click(); await settle();
    const submit = host.querySelector<HTMLButtonElement>('.pr-review-sheet__footer .is-primary');
    assert(submit !== null && !submit.disabled, 'WorkBench Summary must open the shared review dialog with the staged comment');
    const card = fixtureElement<Element>('.pr-review-sheet__pending .pr-thread-comment', host, 'The pending comment must appear in the review sheet');
    const edit = fixtureElement<HTMLButtonElement>('.pr-review-sheet__edit', card, 'Pending comments must offer Edit beside Remove');
    edit.click(); await settle();
    let editor = card.querySelector<HTMLTextAreaElement>('[aria-label="Edit pending review comment"]');
    assert(editor !== null && editor.value === 'Keep this pending review' && submit.disabled,
      'Editing must start with the saved body and block submission of the old text');
    changeInput(editor, 'Discard this edit'); await settle();
    [...card.querySelectorAll<HTMLButtonElement>('.pull-diff-review footer button')].find(button => button.textContent === 'Cancel')?.click(); await settle();
    assert(card.textContent?.includes('Keep this pending review') && !submit.disabled, 'Cancel must keep the original comment');
    edit.click(); await settle();
    editor = card.querySelector<HTMLTextAreaElement>('[aria-label="Edit pending review comment"]');
    assert(editor !== null, 'The comment editor must reopen');
    changeInput(editor, ' \n '); await settle();
    assert(card.querySelector<HTMLButtonElement>('.is-primary')?.disabled, 'Blank comment edits cannot be saved');
    const editedBody = 'Updated pending review\n\n```suggestion\ntry (writer) { finish(); }\n```';
    changeInput(editor, editedBody); await settle();
    card.querySelector<HTMLButtonElement>('[role="tab"]:last-child')?.click(); await settle();
    assert(card.querySelector('.pull-diff-review__preview')?.textContent?.includes('try (writer) { finish(); }'),
      'Preview must preserve suggestion blocks');
    card.querySelector<HTMLButtonElement>('.is-primary')?.click(); await settle();
    assert(card.querySelector('textarea') === null && card.textContent?.includes('Updated pending review') && !submit.disabled,
      'Save must update the pending card without leaving the sheet');
    assert(submissions.length === 0, 'Opening and staging review comments must not publish them');
    submit.click(); await settle();
    assert(Number(submissions.length) === 1, 'The explicit submit action must publish the review exactly once');
    const [repo, number, version, , event, headSha] = submissions[0];
    assert(repo === detail.repo && number === detail.number && version === saved.version && event === 'COMMENT' && headSha === stored.headSha && added.length === 2
      && added[0].path === files[0].filename && added[0].side === 'RIGHT' && added[0].line === 1
      && added[0].commitId === 'two' && added[0].body === editedBody
      && added[1].replyToCommentId === 61 && added[1].body === 'Include this reply in my review',
    'Submitting from WorkBench must preserve the PR, revision, line, and staged comment');
    assert(host.querySelector('.pr-review-sheet') === null && host.querySelector('[aria-label^="Submit review, 2 pending"]') === null,
      'Successful submission must close the dialog and clear the staged comment');
    return [{ name: 'A staged review lasts as long as the pull request page' }];
  }
  catch (error) { return [{ name: 'A staged review lasts as long as the pull request page', error: error instanceof Error ? error.message : String(error) }]; }
  finally {
    window.setTimeout = originalTimeout;
    dispose();
    Object.assign(window, { bridge: previous });
  }
}

export async function runDraftCommentEdit(): Promise<string> {
  let draft: ReviewDraftDto = reviewDraftFixture({ repo: stored.repo, number: stored.number, body: 'Review summary', version: 4,
    updatedAt: '2026-09-07T00:00:00Z', items: [{ id: 'draft-comment', source: 'AGENT_FINDING', findingId: 'finding-1', findingRevision: 1,
      path: files[0].filename, side: 'RIGHT', line: 1, startLine: null, commitId: 'two', body: 'Original finding', included: true,
      published: false, publication: null }] });
  let currentReview = reviewFixture({ draft });
  const edits: Parameters<Bridge['editReviewDraftComment']>[] = [];
  let saveError: string | null = 'The draft changed. Try again.';
  let finishSave = () => {};
  let confirmationError: string | null = 'Could not update the pending review. Try again.';
  let finishConfirmation = () => {};
  const confirmations: Parameters<Bridge['confirmReviewDraftCommentNotPosted']>[] = [];
  let published = 0;
  let closed = false;
  const bridge = {
    getUserProfile: async () => null,
    betterWords: async (text, repo, workspaceId) => {
      assert(text === 'Original finding' && repo === currentReview.repo && workspaceId === currentReview.workspaceId,
        'Better words must rewrite the outgoing comment with its repository and workspace');
      return 'Edited finding';
    },
    fetchPrDiffFiles: async () => files,
    fetchPrDiffCommits: async () => commits,
    getAgentReview: async () => currentReview,
    editReviewDraftComment: async (...request) => {
      edits.push(request);
      await new Promise<void>(resolve => { finishSave = resolve; });
      if (saveError !== null) throw new Error(saveError);
      const [, , version, id, body, included] = request;
      assert(version === draft.version, 'Editing must use the displayed draft version');
      draft = { ...draft, version: version + 1, items: draft.items.map(item => item.id === id ? { ...item, body, included } : item) };
      return draft;
    },
    confirmReviewDraftCommentNotPosted: async (...request) => {
      confirmations.push(request);
      await new Promise<void>(resolve => { finishConfirmation = resolve; });
      if (confirmationError !== null) throw new Error(confirmationError);
      const [repo, number, version, itemId] = request;
      assert(repo === stored.repo && number === stored.number && version === draft.version && itemId === draft.items[0].id,
        'Confirmation must identify the current saved comment and version');
      draft = { ...draft, version: version + 1, items: draft.items.map(item => item.id === itemId ? { ...item, publication: null } : item) };
      return draft;
    },
    saveReviewDraft: async (_repo, _number, version, body, event) => {
      assert(version === draft.version, 'Submitting must use the version returned by the comment edit');
      draft = { ...draft, body, event, version: version + 1 };
      return draft;
    },
    submitReviewDraft: async (_repo, _number, version) => {
      assert(version === draft.version && draft.items[0].body === 'Edited finding', 'Submission must use the saved comment');
      published++;
    },
    fetchPullRequestDetailByRef: async () => stored,
  } satisfies Pick<Bridge, 'getUserProfile' | 'fetchPrDiffFiles' | 'fetchPrDiffCommits' | 'getAgentReview' | 'editReviewDraftComment'
    | 'saveReviewDraft' | 'submitReviewDraft' | 'fetchPullRequestDetailByRef' | 'betterWords' | 'confirmReviewDraftCommentNotPosted'>;
  const previous = window.bridge;
  Object.assign(window, { bridge });
  const { host, root, dispose } = createFixtureRoot('main');
  const render = () => root.render(<ReviewDialog pr={remotePullRequest(stored, 'bob')} detail={stored} review={currentReview}
    onReviewChange={value => { currentReview = value; render(); }} onReviewSubmitted={() => {}} onClose={() => { closed = true; }} />);
  const button = (selector: string) => {
    const element = host.querySelector<HTMLButtonElement>(selector);
    assert(element !== null, `Missing ${selector}`);
    return element;
  };
  try {
    flushSync(render); await settle();
    button('.pr-review-sheet__edit').click(); await settle();
    const input = host.querySelector<HTMLTextAreaElement>('[aria-label="Edit pending review comment"]');
    assert(input !== null && input.value === draft.items[0].body, 'Saved draft comments must open in the same editor');
    button('.pull-diff-review .pr-better-words').click(); await settle();
    assert(input.value === 'Edited finding' && edits.length === 0, 'Better words must update only the editor until Save');
    button('[aria-label="Close review dialog"]').click(); await settle();
    assert(!closed && input.value === 'Edited finding', 'Closing must not silently discard an active comment edit');
    button('.pull-diff-review .is-primary').click(); await settle();
    assert(input.disabled && button('.pr-review-sheet__footer .is-primary').disabled && Number(edits.length) === 1,
      'Saving must block duplicate edits and review submission');
    finishSave(); await settle();
    assert(!input.disabled && input.value === 'Edited finding' && host.querySelector('[role="alert"]')?.textContent === saveError,
      'A failed save must retain editable text and show the error');
    saveError = null;
    button('.pull-diff-review .is-primary').click(); await settle();
    finishSave(); await settle();
    assert(host.querySelector('[aria-label="Edit pending review comment"]') === null
      && host.querySelector('.pr-review-sheet__pending')?.textContent?.includes('Edited finding') && published === 0,
      'Saving a draft edit updates its card without publishing');
    assert(draft.items[0].commitId === 'two' && draft.items[0].path === files[0].filename && draft.items[0].line === 1
      && currentReview.findings[0].body !== 'Edited finding', 'Editing must preserve the anchor and leave the agent finding unchanged');
    currentReview = { ...currentReview, findings: currentReview.findings.map(finding => ({ ...finding,
      state: 'RESOLVED', revision: finding.revision + 1, body: 'The agent changed its conclusion', stateReason: 'Discussed locally' })) };
    flushSync(render); await settle();
    assert(host.querySelector('.pr-review-sheet__pending')?.textContent?.includes('Edited finding')
      && !host.querySelector('.pr-review-sheet__pending')?.textContent?.includes('The agent changed its conclusion')
      && !button('.pr-review-sheet__footer .is-primary').disabled,
      'Resolving or revising the agent finding must not change the saved comment or block submission');

    draft = { ...draft, version: draft.version + 1, items: draft.items.map((item): ReviewDraftItemDto => ({ ...item, publication: {
      state: 'UNCERTAIN', remoteReviewId: null, remoteCommentId: null, url: null, at: draft.updatedAt,
    } })) };
    currentReview = { ...currentReview, draft };
    flushSync(render); await settle();
    const confirm = () => textButton('Confirmed not posted', host, 'Unconfirmed comments must offer explicit recovery');
    const summary = fixtureElement<HTMLTextAreaElement>('[aria-label="Review comment"]', host, 'Review summary must remain editable');
    changeInput(summary, 'Review summary with retries'); await settle();
    assert(button('.pr-review-sheet__footer .is-primary').disabled && confirmations.length === 0 && published === 0,
      'An uncertain comment must never be retried on opening the sheet');
    confirm().click(); confirm().click(); await settle();
    assert(confirm().disabled && Number(confirmations.length) === 1, 'Confirmation must prevent duplicate requests while saving');
    finishConfirmation(); await settle();
    assert(host.textContent?.includes(confirmationError) && !confirm().disabled && button('.pr-review-sheet__footer .is-primary').disabled,
      'Failed confirmation must keep the comment unconfirmed and allow retrying the local action');
    confirmationError = null;
    confirm().click(); await settle(); finishConfirmation(); await settle();
    assert(host.querySelector('.pr-review-sheet__pending')?.textContent?.includes('Pending')
      && !host.querySelector('.pr-review-sheet__pending')?.textContent?.includes('Delivery unconfirmed')
      && !button('.pr-review-sheet__footer .is-primary').disabled && published === 0 && Number(confirmations.length) === 2,
      'Confirmation must restore a pending comment without publishing it');
    assert(summary.value === 'Review summary with retries' && draft.items[0].body === 'Edited finding' && draft.items[0].commitId === 'two',
      'Recovery must preserve unsaved review prose, the saved comment, and its original commit');
    button('.pr-review-sheet__footer .is-primary').click(); await settle();
    assert(Number(published) === 1 && closed && draft.body === 'Review summary with retries', 'Explicit submission must publish the saved edit and preserve review prose');
    return 'Saved draft comments support editing, retry and explicit submission';
  }
  finally {
    dispose();
    Object.assign(window, { bridge: previous });
  }
}

export async function runConfirmedPublication(): Promise<string> {
  const previous = window.bridge;
  const { host, root, dispose } = createFixtureRoot('main');
  try {
    for (const summary of [false, true]) {
      const finding = { ...reviewFixture().findings[0], state: 'OPEN' as const };
      const uncertain: NonNullable<ReviewDraftDto['reviewPublication']> = { state: 'UNCERTAIN', remoteReviewId: null, remoteCommentId: null, url: null, at: finding.updatedAt };
      const draft: ReviewDraftDto = reviewDraftFixture({ repo: stored.repo, number: stored.number, body: 'Review summary',
        reviewPublication: summary ? uncertain : null, version: 4, updatedAt: finding.updatedAt,
        items: summary ? [] : [{ id: 'confirmed-finding', source: 'AGENT_FINDING', findingId: finding.id, findingRevision: finding.revision,
          path: files[0].filename, side: 'RIGHT', line: 1, startLine: null, commitId: 'two', body: finding.body, included: true,
          published: false, publication: uncertain }] });
      let currentReview = reviewFixture({ findings: [finding], draft });
      let savedReview = currentReview;
      let reads = 0;
      const confirm = () => {
        const updated: ReviewDraftDto = { ...draft, body: summary ? '' : draft.body, reviewPublication: null, version: draft.version + 1,
          postedSummaryFindingIds: summary ? [finding.id] : [],
          items: draft.items.map(item => ({ ...item, published: true, publication: { ...uncertain, state: 'PUBLISHED' } })) };
        savedReview = { ...savedReview, draft: updated, findings: [{ ...finding, state: 'RESOLVED', version: finding.version + 1 }] };
        return updated;
      };
      const bridge = {
        getUserProfile: async () => null,
        fetchPrDiffFiles: async () => files,
        fetchPrDiffCommits: async () => commits,
        getAgentReview: async () => { reads++; return savedReview; },
        confirmReviewDraftCommentPosted: async () => confirm(),
        confirmReviewDraftReview: async (_repo, _number, _version, posted) => {
          assert(posted, 'This scenario confirms a posted review');
          return confirm();
        },
      } satisfies Pick<Bridge, 'getUserProfile' | 'fetchPrDiffFiles' | 'fetchPrDiffCommits' | 'getAgentReview'
        | 'confirmReviewDraftCommentPosted' | 'confirmReviewDraftReview'>;
      Object.assign(window, { bridge });
      const render = () => root.render(<ReviewDialog key={String(summary)} pr={remotePullRequest(stored, 'bob')} detail={stored}
        review={currentReview} onReviewChange={value => { currentReview = value; render(); }} onReviewSubmitted={() => {}} onClose={() => {}} />);
      flushSync(render); await settle();
      const button = [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent === 'Confirmed posted');
      assert(button !== undefined && reads === 0 && currentReview.findings[0].state === 'OPEN', 'An uncertain send must leave its finding open');
      button.click(); await settle();
      assert(Number(reads) === 1 && String(currentReview.findings[0].state) === 'RESOLVED' && currentReview.findings[0].version === finding.version + 1,
        `Confirming a posted ${summary ? 'review summary' : 'comment'} must refresh the resolved finding, not only the draft`);
      assert(currentReview.draft?.version === draft.version + 1 && host.textContent?.includes('Confirmed posted') === false,
        'The refreshed review must preserve the confirmed publication and clear its recovery controls');
    }
    return 'Confirmed publications refresh resolved findings for comments and review summaries';
  }
  finally {
    dispose();
    Object.assign(window, { bridge: previous });
  }
}

export async function runApprovalCommitBinding(): Promise<string> {
  const previous = window.bridge;
  const { host, root, dispose } = createFixtureRoot('main');
  const submissions: Parameters<Bridge['submitReviewDraft']>[] = [];
  let writes = 0;
  let draft: ReviewDraftDto = reviewDraftFixture({ repo: stored.repo, number: stored.number, event: 'APPROVE', version: 0, updatedAt: review.createdAt });
  const bridge = {
    saveReviewDraft: async (_repo, _number, version, body, event) => { writes++; return (draft = { ...draft, body, event, version: version + 1 }); },
    submitReviewDraft: async (...request) => { submissions.push(request); },
    getAgentReview: async () => ({ ...review, draft }),
    fetchPullRequestDetailByRef: async () => stored,
  } satisfies Pick<Bridge, 'saveReviewDraft' | 'submitReviewDraft' | 'getAgentReview' | 'fetchPullRequestDetailByRef'>;
  Object.assign(window, { bridge });
  const render = (key: string, headSha: string | null) => flushSync(() => root.render(<ReviewDialog key={key}
    pr={remotePullRequest(stored, 'bob')} detail={{ ...stored, headSha }} review={{ ...review, draft }}
    onReviewChange={() => {}} onReviewSubmitted={() => {}} onClose={() => {}} />));
  const submit = async () => {
    const button = host.querySelector<HTMLButtonElement>('.pr-review-sheet__footer .is-primary');
    assert(button !== null && !button.disabled, 'The approval submission must be available');
    button.click();
    await settle();
  };
  try {
    render('viewed', 'viewed-head'); await settle();
    render('viewed', 'new-head'); await settle();
    await submit();
    assert(JSON.stringify(submissions) === JSON.stringify([[stored.repo, stored.number, 1, '', 'APPROVE', 'viewed-head']]),
      'A later detail refresh must not move an approval from the commit shown when its dialog opened');

    render('missing', null); await settle();
    await submit();
    assert(writes === 1 && submissions.length === 1 && host.querySelector('[role="alert"]')?.textContent?.includes('Refresh the pull request') === true,
      'A review with no viewed commit must explain recovery without saving or publishing');
    render('missing', 'later-head'); await settle();
    await submit();
    assert(writes === 1 && submissions.length === 1, 'A head discovered after opening must not silently become the reviewed commit');
    return 'Approvals stay bound to the viewed commit';
  }
  finally {
    dispose();
    Object.assign(window, { bridge: previous });
  }
}

export async function runDelayedDraftClose(): Promise<string> {
  const previous = window.bridge;
  const { host, root, dispose } = createFixtureRoot('main');
  const pending = Promise.withResolvers<ReviewDraftDto>();
  const writes: string[] = [];
  let closes = 0;
  const bridge = {
    getReviewDraft: () => pending.promise,
    saveReviewDraft: async (_repo, _number, version, body, event) => {
      writes.push(body);
      return reviewDraftFixture({ repo: stored.repo, number: stored.number, version: version + 1, body, event });
    },
  } satisfies Pick<Bridge, 'getReviewDraft' | 'saveReviewDraft'>;
  Object.assign(window, { bridge });
  const render = (key: string) => flushSync(() => root.render(<ReviewDialog key={key}
    pr={remotePullRequest(stored, 'bob')} detail={stored} onReviewSubmitted={() => {}} onClose={() => { closes++; }} />));
  const close = async () => { textButton('Cancel', host).click(); await settle(); };
  try {
    render('loading'); await settle();
    const editor = fixtureElement<HTMLTextAreaElement>('textarea[aria-label="Review comment"]', host);
    changeInput(editor, 'Keep this review'); await settle();
    await close();
    assert(closes === 0 && writes.length === 0 && editor.value === 'Keep this review', 'An unknown saved draft must not silently discard typed text');
    assert(host.textContent?.includes('close without saving') === true, 'The user must be told how to keep or explicitly discard their text');
    pending.resolve(reviewDraftFixture({ repo: stored.repo, number: stored.number, body: '' }));
    await settle();
    await close();
    assert(Number(closes) === 1 && writes.join() === 'Keep this review', 'Once loaded, closing must save the original text');

    const existing = Promise.withResolvers<ReviewDraftDto>();
    bridge.getReviewDraft = () => existing.promise;
    render('existing'); await settle();
    changeInput(fixtureElement<HTMLTextAreaElement>('textarea[aria-label="Review comment"]', host), 'New local prose'); await settle();
    existing.resolve(reviewDraftFixture({ repo: stored.repo, number: stored.number, body: 'Previously saved prose' })); await settle();
    await close();
    assert(Number(closes) === 1 && Number(writes.length) === 1, 'A late conflicting saved draft must preserve both versions until explicit discard');
    assert(host.querySelector('[role="alert"]')?.textContent?.includes('Copy your text') === true
      && fixtureElement<HTMLTextAreaElement>('textarea[aria-label="Review comment"]', host).value === 'New local prose',
    'A loaded draft conflict must explain how to retain edits instead of waiting for another load');
    textButton('Close without saving', host).click(); await settle();
    assert(Number(closes) === 2, 'Discarding edits must remain an explicit available action');
    return 'Unloaded review drafts never silently discard edits';
  }
  finally {
    dispose();
    Object.assign(window, { bridge: previous });
  }
}
