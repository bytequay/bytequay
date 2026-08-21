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

import com.bytequay.core.domain.Workspace;

import java.nio.file.Path;

import static java.util.Objects.requireNonNull;

/** Executes the one authenticated Git write allowed by workspace delivery. */
interface WorkspaceBranchPusher
{
    Push pushBranch(Workspace workspace, Path worktree, String branch);

    record Push(String remote, String head, String sha)
    {
        public Push
        {
            requireNonNull(remote, "remote is null");
            requireNonNull(head, "head is null");
            requireNonNull(sha, "sha is null");
        }
    }
}
