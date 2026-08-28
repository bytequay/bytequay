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
        return builder().build();
    }

    public static PrTimelinePayload withReactions(List<PrReaction> reactions)
    {
        return builder().reactions(reactions).build();
    }

    public static Builder builder()
    {
        return new Builder();
    }

    /** Seeds a builder with every field of {@code payload}, for copy-and-tweak. */
    public static Builder builder(PrTimelinePayload payload)
    {
        return new Builder()
                .actorAvatarUrl(payload.actorAvatarUrl())
                .actorBot(payload.actorBot())
                .state(payload.state())
                .htmlUrl(payload.htmlUrl())
                .label(payload.label())
                .labelColor(payload.labelColor())
                .renameFrom(payload.renameFrom())
                .renameTo(payload.renameTo())
                .commitSha(payload.commitSha())
                .commitMessage(payload.commitMessage())
                .sourceTitle(payload.sourceTitle())
                .sourceUrl(payload.sourceUrl())
                .sourceNumber(payload.sourceNumber())
                .milestoneTitle(payload.milestoneTitle())
                .path(payload.path())
                .diffHunk(payload.diffHunk())
                .line(payload.line())
                .originalLine(payload.originalLine())
                .startLine(payload.startLine())
                .originalStartLine(payload.originalStartLine())
                .side(payload.side())
                .resolved(payload.resolved())
                .outdated(payload.outdated())
                .reactions(payload.reactions())
                .comments(payload.comments());
    }

    /** Fields default to null/false/empty, so a construction site names only what its event carries. */
    public static final class Builder
    {
        private String actorAvatarUrl;
        private boolean actorBot;
        private String state;
        private String htmlUrl;
        private String label;
        private String labelColor;
        private String renameFrom;
        private String renameTo;
        private String commitSha;
        private String commitMessage;
        private String sourceTitle;
        private String sourceUrl;
        private Integer sourceNumber;
        private String milestoneTitle;
        private String path;
        private String diffHunk;
        private Integer line;
        private Integer originalLine;
        private Integer startLine;
        private Integer originalStartLine;
        private String side;
        private boolean resolved;
        private boolean outdated;
        private List<PrReaction> reactions = ImmutableList.of();
        private List<PrTimelineComment> comments = ImmutableList.of();

        private Builder() {}

        public Builder actorAvatarUrl(String actorAvatarUrl)
        {
            this.actorAvatarUrl = actorAvatarUrl;
            return this;
        }

        public Builder actorBot(boolean actorBot)
        {
            this.actorBot = actorBot;
            return this;
        }

        public Builder state(String state)
        {
            this.state = state;
            return this;
        }

        public Builder htmlUrl(String htmlUrl)
        {
            this.htmlUrl = htmlUrl;
            return this;
        }

        public Builder label(String label)
        {
            this.label = label;
            return this;
        }

        public Builder labelColor(String labelColor)
        {
            this.labelColor = labelColor;
            return this;
        }

        public Builder renameFrom(String renameFrom)
        {
            this.renameFrom = renameFrom;
            return this;
        }

        public Builder renameTo(String renameTo)
        {
            this.renameTo = renameTo;
            return this;
        }

        public Builder commitSha(String commitSha)
        {
            this.commitSha = commitSha;
            return this;
        }

        public Builder commitMessage(String commitMessage)
        {
            this.commitMessage = commitMessage;
            return this;
        }

        public Builder sourceTitle(String sourceTitle)
        {
            this.sourceTitle = sourceTitle;
            return this;
        }

        public Builder sourceUrl(String sourceUrl)
        {
            this.sourceUrl = sourceUrl;
            return this;
        }

        public Builder sourceNumber(Integer sourceNumber)
        {
            this.sourceNumber = sourceNumber;
            return this;
        }

        public Builder milestoneTitle(String milestoneTitle)
        {
            this.milestoneTitle = milestoneTitle;
            return this;
        }

        public Builder path(String path)
        {
            this.path = path;
            return this;
        }

        public Builder diffHunk(String diffHunk)
        {
            this.diffHunk = diffHunk;
            return this;
        }

        public Builder line(Integer line)
        {
            this.line = line;
            return this;
        }

        public Builder originalLine(Integer originalLine)
        {
            this.originalLine = originalLine;
            return this;
        }

        public Builder startLine(Integer startLine)
        {
            this.startLine = startLine;
            return this;
        }

        public Builder originalStartLine(Integer originalStartLine)
        {
            this.originalStartLine = originalStartLine;
            return this;
        }

        public Builder side(String side)
        {
            this.side = side;
            return this;
        }

        public Builder resolved(boolean resolved)
        {
            this.resolved = resolved;
            return this;
        }

        public Builder outdated(boolean outdated)
        {
            this.outdated = outdated;
            return this;
        }

        public Builder reactions(List<PrReaction> reactions)
        {
            this.reactions = reactions;
            return this;
        }

        public Builder comments(List<PrTimelineComment> comments)
        {
            this.comments = comments;
            return this;
        }

        public PrTimelinePayload build()
        {
            return new PrTimelinePayload(
                    actorAvatarUrl,
                    actorBot,
                    state,
                    htmlUrl,
                    label,
                    labelColor,
                    renameFrom,
                    renameTo,
                    commitSha,
                    commitMessage,
                    sourceTitle,
                    sourceUrl,
                    sourceNumber,
                    milestoneTitle,
                    path,
                    diffHunk,
                    line,
                    originalLine,
                    startLine,
                    originalStartLine,
                    side,
                    resolved,
                    outdated,
                    reactions,
                    comments);
        }
    }
}
