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
package com.bytequay.core.context;

import com.google.common.hash.Hashing;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;

/** Exact prepared reference text; storage alone does not establish provider submission or acceptance. */
public record ContextPacket(String text, String digest, int utf8Bytes)
{
    public static final int MAXIMUM_BYTES = 32 * 1024;

    public ContextPacket
    {
        ContextLimits.text(text, "context packet", MAXIMUM_BYTES);
        checkArgument(utf8Bytes == ContextLimits.bytes(text), "Context packet byte count does not match its text");
        checkArgument(digest(text).equals(digest), "Context packet digest does not match its text");
    }

    /** Computes the size and digest from the actual packet, without trusting supplied metadata. */
    public static ContextPacket create(String text)
    {
        return new ContextPacket(text, digest(text), ContextLimits.bytes(text));
    }

    private static String digest(String text)
    {
        return "sha256:" + Hashing.sha256().hashString(text, UTF_8);
    }
}
