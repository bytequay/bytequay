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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.localpr.PRService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static java.util.Objects.requireNonNull;

/** Read-only compatibility boundary for the retired LEGACY Brain review. */
@Service
public class BrainReviewServiceImpl
{
    private final TaskStore tasks;
    private final PRService prs;

    public BrainReviewServiceImpl(TaskStore tasks, PRService prs)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.prs = requireNonNull(prs, "prs is null");
    }

    public PR reviewBeforeLocalOpen(String prId, String actor)
    {
        PR pr = prs.findById(prId)
                .orElseThrow(() -> new IllegalArgumentException("unknown local PR: " + prId));
        if (!PR.STATUS_LOCAL_DRAFTED.equals(pr.status())) {
            return pr;
        }
        Task task = tasks.findTaskById(pr.taskId()).orElse(null);
        if (task == null) {
            return prs.requestUserReview(prId, actor);
        }
        if (tasks.isV2Task(task.id())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "V2 Task review is owned by the typed Local Review runtime");
        }
        throw retired();
    }

    public boolean ownsParkedResume(String taskId)
    {
        return false;
    }

    public boolean pauseActiveReview(String taskId, String reason)
    {
        throw retired();
    }

    public boolean resumeParkedReview(String taskId)
    {
        throw retired();
    }

    public void reviewBeforeRoundGate(ReviewRound round, Task task)
    {
        throw retired();
    }

    public void recordVerdict(
            String taskId, String stageId, String agentRunId, String scope, String verdict)
    {
        throw retired();
    }

    private static ResponseStatusException retired()
    {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "LEGACY Brain review is read-only; use a typed V2 review control");
    }
}
