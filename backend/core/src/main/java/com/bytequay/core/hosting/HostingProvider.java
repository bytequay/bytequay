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

import com.google.common.collect.ImmutableList;

import java.net.URI;
import java.util.List;

public enum HostingProvider
{
    GITHUB(true, HostingCapabilities.ALL, "/issues/", "/pull/", "/commit/", ImmutableList.of(
            ".github/pull_request_template.md", ".github/PULL_REQUEST_TEMPLATE.md", "pull_request_template.md", "PULL_REQUEST_TEMPLATE.md",
            "docs/pull_request_template.md", "docs/PULL_REQUEST_TEMPLATE.md")),
    GITLAB(false, new HostingCapabilities(false, false, true, false, false, true), "/-/issues/", "/-/merge_requests/", "/-/commit/",
            ImmutableList.of(".gitlab/merge_request_templates/Default.md"));

    private final boolean pathsIgnoreCase;
    private final HostingCapabilities capabilities;
    private final String issuesPath;
    private final String pullRequestsPath;
    private final String commitsPath;
    private final List<String> pullRequestTemplates;

    HostingProvider(boolean pathsIgnoreCase, HostingCapabilities capabilities, String issuesPath, String pullRequestsPath, String commitsPath,
            List<String> pullRequestTemplates)
    {
        this.pathsIgnoreCase = pathsIgnoreCase;
        this.capabilities = capabilities;
        this.issuesPath = issuesPath;
        this.pullRequestsPath = pullRequestsPath;
        this.commitsPath = commitsPath;
        this.pullRequestTemplates = pullRequestTemplates;
    }

    public HostingCapabilities capabilities()
    {
        return capabilities;
    }

    /** Whether two repository paths that differ only in case name the same repository on this provider. */
    public boolean pathsIgnoreCase()
    {
        return pathsIgnoreCase;
    }

    /** The web page of issue {@code number} in the repository at {@code path} on a server of this provider. */
    public URI issueUrl(URI webBaseUrl, String path, int number)
    {
        return URI.create(webBaseUrl + "/" + path + issuesPath + number);
    }

    /** The web page of pull request {@code number} in the repository at {@code path} on a server of this provider. */
    public URI pullRequestUrl(URI webBaseUrl, String path, int number)
    {
        return URI.create(webBaseUrl + "/" + path + pullRequestsPath + number);
    }

    /** The web page of commit {@code sha} in the repository at {@code path} on a server of this provider. */
    public URI commitUrl(URI webBaseUrl, String path, String sha)
    {
        return URI.create(webBaseUrl + "/" + path + commitsPath + sha);
    }

    /** Repository files this provider reads as the default pull request description, in the order it looks for them. */
    public List<String> pullRequestTemplates()
    {
        return pullRequestTemplates;
    }
}
