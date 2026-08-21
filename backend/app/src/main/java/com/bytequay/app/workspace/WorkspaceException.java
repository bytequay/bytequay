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
package com.bytequay.app.workspace;

import org.springframework.http.HttpStatus;

public final class WorkspaceException
        extends RuntimeException
{
    private final String code;
    private final HttpStatus status;
    private final boolean retryable;

    private WorkspaceException(String code, HttpStatus status, String message, boolean retryable)
    {
        super(message);
        this.code = code;
        this.status = status;
        this.retryable = retryable;
    }

    public static WorkspaceException notFound(String workspaceId)
    {
        return new WorkspaceException(
                "WORKSPACE_NOT_FOUND", HttpStatus.NOT_FOUND,
                "Workspace not found: " + workspaceId, false);
    }

    public static WorkspaceException repositoryNotFound(String owner, String repo)
    {
        return new WorkspaceException(
                "REPOSITORY_NOT_FOUND", HttpStatus.NOT_FOUND,
                "Repository is no longer visible: " + owner + "/" + repo, false);
    }

    public static WorkspaceException repositoryNotFound(long repositoryId)
    {
        return new WorkspaceException(
                "REPOSITORY_NOT_FOUND", HttpStatus.NOT_FOUND,
                "Repository is no longer visible: " + repositoryId, false);
    }

    public static WorkspaceException previewChanged()
    {
        return new WorkspaceException(
                "WATCH_PREVIEW_CHANGED", HttpStatus.CONFLICT,
                "Repository routing or permissions changed. Review the updated preview.", true);
    }

    public static WorkspaceException repositoryNotReady()
    {
        return new WorkspaceException(
                "WORKSPACE_CODE_NOT_READY", HttpStatus.CONFLICT,
                "Repository clone is not ready.", true);
    }

    public static WorkspaceException busy()
    {
        return new WorkspaceException(
                "WORKSPACE_BUSY", HttpStatus.CONFLICT,
                "Workspace has active synchronization or cherry-pick work.", true);
    }

    public static WorkspaceException invalidRepository(String message)
    {
        return new WorkspaceException(
                "REPOSITORY_ROUTING_INVALID", HttpStatus.UNPROCESSABLE_ENTITY,
                message, false);
    }

    public static WorkspaceException issueSyncFailed(String reference)
    {
        return new WorkspaceException(
                "ISSUE_SYNC_FAILED", HttpStatus.BAD_GATEWAY,
                "Issue refresh failed. Retry later. Reference: " + reference, true);
    }

    public static WorkspaceException deliveryConfirmationRequired()
    {
        return new WorkspaceException(
                "DELIVERY_CONFIRMATION_REQUIRED", HttpStatus.BAD_REQUEST,
                "Confirm this remote write before continuing.", false);
    }

    public static WorkspaceException deliveryUnavailable(String message)
    {
        return new WorkspaceException(
                "WORKSPACE_DELIVERY_UNAVAILABLE", HttpStatus.CONFLICT,
                message, false);
    }

    public static WorkspaceException pushNotWritable()
    {
        return new WorkspaceException(
                "WORKSPACE_PUSH_NOT_WRITABLE", HttpStatus.FORBIDDEN,
                "The selected repository is read-only for the configured GitHub account. "
                        + "Grant write access or watch a writable fork; ByteQuay will not choose another remote.",
                false);
    }

    public static WorkspaceException deliveryFailed(String message)
    {
        return new WorkspaceException(
                "WORKSPACE_DELIVERY_FAILED", HttpStatus.BAD_GATEWAY,
                message, true);
    }

    public String code() { return code; }

    public HttpStatus status() { return status; }

    public boolean retryable() { return retryable; }
}
