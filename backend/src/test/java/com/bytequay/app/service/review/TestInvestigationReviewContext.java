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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.PR;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.pr.PullRequestService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TestInvestigationReviewContext
{
    @TempDir
    private Path root;

    @Test
    void validationCountsLinesBeyondThePromptTruncationLimit()
            throws Exception
    {
        String path = "src/Large.java";
        String content = IntStream.rangeClosed(1, 5_000)
                .mapToObj(line -> "line " + line + " " + "x".repeat(30))
                .collect(Collectors.joining("\n"));
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve(path), content);
        InvestigationReviewContext context = new InvestigationReviewContext(
                mock(PRService.class), mock(PullRequestService.class),
                mock(TaskStore.class), mock(GitRunner.class));
        PR pr = PR.createExternal(
                "pr-1", "acme/widget", 1, "https://example.test/1", "octocat",
                "feature", "main", "Large file", "", PR.STATUS_REMOTE_OPEN,
                Instant.EPOCH, null, null);
        InvestigationReviewContext.Snapshot snapshot = new InvestigationReviewContext.Snapshot(
                pr, "base", "head", "", List.of(), root);

        assertThat(context.readFile(snapshot, path)).contains("file truncated");
        assertThat(context.fileLineCount(snapshot, path)).isEqualTo(5_000);
    }
}
