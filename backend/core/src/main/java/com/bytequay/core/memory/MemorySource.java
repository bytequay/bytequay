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
package com.bytequay.core.memory;

import com.bytequay.core.context.ContextLimits;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Where a memory came from, with a short quote of the words it rests on. ByteQuay binds every source; a model never names one
 * it was not given.
 *
 * @param url the comment, review or pull request on GitHub, or the page address; the user's words and commands have none
 * @param quote the words the memory rests on; for a command, the command itself
 * @param at when the comment or the user's words were written, the page was read, or the command finished
 * @param pullRequest the pull request part, for a {@link Kind#PULL_REQUEST} source
 * @param section the page section, for a {@link Kind#PAGE} source
 * @param user where the user wrote the words, for a {@link Kind#USER} source
 * @param command the command that passed, for a {@link Kind#COMMAND} source
 */
public record MemorySource(
        Kind kind,
        Optional<String> url,
        String quote,
        Instant at,
        Optional<PullRequestPart> pullRequest,
        Optional<String> section,
        Optional<UserPart> user,
        Optional<CommandPart> command)
{
    public static final int MAX_QUOTE_BYTES = 300;

    public enum Kind
    {
        PULL_REQUEST,
        PAGE,
        USER,
        COMMAND,
    }

    public MemorySource
    {
        requireNonNull(kind, "kind is null");
        requireNonNull(url, "url is null");
        checkArgument(url.isPresent() == (kind == Kind.PULL_REQUEST || kind == Kind.PAGE), "only a pull request or page source has an address");
        url.ifPresent(value -> {
            ContextLimits.text(value, "source url", 2048);
            checkArgument("https".equals(URI.create(value).getScheme()), "source url must use HTTPS");
        });
        requireNonNull(quote, "quote is null");
        checkArgument(ContextLimits.bytes(quote) <= MAX_QUOTE_BYTES, "source quote exceeds %s UTF-8 bytes", MAX_QUOTE_BYTES);
        requireNonNull(at, "at is null");
        requireNonNull(pullRequest, "pullRequest is null");
        requireNonNull(section, "section is null");
        requireNonNull(user, "user is null");
        requireNonNull(command, "command is null");
        checkArgument(pullRequest.isPresent() == (kind == Kind.PULL_REQUEST), "only a pull request source has a pull request part");
        checkArgument(kind == Kind.PAGE || section.isEmpty(), "only a page source has a section");
        checkArgument(user.isPresent() == (kind == Kind.USER), "only the user's words have a user part");
        checkArgument(command.isPresent() == (kind == Kind.COMMAND), "only a command source has a command part");
        checkArgument(kind == Kind.PAGE || !quote.isBlank(), "a %s source quotes the words it rests on", kind);
        section.ifPresent(value -> ContextLimits.text(value, "source section", 200));
    }

    public static MemorySource pullRequest(String url, String quote, Instant at, PullRequestPart part)
    {
        return new MemorySource(Kind.PULL_REQUEST, Optional.of(url), quote, at, Optional.of(part), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static MemorySource page(String url, String quote, Instant at, Optional<String> section)
    {
        return new MemorySource(Kind.PAGE, Optional.of(url), quote, at, Optional.empty(), section, Optional.empty(), Optional.empty());
    }

    public static MemorySource user(String words, Instant at, UserPart part)
    {
        return new MemorySource(Kind.USER, Optional.empty(), words, at, Optional.empty(), Optional.empty(), Optional.of(part), Optional.empty());
    }

    /** A passing command; the quote is the command, cut to {@value #MAX_QUOTE_BYTES} bytes, and the part keeps it whole. */
    public static MemorySource command(String quote, Instant at, CommandPart part)
    {
        return new MemorySource(Kind.COMMAND, Optional.empty(), quote, at, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(part));
    }

    /** What makes two sources the same: the address, or where the user's words or the command came from. */
    public String key()
    {
        return url.orElseGet(() -> user.map(part -> "user:" + part.where() + ":" + part.id() + ":" + part.item())
                .orElseGet(() -> command.map(part -> "command:" + part.taskId() + ":" + part.turnId() + ":" + part.command()).orElseThrow()));
    }

    /**
     * One comment, review or close on a pull request.
     *
     * @param commentId GitHub's id of the comment or review, unique within the repository
     * @param maintainer whether GitHub reported the author as an owner, member or collaborator
     * @param resolved whether the review thread was resolved
     * @param outdated whether the code under the review thread changed afterwards
     */
    public record PullRequestPart(int number, Part part, String commentId, String author, boolean maintainer, boolean merged, boolean resolved, boolean outdated)
    {
        public PullRequestPart
        {
            checkArgument(number > 0, "pull request number must be positive");
            requireNonNull(part, "part is null");
            checkText(commentId, "commentId", 200);
            checkText(author, "author", 100);
        }
    }

    public enum Part
    {
        REVIEW_COMMENT,
        REVIEW,
        COMMENT,
        CLOSE,
    }

    /**
     * Where the user wrote the words: a message in a development task or a review discussion, or the reason they gave when
     * resolving a review finding or dismissing a spec finding.
     *
     * @param id the task, review or spec check
     * @param item the message, finding or spec candidate
     */
    public record UserPart(Where where, String id, String item)
    {
        public UserPart
        {
            requireNonNull(where, "where is null");
            checkText(id, "id", 200);
            checkText(item, "item", 200);
        }
    }

    public enum Where
    {
        TASK_MESSAGE,
        REVIEW_MESSAGE,
        RESOLVE,
        DISMISS,
    }

    /**
     * A command that finished with exit code 0 in a task, as its event log recorded it.
     *
     * @param directory where it ran, relative to the task's worktree; "." for the worktree itself
     * @param commit the worktree's head when the agent remembered it
     */
    public record CommandPart(String taskId, String turnId, String command, String directory, String commit)
    {
        public static final int MAX_COMMAND_LENGTH = 2000;

        public CommandPart
        {
            checkText(taskId, "taskId", 200);
            checkText(turnId, "turnId", 200);
            checkText(command, "command", MAX_COMMAND_LENGTH);
            checkText(directory, "directory", 1000);
            checkArgument(commit.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "commit must be a full commit ID");
        }
    }
}
