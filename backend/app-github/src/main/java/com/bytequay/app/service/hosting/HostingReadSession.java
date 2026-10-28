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
package com.bytequay.app.service.hosting;

import com.bytequay.core.hosting.HostingAccess;

import java.util.function.Supplier;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Captured authorization for reads; its owner guards acceptance without exposing secrets to the cache. */
public record HostingReadSession(long accountId, String credentialScope, long generation, HostingAccess access, Guard guard)
{
    public HostingReadSession
    {
        checkArgument(accountId > 0, "accountId must be positive");
        checkText(credentialScope, "credentialScope");
        checkArgument(generation > 0, "generation must be positive");
        requireNonNull(access, "access is null");
        requireNonNull(guard, "guard is null");
    }

    public <T> T withCurrent(Supplier<T> action)
    {
        return guard.withCurrent(requireNonNull(action, "action is null"));
    }

    public interface Guard
    {
        <T> T withCurrent(Supplier<T> action);
    }
}
