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

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static java.util.Objects.requireNonNull;

/**
 * Bytes the trusted Electron main process selected for a task, with the name and media type it
 * claims for them. The claim is verified against the bytes before anything is stored.
 */
public record StoreAgentAttachmentCommand(TaskId taskId, String name, String mediaType, byte[] bytes)
{
    public StoreAgentAttachmentCommand
    {
        requireNonNull(taskId, "taskId is null");
        checkNotBlank(name, "name");
        checkNotBlank(mediaType, "mediaType");
        requireNonNull(bytes, "bytes is null");
    }
}
