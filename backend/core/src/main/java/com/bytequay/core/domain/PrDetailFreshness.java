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
package com.bytequay.core.domain;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Successful remote validations; a checks response never dates the cached discussion. */
public record PrDetailFreshness(
        Optional<Instant> metadataAt,
        Optional<Instant> discussionAt,
        Optional<Instant> checksAt,
        Optional<Instant> discussionSourceUpdatedAt)
{
    public PrDetailFreshness
    {
        requireNonNull(metadataAt, "metadataAt is null");
        requireNonNull(discussionAt, "discussionAt is null");
        requireNonNull(checksAt, "checksAt is null");
        requireNonNull(discussionSourceUpdatedAt, "discussionSourceUpdatedAt is null");
    }

    public static PrDetailFreshness unknown()
    {
        return new PrDetailFreshness(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
