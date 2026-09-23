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
package com.bytequay.app.context;

import com.bytequay.core.context.ContextLimits;

import static com.google.common.base.Preconditions.checkArgument;

/** UTF-8 excerpts for context metadata and source previews; complete note bodies are deferred instead. */
public final class ContextText
{
    private ContextText() {}

    public static String excerpt(String text, int maximumBytes)
    {
        checkArgument(maximumBytes >= 3, "Excerpt budget must include its truncation marker");
        if (ContextLimits.bytes(text) <= maximumBytes) {
            return text;
        }
        return prefix(text, maximumBytes - 3) + "…";
    }

    /** Returns only source characters, without adding an excerpt marker to retained evidence. */
    static String prefix(String text, int maximumBytes)
    {
        checkArgument(maximumBytes >= 0, "Prefix budget must not be negative");
        if (ContextLimits.bytes(text) <= maximumBytes) {
            return text;
        }
        int end = 0;
        int bytes = 0;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            int width = codePoint <= 0x7F ? 1 : codePoint <= 0x7FF ? 2 : codePoint <= 0xFFFF ? 3 : 4;
            if (bytes + width > maximumBytes) {
                break;
            }
            bytes += width;
            end += Character.charCount(codePoint);
        }
        return text.substring(0, end);
    }
}
