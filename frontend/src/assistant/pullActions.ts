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
import { workspacePullRequestBridge } from '../pulls/PullRequestWorkspace';
import { mergeReady } from '../pulls/PullsScreen';

/** What a pull request's card in the Assistant panel can do; each is the user's own click, never the assistant's. */
export type PullAction = 'approve' | 'merge' | 'comment' | 'close';

/**
 * Approves as the review sheet does: through the review draft, so a reply lost on the way cannot approve twice, and bound to the head
 * commit the card showed. Resolves false, sending nothing, when the draft holds text or comments: those go out from the review itself.
 */
export async function approvePull(repo: string, number: number, head: string | null, workspaceId?: string): Promise<boolean> {
  const bridge = workspaceId === undefined ? window.bridge : workspacePullRequestBridge(window.bridge, workspaceId);
  const draft = await bridge.getReviewDraft(repo, number);
  if (draft.reviewPublication !== null) throw new Error('A review you sent has no answer yet. Open the pull request to settle it first.');
  if (draft.body.trim() !== '' || draft.items.length > 0) return false;
  if (!head) throw new Error('Refresh the pull request before approving it.');
  const saved = await bridge.saveReviewDraft(repo, number, draft.version, draft.body, 'APPROVE');
  await bridge.submitReviewDraft(repo, number, saved.version, saved.body.trim(), 'APPROVE', head);
  // The stored detail picks up the new review, as the sheet reads it after submitting.
  await bridge.fetchPullRequestDetailByRef(repo, number).then(() => {}, () => {});
  return true;
}

/**
 * Uses the merge sheet's guard after reading GitHub again, bound to the head commit the card showed.
 * Queue branches schedule GitHub's merge when ready.
 */
export async function mergePull(repo: string, number: number, head: string | null, workspaceId?: string): Promise<void> {
  const bridge = workspaceId === undefined ? window.bridge : workspacePullRequestBridge(window.bridge, workspaceId);
  const stored = await bridge.findStoredPullRequest(repo, number);
  if (stored === null) throw new Error('ByteQuay no longer holds this pull request.');
  await bridge.refreshPullRequestDetailByRef(repo, number);
  const detail = await bridge.fetchPullRequestDetailByRef(repo, number);
  if (!head || detail.headSha !== head) throw new Error('New commits arrived after this card was drawn. Open the pull request to check them.');
  const mergeQueue = detail.baseRef ? (await bridge.fetchMergeMode(repo, detail.baseRef)).mergeQueue : null;
  if (!mergeReady(detail, mergeQueue)) throw new Error('Not ready to merge. Open the pull request to see why.');
  await bridge.mergePr(stored.id, repo, number, mergeQueue ? 'when-ready' : 'default', head);
}

/** Closes as the pull request page's Close pull request does. */
export async function closePull(repo: string, number: number, workspaceId?: string): Promise<void> {
  const bridge = workspaceId === undefined ? window.bridge : workspacePullRequestBridge(window.bridge, workspaceId);
  await bridge.closePr(repo, number);
}
