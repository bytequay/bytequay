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

import java.util.List;

/**
 * One creation-ordered GitHub issue-list page for workspace intake.
 * Boundary numbers include pull requests and closed issues because GitHub
 * shares one monotonically increasing number sequence between them. The
 * payload itself contains only open issues that are eligible for triage.
 */
public record RepoIssueIntakePage(
        List<RepoIssue> openIssues,
        int newestNumber,
        int oldestNumber,
        boolean hasMore)
{
    public RepoIssueIntakePage
    {
        openIssues = List.copyOf(openIssues);
    }
}
