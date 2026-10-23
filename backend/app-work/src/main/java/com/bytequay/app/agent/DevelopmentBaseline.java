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
package com.bytequay.app.agent;

import com.bytequay.agent.core.TaskWorktree;

import static java.util.Objects.requireNonNull;

/** Local facts and publication routing frozen before an accepted user turn starts. */
public record DevelopmentBaseline(TaskWorktree worktree, DevelopmentPublicationRoute route, DevelopmentLocalFacts local)
{
    public DevelopmentBaseline
    {
        requireNonNull(worktree, "worktree is null");
        requireNonNull(route, "route is null");
        requireNonNull(local, "local is null");
    }
}
