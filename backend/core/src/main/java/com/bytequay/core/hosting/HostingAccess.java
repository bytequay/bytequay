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

import java.util.OptionalLong;
import java.util.function.Supplier;

import static com.bytequay.core.hosting.HostingFailure.Kind.AUTHENTICATION_REQUIRED;
import static java.util.Objects.requireNonNull;

/** One connection's credentials, bound by the application; never a transport or persistence value. */
public final class HostingAccess
{
    private final HostingServer server;
    private final OptionalLong instanceId;
    private final Supplier<String> token;

    public HostingAccess(HostingInstance instance, Supplier<String> token)
    {
        this(new HostingServer(instance.provider(), instance.webBaseUrl(), instance.apiBaseUrl()), OptionalLong.of(instance.id()), token);
    }

    public HostingAccess(HostingServer server, Supplier<String> token)
    {
        this(server, OptionalLong.empty(), token);
    }

    private HostingAccess(HostingServer server, OptionalLong instanceId, Supplier<String> token)
    {
        this.server = requireNonNull(server, "server is null");
        this.instanceId = requireNonNull(instanceId, "instanceId is null");
        this.token = requireNonNull(token, "token is null");
    }

    public HostingServer server()
    {
        return server;
    }

    public OptionalLong instanceId()
    {
        return instanceId;
    }

    public String token()
    {
        String value = token.get();
        // HttpRequest's own invalid-header exception includes the header value, so reject it before construction.
        if (value == null || value.isEmpty() || value.chars().anyMatch(character -> character < 33 || character > 126)) {
            throw new HostingFailure(AUTHENTICATION_REQUIRED, "The hosting credential is invalid");
        }
        return value;
    }

    @Override
    public String toString()
    {
        return "HostingAccess[instanceId=" + instanceId + ", token=<redacted>]";
    }
}
