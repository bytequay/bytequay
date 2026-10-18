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

import com.bytequay.app.service.epic.EpicService;
import com.bytequay.app.service.epic.EpicView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/epics")
public class EpicController
{
    private final EpicService epics;

    public EpicController(EpicService epics)
    {
        this.epics = requireNonNull(epics, "epics is null");
    }

    @GetMapping
    public EpicView view(@PathVariable String workspaceId, @RequestParam String epicId)
    {
        return epics.view(workspaceId, epicId);
    }

    @PutMapping("/local/{itemId}")
    public void setLocalEpic(@PathVariable String workspaceId, @PathVariable String itemId, @RequestBody MarkLocalEpic request)
    {
        epics.setLocalEpic(workspaceId, itemId, request.isEpic());
    }

    public record MarkLocalEpic(boolean isEpic) {}

    @PostMapping("/children")
    public EpicView refreshChildren(@PathVariable String workspaceId, @RequestBody ReadChildren request)
    {
        checkArgument(request.epicId() != null, "Epic is required");
        return epics.refreshChildren(workspaceId, request.epicId(), request.connectionId());
    }

    @PostMapping("/sync")
    public EpicView sync(@PathVariable String workspaceId, @RequestBody EpicRequest request)
    {
        checkArgument(request.epicId() != null, "Epic is required");
        return epics.sync(workspaceId, request.epicId());
    }

    @PostMapping("/dismiss")
    public EpicView dismiss(@PathVariable String workspaceId, @RequestBody EpicRequest request)
    {
        checkArgument(request.epicId() != null, "Epic is required");
        return epics.dismiss(workspaceId, request.epicId());
    }

    @PostMapping("/propose")
    public EpicView propose(@PathVariable String workspaceId, @RequestBody ReadChildren request)
    {
        checkArgument(request.epicId() != null, "Epic is required");
        return epics.propose(workspaceId, request.epicId(), request.connectionId());
    }

    @PostMapping("/proposals/accept")
    public EpicView acceptProposal(@PathVariable String workspaceId, @RequestBody ProposalRequest request)
    {
        checkArgument(request.epicId() != null && request.proposalId() != null, "Epic and proposal are required");
        return epics.acceptProposal(workspaceId, request.epicId(), request.proposalId());
    }

    @PostMapping("/proposals/dismiss")
    public EpicView dismissProposal(@PathVariable String workspaceId, @RequestBody ProposalRequest request)
    {
        checkArgument(request.epicId() != null && request.proposalId() != null, "Epic and proposal are required");
        return epics.dismissProposal(workspaceId, request.epicId(), request.proposalId());
    }

    public record ProposalRequest(String epicId, String proposalId) {}

    @PostMapping("/conclude")
    public EpicView conclude(@PathVariable String workspaceId, @RequestBody Conclude request)
    {
        checkArgument(request.epicId() != null, "Epic is required");
        return epics.conclude(workspaceId, request.epicId(), request.outcome());
    }

    @GetMapping("/concluded")
    public List<String> concluded(@PathVariable String workspaceId)
    {
        return epics.concluded(workspaceId);
    }

    public record Conclude(String epicId, String outcome) {}

    /** A Jira Epic's children are read through the connection the view shows it with. */
    public record ReadChildren(String epicId, Optional<String> connectionId) {}

    public record EpicRequest(String epicId) {}
}
