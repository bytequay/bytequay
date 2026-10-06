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

import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.app.review.LocalRunRefusedException;
import com.bytequay.app.review.LocalRunService;
import com.bytequay.app.review.ReviewCodeService;
import com.bytequay.app.review.ReviewCodeService.CodeFile;
import com.bytequay.app.review.ReviewCodeService.CodeFileEntry;
import com.bytequay.app.review.ReviewCodeService.CodeFiles;
import com.bytequay.app.review.ReviewCodeService.CodeSource;
import com.bytequay.app.review.ReviewCodeService.LineChange;
import com.bytequay.app.review.ReviewFileConflictException;
import com.bytequay.app.web.GlobalExceptionHandler;
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

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestReviewCodeController
{
    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";
    private static final String FINISHED_RUN_JSON = """
            {"id": "run-2", "ordinal": 2, "initiator": "USER", "scope": "METHOD", "path": "core/src/test/java/demo/TestCode.java",
             "testClass": "demo.TestCode", "testMethod": "fails", "module": "core",
             "command": "./mvnw -B -pl core -Dtest=demo.TestCode#fails -Dsurefire.failIfNoSpecifiedTests=false test",
             "sourceHeadSha": "%s", "localPaths": ["core/src/test/java/demo/TestNew.java"], "sourceFingerprint": "fingerprint",
             "status": "FAILED_ASSERTION", "startedAt": "2026-09-15T10:00:00.123Z", "endedAt": "2026-09-15T10:00:42.500Z", "exitCode": 1,
             "tests": 2, "failures": 1, "errors": 0, "skipped": 0, "outputTruncated": true, "omittedBytes": 1930000, "treeUnverified": false,
             "results": [{"className": "demo.TestCode", "durationMs": 14, "methods": [
               {"name": "passes", "status": "PASSED", "durationMs": 2, "message": null, "trace": null},
               {"name": "fails", "status": "FAILED", "durationMs": 12, "message": "expected: <1> but was: <2>", "trace": "org.opentest4j.AssertionFailedError: expected: <1> but was: <2>"}]}]}
            """.formatted(HEAD);
    private static final String RUNNING_RUN_JSON = """
            {"id": "run-1", "ordinal": 1, "initiator": "USER", "scope": "CLASS", "path": "core/src/test/java/demo/TestCode.java",
             "testClass": "demo.TestCode", "testMethod": null, "module": null,
             "command": "./mvnw -B -Dtest=demo.TestCode -Dsurefire.failIfNoSpecifiedTests=false test",
             "sourceHeadSha": "%s", "localPaths": [], "sourceFingerprint": "fingerprint",
             "status": "RUNNING", "startedAt": "2026-09-15T10:00:00.123Z", "endedAt": null, "exitCode": null,
             "tests": null, "failures": null, "errors": null, "skipped": null, "outputTruncated": false, "omittedBytes": null, "treeUnverified": false,
             "results": []}
            """.formatted(HEAD);

    private final ReviewCodeService code = mock(ReviewCodeService.class);
    private final LocalRunService localRuns = mock(LocalRunService.class);
    // Boot's date setting, so the runs assert the ISO instants the renderer parses.
    private final MockMvc mvc = standaloneSetup(new ReviewCodeController(code, localRuns))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json().featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void listsFilesWithTheWireFieldNames()
            throws Exception
    {
        when(code.files("review-1")).thenReturn(new CodeFiles(CodeSource.WORKING_COPY, HEAD, Optional.of("fingerprint"), true, true, ImmutableList.of(
                new CodeFileEntry("src/test/java/TestCode.java", true, true, false, true))));

        mvc.perform(get("/api/agent-review/review-1/code/files"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"source": "WORKING_COPY", "revision": "%s", "sourceFingerprint": "fingerprint", "savedExperiment": true, "truncated": true,
                         "files": [{"path": "src/test/java/TestCode.java", "changedInPr": true, "addedInPr": true, "onlyInWorkingCopy": false, "uncommitted": true}]}
                        """.formatted(HEAD), JsonCompareMode.STRICT));
    }

    @Test
    void readsFilesWithNullsForAbsentValues()
            throws Exception
    {
        when(code.file("review-1", "src/test/java/TestCode.java", Optional.empty())).thenReturn(new CodeFile(
                "src/test/java/TestCode.java", Optional.of("class TestCode {}\n"), Optional.of("hash"), Optional.empty(), Optional.empty(), Optional.of("@@ -1 +1 @@"),
                ImmutableList.of(new LineChange(LineChange.Kind.MODIFIED, 1, 1), new LineChange(LineChange.Kind.DELETED, 0, 2))));
        when(code.file("review-1", "image.png", Optional.empty())).thenReturn(new CodeFile(
                "image.png", Optional.empty(), Optional.empty(), Optional.of("This review has ended — read-only"), Optional.of("Binary file"), Optional.empty(), ImmutableList.of()));

        mvc.perform(get("/api/agent-review/review-1/code/file").param("path", "src/test/java/TestCode.java"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"path": "src/test/java/TestCode.java", "content": "class TestCode {}\\n", "contentHash": "hash", "editable": true,
                         "readOnlyReason": null, "unavailableReason": null, "prPatch": "@@ -1 +1 @@",
                         "lineChanges": [{"kind": "MODIFIED", "line": 1, "count": 1}, {"kind": "DELETED", "line": 0, "count": 2}]}
                        """, JsonCompareMode.STRICT));
        mvc.perform(get("/api/agent-review/review-1/code/file").param("path", "image.png"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"path": "image.png", "content": null, "contentHash": null, "editable": false,
                         "readOnlyReason": "This review has ended — read-only", "unavailableReason": "Binary file", "prPatch": null, "lineChanges": []}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void refusesAFileReadFromAnEarlierListingRevision()
            throws Exception
    {
        when(code.file("review-1", "TestCode.java", Optional.of(HEAD)))
                .thenThrow(new ReviewConflictException("The working copy revision changed; refresh the file list before reading this file"));

        mvc.perform(get("/api/agent-review/review-1/code/file").param("path", "TestCode.java").param("expectedRevision", HEAD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The working copy revision changed; refresh the file list before reading this file"));
    }

    @Test
    void savesAFileOrAnswersAStaleSaveWithTheDiskVersion()
            throws Exception
    {
        when(code.save("review-1", "src/test/java/TestCode.java", "class TestCode { int x; }\n", "loaded")).thenReturn(new CodeFile(
                "src/test/java/TestCode.java", Optional.of("class TestCode { int x; }\n"), Optional.of("saved"), Optional.empty(), Optional.empty(), Optional.of("@@ -1 +1 @@"), ImmutableList.of()));
        when(code.save("review-1", "src/test/java/TestCode.java", "draft\n", "stale")).thenThrow(new ReviewFileConflictException("disk", Optional.of("class TestCode {}\n")));
        when(code.save("review-1", "src/test/image.png", "draft\n", "stale")).thenThrow(new ReviewFileConflictException("binary", Optional.empty()));
        when(code.save("review-1", ".git/config", "draft\n", "loaded")).thenThrow(new IllegalArgumentException("path must not name Git metadata"));
        when(code.save("review-1", "src/test/java/TestBusy.java", "draft\n", "loaded")).thenThrow(new ReviewConflictException("The review agent is working"));

        mvc.perform(put("/api/agent-review/review-1/code/file").contentType(MediaType.APPLICATION_JSON).content("""
                        {"path": "src/test/java/TestCode.java", "content": "class TestCode { int x; }\\n", "expectedHash": "loaded"}
                        """))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"path": "src/test/java/TestCode.java", "content": "class TestCode { int x; }\\n", "contentHash": "saved", "editable": true,
                         "readOnlyReason": null, "unavailableReason": null, "prPatch": "@@ -1 +1 @@", "lineChanges": []}
                        """, JsonCompareMode.STRICT));
        mvc.perform(put("/api/agent-review/review-1/code/file").contentType(MediaType.APPLICATION_JSON).content(saveRequest("src/test/java/TestCode.java", "stale")))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"currentHash": "disk", "currentContent": "class TestCode {}\\n"}
                        """, JsonCompareMode.STRICT));
        mvc.perform(put("/api/agent-review/review-1/code/file").contentType(MediaType.APPLICATION_JSON).content(saveRequest("src/test/image.png", "stale")))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"currentHash": "binary", "currentContent": null}
                        """, JsonCompareMode.STRICT));
        mvc.perform(put("/api/agent-review/review-1/code/file").contentType(MediaType.APPLICATION_JSON).content(saveRequest(".git/config", "loaded")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("path must not name Git metadata"));
        mvc.perform(put("/api/agent-review/review-1/code/file").contentType(MediaType.APPLICATION_JSON).content(saveRequest("src/test/java/TestBusy.java", "loaded")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The review agent is working"))
                .andExpect(jsonPath("$.currentHash").doesNotExist());
    }

    @Test
    void discardsLocalChangesAndListsTheCleanWorkingCopy()
            throws Exception
    {
        when(code.discard("review-1", HEAD)).thenReturn(new CodeFiles(CodeSource.WORKING_COPY, HEAD, Optional.of("clean"), false, false, ImmutableList.of(
                new CodeFileEntry("src/test/java/TestCode.java", true, false, false, false))));
        when(code.discard("review-1", "moved")).thenThrow(new ReviewConflictException("Review HEAD changed; experiments were preserved"));

        mvc.perform(post("/api/agent-review/review-1/code/discard").contentType(MediaType.APPLICATION_JSON).content("{\"expectedHead\": \"%s\"}".formatted(HEAD)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"source": "WORKING_COPY", "revision": "%s", "sourceFingerprint": "clean", "savedExperiment": false, "truncated": false,
                         "files": [{"path": "src/test/java/TestCode.java", "changedInPr": true, "addedInPr": false, "onlyInWorkingCopy": false, "uncommitted": false}]}
                        """.formatted(HEAD), JsonCompareMode.STRICT));
        mvc.perform(post("/api/agent-review/review-1/code/discard").contentType(MediaType.APPLICATION_JSON).content("{\"expectedHead\": \"moved\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Review HEAD changed; experiments were preserved"));
    }

    @Test
    void terminalAnswersTheWorkingDirectoryOfAnOpenReview()
            throws Exception
    {
        when(code.terminalDirectory("review-1")).thenReturn(Path.of("/managed/worktrees/task-1"));
        when(code.terminalDirectory("ended")).thenThrow(new ReviewConflictException("The review has ended"));

        mvc.perform(post("/api/agent-review/review-1/code/terminal"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"workingDirectory": "/managed/worktrees/task-1"}
                        """, JsonCompareMode.STRICT));
        mvc.perform(post("/api/agent-review/ended/code/terminal"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The review has ended"));
    }

    @Test
    void mapsFailuresToStatuses()
            throws Exception
    {
        when(code.files("missing")).thenThrow(new ReviewNotFoundException("Review not found: missing"));
        when(code.files("new")).thenThrow(new ReviewConflictException("This review has no run yet"));
        when(code.file("review-1", "Missing.java", Optional.empty())).thenThrow(new ReviewNotFoundException("File not found: Missing.java"));
        when(code.file("review-1", "../secret", Optional.empty())).thenThrow(new IllegalArgumentException("path must be a normalized repository-relative path"));

        mvc.perform(get("/api/agent-review/missing/code/files")).andExpect(status().isNotFound());
        mvc.perform(get("/api/agent-review/new/code/files"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This review has no run yet"));
        mvc.perform(get("/api/agent-review/review-1/code/file").param("path", "Missing.java")).andExpect(status().isNotFound());
        mvc.perform(get("/api/agent-review/review-1/code/file").param("path", "../secret")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/agent-review/review-1/code/file")).andExpect(status().isBadRequest());
    }

    @Test
    void listsRunsWithoutTheirStoredOutput()
            throws Exception
    {
        when(localRuns.list("review-1")).thenReturn(ImmutableList.of(finishedRun(), runningRun()));
        when(localRuns.output("review-1", "missing", 0)).thenThrow(new ReviewNotFoundException("Run not found: missing"));

        mvc.perform(get("/api/agent-review/review-1/code/runs"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"runs\": [" + FINISHED_RUN_JSON + ", " + RUNNING_RUN_JSON + "]}", JsonCompareMode.STRICT));
        mvc.perform(get("/api/agent-review/review-1/code/runs/missing/output")).andExpect(status().isNotFound());
    }

    @Test
    void startsAClassOrMethodRun()
            throws Exception
    {
        when(localRuns.start("review-1", "core/src/test/java/demo/TestCode.java", Optional.empty())).thenReturn(runningRun());
        when(localRuns.start("review-1", "core/src/test/java/demo/TestCode.java", Optional.of("fails"))).thenReturn(finishedRun());
        when(localRuns.start("review-1", "src/main/java/Code.java", Optional.empty())).thenThrow(new IllegalArgumentException("path is not a Surefire test source"));

        mvc.perform(post("/api/agent-review/review-1/code/runs").contentType(MediaType.APPLICATION_JSON).content("""
                        {"path": "core/src/test/java/demo/TestCode.java", "method": null}
                        """))
                .andExpect(status().isCreated())
                .andExpect(content().json(RUNNING_RUN_JSON, JsonCompareMode.STRICT));
        mvc.perform(post("/api/agent-review/review-1/code/runs").contentType(MediaType.APPLICATION_JSON).content("""
                        {"path": "core/src/test/java/demo/TestCode.java", "method": "fails"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("run-2"));
        mvc.perform(post("/api/agent-review/review-1/code/runs").contentType(MediaType.APPLICATION_JSON).content("""
                        {"path": "src/main/java/Code.java"}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusalsCarryTheirReasonAndAction()
            throws Exception
    {
        when(localRuns.start(eq("review-1"), eq("src/test/java/TestNoWrapper.java"), any())).thenThrow(new LocalRunRefusedException("No Maven wrapper (mvnw) in this working copy", true));
        when(localRuns.start(eq("review-1"), eq("src/test/java/TestNoModule.java"), any())).thenThrow(new LocalRunRefusedException("No Maven module for this test", false));
        when(localRuns.start(eq("review-1"), eq("src/test/java/TestBusy.java"), any())).thenThrow(new ReviewConflictException("A run is using this working copy"));

        mvc.perform(post("/api/agent-review/review-1/code/runs").contentType(MediaType.APPLICATION_JSON).content("{\"path\": \"src/test/java/TestNoWrapper.java\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().json("""
                        {"reason": "No Maven wrapper (mvnw) in this working copy", "action": "OPEN_TERMINAL"}
                        """, JsonCompareMode.STRICT));
        mvc.perform(post("/api/agent-review/review-1/code/runs").contentType(MediaType.APPLICATION_JSON).content("{\"path\": \"src/test/java/TestNoModule.java\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().json("""
                        {"reason": "No Maven module for this test", "action": null}
                        """, JsonCompareMode.STRICT));
        mvc.perform(post("/api/agent-review/review-1/code/runs").contentType(MediaType.APPLICATION_JSON).content("{\"path\": \"src/test/java/TestBusy.java\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A run is using this working copy"));
    }

    @Test
    void stopsARunAndStreamsItsOutput()
            throws Exception
    {
        when(localRuns.stop("review-1", "run-2")).thenReturn(finishedRun());
        when(localRuns.stop("review-1", "run-3")).thenThrow(new AgentUnavailableException("The run's processes could not be verified stopped"));
        when(localRuns.output("review-1", "run-2", 0)).thenReturn(new LocalRunService.OutputChunk("[INFO] Scanning\n", 16, 0, false));
        when(localRuns.output("review-1", "run-2", 70000)).thenReturn(new LocalRunService.OutputChunk("tail", 2000004, 1930000, true));
        when(localRuns.output(anyString(), anyString(), eq(-1L))).thenThrow(new IllegalArgumentException("after must not be negative"));

        mvc.perform(post("/api/agent-review/review-1/code/runs/run-2/stop"))
                .andExpect(status().isOk())
                .andExpect(content().json(FINISHED_RUN_JSON, JsonCompareMode.STRICT));
        mvc.perform(post("/api/agent-review/review-1/code/runs/run-3/stop")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/agent-review/review-1/code/runs/run-2/output"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"text": "[INFO] Scanning\\n", "nextOffset": 16, "skippedBytes": 0, "complete": false}
                        """, JsonCompareMode.STRICT));
        mvc.perform(get("/api/agent-review/review-1/code/runs/run-2/output").param("after", "70000"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"text": "tail", "nextOffset": 2000004, "skippedBytes": 1930000, "complete": true}
                        """, JsonCompareMode.STRICT));
        mvc.perform(get("/api/agent-review/review-1/code/runs/run-2/output").param("after", "-1")).andExpect(status().isBadRequest());
    }

    private static String saveRequest(String path, String expectedHash)
    {
        return "{\"path\": \"%s\", \"content\": \"draft\\n\", \"expectedHash\": \"%s\"}".formatted(path, expectedHash);
    }

    private static LocalRun runningRun()
    {
        return LocalRun.started("run-1", "task-1", "workspace-1", 1, "core/src/test/java/demo/TestCode.java", "demo.TestCode", Optional.empty(), Optional.empty(),
                "./mvnw -B -Dtest=demo.TestCode -Dsurefire.failIfNoSpecifiedTests=false test", HEAD, ImmutableList.of(), "fingerprint", "launch-1",
                Instant.parse("2026-09-15T10:00:00.123Z"));
    }

    private static LocalRun finishedRun()
    {
        LocalRun started = LocalRun.started("run-2", "task-1", "workspace-1", 2, "core/src/test/java/demo/TestCode.java", "demo.TestCode", Optional.of("fails"),
                Optional.of("core"), "./mvnw -B -pl core -Dtest=demo.TestCode#fails -Dsurefire.failIfNoSpecifiedTests=false test", HEAD,
                ImmutableList.of("core/src/test/java/demo/TestNew.java"), "fingerprint", "launch-2", Instant.parse("2026-09-15T10:00:00.123Z"));
        return started.finished(LocalRun.Status.FAILED_ASSERTION, Instant.parse("2026-09-15T10:00:42.500Z"), OptionalInt.of(1), Optional.of(new LocalRun.Counts(2, 1, 0, 0)),
                ImmutableList.of(new LocalRun.TestClass("demo.TestCode", OptionalLong.of(14), ImmutableList.of(
                        new LocalRun.TestMethod("passes", LocalRun.MethodStatus.PASSED, OptionalLong.of(2), Optional.empty(), Optional.empty()),
                        new LocalRun.TestMethod("fails", LocalRun.MethodStatus.FAILED, OptionalLong.of(12), Optional.of("expected: <1> but was: <2>"),
                                Optional.of("org.opentest4j.AssertionFailedError: expected: <1> but was: <2>"))))),
                true, OptionalLong.of(1930000));
    }
}
