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
import com.bytequay.app.cherrypick.MergedPullRequestResolver;
import com.bytequay.app.cherrypick.PreviewCherryPickRangeCommand;
import com.bytequay.app.workspace.WorkspaceException;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.cherrypick.MergedPullRequestRange;
import com.bytequay.core.domain.PullRequestRef;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    private final MergedPullRequestResolver mergedPullRequests;

    public CherryPickPreviewController(WorkspaceRepositoryService workspaces, CherryPickPreviewService previewService, MergedPullRequestResolver mergedPullRequests)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.previewService = requireNonNull(previewService, "previewService is null");
        this.mergedPullRequests = requireNonNull(mergedPullRequests, "mergedPullRequests is null");
    }

    @PostMapping("/api/workspaces/{workspaceId}/cherry-pick/preview")
    @ResponseStatus(HttpStatus.CREATED)
    public CherryPickPreviewResponse create(@PathVariable String workspaceId, @RequestBody CherryPickPreviewRequest request)
    {
        WorkspaceRepositoryContext context = workspaces.requireReadyRepository(workspaceId);
        String sourceRemote = sourceRemote(context, request.sourceRemote());
        CherryPickPreview preview = previewService.preview(new PreviewCherryPickRangeCommand(
                context.repository(),
                workspaceId,
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

    /** Proves the pull request's landed commits in the source remote, then previews exactly those. */
    @PostMapping("/api/workspaces/{workspaceId}/cherry-pick/preview-pull-request")
    @ResponseStatus(HttpStatus.CREATED)
    public CherryPickPreviewResponse createFromPullRequest(@PathVariable String workspaceId, @RequestBody CherryPickPullRequestPreviewRequest request)
    {
        if (request.owner() == null || request.owner().isBlank() || request.repo() == null || request.repo().isBlank() || request.number() <= 0
                || request.targetBranch() == null || request.targetBranch().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a pull request and a target branch");
        }
        WorkspaceRepositoryContext context = workspaces.requireReadyRepository(workspaceId);
        String sourceRemote = sourceRemote(context, request.sourceRemote());
        MergedPullRequestRange range = mergedPullRequests.resolve(workspaceId, sourceRemote, PullRequestRef.of(request.owner(), request.repo(), request.number()));
        CherryPickPreview preview = previewService.preview(new PreviewCherryPickRangeCommand(
                context.repository(),
                workspaceId,
                context.sourceRepositoryId(sourceRemote),
                sourceRemote,
                range.sourceBaseSha(),
                range.mergeSha(),
                context.sourceRemote(),
                request.targetBranch()), range);
        return CherryPickPreviewResponse.of(preview);
    }

    private static String sourceRemote(WorkspaceRepositoryContext context, String requested)
    {
        boolean explicitSource = requested != null && !requested.isBlank();
        if (!explicitSource && context.sourceRemote().equals(context.targetRemote())) {
            throw WorkspaceException.invalidRemoteSource("Link an upstream repository in Related workspaces before starting a cherry-pick.");
        }
        String sourceRemote = explicitSource ? requested : context.sourceRemote();
        if (!context.knownRemote(sourceRemote)) {
            throw WorkspaceException.invalidRemoteSource("That repository is not a source for this workspace yet. Sync, then try again.");
        }
        if (sourceRemote.equals(context.targetRemote())) {
            throw WorkspaceException.invalidRemoteSource("Choose a source other than this workspace's origin.");
        }
        return sourceRemote;
    }
}
