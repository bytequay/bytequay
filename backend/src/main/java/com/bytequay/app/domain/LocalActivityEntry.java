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
package com.bytequay.app.domain;

import java.time.Instant;

/**
 * One row in the Activity tab. Backed by a {@code git reflog} entry,
 * which records every HEAD movement in the local clone — commits,
 * checkouts, merges, pulls, rebases. The {@link #kind} is parsed
 * from git's reflog subject so the UI can render an icon and label
 * without guessing from free text.
 *
 * @param selector Addressable selector for this entry, such as
 * {@code HEAD@{0}}.
 * @param kind Coarse classification of the event used for icon and label
 * decisions. {@link Kind#UNKNOWN} is the catch-all for reflog subjects the
 * parser does not recognize yet.
 * @param subject The full reflog subject, including the descriptive tail after
 * the kind prefix.
 */
public record LocalActivityEntry(
        String sha,
        String shortSha,
        String selector,
        Kind kind,
        String subject,
        Instant at)
{
    public enum Kind
    {
        COMMIT,
        CHECKOUT,
        MERGE,
        PULL,
        PUSH,
        REBASE,
        RESET,
        BRANCH,
        UNKNOWN
    }
}
