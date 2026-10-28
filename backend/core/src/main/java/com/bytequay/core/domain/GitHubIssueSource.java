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

import static com.bytequay.core.hosting.HostingUrls.canonicalBaseUrl;
import static com.bytequay.core.hosting.HostingUrls.checkRepositoryPath;
import static com.bytequay.core.hosting.HostingUrls.requireWithinBase;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** The accepted issue context, retained independently of the open-issue cache. */
public record GitHubIssueSource(String host, long issueId, String repo, int number, String url, String title, String body, Instant updatedAt)
{
    public GitHubIssueSource
    {
        requireNonNull(host, "host is null");
        requireNonNull(repo, "repo is null");
        requireNonNull(url, "url is null");
        requireNonNull(title, "title is null");
        requireNonNull(body, "body is null");
        requireNonNull(updatedAt, "updatedAt is null");
        host = host.contains("://") ? canonicalBaseUrl(URI.create(host)).toASCIIString() : host.toLowerCase(Locale.ROOT);
        repo = repo.toLowerCase(Locale.ROOT);
        checkArgument(issueId > 0 && number > 0, "invalid issue identity");
        checkRepositoryPath(repo);
        URI base = canonicalBaseUrl(URI.create(host.contains("://") ? host : "https://" + host));
        String checked = requireWithinBase(URI.create(url), base).toASCIIString();
        String prefix = base + "/" + repo;
        checkArgument(checked.equalsIgnoreCase(prefix + "/issues/" + number) || checked.equalsIgnoreCase(prefix + "/-/issues/" + number) ||
                        checked.equalsIgnoreCase(prefix + "/-/work_items/" + number),
                "issue URL does not match its identity");
        checkArgument(!title.isBlank(), "title must not be blank");
    }
}
