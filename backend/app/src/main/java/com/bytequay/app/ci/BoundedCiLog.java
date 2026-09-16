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

import com.bytequay.agent.core.BlobRef;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * A diagnostic blob, with download omissions separate from excerpt compaction.
 * {@code truncated} means the download's ending was omitted; a suffix instead
 * sets {@code omittedBeginning}. Error signatures precede excerpt compaction.
 */
public record BoundedCiLog(
        Optional<BlobRef> blob,
        int byteCount,
        boolean truncated,
        boolean omittedBeginning,
        boolean compacted,
        List<String> terminalErrorLines,
        Optional<String> unavailableReason,
        Optional<String> rawLogId,
        boolean diagnosticsMatched)
{
    public BoundedCiLog
    {
        requireNonNull(blob, "blob is null");
        requireNonNull(rawLogId, "rawLogId is null");
        rawLogId.ifPresent(value -> checkArgument(value.matches("[0-9a-f]{64}"), "rawLogId is invalid"));
        checkArgument(byteCount >= 0 && byteCount <= ReadCiLogQuery.MAXIMUM_BYTES, "byteCount is outside the log limit");
        requireNonNull(terminalErrorLines, "terminalErrorLines is null");
        terminalErrorLines = ImmutableList.copyOf(terminalErrorLines);
        checkArgument(terminalErrorLines.size() <= 20, "terminalErrorLines exceeds 20 values");
        terminalErrorLines.forEach(line -> checkText(line, "terminalErrorLine", 500));
        checkOptionalText(unavailableReason, "unavailableReason", 500);
        checkArgument(blob.isPresent() != unavailableReason.isPresent(), "exactly one of blob and unavailableReason must be present");
        blob.ifPresent(value -> {
            checkArgument(value.size() == byteCount, "blob size and byteCount disagree");
        });
        checkArgument(blob.isPresent() || (byteCount == 0 && !truncated && !omittedBeginning && !compacted && terminalErrorLines.isEmpty()), "unavailable log has content facts");
    }

    public static BoundedCiLog unavailable(String reason)
    {
        return new BoundedCiLog(Optional.empty(), 0, false, false, false, ImmutableList.of(), Optional.of(reason), Optional.empty(), false);
    }
}
