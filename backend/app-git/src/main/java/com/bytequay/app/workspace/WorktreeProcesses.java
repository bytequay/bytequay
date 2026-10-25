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

import com.bytequay.agent.core.TaskWorktree;

/** Stops what still runs in a task worktree, such as its editor, before the worktree is removed; the editor runtime sits above this package. */
public interface WorktreeProcesses
{
    void stopWorktree(TaskWorktree worktree);

    /** Called once the worktree is gone, so what the editor kept for it, such as its Java index, can go too. */
    void forgetWorktree(TaskWorktree worktree);
}
