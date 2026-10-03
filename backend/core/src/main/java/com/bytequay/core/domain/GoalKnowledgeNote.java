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
import java.util.Optional;
import java.util.regex.Pattern;

import static com.bytequay.core.domain.Checks.checkOptionalSha;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * What a research run learned for a goal, with the sources it rests on, for lessons, discovery and later research to reuse.
 *
 * @param taskId the research run that saved it
 */
public record GoalKnowledgeNote(String id, String goalId, String workspaceId, String topic, String body, List<Source> sources, String taskId, Instant savedAt)
{
    public static final int MAX_SOURCES = 8;

    public GoalKnowledgeNote
    {
        checkText(id, "id");
        checkText(goalId, "goalId");
        checkText(workspaceId, "workspaceId");
        checkText(topic, "topic", 120);
        checkText(body, "body", 2_000);
        sources = ImmutableList.copyOf(sources);
        checkArgument(!sources.isEmpty() && sources.size() <= MAX_SOURCES, "a note cites one to %s sources", MAX_SOURCES);
        checkText(taskId, "taskId");
        requireNonNull(savedAt, "savedAt is null");
    }

    /**
     * A page with its section, or a repository path at a commit.
     *
     * @param reference an https address, or {@code owner/repo:path} for a file
     * @param commit the commit a file was read at; empty for a page
     */
    public record Source(String reference, Optional<String> commit)
    {
        private static final Pattern FILE = Pattern.compile("[\\w.-]+/[\\w.-]+:[^\\s:]+");

        public Source
        {
            checkText(reference, "reference", 600);
            checkOptionalSha(commit, "commit");
            checkArgument(commit.isEmpty() ? reference.startsWith("https://") && reference.chars().noneMatch(Character::isWhitespace)
                    : FILE.matcher(reference).matches(), "a source is an https page or a file as owner/repo:path at a commit: %s", reference);
        }
    }
}
