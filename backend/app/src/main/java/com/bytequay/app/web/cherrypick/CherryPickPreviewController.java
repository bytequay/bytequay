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
package com.bytequay.app.web.cherrypick;

import com.bytequay.app.cherrypick.CherryPickPreview;
import com.bytequay.app.cherrypick.CherryPickPreviewService;
import com.bytequay.app.cherrypick.PreviewCherryPickRangeCommand;
import com.bytequay.app.workspace.WorkspaceException;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Read-only preview endpoints.
 *
 * <p>Protected by the desktop session filter, like every other local agent
 * surface.
 */
@RestController
public class CherryPickPreviewController
{
    private final WorkspaceRepositoryService workspaces;
    private final CherryPickPreviewService previewService;

    public CherryPickPreviewController(
            WorkspaceRepositoryService workspaces,
            CherryPickPreviewService previewService)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.previewService = requireNonNull(previewService, "previewService is null");
    }

    @PostMapping("/api/workspaces/{workspaceId}/cherry-pick/preview")
    @ResponseStatus(HttpStatus.CREATED)
    public CherryPickPreviewResponse create(
            @PathVariable String workspaceId,
            @RequestBody CherryPickPreviewRequest request)
    {
        WorkspaceRepositoryContext context = workspaces.requireReadyRepository(workspaceId);
        boolean explicitSource = request.sourceRemote() != null && !request.sourceRemote().isBlank();
        if (!explicitSource && context.sourceRemote().equals(context.targetRemote())) {
            throw WorkspaceException.invalidRemoteSource(
                    "Add a remote source before starting a cherry-pick.");
        }
        String sourceRemote = explicitSource ? request.sourceRemote() : context.sourceRemote();
        if (!context.knownRemote(sourceRemote)) {
            throw WorkspaceException.invalidRemoteSource(
                    "The remote source is not configured for this workspace.");
        }
        if (sourceRemote.equals(context.targetRemote())) {
            throw WorkspaceException.invalidRemoteSource(
                    "Choose a remote source other than origin.");
        }
        CherryPickPreview preview = previewService.preview(new PreviewCherryPickRangeCommand(
                context.repository(),
                Optional.of(workspaceId),
                context.sourceRepositoryId(sourceRemote),
                sourceRemote,
                request.sourceBaseRef(),
                request.sourceTipRef(),
                // The target branch tracks the workspace's development base:
                // the linked upstream when one exists, otherwise origin.
                context.sourceRemote(),
                request.targetBranch()));
        return CherryPickPreviewResponse.of(preview);
    }

    @GetMapping("/api/cherry-pick-ranges/previews/{previewId}")
    public CherryPickPreviewResponse get(@PathVariable String previewId)
    {
        return previewService.find(previewId)
                .map(CherryPickPreviewResponse::of)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Preview is unknown or expired; preview the range again"));
    }
}
