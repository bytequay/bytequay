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

import com.bytequay.app.service.learning.ProjectLearningRun;
import com.bytequay.app.service.learning.ProjectLearningService;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static java.util.Objects.requireNonNull;

/**
 * Project-learning run state and controls for the workspace onboarding card:
 * Pause, Resume, and Retry-failed-sources. Counts (cataloged / analyzed /
 * lessons / proposals) ride in the run's counts JSON.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/learning")
public class WorkspaceLearningController
{
    private final ProjectLearningService learning;
    private final WorkspaceRepositoryResolver repositories;

    public WorkspaceLearningController(
            ProjectLearningService learning,
            WorkspaceRepositoryResolver repositories)
    {
        this.learning = requireNonNull(learning, "learning is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
    }

    /** Learning-run state DTO; countsJson is the run's live counter map. */
    public record LearningRunDto(
            String id,
            String repo,
            String state,
            String countsJson,
            String lastError,
            long updatedAt)
    {
        static LearningRunDto from(ProjectLearningRun run)
        {
            return new LearningRunDto(run.id(), run.repo(), run.state(),
                    run.countsJson(), run.lastError(), run.updatedAtMs());
        }
    }

    @GetMapping
    public LearningRunDto state(@PathVariable String workspaceId)
    {
        return LearningRunDto.from(requireRun(workspaceId));
    }

    @PostMapping("/pause")
    public LearningRunDto pause(@PathVariable String workspaceId)
    {
        ProjectLearningRun run = requireRun(workspaceId);
        return learning.pause(run.workspaceId(), run.repo())
                .map(LearningRunDto::from)
                .orElseThrow(() -> notFound(workspaceId));
    }

    @PostMapping("/resume")
    public LearningRunDto resume(@PathVariable String workspaceId)
    {
        return retry(workspaceId);
    }

    @PostMapping("/retry")
    public LearningRunDto retry(@PathVariable String workspaceId)
    {
        ProjectLearningRun run = requireRun(workspaceId);
        return learning.retry(run.id())
                .map(LearningRunDto::from)
                .orElseThrow(() -> notFound(workspaceId));
    }

    private ProjectLearningRun requireRun(String workspaceId)
    {
        String repo = repositories.resolve(workspaceId).fullName();
        return learning.latest(workspaceId, repo)
                .orElseThrow(() -> notFound(workspaceId));
    }

    private static ResponseStatusException notFound(String workspaceId)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(404),
                "no learning run for workspace " + workspaceId);
    }
}
