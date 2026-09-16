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
import com.bytequay.agent.core.impl.FileAgentResourceService;
import com.bytequay.core.domain.PullRequestRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCiRawLogStore
{
    @TempDir
    private Path directory;

    @Test
    void cachesOnceAndPagesEveryByteWithoutPruningOrCrossingTasks()
    {
        FileAgentResourceService resources = new FileAgentResourceService(directory.resolve("runtime"));
        CiRawLogStore store = new CiRawLogStore(resources);
        ReadCiLogQuery query = query(TaskId.random(), 1);
        resources.allocate(query.taskId());
        String source = "original beginning\r\n" + "日志🙂\r\n".repeat(16000) + "long line ".repeat(4000) + "end";
        String id = CiRawLogStore.id(query);

        assertThat(store.cache(query, path -> write(path, source))).isEmpty();
        assertThat(store.cache(query, path -> {
            throw new AssertionError("Cached log must not download again");
        })).isEmpty();
        assertThat(store.read(query.taskId(), id)).isEqualTo(source);
        StringBuilder joined = new StringBuilder();
        long offset = 0;
        do {
            CiRawLogPage page = store.page(query.taskId(), id, offset, 32768);
            assertThat(page.text()).doesNotContain("\ufffd");
            assertThat(page.text().getBytes(UTF_8).length).isLessThanOrEqualTo(32768);
            assertThat(page.nextOffset()).isGreaterThan(offset);
            assertThat(page.totalBytes()).isEqualTo(source.getBytes(UTF_8).length);
            joined.append(page.text());
            offset = page.nextOffset();
        }
        while (offset < source.getBytes(UTF_8).length);
        assertThat(joined.toString()).isEqualTo(source);
        assertThat(store.page(query.taskId(), id, offset, 32768).text()).isEmpty();
        assertThatThrownBy(() -> store.page(query.taskId(), id, 21, 32768)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.read(query.taskId(), "../events.jsonl")).isInstanceOf(IllegalArgumentException.class);
        TaskId other = TaskId.random();
        resources.allocate(other);
        assertThatThrownBy(() -> store.read(other, id)).hasMessageContaining("No cached raw log");
        assertThat(CiRawLogStore.id(query(query.taskId(), 2))).isNotEqualTo(id);
    }

    @Test
    void incompleteDownloadsAreDiscardedAndWorktreeCleanupPreservesTranscript()
            throws IOException
    {
        FileAgentResourceService resources = new FileAgentResourceService(directory.resolve("runtime"));
        CiRawLogStore store = new CiRawLogStore(resources);
        ReadCiLogQuery query = query(TaskId.random(), 1);
        var taskResources = resources.allocate(query.taskId());
        String id = CiRawLogStore.id(query);
        assertThat(store.cache(query, path -> {
            write(path, "incomplete body");
            return Optional.of("connection closed");
        })).contains("connection closed");
        assertThat(taskResources.worktreeCacheDirectory()).isEmptyDirectory();
        assertThatThrownBy(() -> store.cache(query, path -> {
            write(path, "interrupted body");
            throw new IllegalStateException("interrupted");
        })).hasMessage("interrupted");
        assertThat(taskResources.worktreeCacheDirectory()).isEmptyDirectory();
        store.cache(query, path -> write(path, "complete body"));
        Path blob = Files.writeString(taskResources.blobsDirectory().resolve("excerpt"), "keep excerpt");
        resources.deleteWorktreeCache(query.taskId());
        resources.deleteWorktreeCache(query.taskId());
        assertThat(taskResources.worktreeCacheDirectory()).doesNotExist();
        assertThat(taskResources.eventsFile()).isRegularFile();
        assertThat(blob).hasContent("keep excerpt");
        assertThatThrownBy(() -> store.read(query.taskId(), id)).hasMessageContaining("No cached raw log");
    }

    @Test
    void refusesSymbolicCacheDirectoriesWithoutReadingOrDeletingTheirTargets()
            throws IOException
    {
        FileAgentResourceService resources = new FileAgentResourceService(directory.resolve("runtime"));
        CiRawLogStore store = new CiRawLogStore(resources);
        ReadCiLogQuery query = query(TaskId.random(), 1);
        var taskResources = resources.allocate(query.taskId());
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path log = Files.writeString(outside.resolve(CiRawLogStore.id(query) + ".log"), "outside log");
        Files.createSymbolicLink(taskResources.worktreeCacheDirectory(), outside);
        assertThatThrownBy(() -> store.read(query.taskId(), CiRawLogStore.id(query))).hasMessageContaining("symbolic link");
        assertThatThrownBy(() -> resources.deleteWorktreeCache(query.taskId())).hasMessageContaining("symbolic link");
        assertThat(log).hasContent("outside log");
    }

    private static Optional<String> write(Path path, String text)
    {
        try {
            Files.writeString(path, text, UTF_8);
            return Optional.empty();
        }
        catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static ReadCiLogQuery query(TaskId taskId, int attempt)
    {
        CiJob job = new CiJob(902, 900, "Tests", attempt, "completed", Optional.of("failure"), "https://github.com/job/902", ImmutableList.of());
        CiWorkflowRun run = new CiWorkflowRun(900, 80, "Build", attempt, "completed", Optional.of("failure"), "a".repeat(40),
                "https://github.com/run/900", ImmutableList.of(job));
        return new ReadCiLogQuery(taskId, PullRequestRef.of("owner", "repo", 7), run, job, 1024 * 1024);
    }
}
