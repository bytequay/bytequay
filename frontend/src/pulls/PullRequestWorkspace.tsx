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

import { createContext, useContext, useMemo, type ReactNode } from 'react';
import type { Bridge, HostingServerDto } from '../types';

type HostingWorkspace = { workspaceId?: string; server?: HostingServerDto; repository?: string };
const PullRequestWorkspace = createContext<HostingWorkspace>({});

export function PullRequestWorkspaceProvider({ workspaceId, server, repository, children }: HostingWorkspace & { children: ReactNode }) {
  const parent = useContext(PullRequestWorkspace);
  const value = useMemo(() => ({ workspaceId, server: server ?? parent.server, repository: repository ?? parent.repository }),
    [parent.repository, parent.server, repository, server, workspaceId]);
  return <PullRequestWorkspace value={value}>{children}</PullRequestWorkspace>;
}

export function usePullRequestWorkspace() {
  return useContext(PullRequestWorkspace).workspaceId;
}

export function useHostingPresentation() {
  return useContext(PullRequestWorkspace);
}

/** The existing review controls address the account and server selected for this workspace. */
export function workspacePullRequestBridge(bridge: Bridge, workspaceId: string): Bridge {
  return {
    ...bridge,
    setPrMonitored: (prId, monitored) => bridge.setPrMonitored(prId, monitored, workspaceId),
    findStoredPullRequest: (repo, number) => bridge.findStoredPullRequest(repo, number, workspaceId),
    lookupPullRequest: (repo, number) => bridge.lookupPullRequest(repo, number, workspaceId),
    findStoredPullRequestDescription: (repo, number) => bridge.findStoredPullRequestDescription(repo, number, workspaceId),
    fetchPullRequestDetailByRef: (repo, number) => bridge.fetchPullRequestDetailByRef(repo, number, workspaceId),
    fetchRepoUsers: (repo) => bridge.fetchRepoUsers(repo, workspaceId),
    fetchRepoTeams: (repo) => bridge.fetchRepoTeams(repo, workspaceId),
    fetchRepoLabels: (repo) => bridge.fetchRepoLabels(repo, workspaceId),
    updatePrMetadata: (repo, number, assignees, labels) => bridge.updatePrMetadata(repo, number, assignees, labels, workspaceId),
    updatePrReviewer: (repo, number, login, requested) => bridge.updatePrReviewer(repo, number, login, requested, workspaceId),
    fetchMergeMode: (repo, branch) => bridge.fetchMergeMode(repo, branch, workspaceId),
    fetchPrConflicts: (repo, number) => bridge.fetchPrConflicts(repo, number, workspaceId),
    refreshPullRequestDetailByRef: (repo, number, checksOnly, opened) => bridge.refreshPullRequestDetailByRef(repo, number, checksOnly, opened, workspaceId),
    refreshAllPullRequestDetailByRef: (repo, number) => bridge.refreshAllPullRequestDetailByRef(repo, number, workspaceId),
    fetchPrDiffFiles: (repo, number, baseSha, headSha) => bridge.fetchPrDiffFiles(repo, number, baseSha, headSha, workspaceId),
    refreshPrDiffFiles: (repo, number, baseSha, headSha) => bridge.refreshPrDiffFiles(repo, number, baseSha, headSha, workspaceId),
    fetchPrDiffCommits: (repo, number) => bridge.fetchPrDiffCommits(repo, number, workspaceId),
    refreshPrDiffFileContent: (repo, number, path, baseSha, headSha) => bridge.refreshPrDiffFileContent(repo, number, path, baseSha, headSha, workspaceId),
    postPrReviewComment: (repo, number, comment, key) => bridge.postPrReviewComment(repo, number, comment, key, workspaceId),
    updatePrDescription: (repo, number, body) => bridge.updatePrDescription(repo, number, body, workspaceId),
    updatePrTitle: (repo, number, title) => bridge.updatePrTitle(repo, number, title, workspaceId),
    commentPr: (repo, number, body) => bridge.commentPr(repo, number, body, workspaceId),
    setPrReaction: (repo, number, target, commentId, content, viewerHasReacted) => bridge.setPrReaction(repo, number, target, commentId, content, viewerHasReacted, workspaceId),
    editPrComment: (repo, number, target, commentId, body) => bridge.editPrComment(repo, number, target, commentId, body, workspaceId),
    closePr: (repo, number) => bridge.closePr(repo, number, workspaceId),
    markPrReadyForReview: (repo, number) => bridge.markPrReadyForReview(repo, number, workspaceId),
    replyToReviewComment: (repo, number, commentId, body) => bridge.replyToReviewComment(repo, number, commentId, body, workspaceId),
    setReviewThreadResolved: (repo, number, commentId, resolved) => bridge.setReviewThreadResolved(repo, number, commentId, resolved, workspaceId),
    getPrCheckLog: (repo, number, jobId) => bridge.getPrCheckLog(repo, number, jobId, workspaceId),
    rerunFailedPrChecks: (repo, number) => bridge.rerunFailedPrChecks(repo, number, workspaceId),
    mergePr: (prId, repo, number, strategy, headSha) => bridge.mergePr(prId, repo, number, strategy, headSha, workspaceId),
    removePrFromMergeQueue: (repo, number, autoMerge) => bridge.removePrFromMergeQueue(repo, number, autoMerge, workspaceId),
    getReviewDraft: (repo, number) => bridge.getReviewDraft(repo, number, workspaceId),
    addReviewDraftComment: (repo, number, expectedVersion, comment) => bridge.addReviewDraftComment(repo, number, expectedVersion, comment, workspaceId),
    addReviewDraftFinding: (repo, number, expectedVersion, reviewId, findingId) => bridge.addReviewDraftFinding(repo, number, expectedVersion, reviewId, findingId, workspaceId),
    editReviewDraftComment: (repo, number, expectedVersion, itemId, body, included) => bridge.editReviewDraftComment(repo, number, expectedVersion, itemId, body, included, workspaceId),
    removeReviewDraftComment: (repo, number, expectedVersion, itemId) => bridge.removeReviewDraftComment(repo, number, expectedVersion, itemId, workspaceId),
    confirmReviewDraftCommentNotPosted: (repo, number, expectedVersion, itemId) => bridge.confirmReviewDraftCommentNotPosted(repo, number, expectedVersion, itemId, workspaceId),
    confirmReviewDraftCommentPosted: (repo, number, expectedVersion, itemId) => bridge.confirmReviewDraftCommentPosted(repo, number, expectedVersion, itemId, workspaceId),
    confirmReviewDraftReview: (repo, number, expectedVersion, posted) => bridge.confirmReviewDraftReview(repo, number, expectedVersion, posted, workspaceId),
    saveReviewDraft: (repo, number, expectedVersion, body, event) => bridge.saveReviewDraft(repo, number, expectedVersion, body, event, workspaceId),
    publishReviewDraftComment: (repo, number, expectedVersion, itemId) => bridge.publishReviewDraftComment(repo, number, expectedVersion, itemId, workspaceId),
    submitReviewDraft: (repo, number, expectedVersion, body, event, headSha) => bridge.submitReviewDraft(repo, number, expectedVersion, body, event, headSha, workspaceId),
  };
}

export function usePullRequestBridge(): Bridge {
  const workspaceId = usePullRequestWorkspace();
  return useMemo(() => workspaceId === undefined ? globalThis.window?.bridge : workspacePullRequestBridge(globalThis.window?.bridge, workspaceId), [workspaceId]);
}
