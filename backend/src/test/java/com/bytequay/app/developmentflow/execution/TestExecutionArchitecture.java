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
package com.bytequay.app.developmentflow.execution;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class TestExecutionArchitecture
{
    private static final Path SOURCE = Path.of(
            "src/main/java/com/bytequay/app/developmentflow/execution");

    @Test
    void dispatcherOwnsExactlyTheTwoLockedExecutionFacilities()
            throws IOException
    {
        assertThat(Arrays.stream(ExecutionDispatcher.class.getDeclaredFields())
                .filter(field -> ExecutorService.class.isAssignableFrom(field.getType()))
                .map(Field::getName))
                .containsExactlyInAnyOrder("operationExecutor", "maintenanceExecutor");

        String dispatcher = Files.readString(SOURCE.resolve("ExecutionDispatcher.java"));
        assertThat(occurrences(dispatcher, "Executors.newVirtualThreadPerTaskExecutor()"))
                .isOne();
        assertThat(occurrences(dispatcher, "Executors.newSingleThreadScheduledExecutor("))
                .isOne();
        assertThat(dispatcher)
                .doesNotContain(
                        "newFixedThreadPool",
                        "newCachedThreadPool",
                        "newScheduledThreadPool",
                        "startVirtualThread",
                        "CompletableFuture");
    }

    @Test
    void deliveryPackageHasNoDomainRepositoryKnowledge()
            throws IOException
    {
        List<Path> sources;
        try (var paths = Files.list(SOURCE)) {
            sources = paths.filter(path -> path.toString().endsWith(".java")).toList();
        }

        for (Path source : sources) {
            String content = Files.readString(source);
            assertThat(content)
                    .as(source.getFileName().toString())
                    .doesNotContain(
                            "com.bytequay.app.repository",
                            "developmentflow.task",
                            "developmentflow.stage",
                            "developmentflow.trunk");
        }

        String dispatcher = Files.readString(SOURCE.resolve("ExecutionDispatcher.java"));
        assertThat(dispatcher).containsOnlyOnce("new ExecutionContext(");
        assertThat(Files.readString(SOURCE.resolve("CapacityManager.java")))
                .containsOnlyOnce("transaction.create(draft)");
        for (Path source : sources) {
            if (!source.getFileName().toString().equals("CapacityManager.java")) {
                assertThat(Files.readString(source))
                        .as(source.getFileName().toString())
                        .doesNotContain("transaction.create(draft)");
            }
        }
    }

    @Test
    void sevenFamiliesRemainLogicalKindsRatherThanPools()
    {
        assertThat(DispatchTicket.AsyncFamily.values()).containsExactly(
                DispatchTicket.AsyncFamily.AGENT_TURN,
                DispatchTicket.AsyncFamily.VALIDATION,
                DispatchTicket.AsyncFamily.LOCAL_GIT,
                DispatchTicket.AsyncFamily.GITHUB_EFFECT,
                DispatchTicket.AsyncFamily.REMOTE_OBSERVATION,
                DispatchTicket.AsyncFamily.MERGE,
                DispatchTicket.AsyncFamily.CLEANUP);
    }

    private static int occurrences(String value, String needle)
    {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
