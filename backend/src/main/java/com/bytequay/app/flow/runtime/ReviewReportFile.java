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

import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static java.util.Objects.requireNonNull;

/** One local, opaque reviewer-to-Task handoff outside the Git worktree. */
final class ReviewReportFile
{
    static final String FILE_NAME = "subagent-review.txt";
    private static final int MAX_REPORT_LENGTH = 131_072;

    private ReviewReportFile() {}

    static final class PendingReportException
            extends IllegalStateException
    {
        private static final long serialVersionUID = 1L;

        private PendingReportException()
        {
            super("ask_report must consume the waiting review report first");
        }
    }

    static void save(Task task, String report)
    {
        requireNonNull(task, "task is null");
        if (report == null || report.isBlank()) {
            throw new IllegalArgumentException("report is blank");
        }
        if (report.length() > MAX_REPORT_LENGTH) {
            throw new IllegalArgumentException("report is too large");
        }
        Path target = path(task);
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                throw new IllegalStateException(
                        "a review report is already waiting for this Task");
            }
            temporary = Files.createTempFile(
                    target.getParent(), FILE_NAME + ".", ".tmp");
            Files.writeString(temporary, report, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target);
            }
            temporary = null;
        }
        catch (IOException failure) {
            throw new IllegalStateException(
                    "could not save the review report", failure);
        }
        finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                }
                catch (IOException ignored) {
                    // The exact report was not published; a temp file has no
                    // workflow meaning and can be cleaned up later.
                }
            }
        }
    }

    static String ask(Task task)
    {
        requireNonNull(task, "task is null");
        Path report = path(task);
        if (!Files.isRegularFile(report)) {
            return "";
        }
        try {
            String content = Files.readString(report, StandardCharsets.UTF_8);
            Files.delete(report);
            return content;
        }
        catch (IOException failure) {
            throw new IllegalStateException("could not consume the review report",
                    failure);
        }
    }

    static void requireAbsent(Task task)
    {
        if (Files.exists(path(requireNonNull(task, "task is null")))) {
            throw new PendingReportException();
        }
    }

    static Path path(Task task)
    {
        return Path.of(task.gitCommonDir())
                .resolve("bytequay")
                .resolve("review-reports")
                .resolve(digest(task.taskId()))
                .resolve(FILE_NAME);
    }

    private static String digest(String value)
    {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
