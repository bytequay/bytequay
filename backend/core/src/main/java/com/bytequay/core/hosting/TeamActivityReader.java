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

import com.bytequay.core.domain.StoredPrDetail;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.BiFunction;

/** Bounded read-only observations of people a viewer follows; no stream is a complete audit log. */
public interface TeamActivityReader
{
    /** How far back one pass reads. */
    Duration LOOK_BACK = Duration.ofDays(30);

    /** The person with this login on the account's server. */
    HostingPerson person(HostingAccess access, String login);

    /** One person's recent activity across the server, or none where the provider has no such stream. */
    TeamObservation personActivity(HostingAccess access, String login, TeamIdentity identity);

    /**
     * One repository's recent activity. Request reviews are read for a few of its newest requests starting at {@code offset}, reusing
     * a request's cached detail when it is at least as new as the request.
     */
    TeamObservation repositoryActivity(HostingAccess access, String repository, TeamIdentity identity, int offset,
            BiFunction<Integer, Instant, Optional<StoredPrDetail>> cachedDetails);
}
