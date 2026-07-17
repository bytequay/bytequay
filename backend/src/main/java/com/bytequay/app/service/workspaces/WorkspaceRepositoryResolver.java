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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.domain.WorkspaceRepo;
import org.springframework.stereotype.Component;

import java.util.List;

import static java.util.Objects.requireNonNull;

/** The sole repository identity behind every workspace-scoped façade. */
@Component
public class WorkspaceRepositoryResolver
{
    private final WorkspaceService workspaces;

    public WorkspaceRepositoryResolver(WorkspaceService workspaces)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
    }

    public RepositoryIdentity resolve(String workspaceId)
    {
        workspaces.require(workspaceId);
        List<WorkspaceRepo> rows = workspaces.listRepos(workspaceId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "workspace must own exactly one repository: " + workspaceId);
        }
        String fullName = rows.getFirst().repoFullName();
        int slash = fullName.indexOf('/');
        if (slash < 1 || slash == fullName.length() - 1) {
            throw new IllegalStateException(
                    "invalid workspace repository: " + fullName);
        }
        return new RepositoryIdentity(
                fullName.substring(0, slash),
                fullName.substring(slash + 1),
                fullName,
                rows.getFirst().defaultBaseBranch());
    }

    public record RepositoryIdentity(
            String owner,
            String repo,
            String fullName,
            String defaultBaseBranch) {}
}
