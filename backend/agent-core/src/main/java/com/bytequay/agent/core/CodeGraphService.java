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
package com.bytequay.agent.core;

/**
 * Optional structural code exploration for one managed task worktree.
 *
 * <p>The index is derived cache under the task runtime. Every operation binds to the persisted
 * canonical worktree; nothing here accepts a model- or renderer-supplied path.
 */
public interface CodeGraphService
{
    /**
     * Brings the task index up to date with the worktree, building it when absent, and runs one
     * bounded structural query.
     *
     * @return capped result text that says when it was truncated
     * @throws CodeGraphException when the helper is unavailable or the index cannot be brought current
     */
    String explore(TaskWorktree worktree, TaskResources resources, String query);

    /** Deletes the task's derived index; a missing index is not an error. */
    void deleteIndex(TaskResources resources);
}
