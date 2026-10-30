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
package com.bytequay.integration.gitlab;

import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.integration.gitlab.impl.GitLabConnectionClient;
import com.bytequay.integration.gitlab.impl.GitLabIssueClient;
import com.bytequay.integration.gitlab.impl.GitLabPullRequestContentReader;
import com.bytequay.integration.gitlab.impl.GitLabPullRequestDiscovery;
import com.bytequay.integration.gitlab.impl.GitLabPullRequestReader;
import com.bytequay.integration.gitlab.impl.GitLabPullRequestWriter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;

public final class GitLabIntegration
{
    private GitLabIntegration() {}

    public static HostingIntegration create(HttpClient http, ObjectMapper mapper)
    {
        GitLabConnectionClient connections = new GitLabConnectionClient(http, mapper);
        GitLabIssueClient issues = new GitLabIssueClient(http, mapper);
        return new HostingIntegration(HostingProvider.GITLAB, connections, connections, new GitLabPullRequestReader(http, mapper),
                new GitLabPullRequestContentReader(http, mapper), new GitLabPullRequestWriter(http, mapper), issues, issues, new GitLabPullRequestDiscovery(http, mapper));
    }
}
