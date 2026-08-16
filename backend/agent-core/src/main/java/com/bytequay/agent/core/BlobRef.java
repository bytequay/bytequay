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

import java.nio.file.Path;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;

public record BlobRef(String relativePath, String mediaType, long size)
{
    public BlobRef
    {
        checkNotBlank(relativePath, "relativePath");
        checkNotBlank(mediaType, "mediaType");
        Path path = Path.of(relativePath);
        if (path.isAbsolute() || path.normalize().startsWith("..")) {
            throw new IllegalArgumentException("relativePath is unsafe");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size is negative");
        }
    }
}
