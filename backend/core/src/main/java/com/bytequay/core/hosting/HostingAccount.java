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

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;

/** The remote user ID is stable across login changes and scoped to its installation. */
public record HostingAccount(long id, long instanceId, String remoteUserId, String login)
{
    public HostingAccount
    {
        checkArgument(id > 0, "id must be positive");
        checkArgument(instanceId > 0, "instanceId must be positive");
        checkText(remoteUserId, "remoteUserId");
        checkText(login, "login");
    }
}
