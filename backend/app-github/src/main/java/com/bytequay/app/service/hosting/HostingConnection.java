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

import com.bytequay.core.hosting.HostingAccount;
import com.bytequay.core.hosting.HostingCapabilities;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingPermissions;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * One connected account on its server.
 *
 * @param capabilities what the app supports for this account's server, for a request opened through the account
 */
public record HostingConnection(HostingAccount account, HostingInstance instance, boolean connected, Optional<HostingPermissions> permissions,
        HostingCapabilities capabilities)
{
    public HostingConnection
    {
        requireNonNull(account, "account is null");
        requireNonNull(instance, "instance is null");
        requireNonNull(permissions, "permissions is null");
        requireNonNull(capabilities, "capabilities is null");
    }
}
