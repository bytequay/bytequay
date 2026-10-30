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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** The connection and repository one remote call acts on; both belong to the same hosting instance. */
public record HostingTarget(HostingAccess access, HostingRepository repository)
{
    public HostingTarget
    {
        requireNonNull(access, "access is null");
        requireNonNull(repository, "repository is null");
        checkArgument(access.instanceId().isPresent() && access.instanceId().getAsLong() == repository.instanceId(), "Repository belongs to another hosting instance");
    }

    public HostingProvider provider()
    {
        return access.server().provider();
    }
}
