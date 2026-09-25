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
package com.bytequay.agent.spi;

import static java.util.Objects.requireNonNull;

/** Provider-independent permissions selected for one task session. */
public record AgentPermissionScope(AgentPermissionPolicy policy, SessionAccess access, boolean review)
{
    public AgentPermissionScope
    {
        requireNonNull(policy, "policy is null");
        requireNonNull(access, "access is null");
    }

    /** Attended development may use Git and GitHub commands under the shared authorization rules. */
    public boolean githubCommands()
    {
        return remoteReads() && !review;
    }

    /** Attended sessions that own a worktree may read remotes. */
    public boolean remoteReads()
    {
        return policy == AgentPermissionPolicy.AUTO && !access.restricted();
    }

    /** Full development and CI repair turns can fetch dependencies while building and testing. */
    public boolean buildNetwork()
    {
        return !access.restricted() && (policy == AgentPermissionPolicy.AUTO || policy == AgentPermissionPolicy.CI_AUTOFIX);
    }

    /** Private review can read remotes but cannot write to them. */
    public boolean reviewReads()
    {
        return review && remoteReads();
    }

    /** CI repair and cherry-pick normally run without an attending user. */
    public boolean unattended()
    {
        return policy.unattended();
    }
}
