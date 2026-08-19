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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Display-only pull-request association for one source commit.
 *
 * <p>Grouping by pull request is a convenience for choosing what to skip. It
 * never changes commit order and is never a control fact.
 */
public record PullRequestAssociation(
        int number,
        String title,
        String url,
        String baseRef,
        String mergedAt)
{
    public PullRequestAssociation
    {
        checkArgument(number > 0, "number is not positive");
        requireNonNull(title, "title is null");
        requireNonNull(url, "url is null");
        requireNonNull(baseRef, "baseRef is null");
        requireNonNull(mergedAt, "mergedAt is null");
    }
}
