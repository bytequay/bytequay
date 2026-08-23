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
package com.bytequay.app.web.git;

import com.bytequay.app.git.CommitHistoryService;
import com.bytequay.core.domain.CommitRefreshStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.bytequay.app.git.CommitRefreshCause.MANUAL;
import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/git/commits")
public class CommitHistoryRefreshController
{
    private final CommitHistoryService commits;

    public CommitHistoryRefreshController(CommitHistoryService commits)
    {
        this.commits = requireNonNull(commits, "commits is null");
    }

    @PostMapping("/refresh")
    public ResponseEntity<CommitRefreshStatus> refresh(
            @PathVariable String workspaceId,
            @RequestBody CommitHistoryRefreshRequest request)
    {
        CommitRefreshStatus status = commits.requestRefresh(workspaceId, request.historyRefs(), MANUAL);
        return ResponseEntity.accepted().body(status);
    }
}
