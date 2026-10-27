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

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Coordinate validation shared by persisted identities and remote connection setup. */
public final class HostingUrls
{
    private static final Pattern SCP_CLONE = Pattern.compile("[A-Za-z0-9._-]+@(?:[A-Za-z0-9.-]+|\\[[0-9A-Fa-f:]+]):[^\\s]+\\z");

    private HostingUrls() {}

    public static URI canonicalBaseUrl(URI value)
    {
        return URI.create(canonicalHttpUrl(value).toASCIIString().replaceFirst("/+$", ""));
    }

    public static URI canonicalHttpUrl(URI value)
    {
        requireNonNull(value, "URL is null");
        checkArgument(value.isAbsolute() && !value.isOpaque() && value.getHost() != null, "URL must have an absolute host");
        checkArgument(value.getRawUserInfo() == null && value.getRawQuery() == null && value.getRawFragment() == null, "URL must not contain credentials, query, or fragment");
        String scheme = value.getScheme().toLowerCase(Locale.ROOT);
        String host = value.getHost().toLowerCase(Locale.ROOT);
        checkArgument(scheme.equals("https") || (scheme.equals("http") && isLoopback(host)), "URL requires HTTPS except for loopback hosts");
        checkArgument(value.getPort() == -1 || (value.getPort() > 0 && value.getPort() <= 65535), "URL port is invalid");
        String path = value.getRawPath();
        String decodedPath = value.getPath();
        checkArgument(!decodedPath.contains("\\") && decodedPath.codePoints().noneMatch(Character::isISOControl), "URL path contains unsafe characters");
        checkArgument(!path.toLowerCase(Locale.ROOT).contains("%2f") && !path.toLowerCase(Locale.ROOT).contains("%5c"), "URL path contains an encoded separator");
        for (String segment : decodedPath.split("/", -1)) {
            checkArgument(!segment.equals(".") && !segment.equals(".."), "URL path contains traversal");
        }
        boolean defaultPort = value.getPort() == (scheme.equals("https") ? 443 : 80);
        String port = value.getPort() == -1 || defaultPort ? "" : ":" + value.getPort();
        return URI.create(scheme + "://" + host + port + path);
    }

    public static URI requireWithinBase(URI value, URI base)
    {
        URI checked = canonicalHttpUrl(value);
        URI checkedBase = canonicalBaseUrl(base);
        checkArgument(checked.getScheme().equals(checkedBase.getScheme()) && checked.getHost().equals(checkedBase.getHost()) &&
                        checked.getPort() == checkedBase.getPort(),
                "Repository URL must use the configured instance origin");
        String basePath = checkedBase.getPath();
        checkArgument(checked.getPath().equals(basePath) || checked.getPath().startsWith(basePath + "/"), "Repository URL must stay within the configured instance path");
        return checked;
    }

    public static void checkRepositoryPath(String value)
    {
        checkText(value, "fullPath");
        checkArgument(!value.contains("\\") && !value.contains("?") && !value.contains("#") && value.codePoints().noneMatch(Character::isISOControl),
                "fullPath contains unsafe characters");
        for (String segment : value.split("/", -1)) {
            checkArgument(!segment.isBlank() && !segment.equals(".") && !segment.equals(".."), "fullPath contains an invalid segment");
        }
    }

    public static void checkSshCloneUrl(String value)
    {
        checkText(value, "sshCloneUrl");
        checkArgument(value.codePoints().noneMatch(character -> Character.isWhitespace(character) || Character.isISOControl(character)), "SSH URL contains whitespace");
        if (!value.startsWith("ssh://")) {
            checkArgument(SCP_CLONE.matcher(value).matches(), "SSH URL must use ssh:// or user@host:path syntax");
            return;
        }
        URI uri = URI.create(value);
        checkArgument(uri.getHost() != null && uri.getRawQuery() == null && uri.getRawFragment() == null && !uri.getPath().isBlank(), "SSH URL is invalid");
        checkArgument(uri.getPort() == -1 || (uri.getPort() > 0 && uri.getPort() <= 65535), "SSH URL port is invalid");
        checkArgument(uri.getUserInfo() == null || !uri.getUserInfo().contains(":"), "SSH URL must not contain a password");
    }

    private static boolean isLoopback(String host)
    {
        if (host.equals("localhost") || host.equals("[::1]") || host.equals("[0:0:0:0:0:0:0:1]")) {
            return true;
        }
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4 || !octets[0].equals("127")) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3 || !octet.chars().allMatch(Character::isDigit) || Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }
}
