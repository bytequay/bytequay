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
import static java.util.Objects.requireNonNull;

/** A retained excerpt, its immutable source identity, and how the application obtained it. */
public record ContextSourceEvidence(
        String id,
        ContextReference reference,
        String provenance,
        String excerpt,
        Capture capture,
        Optional<Instant> retrievedAt,
        Optional<String> contentHash,
        Optional<String> version,
        boolean truncated)
{
    public ContextSourceEvidence
    {
        ContextLimits.text(id, "evidence id", 500);
        requireNonNull(reference, "reference is null");
        ContextLimits.text(provenance, "provenance", 4000);
        ContextLimits.text(excerpt, "excerpt", 16 * 1024);
        requireNonNull(capture, "capture is null");
        requireNonNull(retrievedAt, "retrievedAt is null");
        checkOptionalText(contentHash, "contentHash", 200);
        checkOptionalText(version, "version", 200);
    }

    public enum Capture
    {
        BYTEQUAY_READ,
        AGENT_REPORTED
    }
}
