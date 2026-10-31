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
package com.bytequay.integration.github;

import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.IssueClient;
import com.bytequay.core.hosting.PullRequestCi;
import com.bytequay.core.hosting.PullRequestContentReader;
import com.bytequay.core.hosting.PullRequestDiscovery;
import com.bytequay.core.hosting.PullRequestWriter;
import com.bytequay.core.hosting.RepositoryMetadataReader;
import com.bytequay.core.hosting.TeamActivityReader;
import com.bytequay.integration.github.impl.GitHubConnectionClient;
import com.bytequay.integration.github.impl.GitHubPullRequestReader;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;

public final class GitHubIntegration
{
    private GitHubIntegration() {}

    /** Content, write, issue, discovery, CI and team adapters are supplied by the application, which still builds them on its existing GitHub clients. */
    public static HostingIntegration create(HttpClient http, ObjectMapper mapper, PullRequestContentReader contents, PullRequestWriter writer, IssueClient issues,
            RepositoryMetadataReader metadata, PullRequestDiscovery discovery, PullRequestCi ci, TeamActivityReader team)
    {
        GitHubConnectionClient connections = new GitHubConnectionClient(http, mapper);
        return new HostingIntegration(HostingProvider.GITHUB, connections, connections, new GitHubPullRequestReader(http, mapper), contents, writer, issues, metadata,
                discovery, ci, team);
    }
}
