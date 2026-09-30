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
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One task's judgment of an exact note version it received or opened, or the user's report of it. A task keeps one
 * grade per grader and note version; a later grade replaces it. A Wrong or Holds quotes one statement of that version
 * and cites the lines that contradict or support it. A Wrong that saved a correction names the version it produced
 * and the statement that replaced the quote.
 */
public record ContextGrade(
        String workspaceId,
        String taskId,
        Optional<String> reviewRunId,
        ContextUsageEvent.TaskKind taskKind,
        Grader grader,
        Grade grade,
        ContextUsageEvent.Note note,
        String reason,
        Optional<String> quote,
        List<Citation> citations,
        Optional<Correction> correction,
        Instant gradedAt)
{
    public static final int MAX_REASON_BYTES = 300;
    public static final int MAX_QUOTE_BYTES = 500;
    public static final int MAX_CITATIONS = 3;

    public ContextGrade
    {
        ContextLimits.text(workspaceId, "workspaceId", 200);
        ContextLimits.text(taskId, "taskId", 200);
        requireNonNull(reviewRunId, "reviewRunId is null");
        requireNonNull(taskKind, "taskKind is null");
        checkArgument((taskKind == ContextUsageEvent.TaskKind.PR_REVIEW) == reviewRunId.isPresent(), "Only review grades have a review run ID");
        requireNonNull(grader, "grader is null");
        requireNonNull(grade, "grade is null");
        requireNonNull(note, "note is null");
        requireNonNull(reason, "reason is null");
        checkArgument(ContextLimits.bytes(reason) <= MAX_REASON_BYTES, "A grade's reason exceeds %s UTF-8 bytes", MAX_REASON_BYTES);
        requireNonNull(quote, "quote is null");
        quote.ifPresent(value -> checkArgument(!value.isBlank() && ContextLimits.bytes(value) <= MAX_QUOTE_BYTES,
                "A quoted statement must be nonblank and at most %s UTF-8 bytes", MAX_QUOTE_BYTES));
        citations = ImmutableList.copyOf(citations);
        requireNonNull(correction, "correction is null");
        requireNonNull(gradedAt, "gradedAt is null");
        boolean statement = grade == Grade.WRONG || grade == Grade.HOLDS;
        if (grader == Grader.USER) {
            checkArgument(grade == Grade.WRONG && quote.isEmpty() && citations.isEmpty() && correction.isEmpty(),
                    "A user report is a Wrong with an optional reason only");
        }
        else {
            checkArgument(!reason.isBlank(), "An agent grade needs a reason");
            checkArgument(statement == quote.isPresent(), "Wrong and Holds quote one statement; other grades quote none");
            checkArgument(statement ? citations.size() >= 1 && citations.size() <= MAX_CITATIONS : citations.isEmpty(),
                    "Wrong and Holds cite one to %s line ranges; other grades cite none", MAX_CITATIONS);
        }
        checkArgument(correction.isEmpty() || grade == Grade.WRONG, "Only a Wrong can save a correction");
    }

    public enum Grader
    {
        AGENT,
        USER
    }

    public enum Grade
    {
        HELPED,
        NOT_RELEVANT,
        WRONG,
        HOLDS
    }

    /** Lines a Wrong or Holds cites, captured at the grader's base. */
    public record Citation(String path, int startLine, int endLine, String commit, String excerpt)
    {
        public Citation
        {
            ContextLimits.text(path, "path", 1000);
            checkArgument(startLine > 0 && endLine >= startLine, "Invalid cited line range");
            checkArgument(commit != null && commit.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Invalid cited commit");
            requireNonNull(excerpt, "excerpt is null");
            checkArgument(ContextLimits.bytes(excerpt) <= 24 * 1024, "Cited excerpt exceeds 24 KiB");
        }
    }

    /** The version a Wrong saved and the statement that replaced the quote in it. */
    public record Correction(long version, String statement)
    {
        public Correction
        {
            checkArgument(version > 0, "Correction version must be positive");
            requireNonNull(statement, "statement is null");
            checkArgument(!statement.isBlank() && ContextLimits.bytes(statement) <= MAX_QUOTE_BYTES,
                    "A corrected statement must be nonblank and at most %s UTF-8 bytes", MAX_QUOTE_BYTES);
        }
    }
}
