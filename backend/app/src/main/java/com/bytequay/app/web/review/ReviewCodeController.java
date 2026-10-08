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

import com.bytequay.app.review.LocalRunService;
import com.bytequay.app.review.ReviewCodeService;
import com.bytequay.app.review.ReviewFileConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Whole-file and symbol reads, source edits, local test runs and the terminal's directory for an Agent review's Code and Tests tab. */
@RestController
@RequestMapping("/api/agent-review/{reviewId}/code")
public class ReviewCodeController
{
    private final ReviewCodeService code;
    private final LocalRunService localRuns;

    public ReviewCodeController(ReviewCodeService code, LocalRunService localRuns)
    {
        this.code = requireNonNull(code, "code is null");
        this.localRuns = requireNonNull(localRuns, "localRuns is null");
    }

    @GetMapping("/changes")
    public ReviewCodeService.Changes changes(
            @PathVariable String reviewId,
            @RequestParam(defaultValue = "ALL") ReviewCodeService.ChangeScope scope,
            @RequestParam Optional<String> commit,
            @RequestParam Optional<String> base,
            @RequestParam Optional<String> head)
    {
        return code.changes(reviewId, scope, commit, base, head);
    }

    @GetMapping("/files")
    public ReviewCodeHttpModels.FilesResponse files(@PathVariable String reviewId)
    {
        return ReviewCodeHttpModels.files(code.files(reviewId));
    }

    @GetMapping("/file")
    public ReviewCodeHttpModels.FileResponse file(@PathVariable String reviewId, @RequestParam String path, @RequestParam Optional<String> expectedRevision)
    {
        return ReviewCodeHttpModels.file(code.file(reviewId, path, expectedRevision));
    }

    @PutMapping("/file")
    public ReviewCodeHttpModels.FileResponse save(@PathVariable String reviewId, @RequestBody ReviewCodeHttpModels.SaveFileRequest request)
    {
        return ReviewCodeHttpModels.file(code.save(reviewId, request.path(), request.content(), request.expectedHash()));
    }

    @PostMapping("/terminal")
    public ReviewCodeHttpModels.TerminalResponse terminal(@PathVariable String reviewId)
    {
        return new ReviewCodeHttpModels.TerminalResponse(code.terminalDirectory(reviewId).toString());
    }

    @GetMapping("/runs")
    public ReviewCodeHttpModels.RunsResponse runs(@PathVariable String reviewId)
    {
        return new ReviewCodeHttpModels.RunsResponse(localRuns.list(reviewId).stream().map(ReviewCodeHttpModels::run).collect(toImmutableList()));
    }

    @PostMapping("/runs/{runId}/stop")
    public ReviewCodeHttpModels.RunResponse stopRun(@PathVariable String reviewId, @PathVariable String runId)
    {
        return ReviewCodeHttpModels.run(localRuns.stop(reviewId, runId));
    }

    @GetMapping("/runs/{runId}/output")
    public ReviewCodeHttpModels.RunOutputResponse runOutput(@PathVariable String reviewId, @PathVariable String runId, @RequestParam(defaultValue = "0") long after)
    {
        LocalRunService.OutputChunk chunk = localRuns.output(reviewId, runId, after);
        return new ReviewCodeHttpModels.RunOutputResponse(chunk.text(), chunk.nextOffset(), chunk.skippedBytes(), chunk.complete());
    }

    @ExceptionHandler(ReviewFileConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ReviewCodeHttpModels.SaveConflictResponse fileChanged(ReviewFileConflictException conflict)
    {
        return new ReviewCodeHttpModels.SaveConflictResponse(conflict.currentHash(), conflict.currentContent());
    }
}
