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
package com.bytequay.core.context;

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Exact, turn-owned discussion input captured after the original review; never replaces its frozen context. */
public record ContextDiscussionSupplement(ContextPacket packet, List<String> evidenceRefs, Instant capturedAt, List<String> gaps)
{
    public static final int MAXIMUM_BYTES = 8 * 1024;

    public ContextDiscussionSupplement
    {
        requireNonNull(packet, "packet is null");
        checkArgument(packet.utf8Bytes() <= MAXIMUM_BYTES, "Discussion supplement exceeds UTF-8 limit");
        evidenceRefs = ImmutableList.copyOf(evidenceRefs);
        ContextLimits.unique(evidenceRefs, "discussion evidence", 6);
        evidenceRefs.forEach(ref -> ContextLimits.text(ref, "discussion evidence reference", 200));
        requireNonNull(capturedAt, "capturedAt is null");
        gaps = ImmutableList.copyOf(gaps);
        checkArgument(gaps.size() <= 10, "Too many discussion gaps");
        gaps.forEach(gap -> ContextLimits.text(gap, "discussion gap", 500));
    }
}
