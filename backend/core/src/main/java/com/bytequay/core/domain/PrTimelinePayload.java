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

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Event-specific fields for the finite PR overview timeline variants. Fields
 * that do not apply to an event stay null; collection fields are never null.
 */
public record PrTimelinePayload(
        String actorAvatarUrl,
        boolean actorBot,
        String state,
        String htmlUrl,
        String label,
        String labelColor,
        String renameFrom,
        String renameTo,
        String commitSha,
        String commitMessage,
        String sourceTitle,
        String sourceUrl,
        Integer sourceNumber,
        String milestoneTitle,
        String path,
        String diffHunk,
        Integer line,
        Integer originalLine,
        Integer startLine,
        Integer originalStartLine,
        String side,
        boolean resolved,
        boolean outdated,
        List<PrReaction> reactions,
        List<PrTimelineComment> comments)
{
    public PrTimelinePayload
    {
        reactions = ImmutableList.copyOf(requireNonNull(reactions, "reactions is null"));
        comments = ImmutableList.copyOf(requireNonNull(comments, "comments is null"));
    }

    public static PrTimelinePayload empty()
    {
        return new PrTimelinePayload(
                null, false, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, false, false, ImmutableList.of(), ImmutableList.of());
    }

    public static PrTimelinePayload withReactions(List<PrReaction> reactions)
    {
        return new PrTimelinePayload(
                null, false, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, false, false, reactions, ImmutableList.of());
    }
}
