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
package com.bytequay.app.service;

import com.bytequay.app.domain.IssueDetail;
import com.bytequay.app.domain.IssueOrigin;
import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.sqlite.IssueOriginStore;
import org.springframework.stereotype.Service;

import java.util.Locale;

import static java.util.Objects.requireNonNull;

/** Resolves trusted issue provenance once, then keeps it immutable locally. */
@Service
public class IssueOriginService
{
    static final String CANONICAL_OWNER = "bytequay";
    static final String CANONICAL_REPO = "bytequay";
    static final String TRUSTED_MARKER_AUTHOR = "chenjian2664";

    private final IssueOriginStore origins;

    public IssueOriginService(IssueOriginStore origins)
    {
        this.origins = requireNonNull(origins, "origins is null");
    }

    public RepoIssue attribute(RepoRef repo, RepoIssue issue)
    {
        requireNonNull(repo, "repo is null");
        requireNonNull(issue, "issue is null");
        return origins.find(issue.id())
                .map(issue::withOrigin)
                .orElseGet(() -> isCanonical(repo)
                        ? rememberListOrigin(issue)
                        : issue.withOrigin(IssueOrigin.USER));
    }

    public IssueDetail attributeDetail(RepoRef repo, IssueDetail issue)
    {
        requireNonNull(repo, "repo is null");
        requireNonNull(issue, "issue is null");
        return origins.find(issue.id())
                .map(issue::withOrigin)
                .orElseGet(() -> {
                    if (!isCanonical(repo)) {
                        return issue.withOrigin(IssueOrigin.USER);
                    }
                    String detected = IssueOrigin.UNKNOWN.equals(issue.origin())
                            ? IssueOrigin.USER
                            : trustedMarker(issue.origin(), issue.author());
                    origins.saveIfAbsent(issue.id(), issue.number(), detected);
                    return issue.withOrigin(detected);
                });
    }

    public RepoIssue recordCreated(RepoIssue issue, String origin)
    {
        requireNonNull(issue, "issue is null");
        origins.saveIfAbsent(issue.id(), issue.number(), origin);
        return issue.withOrigin(origin);
    }

    private RepoIssue rememberListOrigin(RepoIssue issue)
    {
        if (IssueOrigin.UNKNOWN.equals(issue.origin())) {
            return issue;
        }
        String origin = trustedMarker(issue.origin(), issue.author());
        origins.saveIfAbsent(issue.id(), issue.number(), origin);
        return issue.withOrigin(origin);
    }

    private static String trustedMarker(String detected, String author)
    {
        if ((IssueOrigin.QUALITY_SCAN.equals(detected) || IssueOrigin.USER_REPORT.equals(detected))
                && !TRUSTED_MARKER_AUTHOR.equalsIgnoreCase(author == null ? "" : author.strip())) {
            return IssueOrigin.USER;
        }
        return detected;
    }

    private static boolean isCanonical(RepoRef repo)
    {
        return CANONICAL_OWNER.toLowerCase(Locale.ROOT).equals(repo.owner().toLowerCase(Locale.ROOT))
                && CANONICAL_REPO.toLowerCase(Locale.ROOT).equals(repo.repo().toLowerCase(Locale.ROOT));
    }
}
