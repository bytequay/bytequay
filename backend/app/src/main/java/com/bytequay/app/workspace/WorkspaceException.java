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
                "Workspace has an active cherry-pick execution.", true);
    }

    public static WorkspaceException invalidRepository(String message)
    {
        return new WorkspaceException(
                "REPOSITORY_ROUTING_INVALID", HttpStatus.UNPROCESSABLE_ENTITY,
                message, false);
    }

    public String code() { return code; }

    public HttpStatus status() { return status; }

    public boolean retryable() { return retryable; }
}
