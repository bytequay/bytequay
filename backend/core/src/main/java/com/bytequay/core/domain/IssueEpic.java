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

import java.net.URI;
import java.time.Instant;
import java.util.Locale;

import static com.bytequay.core.domain.Checks.checkText;
import static com.bytequay.core.hosting.HostingUrls.canonicalBaseUrl;
import static com.bytequay.core.hosting.HostingUrls.checkRepositoryPath;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Local classification shared by every workspace displaying the same issue. */
public record IssueEpic(String host, long issueId, String repo, int number, boolean isEpic, Instant updatedAt)
{
    public IssueEpic
    {
        requireNonNull(host, "host is null");
        requireNonNull(repo, "repo is null");
        requireNonNull(updatedAt, "updatedAt is null");
        host = host.contains("://") ? canonicalBaseUrl(URI.create(host)).toASCIIString() : host.toLowerCase(Locale.ROOT);
        repo = repo.toLowerCase(Locale.ROOT);
        checkText(host, "host");
        checkArgument(issueId > 0 && number > 0, "invalid issue identity");
        checkRepositoryPath(repo);
    }
}
