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
package com.bytequay.app.service.pr;

import java.util.Optional;
import java.util.OptionalLong;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** How a request is reached: through a workspace, through a connected account, or through the desktop sign-in when neither is named. */
public record PullRequestRoute(Optional<String> workspaceId, OptionalLong accountId)
{
    public PullRequestRoute
    {
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(accountId, "accountId is null");
        checkArgument(workspaceId.isEmpty() || accountId.isEmpty(), "A request is reached through a workspace or an account, not both");
    }

    /** Request parameters name at most one of them; a blank workspace names none. */
    public static PullRequestRoute of(String workspaceId, Long accountId)
    {
        return new PullRequestRoute(Optional.ofNullable(workspaceId).filter(value -> !value.isBlank()),
                accountId == null ? OptionalLong.empty() : OptionalLong.of(accountId));
    }

    public static PullRequestRoute account(long accountId)
    {
        return new PullRequestRoute(Optional.empty(), OptionalLong.of(accountId));
    }
}
