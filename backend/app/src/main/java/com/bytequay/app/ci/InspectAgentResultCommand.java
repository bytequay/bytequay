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
package com.bytequay.app.ci;

import com.bytequay.agent.core.RepositoryIdentity;

import java.nio.file.Path;

import static com.bytequay.app.ci.CiActionsChecks.checkSha;
import static com.bytequay.app.ci.CiActionsChecks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record InspectAgentResultCommand(
        Path worktree,
        RepositoryIdentity repository,
        String headRef,
        String inputSha,
        String baseSha)
{
    public InspectAgentResultCommand
    {
        requireNonNull(worktree, "worktree is null");
        checkArgument(worktree.isAbsolute(), "worktree is not absolute");
        requireNonNull(repository, "repository is null");
        checkText(headRef, "headRef", 255);
        checkSha(inputSha, "inputSha");
        checkSha(baseSha, "baseSha");
    }
}
