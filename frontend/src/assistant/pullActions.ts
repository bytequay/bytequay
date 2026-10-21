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
import { mergeReady } from '../pulls/PullsScreen';

/** What a pull request's card in the Assistant panel can do; each is the user's own click, never the assistant's. */
export type PullAction = 'approve' | 'merge' | 'comment' | 'close';

/**
 * Approves as the review sheet does: through the review draft, so a reply lost on the way cannot approve twice, and bound to the head
 * commit the card showed. Resolves false, sending nothing, when the draft holds text or comments: those go out from the review itself.
 */
export async function approvePull(repo: string, number: number, head: string | null): Promise<boolean> {
  const draft = await window.bridge.getReviewDraft(repo, number);
  if (draft.reviewPublication !== null) throw new Error('A review you sent has no answer yet. Open the pull request to settle it first.');
  if (draft.body.trim() !== '' || draft.items.length > 0) return false;
  if (!head) throw new Error('Refresh the pull request before approving it.');
  const saved = await window.bridge.saveReviewDraft(repo, number, draft.version, draft.body, 'APPROVE');
  await window.bridge.submitReviewDraft(repo, number, saved.version, saved.body.trim(), 'APPROVE', head);
  // The stored detail picks up the new review, as the sheet reads it after submitting.
  await window.bridge.fetchPullRequestDetailByRef(repo, number).then(() => {}, () => {});
  return true;
}

/**
 * Merges as the merge sheet's button does, after reading the pull request from GitHub again: the sheet's own check decides, and only
 * the head commit the card showed is merged.
 */
export async function mergePull(repo: string, number: number, head: string | null): Promise<void> {
  const stored = await window.bridge.findStoredPullRequest(repo, number);
  if (stored === null) throw new Error('ByteQuay no longer holds this pull request.');
  await window.bridge.refreshPullRequestDetailByRef(repo, number);
  const detail = await window.bridge.fetchPullRequestDetailByRef(repo, number);
  if (!head || detail.headSha !== head) throw new Error('New commits arrived after this card was drawn. Open the pull request to check them.');
  const mergeQueue = detail.baseRef ? (await window.bridge.fetchMergeMode(repo, detail.baseRef)).mergeQueue : null;
  if (!mergeReady(detail, mergeQueue)) throw new Error('Not ready to merge. Open the pull request to see why.');
  await window.bridge.mergePr(stored.id, repo, number, mergeQueue ? 'queue' : 'rebase', head);
}

/** Closes as the pull request page's Close pull request does. */
export async function closePull(repo: string, number: number): Promise<void> {
  await window.bridge.closePr(repo, number);
}
