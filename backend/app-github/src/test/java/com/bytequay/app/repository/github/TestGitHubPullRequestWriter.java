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
package com.bytequay.app.repository.github;

import com.bytequay.core.domain.CreatedPullRequest;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingTarget;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static com.bytequay.core.hosting.HostingServer.GITHUB_COM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestGitHubPullRequestWriter
{
    private static final RepoRef UPSTREAM = RepoRef.of("upstream", "project");
    private static final CreatedPullRequest CREATED = new CreatedPullRequest(900, 12, "https://github.com/upstream/project/pull/12");

    private final GitHubPullRequestWriteClient writes = mock(GitHubPullRequestWriteClient.class);
    private final GitHubPullRequestWriter writer = new GitHubPullRequestWriter(mock(GitHubPullRequestReadClient.class), writes, mock(GitHubActionsClient.class));
    private final HostingTarget target = new HostingTarget(
            new HostingAccess(new HostingInstance(1, GITHUB_COM.provider(), GITHUB_COM.webBaseUrl(), GITHUB_COM.apiBaseUrl()), "pat"),
            new HostingRepository(2, 1, "3", "upstream/project", URI.create("https://github.com/upstream/project"), URI.create("https://github.com/upstream/project.git"),
                    Optional.empty(), Optional.of("main")));

    @Test
    void namesABranchOfTheTargetRepositoryByItself()
    {
        when(writes.createPullRequest("pat", UPSTREAM, "feature", "main", "Title", "Body", true)).thenReturn(CREATED);

        assertThat(writer.create(target, RepoRef.of("Upstream", "Project"), "feature", "main", "Title", "Body", true)).isEqualTo(CREATED);
    }

    @Test
    void namesAForkBranchByItsOwner()
    {
        when(writes.createPullRequest("pat", UPSTREAM, "contributor:feature", "release", "Title", "Body", false)).thenReturn(CREATED);

        assertThat(writer.create(target, RepoRef.of("contributor", "project"), "feature", "release", "Title", "Body", false)).isEqualTo(CREATED);
    }
}
