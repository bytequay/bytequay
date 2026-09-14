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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A concrete unanswered question and the observation that would settle it. */
public record ReviewEvidenceRequest(String question, String check, String resolvesWhen)
{
    public ReviewEvidenceRequest
    {
        requireNonNull(question, "question is null");
        requireNonNull(check, "check is null");
        requireNonNull(resolvesWhen, "resolvesWhen is null");
        checkArgument(!question.isBlank() && !check.isBlank() && !resolvesWhen.isBlank(), "evidence request fields must not be blank");
    }
}
