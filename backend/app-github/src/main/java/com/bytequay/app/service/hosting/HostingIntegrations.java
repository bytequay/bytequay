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

import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.hosting.HostingVersion;
import com.bytequay.core.store.HostingIdentityStore;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static com.bytequay.core.hosting.HostingFailure.Kind.UNAVAILABLE;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.groupingBy;

/** The only place that chooses a server's hosting implementation: the provider's newest one that the server's release supports. */
@Service
public class HostingIntegrations
{
    private static final Comparator<HostingIntegration> NEWEST_FIRST = Comparator.comparing(
            (HostingIntegration integration) -> integration.since().orElse(new HostingVersion(0, 0, 0)), Comparator.reverseOrder());

    // GitHub's adapters reach credentials through the connection service, which itself selects through this registry,
    // so the integrations are looked up on first use rather than while the registry is created.
    private final Supplier<Map<HostingProvider, ImmutableList<HostingIntegration>>> integrations;
    private final HostingIdentityStore identities;

    public HostingIntegrations(ObjectProvider<HostingIntegration> integrations, HostingIdentityStore identities)
    {
        requireNonNull(integrations, "integrations is null");
        this.identities = requireNonNull(identities, "identities is null");
        this.integrations = Suppliers.memoize(() -> integrations.orderedStream()
                .sorted(NEWEST_FIRST)
                .collect(groupingBy(HostingIntegration::provider, toImmutableList())));
    }

    /** The implementations for the server's release last read when an account on it was verified; a server not read yet gets the newest. */
    public HostingIntegration of(HostingServer server)
    {
        requireNonNull(server, "server is null");
        return of(server.provider(), identities.findVersion(server));
    }

    public HostingIntegration of(HostingTarget target)
    {
        return of(target.access().server());
    }

    public HostingIntegration of(HostingProvider provider, Optional<HostingVersion> version)
    {
        requireNonNull(provider, "provider is null");
        requireNonNull(version, "version is null");
        List<HostingIntegration> candidates = integrations.get().get(provider);
        checkArgument(candidates != null, "unsupported hosting provider");
        return candidates.stream()
                .filter(integration -> version.isEmpty() || integration.since().filter(since -> !version.orElseThrow().isAtLeast(since)).isEmpty())
                .findFirst()
                .orElseThrow(() -> new HostingFailure(UNAVAILABLE, "This hosting server's release (" + version.orElseThrow() + ") is older than ByteQuay supports"));
    }
}
