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

import com.bytequay.app.review.ReviewCodeService.CodeFile;
import com.bytequay.app.review.ReviewCodeService.CodeFiles;
import com.bytequay.core.review.LocalRun;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.ToIntFunction;

import static com.google.common.collect.ImmutableList.toImmutableList;

/** The Code and Tests tab's HTTP shapes. Paths are repository-relative; only the terminal route returns a filesystem path. */
final class ReviewCodeHttpModels
{
    private ReviewCodeHttpModels() {}

    static FilesResponse files(CodeFiles files)
    {
        return new FilesResponse(
                files.source().name(),
                files.revision(),
                files.sourceFingerprint(),
                files.savedExperiment(),
                files.truncated(),
                files.files().stream()
                        .map(file -> new FileEntryResponse(file.path(), file.changedInPr(), file.addedInPr(), file.onlyInWorkingCopy(), file.uncommitted()))
                        .collect(toImmutableList()));
    }

    static FileResponse file(CodeFile file)
    {
        return new FileResponse(file.path(), file.content(), file.contentHash(), file.editable(), file.readOnlyReason(), file.unavailableReason(), file.prPatch(),
                file.lineChanges().stream().map(change -> new LineChangeResponse(change.kind().name(), change.line(), change.count())).collect(toImmutableList()));
    }

    static RunResponse run(LocalRun run)
    {
        return new RunResponse(
                run.id(),
                run.ordinal(),
                run.initiator().name(),
                run.scope().name(),
                run.path(),
                run.testClass(),
                run.testMethod(),
                run.module(),
                run.command(),
                run.sourceHeadSha(),
                run.localPaths(),
                run.sourceFingerprint(),
                run.status().name(),
                run.startedAt(),
                run.endedAt(),
                run.exitCode(),
                count(run, LocalRun.Counts::tests),
                count(run, LocalRun.Counts::failures),
                count(run, LocalRun.Counts::errors),
                count(run, LocalRun.Counts::skipped),
                run.outputTruncated(),
                run.omittedBytes(),
                run.treeUnverified(),
                run.results().stream()
                        .map(result -> new RunClassResponse(result.className(), result.durationMs(), result.methods().stream()
                                .map(method -> new RunMethodResponse(method.name(), method.status().name(), method.durationMs(), method.message(), method.trace()))
                                .collect(toImmutableList())))
                        .collect(toImmutableList()));
    }

    private static OptionalInt count(LocalRun run, ToIntFunction<LocalRun.Counts> count)
    {
        return run.counts().map(counts -> OptionalInt.of(count.applyAsInt(counts))).orElse(OptionalInt.empty());
    }

    record FilesResponse(
            String source,
            String revision,
            Optional<String> sourceFingerprint,
            boolean savedExperiment,
            boolean truncated,
            List<FileEntryResponse> files) {}

    record FileEntryResponse(String path, boolean changedInPr, boolean addedInPr, boolean onlyInWorkingCopy, boolean uncommitted) {}

    record FileResponse(
            String path,
            Optional<String> content,
            Optional<String> contentHash,
            boolean editable,
            Optional<String> readOnlyReason,
            Optional<String> unavailableReason,
            Optional<String> prPatch,
            List<LineChangeResponse> lineChanges) {}

    record LineChangeResponse(String kind, int line, int count) {}

    record RunsResponse(List<RunResponse> runs) {}

    /** A run without its stored output, which the output route streams. */
    record RunResponse(
            String id,
            int ordinal,
            String initiator,
            String scope,
            Optional<String> path,
            Optional<String> testClass,
            Optional<String> testMethod,
            Optional<String> module,
            String command,
            Optional<String> sourceHeadSha,
            List<String> localPaths,
            Optional<String> sourceFingerprint,
            String status,
            Instant startedAt,
            Optional<Instant> endedAt,
            OptionalInt exitCode,
            OptionalInt tests,
            OptionalInt failures,
            OptionalInt errors,
            OptionalInt skipped,
            boolean outputTruncated,
            OptionalLong omittedBytes,
            boolean treeUnverified,
            List<RunClassResponse> results) {}

    record RunClassResponse(String className, OptionalLong durationMs, List<RunMethodResponse> methods) {}

    record RunMethodResponse(String name, String status, OptionalLong durationMs, Optional<String> message, Optional<String> trace) {}

    record RunOutputResponse(String text, long nextOffset, long skippedBytes, boolean complete) {}

    record SaveFileRequest(String path, String content, String expectedHash) {}

    /** The renderer recognizes a stale save by {@code currentHash}; the content is null when the file cannot be shown. */
    record SaveConflictResponse(String currentHash, Optional<String> currentContent) {}

    /** For Electron main only, which never forwards these paths to a renderer. */
    record TerminalResponse(String workingDirectory, Optional<String> claudeConfigDirectory) {}
}
