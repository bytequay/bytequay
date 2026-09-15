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

/** A configured documentation location, never proof that its content was retrieved. */
public record RegisteredContextSource(String id, Kind kind, String url, ContextEntryOrigin origin)
{
    public RegisteredContextSource
    {
        ContextLimits.text(id, "source id", 200);
        requireNonNull(kind, "kind is null");
        requireNonNull(origin, "origin is null");
        ContextLimits.documentationUrl(url);
        checkArgument(kind != Kind.DOCUMENTATION_ROOT || url.endsWith("/"), "Documentation root must end with a slash");
    }

    public enum Kind
    {
        DOCUMENTATION_ROOT,
        DOCUMENTATION_PAGE
    }
}
