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
package com.bytequay.core.hosting;

import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;

/** A hosting server's release, such as GitLab 14.10.5, compared by its numbers only. */
public record HostingVersion(int major, int minor, int patch)
        implements Comparable<HostingVersion>
{
    private static final Pattern RELEASE = Pattern.compile("(\\d{1,4})\\.(\\d{1,4})(?:\\.(\\d{1,6}))?(?:[-+.].*)?");
    private static final Comparator<HostingVersion> ORDER = Comparator.comparingInt(HostingVersion::major)
            .thenComparingInt(HostingVersion::minor)
            .thenComparingInt(HostingVersion::patch);

    public HostingVersion
    {
        checkArgument(major >= 0 && minor >= 0 && patch >= 0, "version numbers must not be negative");
    }

    public static HostingVersion of(int major, int minor)
    {
        return new HostingVersion(major, minor, 0);
    }

    /** Reads a release such as {@code 14.10.5}, {@code 16.11.0-pre} or {@code 19.6.0-ee}; anything else is unknown. */
    public static Optional<HostingVersion> parse(String text)
    {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = RELEASE.matcher(text.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int patch = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        return Optional.of(new HostingVersion(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), patch));
    }

    public boolean isAtLeast(HostingVersion other)
    {
        return compareTo(other) >= 0;
    }

    @Override
    public int compareTo(HostingVersion other)
    {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString()
    {
        return major + "." + minor + "." + patch;
    }
}
