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
package com.bytequay.core.cherrypick;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static com.bytequay.core.cherrypick.CherryPickChecks.checkOptionalSha;
import static com.bytequay.core.cherrypick.CherryPickChecks.checkOptionalText;
import static com.bytequay.core.cherrypick.CherryPickChecks.checkSha;
import static com.bytequay.core.cherrypick.CherryPickChecks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One source commit in a confirmed plan, with the durable answer for it.
 *
 * <p>{@link Disposition} is frozen at confirmation and never changes.
 * {@link Result} is what actually happened, and only the program that owns the
 * execution may advance it.
 */
public record CherryPickItem(
        int position,
        String sourceSha,
        String sourceParentSha,
        String title,
        Optional<PullRequestRefView> pullRequest,
        Disposition disposition,
        Result result,
        Optional<String> targetSha,
        List<ConflictPath> conflictPaths,
        int agentTurnCount)
{
    public static final int MAX_AGENT_TURNS = 3;
    private static final int MAX_CONFLICT_PATHS = 200;

    public CherryPickItem
    {
        checkArgument(position >= 1, "position is not one-based");
        checkSha(sourceSha, "sourceSha");
        checkSha(sourceParentSha, "sourceParentSha");
        checkArgument(!sourceSha.equals(sourceParentSha), "sourceSha equals sourceParentSha");
        checkText(title, "title", 1000);
        requireNonNull(pullRequest, "pullRequest is null");
        requireNonNull(disposition, "disposition is null");
        requireNonNull(result, "result is null");
        checkOptionalSha(targetSha, "targetSha");
        requireNonNull(conflictPaths, "conflictPaths is null");
        conflictPaths = ImmutableList.copyOf(conflictPaths);
        checkArgument(conflictPaths.size() <= MAX_CONFLICT_PATHS, "conflictPaths exceeds " + MAX_CONFLICT_PATHS + " values");
        checkArgument(agentTurnCount >= 0 && agentTurnCount <= MAX_AGENT_TURNS, "agentTurnCount is out of range");
        validate(disposition, result, targetSha);
    }

    public static CherryPickItem planned(
            int position,
            String sourceSha,
            String sourceParentSha,
            String title,
            Optional<PullRequestRefView> pullRequest,
            Disposition disposition)
    {
        return new CherryPickItem(
                position,
                sourceSha,
                sourceParentSha,
                title,
                pullRequest,
                disposition,
                initialResult(disposition),
                Optional.empty(),
                ImmutableList.of(),
                0);
    }

    /** Returns true when this item still needs work. */
    public boolean pending()
    {
        return result == Result.PENDING;
    }

    public CherryPickItem applied(String createdTargetSha)
    {
        checkArgument(pending(), "item %s is not pending", position);
        return new CherryPickItem(
                position,
                sourceSha,
                sourceParentSha,
                title,
                pullRequest,
                disposition,
                Result.APPLIED,
                Optional.of(checkSha(createdTargetSha, "createdTargetSha")),
                conflictPaths,
                agentTurnCount);
    }

    public CherryPickItem emptySkipped()
    {
        checkArgument(pending(), "item %s is not pending", position);
        return new CherryPickItem(
                position,
                sourceSha,
                sourceParentSha,
                title,
                pullRequest,
                disposition,
                Result.EMPTY_SKIPPED,
                Optional.empty(),
                conflictPaths,
                agentTurnCount);
    }

    public CherryPickItem withConflict(List<ConflictPath> paths)
    {
        checkArgument(pending(), "item %s is not pending", position);
        return new CherryPickItem(
                position,
                sourceSha,
                sourceParentSha,
                title,
                pullRequest,
                disposition,
                result,
                targetSha,
                paths,
                agentTurnCount);
    }

    public CherryPickItem withAgentTurnStarted()
    {
        checkArgument(pending(), "item %s is not pending", position);
        checkArgument(agentTurnCount < MAX_AGENT_TURNS, "item %s exhausted its agent turns", position);
        return new CherryPickItem(
                position,
                sourceSha,
                sourceParentSha,
                title,
                pullRequest,
                disposition,
                result,
                targetSha,
                conflictPaths,
                agentTurnCount + 1);
    }

    private static Result initialResult(Disposition disposition)
    {
        return switch (disposition) {
            case ALREADY_ON_TARGET -> Result.ALREADY_ON_TARGET;
            case USER_SKIPPED -> Result.USER_SKIPPED;
            case TO_CHERRY_PICK -> Result.PENDING;
        };
    }

    private static void validate(Disposition disposition, Result result, Optional<String> targetSha)
    {
        switch (disposition) {
            case ALREADY_ON_TARGET -> checkArgument(
                    result == Result.ALREADY_ON_TARGET,
                    "already-on-target item carries result %s", result);
            case USER_SKIPPED -> checkArgument(
                    result == Result.USER_SKIPPED,
                    "user-skipped item carries result %s", result);
            case TO_CHERRY_PICK -> checkArgument(
                    result == Result.PENDING || result == Result.APPLIED || result == Result.EMPTY_SKIPPED,
                    "cherry-pick item carries result %s", result);
        }
        checkArgument(
                (result == Result.APPLIED) == targetSha.isPresent(),
                "only an applied item carries a target SHA");
    }

    /** Frozen at confirmation; never recomputed from Git. */
    public enum Disposition
    {
        ALREADY_ON_TARGET,
        USER_SKIPPED,
        TO_CHERRY_PICK
    }

    /** The durable answer for one source commit. */
    public enum Result
    {
        PENDING,
        ALREADY_ON_TARGET,
        USER_SKIPPED,
        APPLIED,
        EMPTY_SKIPPED
    }

    /** Display-only pull-request association; never a control fact. */
    public record PullRequestRefView(int number, String title, String url)
    {
        public PullRequestRefView
        {
            checkArgument(number > 0, "number is not positive");
            checkText(title, "title", 1000);
            checkText(url, "url", 2000);
        }
    }

    public record ConflictPath(String path, ConflictType type, Optional<String> module)
    {
        public ConflictPath
        {
            checkText(path, "path", 1000);
            requireNonNull(type, "type is null");
            checkOptionalText(module, "module", 1000);
        }
    }

    public enum ConflictType
    {
        CONTENT,
        ADD_ADD,
        MODIFY_DELETE,
        RENAME,
        BINARY,
        SUBMODULE,
        UNKNOWN
    }
}
