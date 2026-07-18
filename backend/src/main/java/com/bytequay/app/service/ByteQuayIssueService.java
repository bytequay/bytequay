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
import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.credentials.PatResolver;
import org.springframework.stereotype.Service;

import java.util.List;

import static java.util.Objects.requireNonNull;

/** Fixed-repository boundary for ByteQuay product issues. */
@Service
public class ByteQuayIssueService
{
    public static final String OWNER = "bytequay";
    public static final String REPO = "bytequay";
    public static final String FULL_NAME = OWNER + "/" + REPO;

    private static final RepoRef REF = RepoRef.of(OWNER, REPO);

    private final PullRequestRepository gitHub;
    private final PatResolver pats;

    public ByteQuayIssueService(PullRequestRepository gitHub, PatResolver pats)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.pats = requireNonNull(pats, "pats is null");
    }

    public RepoIssue report(String title, String body)
    {
        return gitHub.createIssue(pats.resolve(), REF, title, body);
    }

    public List<RepoIssue> listAll()
    {
        return gitHub.fetchRepoIssues(pats.resolve(FULL_NAME), REF, "all");
    }

    public IssueDetail detail(int number)
    {
        return gitHub.fetchIssueDetail(pats.resolve(FULL_NAME), REF, number);
    }

    public boolean viewerCanMaintain()
    {
        return gitHub.fetchViewerCanWrite(pats.resolve(FULL_NAME), REF);
    }
}
