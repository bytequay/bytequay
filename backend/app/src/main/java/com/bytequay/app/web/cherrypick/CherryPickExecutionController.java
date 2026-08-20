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
package com.bytequay.app.web.cherrypick;

import com.bytequay.app.cherrypick.CherryPickExecutionService;
import com.bytequay.app.cherrypick.StartCherryPickExecutionCommand;
import com.bytequay.core.cherrypick.CherryPickExecutionId;
import com.bytequay.core.cherrypick.CherryPickInvalidRequestException;
import com.google.common.collect.ImmutableList;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Execution endpoints, behind the desktop session filter like every other. */
@RestController
@RequestMapping("/api/cherry-pick-ranges/executions")
public class CherryPickExecutionController
{
    private final CherryPickExecutionService executions;

    public CherryPickExecutionController(CherryPickExecutionService executions)
    {
        this.executions = requireNonNull(executions, "executions is null");
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CherryPickExecutionResponse start(@RequestBody CherryPickExecutionRequest request)
    {
        requireNonNull(request, "request is null");
        return CherryPickExecutionResponse.of(executions.start(
                new StartCherryPickExecutionCommand(
                        text(request.previewId(), "previewId"),
                        text(request.expectedSourceBaseSha(), "expectedSourceBaseSha"),
                        text(request.expectedSourceTipSha(), "expectedSourceTipSha"),
                        text(request.expectedTargetSha(), "expectedTargetSha"),
                        request.userSkippedSourceShas() == null
                                ? ImmutableList.of()
                                : ImmutableList.copyOf(request.userSkippedSourceShas()),
                        text(request.laneId(), "laneId"),
                        text(request.idempotencyKey(), "idempotencyKey")),
                absolutePath(request.repository(), "repository"),
                absolutePath(request.worktreeParent(), "worktreeParent")));
    }

    @GetMapping
    public List<CherryPickExecutionResponse> list(@RequestParam(defaultValue = "20") int limit)
    {
        return executions.list(limit).stream()
                .map(CherryPickExecutionResponse::of)
                .collect(toImmutableList());
    }

    @GetMapping("/{executionId}")
    public CherryPickExecutionResponse get(@PathVariable String executionId)
    {
        return CherryPickExecutionResponse.of(executions.get(executionId(executionId)));
    }

    @PostMapping("/{executionId}/messages")
    public CherryPickExecutionResponse sendMessage(
            @PathVariable String executionId,
            @RequestBody CherryPickMessageRequest request)
    {
        requireNonNull(request, "request is null");
        return CherryPickExecutionResponse.of(executions.sendMessage(
                executionId(executionId), text(request.message(), "message")));
    }

    @PostMapping("/{executionId}/cancel")
    public CherryPickExecutionResponse cancel(
            @PathVariable String executionId,
            @RequestParam String repository)
    {
        return CherryPickExecutionResponse.of(executions.cancel(
                executionId(executionId), absolutePath(repository, "repository")));
    }

    private static CherryPickExecutionId executionId(String value)
    {
        try {
            return CherryPickExecutionId.valueOf(value);
        }
        catch (IllegalArgumentException e) {
            throw new CherryPickInvalidRequestException("executionId is not a valid identifier");
        }
    }

    private static String text(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new CherryPickInvalidRequestException(name + " is blank");
        }
        return value;
    }

    private static Path absolutePath(String value, String name)
    {
        try {
            Path path = Path.of(text(value, name));
            if (!path.isAbsolute()) {
                throw new CherryPickInvalidRequestException(name + " is not absolute");
            }
            return path.normalize();
        }
        catch (InvalidPathException e) {
            throw new CherryPickInvalidRequestException(name + " is not a valid path");
        }
    }
}
