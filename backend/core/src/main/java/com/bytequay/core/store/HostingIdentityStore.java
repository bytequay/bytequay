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
package com.bytequay.core.store;

import com.bytequay.core.hosting.HostingAccount;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.RemoteHostingAccount;
import com.bytequay.core.hosting.RemoteHostingRepository;

import java.util.List;
import java.util.Optional;

/** Retained local identities. Remote IDs are scoped to an installation; paths and logins are metadata. */
public interface HostingIdentityStore
{
    HostingInstance upsertInstance(HostingServer server);

    Optional<HostingInstance> findInstance(long id);

    List<HostingInstance> listInstances();

    HostingAccount upsertAccount(long instanceId, RemoteHostingAccount remote);

    Optional<HostingAccount> findAccount(long id);

    List<HostingAccount> listAccounts(long instanceId);

    HostingRepository upsertRepository(long instanceId, RemoteHostingRepository remote);

    Optional<HostingRepository> findRepository(long id);

    /** All retained identities at these coordinates, including a path later assigned to another remote repository. */
    List<HostingRepository> findRepositories(HostingServer server, String fullPath);
}
