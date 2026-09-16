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
import java.util.UUID;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One page of a successful registered-document capture. Blank pages retain a receipt without invented evidence text.
 * A failed refresh may return the original capture; its original read time and content identity remain unchanged.
 */
public record ContextDocumentRead(
        String id,
        ContextReference.Document reference,
        RegisteredContextSource registration,
        long notebookRevision,
        String captureId,
        String contentHash,
        Instant readAt,
        int page,
        int totalPages,
        int utf8Bytes,
        boolean blank,
        boolean truncated,
        boolean refreshFailed,
        Optional<String> evidenceRef)
        implements ContextSourceRead
{
    public ContextDocumentRead
    {
        ContextLimits.text(id, "source read id", 100);
        checkArgument(id.startsWith("context-read:"), "Invalid source read id");
        requireNonNull(reference, "reference is null");
        requireNonNull(registration, "registration is null");
        checkArgument(reference.sourceId().equals(registration.id()), "Document source identity differs from registration");
        checkArgument(registration.kind() == RegisteredContextSource.Kind.DOCUMENTATION_ROOT
                ? reference.url().startsWith(registration.url()) : reference.url().equals(registration.url()), "Document is outside its registered location");
        checkArgument(notebookRevision >= 0, "Negative notebook revision");
        ContextLimits.text(captureId, "captureId", 36);
        checkArgument(UUID.fromString(captureId).toString().equals(captureId), "Invalid document capture id");
        ContextLimits.text(contentHash, "contentHash", 64);
        checkArgument(contentHash.matches("[0-9a-f]{64}"), "Invalid document content hash");
        requireNonNull(readAt, "readAt is null");
        checkArgument(totalPages >= 1 && totalPages <= 16 && page >= 1 && page <= totalPages, "Invalid document page");
        checkArgument(utf8Bytes >= 0 && utf8Bytes <= 16 * 1024, "Document excerpt exceeds UTF-8 budget");
        checkOptionalText(evidenceRef, "evidenceRef", 500);
        checkArgument(blank == evidenceRef.isEmpty(), "Only nonblank document reads retain evidence");
        checkArgument(blank || utf8Bytes > 0, "Nonblank document read requires excerpt bytes");
    }

    @Override
    public String getKind()
    {
        return "DOCUMENT";
    }
}
