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

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** One root or reply comment in an inline pull-request review thread. */
public record PrTimelineComment(
        Long githubId,
        String actor,
        String actorAvatarUrl,
        boolean actorBot,
        Instant timestamp,
        String body,
        String htmlUrl,
        Long reviewId,
        Long inReplyToId,
        List<PrReaction> reactions)
{
    public PrTimelineComment
    {
        reactions = ImmutableList.copyOf(requireNonNull(reactions, "reactions is null"));
    }
}
