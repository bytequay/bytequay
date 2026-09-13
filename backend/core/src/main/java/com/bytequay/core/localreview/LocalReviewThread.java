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
package com.bytequay.core.localreview;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Task-owned discussion and immutable code evidence, independent of disposable agent history. */
public record LocalReviewThread(String id, String taskId, String workspaceId, String repo, int number,
        String path, String side, int startLine, int line, Optional<String> baseSha, Optional<String> headSha, String code,
        boolean resolved, long version, Instant createdAt, Instant updatedAt, List<LocalReviewMessage> messages, Map<String, String> operations)
{
    public LocalReviewThread
    {
        requireNonNull(id, "id is null");
        requireNonNull(taskId, "taskId is null");
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(repo, "repo is null");
        requireNonNull(path, "path is null");
        requireNonNull(side, "side is null");
        requireNonNull(baseSha, "baseSha is null");
        requireNonNull(headSha, "headSha is null");
        requireNonNull(code, "code is null");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
        checkArgument(id.matches("[A-Za-z0-9._:-]{1,100}"), "Invalid comment id");
        checkArgument(!taskId.isBlank() && !workspaceId.isBlank(), "Task and workspace are required");
        checkArgument(repo.matches("[^/\\s]+/[^/\\s]+") && repo.length() <= 300 && number > 0, "Invalid pull request");
        checkArgument(!path.isBlank() && path.length() <= 4096 && !path.startsWith("/") && path.indexOf('\0') < 0, "Invalid relative path");
        checkArgument(!path.contains("\\") && !path.matches("(^|.*/)\\.\\.?(/.*|$)"), "Path contains traversal segments");
        checkArgument(side.equals("LEFT") || side.equals("RIGHT"), "Invalid diff side");
        checkArgument(startLine > 0 && line >= startLine && line <= 10_000_000, "Invalid line range");
        checkArgument(code.length() <= 60_000, "Selected code exceeds 60000 characters");
        baseSha.ifPresent(value -> checkArgument(value.matches("[0-9a-fA-F]{7,64}"), "Invalid base revision"));
        headSha.ifPresent(value -> checkArgument(value.matches("[0-9a-fA-F]{7,64}"), "Invalid head revision"));
        checkArgument(version >= 0, "Invalid version");
        messages = ImmutableList.copyOf(messages);
        operations = ImmutableMap.copyOf(operations);
        checkArgument(messages.size() <= 200 && operations.size() <= 1000, "Local review thread is full");
    }

    public LocalReviewThread withVersion(long value)
    {
        return new LocalReviewThread(id, taskId, workspaceId, repo, number, path, side, startLine, line, baseSha, headSha, code,
                resolved, value, createdAt, updatedAt, messages, operations);
    }

    public LocalReviewThread update(boolean nextResolved, List<LocalReviewMessage> nextMessages, Map<String, String> nextOperations, Instant now)
    {
        return new LocalReviewThread(id, taskId, workspaceId, repo, number, path, side, startLine, line, baseSha, headSha, code,
                nextResolved, version, createdAt, now, nextMessages, nextOperations);
    }
}
