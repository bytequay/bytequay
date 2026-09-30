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
package com.bytequay.app.context;

import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.context.RegisteredContextSource;
import org.springframework.stereotype.Service;

import java.util.List;

import static java.util.Objects.requireNonNull;

/** Spec checks: which specifications a topic's code implements, and checks that reproduce each gap with a test. */
@Service
public class ContextSpecCheckService
{
    private final ContextLearningPlanStore plans;
    private final WorkspaceRepositoryService workspaces;
    private final GitCommandRunner git;

    public ContextSpecCheckService(ContextLearningPlanStore plans, WorkspaceRepositoryService workspaces, GitCommandRunner git)
    {
        this.plans = requireNonNull(plans, "plans is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.git = requireNonNull(git, "git is null");
    }

    /** The documents the topic's code links at the plan's baseline, and its approved documentation. A local read only. */
    public List<ContextSpecSource> sources(String workspaceId, String topicId)
    {
        ContextLearningPlan plan = plans.find(workspaceId).orElseThrow(() -> new IllegalArgumentException("This repository has no learning plan"));
        ContextLearningPlan.Topic topic = plan.topic(topicId);
        List<String> approved = plan.documentationSources().stream().filter(source -> topic.sourceIds().contains(source.id()))
                .map(RegisteredContextSource::url).toList();
        return ContextSpecSource.scan(git, workspaces.requireReadableRepository(workspaceId), plan.baselineSha(), topic.paths(), approved);
    }
}
