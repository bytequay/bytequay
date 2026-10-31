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

import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import type { Bridge, HostingCapabilitiesDto, HostingConnectionDto, HostingServerDto, PullRequestRoute, PullRequestViewer } from '../types';

type HostingWorkspace = { workspaceId?: string; accountId?: number; server?: HostingServerDto; capabilities?: HostingCapabilitiesDto; repository?: string };
const PullRequestWorkspace = createContext<HostingWorkspace>({});

export function PullRequestWorkspaceProvider({ workspaceId, accountId, server, capabilities, repository, children }: HostingWorkspace & { children: ReactNode }) {
  const parent = useContext(PullRequestWorkspace);
  // A request outside any workspace keeps the account it opens through.
  const account = workspaceId === undefined ? accountId ?? parent.accountId : undefined;
  const value = useMemo(() => ({ workspaceId, accountId: account, server: server ?? parent.server, capabilities: capabilities ?? parent.capabilities,
    repository: repository ?? parent.repository }), [account, capabilities, parent.capabilities, parent.repository, parent.server, repository, server, workspaceId]);
  return <PullRequestWorkspace value={value}>{children}</PullRequestWorkspace>;
}

/** The provider's name for people; a screen with no server reads through the GitHub sign-in. */
export function providerName(server: HostingServerDto | undefined): string {
  return server?.provider === 'GITLAB' ? 'GitLab' : 'GitHub';
}

/** The name of the provider this screen's requests reach. */
export function useProviderName(): string {
  return providerName(useContext(PullRequestWorkspace).server);
}

export function usePullRequestWorkspace() {
  return useContext(PullRequestWorkspace).workspaceId;
}

export function useHostingPresentation() {
  return useContext(PullRequestWorkspace);
}

/** How this screen's requests are reached: its workspace, the account it opens through, or neither for the desktop sign-in. */
export function usePullRequestRoute(): PullRequestRoute | undefined {
  const { workspaceId, accountId } = useContext(PullRequestWorkspace);
  return useMemo(() => workspaceId ?? (accountId === undefined ? undefined : { accountId }), [accountId, workspaceId]);
}

/** A request no workspace lists opens through the connected account that discovered it, with that provider's server and capabilities. */
export function AccountPullRequestScope({ accountId, children }: { accountId?: number; children: ReactNode }) {
  const [connection, setConnection] = useState<HostingConnectionDto | null>(null);
  useEffect(() => {
    if (accountId === undefined) return;
    let current = true;
    void globalThis.window?.bridge.listHostingAccounts()
      .then(accounts => { if (current) setConnection(accounts.find(item => item.account.id === accountId) ?? null); })
      .catch(() => { /* The route still names the account; only its presentation waits. */ });
    return () => { current = false; };
  }, [accountId]);
  if (accountId === undefined) return children;
  return (
    <PullRequestWorkspaceProvider accountId={accountId} server={connection?.instance} capabilities={connection?.capabilities}>
      {children}
    </PullRequestWorkspaceProvider>
  );
}

/** Text from a workspace shown outside its screens, such as a Home row, presented with that workspace's server. */
export function WorkspacePullRequestScope({ workspaceId, children }: { workspaceId?: string; children: ReactNode }) {
  const [server, setServer] = useState<HostingServerDto | undefined>();
  useEffect(() => {
    if (workspaceId === undefined) return;
    let current = true;
    void globalThis.window?.bridge.getWorkspace(workspaceId)
      .then(workspace => { if (current) setServer(workspace.hostingServer); })
      .catch(() => { /* Text still renders; only its links wait for the server. */ });
    return () => { current = false; };
  }, [workspaceId]);
  if (workspaceId === undefined) return children;
  return <PullRequestWorkspaceProvider workspaceId={workspaceId} server={server}>{children}</PullRequestWorkspaceProvider>;
}

export async function readPullRequestViewer(
  bridge: Pick<Bridge, 'getUserProfile' | 'getWorkspaceHostingViewer' | 'fetchRepoUsers'> & Partial<Pick<Bridge, 'listHostingAccounts'>>,
  { workspaceId, accountId, repository }: HostingWorkspace): Promise<PullRequestViewer | null> {
  if (workspaceId === undefined && accountId !== undefined) {
    const connection = (await bridge.listHostingAccounts?.())?.find(item => item.account.id === accountId);
    return connection === undefined ? null : { login: connection.account.login, avatarUrl: null };
  }
  if (workspaceId === undefined) return bridge.getUserProfile();
  const login = await bridge.getWorkspaceHostingViewer(workspaceId);
  if (login === null) return null;
  // Repository users already carry locally cached avatars from the workspace's hosting provider.
  let avatarUrl: string | null = null;
  try {
    const users = repository === undefined ? [] : await bridge.fetchRepoUsers(repository, workspaceId);
    avatarUrl = users.find(user => user.login.toLowerCase() === login.toLowerCase())?.avatarUrl ?? null;
  }
  catch { /* Keep the account identity when its cached image is unavailable. */ }
  return { login, avatarUrl };
}

/** The review controls address the account the route names: a workspace's selected account, or a connected account. */
export function routedPullRequestBridge(bridge: Bridge, route: PullRequestRoute): Bridge {
  return {
    ...bridge,
    setPrMonitored: (prId, monitored) => bridge.setPrMonitored(prId, monitored, route),
    findStoredPullRequest: (repo, number) => bridge.findStoredPullRequest(repo, number, route),
    lookupPullRequest: (repo, number) => bridge.lookupPullRequest(repo, number, route),
    findStoredPullRequestDescription: (repo, number) => bridge.findStoredPullRequestDescription(repo, number, route),
    fetchPullRequestDetailByRef: (repo, number) => bridge.fetchPullRequestDetailByRef(repo, number, route),
    fetchRepoUsers: (repo) => bridge.fetchRepoUsers(repo, route),
    fetchRepoTeams: (repo) => bridge.fetchRepoTeams(repo, route),
    fetchRepoLabels: (repo) => bridge.fetchRepoLabels(repo, route),
    updatePrMetadata: (repo, number, assignees, labels) => bridge.updatePrMetadata(repo, number, assignees, labels, route),
    updatePrReviewer: (repo, number, login, requested) => bridge.updatePrReviewer(repo, number, login, requested, route),
    fetchMergeMode: (repo, branch) => bridge.fetchMergeMode(repo, branch, route),
    fetchPrConflicts: (repo, number) => bridge.fetchPrConflicts(repo, number, route),
    refreshPullRequestDetailByRef: (repo, number, checksOnly, opened) => bridge.refreshPullRequestDetailByRef(repo, number, checksOnly, opened, route),
    refreshAllPullRequestDetailByRef: (repo, number) => bridge.refreshAllPullRequestDetailByRef(repo, number, route),
    fetchPrDiffFiles: (repo, number, baseSha, headSha) => bridge.fetchPrDiffFiles(repo, number, baseSha, headSha, route),
    refreshPrDiffFiles: (repo, number, baseSha, headSha) => bridge.refreshPrDiffFiles(repo, number, baseSha, headSha, route),
    fetchPrDiffCommits: (repo, number) => bridge.fetchPrDiffCommits(repo, number, route),
    refreshPrDiffFileContent: (repo, number, path, baseSha, headSha) => bridge.refreshPrDiffFileContent(repo, number, path, baseSha, headSha, route),
    postPrReviewComment: (repo, number, comment, key) => bridge.postPrReviewComment(repo, number, comment, key, route),
    updatePrDescription: (repo, number, body) => bridge.updatePrDescription(repo, number, body, route),
    updatePrTitle: (repo, number, title) => bridge.updatePrTitle(repo, number, title, route),
    commentPr: (repo, number, body) => bridge.commentPr(repo, number, body, route),
    setPrReaction: (repo, number, target, commentId, content, viewerHasReacted) => bridge.setPrReaction(repo, number, target, commentId, content, viewerHasReacted, route),
    editPrComment: (repo, number, target, commentId, body) => bridge.editPrComment(repo, number, target, commentId, body, route),
    closePr: (repo, number) => bridge.closePr(repo, number, route),
    markPrReadyForReview: (repo, number) => bridge.markPrReadyForReview(repo, number, route),
    replyToReviewComment: (repo, number, commentId, body) => bridge.replyToReviewComment(repo, number, commentId, body, route),
    setReviewThreadResolved: (repo, number, commentId, resolved) => bridge.setReviewThreadResolved(repo, number, commentId, resolved, route),
    getPrCheckLog: (repo, number, jobId) => bridge.getPrCheckLog(repo, number, jobId, route),
    rerunFailedPrChecks: (repo, number) => bridge.rerunFailedPrChecks(repo, number, route),
    mergePr: (prId, repo, number, strategy, headSha) => bridge.mergePr(prId, repo, number, strategy, headSha, route),
    removePrFromMergeQueue: (repo, number, autoMerge) => bridge.removePrFromMergeQueue(repo, number, autoMerge, route),
    getReviewDraft: (repo, number) => bridge.getReviewDraft(repo, number, route),
    addReviewDraftComment: (repo, number, expectedVersion, comment) => bridge.addReviewDraftComment(repo, number, expectedVersion, comment, route),
    addReviewDraftFinding: (repo, number, expectedVersion, reviewId, findingId) => bridge.addReviewDraftFinding(repo, number, expectedVersion, reviewId, findingId, route),
    editReviewDraftComment: (repo, number, expectedVersion, itemId, body, included) => bridge.editReviewDraftComment(repo, number, expectedVersion, itemId, body, included, route),
    removeReviewDraftComment: (repo, number, expectedVersion, itemId) => bridge.removeReviewDraftComment(repo, number, expectedVersion, itemId, route),
    confirmReviewDraftCommentNotPosted: (repo, number, expectedVersion, itemId) => bridge.confirmReviewDraftCommentNotPosted(repo, number, expectedVersion, itemId, route),
    confirmReviewDraftCommentPosted: (repo, number, expectedVersion, itemId) => bridge.confirmReviewDraftCommentPosted(repo, number, expectedVersion, itemId, route),
    confirmReviewDraftReview: (repo, number, expectedVersion, posted) => bridge.confirmReviewDraftReview(repo, number, expectedVersion, posted, route),
    saveReviewDraft: (repo, number, expectedVersion, body, event) => bridge.saveReviewDraft(repo, number, expectedVersion, body, event, route),
    publishReviewDraftComment: (repo, number, expectedVersion, itemId) => bridge.publishReviewDraftComment(repo, number, expectedVersion, itemId, route),
    submitReviewDraft: (repo, number, expectedVersion, body, event, headSha) => bridge.submitReviewDraft(repo, number, expectedVersion, body, event, headSha, route),
  };
}

export function usePullRequestBridge(): Bridge {
  const route = usePullRequestRoute();
  return useMemo(() => route === undefined ? globalThis.window?.bridge : routedPullRequestBridge(globalThis.window?.bridge, route), [route]);
}
