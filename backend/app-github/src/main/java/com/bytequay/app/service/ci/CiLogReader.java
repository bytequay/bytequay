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
package com.bytequay.app.service.ci;

import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.hosting.PullRequestCi;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/** Caches a job's complete log through its provider and selects the bounded excerpt a CI repair reads. */
@Component
public class CiLogReader
{
    private static final int MAX_TERMINAL_ERRORS = 20;
    private static final int MAX_ERROR_LINE = 500;
    private static final String TAIL_NOTE = "No diagnostic keywords or stack trace matched. Showing only the log tail (at most 32 KiB); "
            + "request the cached raw log for complete evidence.\n";
    private static final String LOG_MEDIA_TYPE = "text/plain; charset=utf-8";
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\x1B(?:[@-Z\\\\-_]|\\[[0-?]*[ -/]*[@-~])");
    private static final Pattern TIMESTAMP_PREFIX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z\\s+");

    private final CiRawLogStore rawLogs;
    private final TaskLogStore taskLogs;

    public CiLogReader(CiRawLogStore rawLogs, TaskLogStore taskLogs)
    {
        this.rawLogs = requireNonNull(rawLogs, "rawLogs is null");
        this.taskLogs = requireNonNull(taskLogs, "taskLogs is null");
    }

    /**
     * Caches a complete credential-redacted job log and selects intact diagnostic blocks.
     *
     * @param query exact run, job, attempt, task, and excerpt byte limit
     * @return bounded excerpt blob, cached raw-log identifier, terminal errors, and selection facts
     */
    public BoundedCiLog read(HostingTarget target, PullRequestCi ci, ReadCiLogQuery query)
    {
        requireNonNull(target, "target is null");
        requireNonNull(ci, "ci is null");
        requireNonNull(query, "query is null");
        Optional<String> unavailable = rawLogs.cache(query, path -> ci.downloadLog(target, query.pullRequest().number(), query.job().id(), path));
        if (unavailable.isPresent()) {
            return BoundedCiLog.unavailable(unavailable.orElseThrow());
        }
        String logId = CiRawLogStore.id(query);
        String text = rawLogs.read(query.taskId(), logId);
        String rawNote = "Raw log: read_ci_raw_log({\"logId\":\"" + logId + "\"}).\n";
        int budget = query.maximumBytes() - rawNote.length() - TAIL_NOTE.length();
        if (budget <= 0) {
            return BoundedCiLog.unavailable("CI log byte limit is too small to include log retrieval details");
        }
        CiLogExcerpt excerpt = CiLogExcerpt.from(text, Math.min(budget, 32 * 1024), budget);
        byte[] stored = (rawNote + (excerpt.diagnosticsMatched() ? "" : TAIL_NOTE) + excerpt.text()).getBytes(UTF_8);
        BlobRef blob = taskLogs.writeBlob(query.taskId(), ByteBuffer.wrap(stored), LOG_MEDIA_TYPE);
        return new BoundedCiLog(Optional.of(blob), excerpt.compacted(), terminalErrors(text), Optional.empty(), Optional.of(logId), excerpt.diagnosticsMatched());
    }

    /** One log or note line without color, timestamp or repeated whitespace, bounded for display. */
    public static String normalizeLine(String value)
    {
        String normalized = ANSI_ESCAPE.matcher(value).replaceAll("");
        normalized = TIMESTAMP_PREFIX.matcher(normalized).replaceFirst("");
        normalized = normalized.strip().replaceAll("\\s+", " ");
        return Ascii.truncate(normalized, MAX_ERROR_LINE, "");
    }

    private static List<String> terminalErrors(String log)
    {
        List<String> errors = log.lines()
                .map(CiLogReader::normalizeLine)
                .filter(line -> {
                    String lower = line.toLowerCase(Locale.ROOT);
                    return lower.contains("error")
                            || lower.contains("exception")
                            || lower.contains("fail")
                            || lower.contains("fatal");
                })
                .filter(line -> !line.isBlank())
                .collect(toImmutableList());
        int first = Math.max(0, errors.size() - MAX_TERMINAL_ERRORS);
        return ImmutableList.copyOf(errors.subList(first, errors.size()));
    }
}
