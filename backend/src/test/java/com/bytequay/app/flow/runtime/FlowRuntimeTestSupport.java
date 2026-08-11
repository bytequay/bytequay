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
package com.bytequay.app.flow.runtime;

import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

import static java.util.Objects.requireNonNull;

/** Test-only bridge for pre-provisioning runtime fixtures. */
public final class FlowRuntimeTestSupport
{
    private FlowRuntimeTestSupport() {}

    public static Task startTask(
            FlowRuntime runtime,
            String requestKey,
            String repositoryId,
            String goal,
            String requestedWorktree)
    {
        try {
            if (runtime.taskForRequestKey(requestKey).isPresent()) {
                return new TaskProvisioning(
                        dataSource(runtime),
                        runtime,
                        ignored -> {
                            throw new AssertionError(
                                    "replay consulted repository catalog");
                        },
                        Clock.systemUTC())
                        .startTask(requestKey, repositoryId, goal);
            }
            String key = stableId("task", requestKey).split(":", 2)[1];
            Path requested = Path.of(requestedWorktree).toAbsolutePath();
            boolean realWorktree = Files.exists(requested);
            Path owner = realWorktree
                    ? requested.getParent()
                    : Files.createTempDirectory("flow-runtime-test-");
            Path root = owner.resolve("test-worktrees");
            Files.createDirectories(root);
            Path derived = root.resolve(key);
            Path repository = owner.resolve("test-repository-" + key);
            Path common = repository.resolve(".git");
            if (realWorktree) {
                repository = Path.of(git(requested, "worktree", "list", "--porcelain")
                        .lines().filter(line -> line.startsWith("worktree "))
                        .findFirst().orElseThrow().substring("worktree ".length()));
                common = Path.of(git(requested, "rev-parse",
                        "--path-format=absolute", "--git-common-dir"));
                git(requested, "worktree", "move",
                        requested.toString(), derived.toString());
                git(derived, "branch", "-m", "bytequay/" + key);
            }
            else {
                Files.createDirectories(common);
            }
            Path frozenRepository = repository;
            Path frozenCommon = common;
            TaskProvisioning provisioning = new TaskProvisioning(
                    dataSource(runtime),
                    runtime,
                    ignored -> new TaskProvisioning.RepositoryConfig(
                            repositoryId,
                            "octocat",
                            "bytequay",
                            frozenRepository,
                            frozenCommon,
                            "origin",
                            "refs/remotes/origin/main",
                            root),
                    Clock.systemUTC());
            return provisioning.startTask(requestKey, repositoryId, goal);
        }
        catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        catch (Exception failure) {
            throw failure instanceof RuntimeException runtimeFailure
                    ? runtimeFailure : new IllegalStateException(failure);
        }
    }

    public static void provisionTask(
            FlowRuntime runtime, Claim claim, String baseSha)
    {
        requireObjectId(baseSha);
        try {
            Operation operation = runtime.operation(claim.operationId())
                    .orElseThrow();
            Task task = runtime.task(operation.taskId()).orElseThrow();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource(runtime));
            jdbc.update(
                    """
                    INSERT OR IGNORE INTO flow_runtime_provision_subject (
                        operation_id, task_id, launch_digest, base_sha,
                        mutation_digest, bound_at
                    ) VALUES (?, ?, ?, ?, ?, 0)
                    """,
                    operation.operationId(),
                    task.taskId(),
                    task.launchDigest(),
                    baseSha,
                    "test-mutation:" + baseSha);
            TaskProvisioning.ResolvedSubject subject =
                    new TaskProvisioning.ResolvedSubject(
                            operation.operationId(),
                            task.taskId(),
                            task.launchDigest(),
                            baseSha,
                            "test-mutation:" + baseSha,
                            Instant.EPOCH);
            Constructor<TaskProvisioning.ProvisionedWorktree> constructor =
                    TaskProvisioning.ProvisionedWorktree.class
                            .getDeclaredConstructor(
                                    TaskProvisioning.ResolvedSubject.class,
                                    FlowWorktreeInspector.Inspection.class);
            constructor.setAccessible(true);
            var proof = constructor.newInstance(
                    subject,
                    new FlowWorktreeInspector.Inspection(
                            baseSha, "test-tree", "test-diff", false));
            runtime.provisionTask(claim, proof);
        }
        catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static DataSource dataSource(FlowRuntime runtime)
            throws ReflectiveOperationException
    {
        Field field = FlowRuntime.class.getDeclaredField("jdbc");
        field.setAccessible(true);
        return requireNonNull(((JdbcTemplate) field.get(runtime)).getDataSource());
    }

    private static String git(Path directory, String... arguments)
            throws Exception
    {
        String[] command = new String[arguments.length + 3];
        command[0] = "/usr/bin/git";
        command[1] = "-C";
        command[2] = directory.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command).start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .strip();
        if (process.waitFor() != 0) {
            String error = new String(
                    process.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8).strip();
            throw new IllegalStateException(
                    "test Git command failed: "
                            + String.join(" ", command) + ": " + error);
        }
        return output;
    }

    private static String stableId(String domain, String... values)
            throws Exception
    {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        frame(digest, domain);
        for (String value : values) {
            frame(digest, value);
        }
        return domain + ":" + HexFormat.of().formatHex(digest.digest());
    }

    private static void frame(MessageDigest digest, String value)
    {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length)
                .getBytes(StandardCharsets.UTF_8));
        digest.update((byte) ':');
        digest.update(bytes);
    }

    private static void requireObjectId(String value)
    {
        if (value == null
                || !value.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) {
            throw new IllegalArgumentException("not a full object ID");
        }
    }
}
