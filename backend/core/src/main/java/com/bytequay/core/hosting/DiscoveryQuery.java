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

import java.time.Instant;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Which of an account's requests to find; each provider turns it into its own search. */
public record DiscoveryQuery(Kind kind, Optional<String> repository, State state, Optional<Instant> updatedSince)
{
    public enum Kind
    {
        AUTHORED,
        REVIEW_REQUESTED,
        REVIEWED,
        INVOLVED,
        REPOSITORY
    }

    public enum State
    {
        OPEN,
        CLOSED,
        ANY
    }

    public DiscoveryQuery
    {
        requireNonNull(kind, "kind is null");
        requireNonNull(repository, "repository is null");
        requireNonNull(state, "state is null");
        requireNonNull(updatedSince, "updatedSince is null");
        checkArgument(repository.isPresent() == (kind == Kind.REPOSITORY), "only a repository query names a repository");
    }

    public static DiscoveryQuery of(Kind kind, State state)
    {
        return new DiscoveryQuery(kind, Optional.empty(), state, Optional.empty());
    }

    public static DiscoveryQuery repository(String fullPath)
    {
        return new DiscoveryQuery(Kind.REPOSITORY, Optional.of(fullPath), State.ANY, Optional.empty());
    }

    public DiscoveryQuery updatedSince(Instant since)
    {
        return new DiscoveryQuery(kind, repository, state, Optional.of(since));
    }

    /** Names the query without its time window, so a checkpoint survives from one run to the next. */
    public String key()
    {
        return kind + "." + state + repository.map(path -> "." + path).orElse("");
    }
}
