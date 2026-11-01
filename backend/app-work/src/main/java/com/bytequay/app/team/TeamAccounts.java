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
package com.bytequay.app.team;

import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.hosting.HostingConnection;
import com.bytequay.app.service.hosting.HostingConnectionService;
import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.store.HostingIdentityStore;
import com.google.common.collect.ImmutableList;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static com.bytequay.core.hosting.HostingFailure.Kind.MISSING_OR_INACCESSIBLE;
import static java.util.Objects.requireNonNull;

/** The accounts circles belong to: the desktop GitHub sign-in when one is set up, then every other connected account. */
@Component
class TeamAccounts
{
    private final CredentialService desktop;
    private final HostingConnectionService connections;
    private final HostingIdentityStore identities;

    TeamAccounts(CredentialService desktop, HostingConnectionService connections, HostingIdentityStore identities)
    {
        this.desktop = requireNonNull(desktop, "desktop is null");
        this.connections = requireNonNull(connections, "connections is null");
        this.identities = requireNonNull(identities, "identities is null");
    }

    /** Every account; the desktop sign-in is verified once per token. */
    List<TeamAccount> all()
    {
        return accounts(desktop.isGitHubAccountConfigured() ? Optional.of(desktop.captureHostingRead()) : Optional.empty());
    }

    /** The accounts known without contacting a provider: the desktop sign-in only once a read has verified it. */
    List<TeamAccount> cached()
    {
        return accounts(desktop.cachedHostingRead());
    }

    TeamAccount require(long accountId)
    {
        return all().stream().filter(account -> account.id() == accountId).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "This account is no longer signed in"));
    }

    private List<TeamAccount> accounts(Optional<HostingReadSession> signIn)
    {
        ImmutableList.Builder<TeamAccount> accounts = ImmutableList.builder();
        signIn.ifPresent(session -> accounts.add(account(session)));
        connections.connections().stream()
                .filter(HostingConnection::connected)
                .filter(connection -> signIn.filter(session -> session.accountId() == connection.account().id()).isEmpty())
                .forEach(connection -> accounts.add(new TeamAccount(connections.readSession(connection.account().id()), connection.account())));
        return accounts.build();
    }

    private TeamAccount account(HostingReadSession session)
    {
        return new TeamAccount(session, identities.findAccount(session.accountId())
                .orElseThrow(() -> new HostingFailure(MISSING_OR_INACCESSIBLE, "Hosting account is unavailable")));
    }
}
