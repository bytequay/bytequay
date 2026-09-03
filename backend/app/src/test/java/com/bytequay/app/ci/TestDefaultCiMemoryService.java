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
package com.bytequay.app.ci;

import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.StoreAgentAttachmentCommand;
import com.bytequay.agent.core.StoredAgentAttachment;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.core.ci.CiAutofixAttempt;
import com.bytequay.core.ci.CiAutofixAttempt.CiResult;
import com.bytequay.core.ci.CiAutofixAttempt.FailureEvidence;
import com.bytequay.core.ci.CiAutofixAttempt.GitResult;
import com.bytequay.core.ci.CiAutofixAttemptId;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRun.Authorization;
import com.bytequay.core.ci.CiAutofixRun.Scope;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.PullRequestRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestDefaultCiMemoryService
{
    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String PUSHED = "c".repeat(40);

    @TempDir
    private Path temporaryDirectory;

    private TaskId taskId;
    private MemoryStore store;
    private MemoryResources resources;
    private DefaultCiMemoryService service;

    @BeforeEach
    void setUp()
            throws Exception
    {
        taskId = TaskId.random();
        store = new MemoryStore();
        resources = new MemoryResources(temporaryDirectory.resolve("runtime"));
        Files.createDirectories(resources.task(taskId).runtimeDirectory());
        service = new DefaultCiMemoryService(
                store,
                new CiMemoryFile(resources),
                Clock.fixed(NOW.plus(Duration.ofHours(1)), ZoneOffset.UTC));
    }

    @Test
    void rebuildsIdempotentlyGroupsNewestAttemptsAndSearchesBoundedSections()
            throws Exception
    {
        CiAutofixRun run = run();
        CiAutofixAttempt first = attempt(
                run,
                1,
                HEAD,
                ImmutableList.of(
                        failure("compile", "old compiler failure"),
                        failure("test", "unit test failure")),
                NOW.plusSeconds(2))
                .withAgentNote("root cause\n## Failure injected\nclaimed fix", NOW.plusSeconds(3))
                .withGitResult(new GitResult(
                        PUSHED,
                        "fingerprint-1",
                        ImmutableList.of("src/Main.java"),
                        ImmutableList.of("Fix feature")), NOW.plusSeconds(4));
        run = run.addAttempt(first, NOW.plusSeconds(4));
        CiAutofixAttempt second = attempt(
                run,
                2,
                PUSHED,
                ImmutableList.of(failure("compile", "new compiler failure")),
                NOW.plusSeconds(5))
                .withGitResult(new GitResult(
                        "d".repeat(40),
                        "fingerprint-2",
                        ImmutableList.of("pom.xml"),
                        ImmutableList.of("Fix build")), NOW.plusSeconds(6))
                .withPushResult(PUSHED, "d".repeat(40), NOW.plusSeconds(7), NOW.plusSeconds(7))
                .withCiResult(CiResult.FAILED, NOW.plusSeconds(8));
        store.run = run.addAttempt(second, NOW.plusSeconds(8));

        service.update(store.run.id());
        Path memory = resources.task(taskId).runtimeDirectory().resolve("ci-memory.md");
        String firstBuild = Files.readString(memory);
        service.update(store.run.id());

        assertThat(Files.readString(memory)).isEqualTo(firstBuild);
        assertThat(firstBuild.indexOf("### Attempt 2")).isLessThan(firstBuild.indexOf("### Attempt 1"));
        assertThat(firstBuild).contains("> ## Failure injected", "- Observed CI result: FAILED", "pom.xml");
        assertThat(firstBuild.lines().filter(line -> line.startsWith("## Failure "))).hasSize(2);

        CiMemoryView recent = service.read(new ReadCiMemoryCommand(taskId, Optional.empty()));
        assertThat(recent.matchedSections()).isEqualTo(2);
        assertThat(recent.markdown()).startsWith("## Failure compile");
        assertThat(service.read(new ReadCiMemoryCommand(taskId, Optional.of("OLD COMPILER"))).matchedSections())
                .isEqualTo(1);
        assertThat(service.read(new ReadCiMemoryCommand(taskId, Optional.of("not present"))).markdown())
                .startsWith("No CI memory matched");
    }

    @Test
    void capsFileRecentSectionsAndToolResult()
            throws Exception
    {
        CiAutofixRun run = run();
        List<FailureEvidence> failures = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            failures.add(failure("signature-" + index, "failure-" + index + " " + "x".repeat(4_000)));
        }
        for (int number = 1; number <= 3; number++) {
            CiAutofixAttempt attempt = attempt(
                    run,
                    number,
                    Integer.toHexString(number + 9).repeat(40),
                    failures,
                    NOW.plusSeconds(number * 10L));
            run = run.addAttempt(attempt, NOW.plusSeconds(number * 10L));
        }
        store.run = run;

        service.update(run.id());
        Path memory = resources.task(taskId).runtimeDirectory().resolve("ci-memory.md");
        assertThat(Files.size(memory)).isLessThanOrEqualTo(256 * 1024);

        CiMemoryView recent = service.read(new ReadCiMemoryCommand(taskId, Optional.empty()));
        assertThat(recent.matchedSections()).isEqualTo(10);
        assertThat(recent.truncated()).isTrue();
        assertThat(recent.markdown().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(32 * 1024);
    }

    @Test
    void preservesValidMemoryAndOnlyWarnsWhenRefreshFails()
            throws Exception
    {
        CiAutofixRun run = run();
        store.run = run.addAttempt(
                attempt(run, 1, HEAD, ImmutableList.of(failure("compile", "compiler failure")), NOW.plusSeconds(2)),
                NOW.plusSeconds(2));
        service.update(store.run.id());
        Path memory = resources.task(taskId).runtimeDirectory().resolve("ci-memory.md");
        String valid = Files.readString(memory);

        DefaultCiMemoryService failingService = new DefaultCiMemoryService(
                store,
                new CiMemoryFile(resources, (target, markdown) -> {
                    throw new IOException("simulated atomic replacement failure");
                }),
                Clock.fixed(NOW.plus(Duration.ofHours(1)), ZoneOffset.UTC));
        failingService.update(store.run.id());

        assertThat(Files.readString(memory)).isEqualTo(valid);
        assertThat(store.run.status()).isEqualTo(CiAutofixRun.Status.ACTIVE);
        assertThat(store.run.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.code()).isEqualTo("CI_MEMORY_UPDATE_FAILED");
            assertThat(warning.message()).hasSizeLessThanOrEqualTo(2 * 1024);
        });

        Files.delete(memory);
        assertThat(service.read(new ReadCiMemoryCommand(taskId, Optional.empty())).markdown())
                .startsWith("CI memory is unavailable");
        assertThat(service.read(new ReadCiMemoryCommand(TaskId.random(), Optional.empty())).markdown())
                .startsWith("CI memory is unavailable");
    }

    private CiAutofixRun run()
    {
        CiAutofixRun run = CiAutofixRun.create(
                CiAutofixRunId.random(),
                "memory-run",
                new Scope(
                        new PullRequestRef("bytequay", "bytequay", 123),
                        "workspace-1",
                        Optional.empty(),
                        "author",
                        "feature",
                        "main",
                        "claude"),
                HEAD,
                HEAD,
                BASE,
                new Authorization(true, true, true, 3, NOW, NOW.plus(Duration.ofHours(8))),
                NOW);
        return run.assignAgentTask(taskId.toString(), NOW.plusSeconds(1));
    }

    private static CiAutofixAttempt attempt(
            CiAutofixRun run,
            int number,
            String inputSha,
            List<FailureEvidence> failures,
            Instant now)
    {
        return CiAutofixAttempt.create(
                CiAutofixAttemptId.random(),
                run.id(),
                number,
                inputSha,
                failures,
                now);
    }

    private static FailureEvidence failure(String signature, String summary)
    {
        int number = Math.abs(signature.hashCode()) + 1;
        return new FailureEvidence(signature, summary, number, 1, number, 1, Optional.empty());
    }

    private static final class MemoryStore
            implements CiAutofixStore
    {
        private CiAutofixRun run;

        @Override
        public void insert(CiAutofixRun value)
        {
            run = value;
        }

        @Override
        public Optional<CiAutofixRun> find(CiAutofixRunId runId)
        {
            return Optional.ofNullable(run).filter(value -> value.id().equals(runId));
        }

        @Override
        public Optional<CiAutofixRun> findByIdempotencyKey(String key)
        {
            return Optional.ofNullable(run).filter(value -> value.startIdempotencyKey().equals(key));
        }

        @Override
        public Optional<CiAutofixRun> findByAgentTaskId(String requestedTaskId)
        {
            return Optional.ofNullable(run).filter(value -> value.agentTaskId().filter(requestedTaskId::equals).isPresent());
        }

        @Override
        public List<CiAutofixRun> listRecent(int limit)
        {
            return run == null ? ImmutableList.of() : ImmutableList.of(run);
        }

        @Override
        public List<CiAutofixRun> listNonFinished(int limit)
        {
            return listRecent(limit);
        }

        @Override
        public CiAutofixRun update(CiAutofixRun value)
        {
            run = value.withVersion(value.version() + 1);
            return run;
        }
    }

    private static final class MemoryResources
            implements AgentResourceService
    {
        @Override
        public StoredAgentAttachment storeAttachment(StoreAgentAttachmentCommand command)
        {
            throw new UnsupportedOperationException("attachments are not part of this test");
        }

        @Override
        public StoredAgentAttachment readAttachment(TaskId taskId, String attachmentId)
        {
            throw new UnsupportedOperationException("attachments are not part of this test");
        }

        @Override
        public void deleteAttachment(TaskId taskId, String attachmentId)
        {
            throw new UnsupportedOperationException("attachments are not part of this test");
        }
        private final Path root;

        private MemoryResources(Path root)
        {
            this.root = root.toAbsolutePath();
        }

        @Override
        public TaskResources allocate(TaskId taskId)
        {
            return task(taskId);
        }

        @Override
        public TaskResources require(TaskId taskId)
        {
            return task(taskId);
        }

        @Override
        public void delete(TaskId taskId) {}

        private TaskResources task(TaskId taskId)
        {
            Path runtime = root.resolve(taskId.toString());
            return new TaskResources(
                    runtime,
                    runtime.resolve("events.jsonl"),
                    runtime.resolve("blobs"),
                    runtime.resolve("provider"));
        }
    }
}
