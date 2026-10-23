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

import static com.bytequay.core.domain.Checks.checkSha;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record FixupPositionCheck(String headSha, Result result, String diagnostics)
{
    public FixupPositionCheck
    {
        checkSha(headSha, "headSha");
        requireNonNull(result, "result is null");
        requireNonNull(diagnostics, "diagnostics is null");
        checkArgument(diagnostics.length() <= 8 * 1024, "diagnostics exceeds 8192 characters");
    }

    public static FixupPositionCheck valid(String headSha)
    {
        return new FixupPositionCheck(headSha, Result.VALID, "");
    }

    public static FixupPositionCheck invalid(String headSha, String diagnostics)
    {
        return new FixupPositionCheck(headSha, Result.INVALID, diagnostics);
    }

    public enum Result
    {
        VALID,
        INVALID
    }
}
