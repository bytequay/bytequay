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
package com.bytequay.app.agent;

import com.bytequay.core.domain.RepoRef;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static java.util.Objects.requireNonNull;

/** The accepted destination; later workspace changes cannot silently reroute publication. */
public record DevelopmentPublicationRoute(
        RepoRef headRepository,
        RepoRef baseRepository,
        String pushUrl,
        String sourceRemote,
        String baseBranch,
        String baseSha,
        String headBranch)
{
    public DevelopmentPublicationRoute
    {
        requireNonNull(headRepository, "headRepository is null");
        requireNonNull(baseRepository, "baseRepository is null");
        checkNotBlank(pushUrl, "pushUrl");
        checkNotBlank(sourceRemote, "sourceRemote");
        checkNotBlank(baseBranch, "baseBranch");
        checkNotBlank(baseSha, "baseSha");
        checkNotBlank(headBranch, "headBranch");
    }

    public DevelopmentPublicationRoute withHeadBranch(String branch)
    {
        return new DevelopmentPublicationRoute(headRepository, baseRepository, pushUrl, sourceRemote, baseBranch, baseSha, branch);
    }
}
