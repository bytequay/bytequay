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

import com.bytequay.app.service.goal.GoalService;
import com.bytequay.app.workitem.GoalItemView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static java.util.Objects.requireNonNull;

/** The add-existing-work search. Epics use it in every build, so it stays outside the Goals experiment's controller. */
@RestController
public class WorkCandidateController
{
    private final GoalService goals;

    public WorkCandidateController(GoalService goals)
    {
        this.goals = requireNonNull(goals, "goals is null");
    }

    @GetMapping("/api/workspaces/{workspaceId}/goals/candidates")
    public List<GoalItemView> candidates(@PathVariable String workspaceId, @RequestParam(defaultValue = "") String q)
    {
        return goals.candidates(workspaceId, q);
    }
}
