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

import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Retained hosting identities selected for an existing workspace; repository IDs are ByteQuay-owned. */
public record WorkspaceHostingBinding(String workspaceId, long accountId, long selectedRepositoryId, Optional<Long> upstreamRepositoryId)
{
    public WorkspaceHostingBinding
    {
        checkText(workspaceId, "workspaceId");
        checkArgument(accountId > 0, "accountId must be positive");
        checkArgument(selectedRepositoryId > 0, "selectedRepositoryId must be positive");
        requireNonNull(upstreamRepositoryId, "upstreamRepositoryId is null");
        upstreamRepositoryId.ifPresent(id -> checkArgument(id > 0 && id != selectedRepositoryId, "upstreamRepositoryId must be positive and differ from selectedRepositoryId"));
    }

    public long monitoredRepositoryId()
    {
        return upstreamRepositoryId.orElse(selectedRepositoryId);
    }
}
