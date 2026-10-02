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
package com.bytequay.core.memory;

import com.bytequay.core.context.ContextLimits;

import java.time.Instant;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * A report that a memory is wrong. It flags the memory under Needs you and travels with it to agents; it never edits or hides it.
 *
 * @param quote the evidence the reporter quoted, such as the code line that contradicts the memory
 * @param taskId the agent task that reported it
 */
public record MemoryReport(String reason, String quote, String taskId, Instant at)
{
    public static final int MAX_REASON_BYTES = 500;

    public MemoryReport
    {
        ContextLimits.text(reason, "report reason", MAX_REASON_BYTES);
        requireNonNull(quote, "quote is null");
        checkArgument(ContextLimits.bytes(quote) <= MemorySource.MAX_QUOTE_BYTES, "report quote exceeds %s UTF-8 bytes", MemorySource.MAX_QUOTE_BYTES);
        checkText(taskId, "taskId");
        requireNonNull(at, "at is null");
    }
}
