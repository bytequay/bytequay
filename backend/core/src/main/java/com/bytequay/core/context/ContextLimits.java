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

import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/** UTF-8 and collection limits shared by notebook values and their application boundary. */
public final class ContextLimits
{
    public static final int MAX_ENTRIES = 100;
    public static final int MAX_BODY_BYTES = 8 * 1024;
    public static final int MAX_PROSE_BYTES = 256 * 1024;
    public static final int MAX_NOTEBOOK_BYTES = 1024 * 1024;
    public static final int MAX_SOURCES = 8;
    public static final int MAX_SOURCE_READS = 128;

    private ContextLimits() {}

    public static void text(String value, String name, int maximumBytes)
    {
        checkText(value, name);
        checkArgument(bytes(value) <= maximumBytes, "%s exceeds %s UTF-8 bytes", name, maximumBytes);
    }

    public static int bytes(String value)
    {
        return requireNonNull(value, "value is null").getBytes(UTF_8).length;
    }

    public static void unique(Collection<?> values, String name, int maximum)
    {
        checkArgument(values.size() <= maximum, "%s exceeds %s items", name, maximum);
        checkArgument(values.stream().distinct().count() == values.size(), "%s contains duplicates", name);
    }

    /** Validates a component prefix; this does not authorize a filesystem read. */
    public static void pathPrefix(String path)
    {
        text(path, "path prefix", 1000);
        checkArgument(!path.startsWith("/") && !path.startsWith("-") && !path.contains("\\") && path.endsWith("/"), "Invalid relative directory prefix");
        checkArgument(Arrays.stream(path.substring(0, path.length() - 1).split("/", -1))
                .noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..")), "Invalid relative directory prefix");
        checkArgument(path.chars().noneMatch(Character::isISOControl), "Invalid directory character");
    }

    /** Checks source configuration only; DNS and redirect checks belong to actual source reads. */
    public static void documentationUrl(String url)
    {
        text(url, "documentation URL", 2048);
        URI uri = URI.create(url);
        checkArgument("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null, "Documentation must use a public HTTPS URL");
        checkArgument(uri.getFragment() == null && uri.getQuery() == null && (uri.getPort() == -1 || uri.getPort() == 443),
                "Documentation URL cannot contain a query, fragment, or custom port");
        checkArgument(uri.normalize().equals(uri) && !url.contains("%") && !url.contains("\\"), "Documentation URL must have a normalized unescaped path");
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        checkArgument(!host.equalsIgnoreCase("localhost") && !host.endsWith(".localhost") && !host.endsWith(".local")
                && !host.contains(":") && !host.matches("[0-9.]+") && host.contains("."), "Documentation must use a public hostname");
    }
}
