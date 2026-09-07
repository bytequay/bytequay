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
package com.bytequay.core.review;

import static java.util.Objects.requireNonNull;

/**
 * One bounded excerpt kept with a finding: where it came from, how it was obtained, and why the
 * agent says it supports the finding. Kept with the finding, so it outlives the runtime log.
 */
public record ReviewEvidence(String ref, String source, String provenance, String excerpt, String explanation)
{
    public ReviewEvidence
    {
        requireNonNull(ref, "ref is null");
        requireNonNull(source, "source is null");
        requireNonNull(provenance, "provenance is null");
        requireNonNull(excerpt, "excerpt is null");
        requireNonNull(explanation, "explanation is null");
    }
}
