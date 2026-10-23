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
package com.bytequay.app.repository.github.actions;

import com.bytequay.agent.core.BlobRef;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A diagnostic excerpt and its cached raw-log identity. */
public record BoundedCiLog(
        Optional<BlobRef> blob,
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
        requireNonNull(terminalErrorLines, "terminalErrorLines is null");
        terminalErrorLines = ImmutableList.copyOf(terminalErrorLines);
        checkArgument(terminalErrorLines.size() <= 20, "terminalErrorLines exceeds 20 values");
        terminalErrorLines.forEach(line -> checkText(line, "terminalErrorLine", 500));
        checkOptionalText(unavailableReason, "unavailableReason", 500);
        checkArgument(blob.isPresent() != unavailableReason.isPresent(), "exactly one of blob and unavailableReason must be present");
        checkArgument(blob.isPresent() || (!compacted && terminalErrorLines.isEmpty()), "unavailable log has content facts");
    }

    public static BoundedCiLog unavailable(String reason)
    {
        return new BoundedCiLog(Optional.empty(), false, ImmutableList.of(), Optional.of(reason), Optional.empty(), false);
    }
}
