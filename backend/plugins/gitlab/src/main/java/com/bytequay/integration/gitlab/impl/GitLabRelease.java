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
package com.bytequay.integration.gitlab.impl;

import com.bytequay.core.hosting.HostingCapabilities;
import com.bytequay.core.hosting.HostingVersion;

import java.util.Optional;

/** What ByteQuay reads differently from older GitLab releases; every other call is the same from 14.10 on. */
public enum GitLabRelease
{
    /** 16.11 and later: paged merge request diffs, pipeline messages in GraphQL, work items and token self-inspection. */
    CURRENT(Optional.of(HostingVersion.of(16, 11)), new HostingCapabilities(false, false, true, true, false, true))
    {
        @Override
        GitLabDiffs diffs(GitLabHttpClient http)
        {
            return new GitLabPagedDiffs(http);
        }

        @Override
        GitLabPipelineFailures pipelineFailures(GitLabHttpClient http)
        {
            return new GitLabPipelineMessages(http);
        }
    },
    /** Before 16.11, checked on 14.10: one unpaged changes read, configuration errors in REST, no work items. */
    LEGACY(Optional.empty(), new HostingCapabilities(false, false, true, false, false, true))
    {
        @Override
        GitLabDiffs diffs(GitLabHttpClient http)
        {
            return new GitLabLegacyDiffs(http);
        }

        @Override
        GitLabPipelineFailures pipelineFailures(GitLabHttpClient http)
        {
            return new GitLabPipelineYamlErrors(http);
        }
    };

    private final Optional<HostingVersion> since;
    private final HostingCapabilities capabilities;

    GitLabRelease(Optional<HostingVersion> since, HostingCapabilities capabilities)
    {
        this.since = since;
        this.capabilities = capabilities;
    }

    /** The oldest release this serves; the legacy one serves everything older than the current one. */
    public Optional<HostingVersion> since()
    {
        return since;
    }

    public HostingCapabilities capabilities()
    {
        return capabilities;
    }

    abstract GitLabDiffs diffs(GitLabHttpClient http);

    abstract GitLabPipelineFailures pipelineFailures(GitLabHttpClient http);

    /** A personal access token reports its own permissions from GitLab 15.5. */
    boolean inspectsPersonalTokens()
    {
        return this == CURRENT;
    }
}
