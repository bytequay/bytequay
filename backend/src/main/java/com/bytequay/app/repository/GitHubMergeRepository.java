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
package com.bytequay.app.repository;

import com.bytequay.app.domain.MergePullRequestCommand;
import com.bytequay.app.domain.MergeResult;
import com.bytequay.app.domain.PullRequestRef;

import java.util.Optional;

/** GitHub merge, merge-queue, branch, and auto-merge operations. */
public interface GitHubMergeRepository {
    default MergeQueueInfo fetchMergeQueueInfo(String pat, PullRequestRef pr) {
        throw unsupported();
    }

    record MergeQueueInfo(boolean queueConfigured, String entryState) {}

    default MergeResult mergePullRequest(
            String pat, PullRequestRef pr, MergePullRequestCommand command) {
        throw unsupported();
    }

    default void deleteBranch(String pat, PullRequestRef pr, String branchName) {
        throw unsupported();
    }

    default Optional<String> fetchBranchHeadSha(
            String pat, PullRequestRef repository, String branchName) {
        throw unsupported();
    }

    default Optional<MergeQueueProbe> probeMergeQueue(String pat, PullRequestRef pr) {
        throw unsupported();
    }

    default MergeResult enqueuePullRequest(String pat, String pullRequestNodeId) {
        throw unsupported();
    }

    default MergeResult enqueuePullRequest(
            String pat, String pullRequestNodeId, String expectedHeadOid) {
        throw unsupported();
    }

    default void dequeuePullRequest(String pat, PullRequestRef pr) {
        throw unsupported();
    }

    default Optional<String> pullRequestNodeId(String pat, PullRequestRef pr) {
        throw unsupported();
    }

    record MergeQueueProbe(String pullRequestNodeId) {}

    default boolean isPullRequestMerged(String pat, PullRequestRef pr) {
        throw unsupported();
    }

    default void updatePullRequestBranch(String pat, PullRequestRef pr, String expectedHeadSha) {
        throw unsupported();
    }

    default void enableAutoMerge(String pat, PullRequestRef pr, String mergeMethod) {
        throw unsupported();
    }

    default void disableAutoMerge(String pat, PullRequestRef pr) {
        throw unsupported();
    }

    default Optional<AutoMergeStatus> fetchAutoMergeStatus(String pat, PullRequestRef pr) {
        throw unsupported();
    }

    record AutoMergeStatus(String mergeMethod, String enabledByLogin) {}

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("GitHub merge operation not implemented");
    }
}
