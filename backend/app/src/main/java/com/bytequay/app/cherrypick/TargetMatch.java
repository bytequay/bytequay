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
package com.bytequay.app.cherrypick;

import static java.util.Objects.requireNonNull;

/**
 * Proof that a source commit is already represented on the target.
 *
 * <p>Only these two kinds exist. A matching title, a similar patch, or
 * {@code range-diff} output is never evidence.
 *
 * @param kind how the commit was found
 * @param targetCommitSha the target commit carrying the evidence
 */
public record TargetMatch(Kind kind, String targetCommitSha)
{
    public TargetMatch
    {
        requireNonNull(kind, "kind is null");
        requireNonNull(targetCommitSha, "targetCommitSha is null");
    }

    public enum Kind
    {
        DIRECT_ANCESTOR,
        CHERRY_PICK_ORIGIN
    }
}
