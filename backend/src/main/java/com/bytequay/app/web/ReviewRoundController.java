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
package com.bytequay.app.web;

import com.bytequay.app.developmentflow.stage.V2RemoteFeedbackControlService;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.review.ReviewRoundService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Read-only listing plus the one write: approving a round's gate (posts
 * drafted replies + pushes commits, user-gated — same pattern as push/merge
 * in {@code PRController}).
 */
@RestController
public class ReviewRoundController
{
    private final ReviewRoundService rounds;
    private final V2RemoteFeedbackControlService v2Rounds;
    private final TaskStore tasks;

    public ReviewRoundController(
            ReviewRoundService rounds,
            V2RemoteFeedbackControlService v2Rounds,
            TaskStore tasks)
    {
        this.rounds = requireNonNull(rounds, "rounds is null");
        this.v2Rounds = requireNonNull(v2Rounds, "v2Rounds is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
    }

    @GetMapping("/api/tasks/{taskId}/rounds")
    public List<ReviewRound> roundsForTask(@PathVariable String taskId)
    {
        String route = tasks.findWorkflowVersion(taskId).orElse(null);
        if ("V2".equals(route)) {
            return v2Rounds.findByTask(taskId);
        }
        if (route != null && !"LEGACY".equals(route)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "unsupported Task workflow version " + route);
        }
        return rounds.findByTask(taskId);
    }

    @PostMapping("/api/rounds/{roundId}/approve")
    public ReviewRound approve(@PathVariable String roundId)
    {
        var v2TaskId = v2Rounds.findTaskId(roundId);
        if (v2TaskId.isPresent()) {
            String route = tasks.findWorkflowVersion(v2TaskId.orElseThrow())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "V2 Remote feedback batch has no immutable Task route"));
            if (!"V2".equals(route)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "unsupported Task workflow version " + route);
            }
            return v2Rounds.approve(roundId);
        }
        return rounds.approve(roundId);
    }
}
