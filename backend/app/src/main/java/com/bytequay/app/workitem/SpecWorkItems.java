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
package com.bytequay.app.workitem;

import com.bytequay.app.context.ContextSpecCheck;
import com.bytequay.app.context.ContextSpecCheckStore;
import com.bytequay.app.service.goal.WorkItemService;
import com.bytequay.app.service.goal.WorkItemView;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.store.WorkItemStore;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

@Service
public class SpecWorkItems
{
    private final WorkItemService work;
    private final WorkItemStore items;
    private final ContextSpecCheckStore checks;

    public SpecWorkItems(WorkItemService work, WorkItemStore items, ContextSpecCheckStore checks)
    {
        this.work = requireNonNull(work, "work is null");
        this.items = requireNonNull(items, "items is null");
        this.checks = requireNonNull(checks, "checks is null");
    }

    public WorkItemView save(String workspaceId, String checkId, String candidateId)
    {
        ContextSpecCheck check = checks.find(workspaceId, checkId).orElseThrow(() -> new IllegalArgumentException("Spec check not found"));
        return save(workspaceId, check.directory(), check.candidate(candidateId));
    }

    public WorkItemView save(String workspaceId, String directory, ContextSpecCheck.Candidate candidate)
    {
        checkArgument(candidate.status() == ContextSpecCheck.Status.CONFIRMED && candidate.draft().isPresent(), "Only confirmed findings become work items");
        String id = identity(workspaceId, directory, candidate);
        if (items.find(id).isPresent()) {
            return work.list(workspaceId).stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
        }
        ContextSpecCheck.Draft draft = candidate.draft().orElseThrow();
        return work.create(workspaceId, id, Optional.empty(), draft.title(), WorkItem.Kind.DEVELOP, draft.body(), WorkItem.CompletionRule.MERGED_DELIVERY);
    }

    public static String identity(String workspaceId, String directory, ContextSpecCheck.Candidate candidate)
    {
        String evidence = "spec-work:" + workspaceId + ":" + directory + ":" + candidate.specUrl() + "\n" + candidate.rule() + "\n" + candidate.code();
        return UUID.nameUUIDFromBytes(evidence.getBytes(UTF_8)).toString();
    }
}
