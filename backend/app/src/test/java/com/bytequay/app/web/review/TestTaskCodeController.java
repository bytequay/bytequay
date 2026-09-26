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
package com.bytequay.app.web.review;

import com.bytequay.agent.core.CodeSymbolResult;
import com.bytequay.app.review.LocalRunRefusedException;
import com.bytequay.app.review.LocalRunService;
import com.bytequay.app.review.ReviewCodeService;
import com.bytequay.app.review.ReviewCodeService.ChangeScope;
import com.bytequay.app.review.ReviewCodeService.Changes;
import com.bytequay.app.review.ReviewCodeService.CodeFile;
import com.bytequay.app.review.ReviewCodeService.CodeFileEntry;
import com.bytequay.app.review.ReviewCodeService.CodeFiles;
import com.bytequay.app.review.ReviewCodeService.CodeGraphState;
import com.bytequay.app.review.ReviewCodeService.CodeOutline;
import com.bytequay.app.review.ReviewCodeService.CodeSource;
import com.bytequay.app.review.ReviewFileConflictException;
import com.bytequay.app.review.TaskCodeHost;
import com.bytequay.app.web.GlobalExceptionHandler;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.review.LocalRun;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewNotFoundException;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestTaskCodeController
{
    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";
    private static final String BASE = "/agent/tasks/task-1/code";
    private static final TaskCodeHost HOST = new TaskCodeHost("workspace-1", "task-1");

    private final ReviewCodeService code = mock(ReviewCodeService.class);
    private final LocalRunService localRuns = mock(LocalRunService.class);
    private final MockMvc mvc = standaloneSetup(new TaskCodeController(code, localRuns))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                    .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void readsAndSavesTheTaskWorkingCopyWithTheReviewRouteShapes()
            throws Exception
    {
        when(code.files(HOST)).thenReturn(new CodeFiles(CodeSource.WORKING_COPY, HEAD, CodeGraphState.READY, Optional.of("fingerprint"), false, false, ImmutableList.of(
                new CodeFileEntry("src/main/java/Code.java", true, false, false, true))));
        when(code.save(HOST, "src/main/java/Code.java", "class Code {}\n", "loaded")).thenReturn(new CodeFile(
                "src/main/java/Code.java", Optional.of("class Code {}\n"), Optional.of("saved"), Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of()));
        when(code.save(HOST, "src/main/java/Code.java", "draft\n", "stale")).thenThrow(new ReviewFileConflictException("disk", Optional.of("class Code { int x; }\n")));
        when(code.outline(HOST, "src/main/java/Code.java")).thenReturn(new CodeOutline(CodeSymbolResult.Status.BUSY, ImmutableList.of()));
        when(code.symbols(HOST, "Code", 50)).thenReturn(CodeSymbolResult.UPDATING);
        when(code.terminalDirectory(HOST)).thenReturn(Path.of("/managed/worktrees/task-1"));

        mvc.perform(withWorkspace(get(BASE + "/files")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"source": "WORKING_COPY", "revision": "%s", "codeGraph": "READY", "sourceFingerprint": "fingerprint", "savedExperiment": false, "truncated": false,
                         "files": [{"path": "src/main/java/Code.java", "changedInPr": true, "addedInPr": false, "onlyInWorkingCopy": false, "uncommitted": true}]}
                        """.formatted(HEAD), JsonCompareMode.STRICT));
        mvc.perform(withWorkspace(put(BASE + "/file")).contentType(MediaType.APPLICATION_JSON).content("""
                        {"path": "src/main/java/Code.java", "content": "class Code {}\\n", "expectedHash": "loaded"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentHash").value("saved"))
                .andExpect(jsonPath("$.editable").value(true));
        mvc.perform(withWorkspace(put(BASE + "/file")).contentType(MediaType.APPLICATION_JSON).content("""
                        {"path": "src/main/java/Code.java", "content": "draft\\n", "expectedHash": "stale"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"currentHash": "disk", "currentContent": "class Code { int x; }\\n"}
                        """, JsonCompareMode.STRICT));
        mvc.perform(withWorkspace(get(BASE + "/outline")).param("path", "src/main/java/Code.java"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\": \"BUSY\", \"symbols\": []}", JsonCompareMode.STRICT));
        mvc.perform(withWorkspace(get(BASE + "/symbols")).param("query", "Code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPDATING"));
        mvc.perform(withWorkspace(post(BASE + "/terminal")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"workingDirectory\": \"/managed/worktrees/task-1\"}", JsonCompareMode.STRICT));
    }

    @Test
    void readsLocalChangesWithoutPullRequestParameters()
            throws Exception
    {
        when(code.changes(HOST, ChangeScope.UNCOMMITTED, Optional.empty(), Optional.empty(), Optional.empty()))
                .thenReturn(new Changes("task-branch", HEAD, HEAD, "Task starting commit", ChangeScope.UNCOMMITTED, Optional.empty(), Optional.of("fingerprint"),
                        ImmutableList.of(), ImmutableList.of(new DiffFile("new.txt", "added", 1, 0, "@@ -0,0 +1 @@\n+new")),
                        ImmutableList.of(), ImmutableList.of(), ImmutableList.of("new.txt"), false));
        mvc.perform(withWorkspace(get(BASE + "/changes")).param("scope", "UNCOMMITTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headSha").value(HEAD))
                .andExpect(jsonPath("$.untracked[0]").value("new.txt"))
                .andExpect(jsonPath("$.files[0].filename").value("new.txt"))
                .andExpect(jsonPath("$.selectedCommit").isEmpty());
        mvc.perform(withWorkspace(get(BASE + "/changes")).param("scope", "UNKNOWN")).andExpect(status().isBadRequest());
    }

    @Test
    void runsTheTaskTestsAndMapsRefusals()
            throws Exception
    {
        LocalRun run = LocalRun.started("run-1", "task-1", "workspace-1", 1, "src/test/java/TestCode.java", "TestCode", Optional.empty(), Optional.empty(),
                "./mvnw -B -Dtest=TestCode -Dsurefire.failIfNoSpecifiedTests=false test", HEAD, ImmutableList.of(), "fingerprint", "launch-1",
                Instant.parse("2026-09-15T10:00:00.123Z"));
        when(localRuns.list(HOST)).thenReturn(ImmutableList.of(run));
        when(localRuns.start(HOST, "src/test/java/TestCode.java", Optional.empty())).thenReturn(run);
        when(localRuns.start(HOST, "src/test/java/TestNoWrapper.java", Optional.empty())).thenThrow(new LocalRunRefusedException("No Maven wrapper (mvnw) in this working copy", true));
        when(localRuns.stop(HOST, "run-1")).thenReturn(run);
        when(localRuns.output(HOST, "run-1", 5)).thenReturn(new LocalRunService.OutputChunk("tail", 9, 0, false));

        mvc.perform(withWorkspace(get(BASE + "/runs")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs[0].id").value("run-1"))
                .andExpect(jsonPath("$.runs[0].ordinal").value(1));
        mvc.perform(withWorkspace(post(BASE + "/runs")).contentType(MediaType.APPLICATION_JSON).content("{\"path\": \"src/test/java/TestCode.java\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("run-1"));
        mvc.perform(withWorkspace(post(BASE + "/runs")).contentType(MediaType.APPLICATION_JSON).content("{\"path\": \"src/test/java/TestNoWrapper.java\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().json("""
                        {"reason": "No Maven wrapper (mvnw) in this working copy", "action": "OPEN_TERMINAL"}
                        """, JsonCompareMode.STRICT));
        mvc.perform(withWorkspace(post(BASE + "/runs/run-1/stop"))).andExpect(status().isOk());
        mvc.perform(withWorkspace(get(BASE + "/runs/run-1/output")).param("after", "5"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"text\": \"tail\", \"nextOffset\": 9, \"skippedBytes\": 0, \"complete\": false}", JsonCompareMode.STRICT));
    }

    @Test
    void refusesAFileReadFromAnEarlierListingRevision()
            throws Exception
    {
        when(code.file(HOST, "TestCode.java", Optional.of(HEAD)))
                .thenThrow(new ReviewConflictException("The working copy revision changed; refresh the file list before reading this file"));

        mvc.perform(withWorkspace(get(BASE + "/file")).param("path", "TestCode.java").param("expectedRevision", HEAD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The working copy revision changed; refresh the file list before reading this file"));
    }

    @Test
    void mapsAWorkspaceTheTaskDoesNotOwnAndARemovedWorktree()
            throws Exception
    {
        TaskCodeHost other = new TaskCodeHost("other", "task-1");
        when(code.files(other)).thenThrow(new ReviewNotFoundException("Task does not belong to this workspace"));
        when(localRuns.list(other)).thenThrow(new ReviewNotFoundException("Task does not belong to this workspace"));
        when(code.file(any(TaskCodeHost.class), any(), any())).thenThrow(new ReviewConflictException("The task's worktree was removed"));

        mvc.perform(get(BASE + "/files").param("workspaceId", "other")).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/runs").param("workspaceId", "other")).andExpect(status().isNotFound());
        mvc.perform(withWorkspace(get(BASE + "/file")).param("path", "README.md"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The task's worktree was removed"));
        mvc.perform(get(BASE + "/files")).andExpect(status().isBadRequest());
    }

    private static MockHttpServletRequestBuilder withWorkspace(MockHttpServletRequestBuilder request)
    {
        return request.param("workspaceId", HOST.workspaceId());
    }
}
