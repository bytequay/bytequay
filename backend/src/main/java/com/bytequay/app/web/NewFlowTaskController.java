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
package com.bytequay.app.web;

import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.TaskCommands;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static java.util.Objects.requireNonNull;

/** Local program command for starting one greenfield Task. */
@RestController
@RequestMapping("/api/new-flow/repositories/{owner}/{repository}/tasks")
public final class NewFlowTaskController
{
    private static final int MAX_REPOSITORY_PART = 100;
    private static final int MAX_IDEMPOTENCY_KEY = 256;

    private final TaskCommands commands;

    public NewFlowTaskController(TaskCommands commands)
    {
        this.commands = requireNonNull(commands, "commands is null");
    }

    @PostMapping
    public ResponseEntity<StartedTask> start(
            @PathVariable String owner,
            @PathVariable String repository,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody StartTaskBody body)
    {
        requireRepositoryPart(owner, "owner");
        requireRepositoryPart(repository, "repository");
        requireHeader(idempotencyKey);
        requireNonNull(body, "body is null");
        String repositoryId = owner + "/" + repository;
        Task task = commands.startTask(
                requestKey(repositoryId, idempotencyKey),
                repositoryId,
                body.goalText());
        return ResponseEntity.accepted().body(
                new StartedTask(task.taskId(), task.repositoryId(), task.status().name()));
    }

    private static String requestKey(String repositoryId, String idempotencyKey)
    {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        update(digest, repositoryId);
        update(digest, idempotencyKey);
        return "task-command:v1:" + HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value)
    {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        digest.update(bytes);
    }

    private static void requireRepositoryPart(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank() || value.length() > MAX_REPOSITORY_PART
                || !value.equals(value.strip())
                || value.indexOf('/') >= 0
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static void requireHeader(String value)
    {
        requireNonNull(value, "Idempotency-Key is null");
        if (value.isBlank() || value.length() > MAX_IDEMPOTENCY_KEY
                || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Idempotency-Key is invalid");
        }
    }

    public record StartTaskBody(String goalText) {}

    public record StartedTask(String taskId, String repositoryId, String status) {}
}
