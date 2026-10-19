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
package com.bytequay.core.domain;

/**
 * Outcome of an attempted pull request merge. A scheduled merge can have
 * both {@code merged=false} and {@code queued=false} while GitHub waits for
 * requirements before adding the pull request to its merge queue.
 */
public record MergeResult(String sha, boolean merged, String message, boolean queued)
{
    public MergeResult(String sha, boolean merged, String message)
    {
        this(sha, merged, message, false);
    }

    /** The pull request joined GitHub's queue but has not merged yet. */
    public static MergeResult enqueued(String message)
    {
        return new MergeResult(null, false, message, true);
    }
}
