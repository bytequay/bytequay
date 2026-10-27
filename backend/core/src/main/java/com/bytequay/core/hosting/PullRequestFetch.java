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
package com.bytequay.core.hosting;

import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PrRawDetail;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** A conditional metadata read, with normalized detail from the same remote response when available. */
public record PullRequestFetch(Optional<FetchedPullRequest> pullRequest, Optional<String> etag, Optional<PrRawDetail> detail)
{
    public PullRequestFetch
    {
        requireNonNull(pullRequest, "pullRequest is null");
        requireNonNull(etag, "etag is null");
        requireNonNull(detail, "detail is null");
    }

    public PullRequestFetch(Optional<FetchedPullRequest> pullRequest, Optional<String> etag)
    {
        this(pullRequest, etag, Optional.empty());
    }
}
