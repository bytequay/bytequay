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

import com.bytequay.agent.core.TaskId;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.hosting.HostingServer;

import java.nio.file.Path;

/** Every task-owned external write verifies its destination and reviews its exact payload when required, including resumed CI and replies. */
public interface PublicationPrivacy
{
    void checkGit(TaskId taskId, HostingServer server, Path worktree, String base, String head, RepoRef pushRepository, RepoRef targetRepository, String branch, String text);

    void checkText(TaskId taskId, HostingServer server, RepoRef repository, String text);
}
