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

import static com.bytequay.app.ci.CiActionsChecks.checkOptionalText;
import static com.bytequay.app.ci.CiActionsChecks.checkText;
import static java.util.Objects.requireNonNull;

public record BoundedCiLog(
        Optional<BlobRef> blob,
        int byteCount,
        boolean truncated,
        List<String> terminalErrorLines,
        Optional<String> unavailableReason)
{
    public BoundedCiLog
    {
        requireNonNull(blob, "blob is null");
        if (byteCount < 0 || byteCount > ReadCiLogQuery.MAXIMUM_BYTES) {
            throw new IllegalArgumentException("byteCount is outside the log limit");
        }
        requireNonNull(terminalErrorLines, "terminalErrorLines is null");
        terminalErrorLines = ImmutableList.copyOf(terminalErrorLines);
        if (terminalErrorLines.size() > 20) {
            throw new IllegalArgumentException("terminalErrorLines exceeds 20 values");
        }
        terminalErrorLines.forEach(line -> checkText(line, "terminalErrorLine", 500));
        checkOptionalText(unavailableReason, "unavailableReason", 500);
        if (blob.isPresent() == unavailableReason.isPresent()) {
            throw new IllegalArgumentException("exactly one of blob and unavailableReason must be present");
        }
        blob.ifPresent(value -> {
            if (value.size() != byteCount) {
                throw new IllegalArgumentException("blob size and byteCount disagree");
            }
        });
        if (blob.isEmpty() && (byteCount != 0 || truncated || !terminalErrorLines.isEmpty())) {
            throw new IllegalArgumentException("unavailable log carries content facts");
        }
    }

    public static BoundedCiLog unavailable(String reason)
    {
        return new BoundedCiLog(Optional.empty(), 0, false, ImmutableList.of(), Optional.of(reason));
    }
}
