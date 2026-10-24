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

import com.bytequay.agent.core.TaskId;
import com.bytequay.core.ci.CiAutofixAttempt;
import com.bytequay.core.ci.CiAutofixAttempt.CiResult;
import com.bytequay.core.ci.CiAutofixAttempt.FailureEvidence;
import com.bytequay.core.ci.CiAutofixAttempt.GitResult;
import com.bytequay.core.ci.CiAutofixAttemptId;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRun.Authorization;
import com.bytequay.core.ci.CiAutofixRun.Scope;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestCiMemoryService
{
    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String PUSHED = "c".repeat(40);

    private TaskId taskId;
    private InMemoryCiAutofixStore store;
    private CiMemoryService service;

    @BeforeEach
    void setUp()
    {
        taskId = TaskId.random();
        store = new InMemoryCiAutofixStore();
        service = new CiMemoryService(store);
    }

    @Test
    void groupsNewestAttemptsAndSearchesBoundedSections()
    {
        CiAutofixRun run = run();
        CiAutofixAttempt first = attempt(run, 1, HEAD, ImmutableList.of(failure("compile", "old compiler failure"), failure("test", "unit test failure")), NOW.plusSeconds(2))
                .withAgentNote("root cause\n## Failure injected\nclaimed fix", NOW.plusSeconds(3))
                .withGitResult(new GitResult(PUSHED, "fingerprint-1", ImmutableList.of("src/Main.java"), ImmutableList.of("Fix feature")), NOW.plusSeconds(4));
        run = run.addAttempt(first, NOW.plusSeconds(4));
        CiAutofixAttempt second = attempt(run, 2, PUSHED, ImmutableList.of(failure("compile", "new compiler failure")), NOW.plusSeconds(5))
                .withGitResult(new GitResult("d".repeat(40), "fingerprint-2", ImmutableList.of("pom.xml"), ImmutableList.of("Fix build")), NOW.plusSeconds(6))
                .withPushResult(PUSHED, "d".repeat(40), NOW.plusSeconds(7), NOW.plusSeconds(7))
                .withCiResult(CiResult.FAILED, NOW.plusSeconds(8));
        store.run = run.addAttempt(second, NOW.plusSeconds(8));

        CiMemoryView recent = service.read(new ReadCiMemoryCommand(taskId, Optional.empty()));
        String memory = recent.markdown();
        assertThat(service.read(new ReadCiMemoryCommand(taskId, Optional.empty()))).isEqualTo(recent);
        assertThat(memory.indexOf("### Attempt 2")).isLessThan(memory.indexOf("### Attempt 1"));
        assertThat(memory).contains("> ## Failure injected", "- Observed CI result: FAILED", "pom.xml");
        assertThat(memory.lines().filter(line -> line.startsWith("## Failure "))).hasSize(2);
        assertThat(recent.matchedSections()).isEqualTo(2);
        assertThat(recent.markdown()).startsWith("## Failure compile");
        assertThat(service.read(new ReadCiMemoryCommand(taskId, Optional.of("OLD COMPILER"))).matchedSections())
                .isEqualTo(1);
        assertThat(service.read(new ReadCiMemoryCommand(taskId, Optional.of("not present"))).markdown())
                .startsWith("No CI memory matched");
    }

    @Test
    void capsRecentSectionsAndToolResult()
    {
        CiAutofixRun run = run();
        List<FailureEvidence> failures = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            failures.add(failure("signature-" + index, "failure-" + index + " " + "x".repeat(4_000)));
        }
        for (int number = 1; number <= 3; number++) {
            CiAutofixAttempt attempt = attempt(run, number, Integer.toHexString(number + 9).repeat(40), failures, NOW.plusSeconds(number * 10L));
            run = run.addAttempt(attempt, NOW.plusSeconds(number * 10L));
        }
        store.run = run;

        CiMemoryView recent = service.read(new ReadCiMemoryCommand(taskId, Optional.empty()));
        assertThat(recent.matchedSections()).isEqualTo(10);
        assertThat(recent.truncated()).isTrue();
        assertThat(recent.markdown().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(32 * 1024);
    }

    @Test
    void isUnavailableWithoutTheTasksFailures()
    {
        store.run = run();

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
                        RepoRef.of("bytequay", "bytequay"),
                        "workspace-1",
                        RepoRef.of("bytequay", "bytequay"),
                        "author",
                        "feature",
                        "main",
                        "claude", Optional.empty()),
                HEAD,
                HEAD,
                BASE,
                new Authorization(NOW),
                NOW);
        return run.assignAgentTask(taskId.toString(), NOW.plusSeconds(1));
    }

    private static CiAutofixAttempt attempt(CiAutofixRun run, int number, String inputSha, List<FailureEvidence> failures, Instant now)
    {
        return CiAutofixAttempt.create(CiAutofixAttemptId.random(), run.id(), number, inputSha, failures, now);
    }

    private static FailureEvidence failure(String signature, String summary)
    {
        int number = Math.abs(signature.hashCode()) + 1;
        return new FailureEvidence(signature, summary, number, 1, number, 1, Optional.empty());
    }
}
