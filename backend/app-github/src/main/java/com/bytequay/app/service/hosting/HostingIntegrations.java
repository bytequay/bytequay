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

import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.core.hosting.HostingProvider;
import com.google.common.base.Suppliers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

/** The only place that chooses a provider's hosting implementation. */
@Service
public class HostingIntegrations
{
    // GitHub's adapters reach credentials through the connection service, which itself selects through this registry,
    // so the integrations are looked up on first use rather than while the registry is created.
    private final Supplier<Map<HostingProvider, HostingIntegration>> integrations;

    public HostingIntegrations(ObjectProvider<HostingIntegration> integrations)
    {
        requireNonNull(integrations, "integrations is null");
        this.integrations = Suppliers.memoize(() -> integrations.orderedStream().collect(toImmutableMap(HostingIntegration::provider, integration -> integration)));
    }

    public HostingIntegration of(HostingProvider provider)
    {
        HostingIntegration integration = integrations.get().get(requireNonNull(provider, "provider is null"));
        checkArgument(integration != null, "unsupported hosting provider");
        return integration;
    }

    public HostingIntegration of(HostingReadSession session)
    {
        return of(session.access().server().provider());
    }
}
