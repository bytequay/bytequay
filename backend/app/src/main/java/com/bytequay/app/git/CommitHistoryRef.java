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
package com.bytequay.app.git;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A backend-routed commit-history branch selector. */
public record CommitHistoryRef(Source source, String branch)
{
    public CommitHistoryRef
    {
        requireNonNull(source, "source is null");
        requireNonNull(branch, "branch is null");
        checkArgument(!branch.isBlank(), "branch is blank");
        checkArgument(!branch.startsWith("-"), "branch must not be a Git option");
        checkArgument(branch.length() <= 512, "branch is too long");
    }

    public enum Source
    {
        WORKSPACE,
        UPSTREAM
    }
}
