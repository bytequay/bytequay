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
package com.bytequay.core.context;

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkSha;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One numbered excerpt from a frozen repository blob, including a successful empty-file read.
 * New captures record the returned line range; older truncated captures may retain the requested range.
 * Truncation means the requested source window was not returned completely, and an oversized line may itself be partial.
 */
public record ContextRepositoryRead(
        String id,
        ContextReference.RepositoryFile reference,
        String blobId,
        Instant readAt,
        int startLine,
        int endLine,
        int totalLines,
        int utf8Bytes,
        boolean truncated,
        Optional<String> evidenceRef)
        implements ContextSourceRead
{
    public ContextRepositoryRead
    {
        ContextLimits.text(id, "source read id", 100);
        checkArgument(id.startsWith("context-read:"), "Invalid source read id");
        requireNonNull(reference, "reference is null");
        checkSha(blobId, "blobId");
        requireNonNull(readAt, "readAt is null");
        checkArgument(totalLines >= 0 && startLine >= 1, "Invalid source line range");
        checkArgument(utf8Bytes >= 0 && utf8Bytes <= 16 * 1024, "Source excerpt exceeds UTF-8 budget");
        checkOptionalText(evidenceRef, "evidenceRef", 500);
        if (totalLines == 0) {
            checkArgument(startLine == 1 && endLine == 0 && utf8Bytes == 0 && evidenceRef.isEmpty() && !truncated, "Invalid empty source read");
        }
        else {
            checkArgument(startLine <= endLine && endLine <= totalLines, "Invalid source line range");
            checkArgument(utf8Bytes > 0 && evidenceRef.isPresent(), "Nonempty source read requires retained evidence");
        }
    }

    @Override
    public String getKind()
    {
        return "REPOSITORY_FILE";
    }
}
