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

import java.time.Instant;
import java.util.Optional;

/** A navigable task in the sidebar, with a deadline only after its work has settled. */
public record RunningTask(String id, String workspaceId, String title, String repository, Kind kind, String targetId,
        Status status, Instant startedAt, Optional<Instant> expiresAt)
{
    public enum Kind { TASK, REVIEW, CHERRY_PICK, CI_FIX }

    public enum Status { RUNNING, WAITING, COMPLETED, FAILED, STOPPED, BLOCKED }
}
