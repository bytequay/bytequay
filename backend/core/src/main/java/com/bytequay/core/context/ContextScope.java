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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A stable knowledge topic; disabling it preserves its notes and their identity. */
public record ContextScope(String id, String title, ContextMatch match, boolean enabled)
{
    public ContextScope
    {
        ContextLimits.text(id, "scope id", 1100);
        ContextLimits.text(title, "scope title", 200);
        requireNonNull(match, "match is null");
        checkArgument(id.equals("project") == (match instanceof ContextMatch.Repository), "Only project is repository-wide");
        if (id.startsWith("path:")) {
            String prefix = id.substring("path:".length());
            ContextLimits.pathPrefix(prefix);
            checkArgument(match instanceof ContextMatch.Selected selected && selected.pathPrefixes().contains(prefix), "Path scope must retain its named prefix");
        }
    }

    public static ContextScope project()
    {
        return new ContextScope("project", "Project overview", new ContextMatch.Repository(), true);
    }
}
