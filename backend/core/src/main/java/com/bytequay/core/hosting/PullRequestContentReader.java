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
    HostingProvider provider();

    String headRef(int number);

    GitHubPullRequestIdentity identity(HostingAccess access, HostingRepository repository, int number);

    List<GitHubPullRequestIdentity> identitiesByHead(HostingAccess access, HostingRepository repository, RepoRef sourceRepository, String branch);

    boolean viewerCanWrite(HostingAccess access, HostingRepository repository);

    default Optional<DiscussionProbe> probeDiscussion(HostingAccess access, HostingRepository repository, int number, Map<String, String> etags)
    {
        return Optional.empty();
    }

    StoredPrDetail discussion(HostingAccess access, HostingRepository repository, int number, StoredPrDetail existing);

    Checks checks(HostingAccess access, HostingRepository repository, int number, String headSha,
            List<PrCheckRunState> existing, Optional<String> checksEtag, Optional<String> statusesEtag);

    Diff diff(HostingAccess access, HostingRepository repository, int number, Optional<String> baseSha, Optional<String> headSha,
            StoredPrDiff existing, PrDiffEtags etags);

    PullRequestCheckLog readCheckLog(HostingAccess access, HostingRepository repository, int number, long jobId);

    DiffFileContent fileContent(HostingAccess access, HostingRepository repository, int number, String path, String baseSha, String headSha);

    record Checks(List<PrCheckRunState> checkRuns, Optional<String> checkRunsEtag, Optional<String> commitStatusesEtag)
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
