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

import com.bytequay.agent.spi.AgentAttachment;

import java.nio.file.Path;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A verified, task-owned attachment: its facts, and the blob path only the provider input may use. */
public record StoredAgentAttachment(
        String id,
        String name,
        String mediaType,
        long byteSize,
        int width,
        int height,
        Path path)
{
    public StoredAgentAttachment
    {
        checkNotBlank(id, "id");
        checkNotBlank(name, "name");
        checkNotBlank(mediaType, "mediaType");
        checkArgument(byteSize > 0, "byteSize must be positive");
        checkArgument(width > 0 && height > 0, "dimensions must be positive");
        requireNonNull(path, "path is null");
        checkArgument(path.isAbsolute(), "path is not absolute");
    }

    public AgentAttachmentReference reference()
    {
        return new AgentAttachmentReference(id, name, mediaType);
    }

    public AgentAttachment input()
    {
        return new AgentAttachment(id, name, mediaType, path);
    }
}
