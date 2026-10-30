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
package com.bytequay.core.hosting;

import com.bytequay.core.domain.DiffCommit;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.GitHubPullRequestIdentity;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrDiffEtags;
import com.bytequay.core.domain.PullRequestCheckLog;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.StoredPrDiff;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Remote content normalized to the existing desktop review contracts. */
public interface PullRequestContentReader
{
    String headRef(int number);

    GitHubPullRequestIdentity identity(HostingTarget target, int number);

    List<GitHubPullRequestIdentity> identitiesByHead(HostingTarget target, RepoRef sourceRepository, String branch);

    boolean viewerCanWrite(HostingTarget target);

    /** A provider signal that the viewer was asked to review, such as a team request the request's own reviewer list does not name. */
    default boolean reviewRequested(HostingTarget target, int number, String login)
    {
        return false;
    }

    default Optional<DiscussionProbe> probeDiscussion(HostingTarget target, int number, Map<String, String> etags)
    {
        return Optional.empty();
    }

    StoredPrDetail discussion(HostingTarget target, int number, StoredPrDetail existing);

    Checks checks(HostingTarget target, int number, String headSha,
            List<PrCheckRunState> existing, Optional<String> checksEtag, Optional<String> statusesEtag);

    Diff diff(HostingTarget target, int number, Optional<String> baseSha, Optional<String> headSha,
            StoredPrDiff existing, PrDiffEtags etags);

    PullRequestCheckLog readCheckLog(HostingTarget target, int number, long jobId);

    /** Full diff coordinates identify the request revision; explicit ranges always compare the supplied endpoints directly. */
    DiffFileContent fileContent(HostingTarget target, int number, String path, String baseSha, String headSha, boolean fullDiff);

    record Checks(List<PrCheckRunState> checkRuns, Optional<String> checkRunsEtag, Optional<String> commitStatusesEtag, boolean complete)
    {
        public Checks
        {
            checkRuns = ImmutableList.copyOf(checkRuns);
        }
    }

    record Diff(List<DiffFile> files, List<DiffCommit> commits, PrDiffEtags etags)
    {
        public Diff
        {
            files = ImmutableList.copyOf(files);
            commits = ImmutableList.copyOf(commits);
        }
    }
}
