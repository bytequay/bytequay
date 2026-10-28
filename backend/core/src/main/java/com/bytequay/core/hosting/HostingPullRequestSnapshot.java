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

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Metadata and its validator observed together through one account and credential generation. */
public record HostingPullRequestSnapshot(
        HostingPullRequest request,
        long accountId,
        long credentialGeneration,
        FetchedPullRequest pullRequest,
        PrRawDetail detail,
        Optional<String> etag,
        Instant validatedAt)
{
    public HostingPullRequestSnapshot
    {
        requireNonNull(request, "request is null");
        checkArgument(accountId > 0, "accountId must be positive");
        checkArgument(credentialGeneration > 0, "credentialGeneration must be positive");
        requireNonNull(pullRequest, "pullRequest is null");
        requireNonNull(detail, "detail is null");
        checkOptionalText(etag, "etag");
        requireNonNull(validatedAt, "validatedAt is null");
        checkArgument(pullRequest.number() == request.number(), "Snapshot request number differs from its identity");
        checkArgument(pullRequest.githubId().toString().equals(request.remoteId()), "Snapshot remote ID differs from its identity");
    }
}
