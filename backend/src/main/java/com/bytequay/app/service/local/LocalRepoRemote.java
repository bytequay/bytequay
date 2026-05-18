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
package com.bytequay.app.service.local;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class LocalRepoRemote
{
    private static final String GITHUB_URL_MARKER = "github.com/";
    private static final String GITHUB_SSH_PREFIX = "git@github.com:";

    private LocalRepoRemote() {}

    /**
     * Picks the remote that ByteQuay should treat as the watched repo
     * remote. Direct clones return null; fork-based clones return the
     * matching non-origin remote name.
     */
    static String pickUpstreamRemoteName(List<GitRunner.Remote> remotes, String owner, String repo)
    {
        GitRunner.Remote origin = remotes.stream()
                .filter(remote -> "origin".equals(remote.name()))
                .findFirst()
                .orElse(null);
        if (origin != null && remoteMatchesRepo(origin.url(), owner, repo)) {
            return null;
        }
        return remotes.stream()
                .filter(remote -> remoteMatchesRepo(remote.url(), owner, repo))
                .map(GitRunner.Remote::name)
                .findFirst()
                .orElse(null);
    }

    /**
     * Strips embedded credentials from a git URL so PATs or
     * username/password pairs do not leak into user-visible errors.
     */
    static String redactCredentials(String url)
    {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return url;
        }
        int at = url.indexOf('@', schemeEnd + 3);
        if (at < 0) {
            return url;
        }
        return url.substring(0, schemeEnd + 3) + url.substring(at + 1);
    }

    /**
     * Pulls the GitHub owner segment out of HTTPS/git/SSH remote URLs.
     * Returns null for non-github.com remotes.
     */
    static String parseGithubOwner(String url)
    {
        return parseGithubRemote(url)
                .map(GitHubRemote::owner)
                .orElse(null);
    }

    /**
     * True when {@code remoteUrl} points exactly at github.com/{owner}/{repo}.
     */
    static boolean remoteMatchesRepo(String remoteUrl, String owner, String repo)
    {
        return parseGithubRemote(remoteUrl)
                .map(remote -> remote.owner().equalsIgnoreCase(owner) && remote.repo().equalsIgnoreCase(repo))
                .orElse(false);
    }

    private static Optional<GitHubRemote> parseGithubRemote(String url)
    {
        String cleaned = url == null ? "" : url.trim();
        if (cleaned.endsWith(".git")) {
            cleaned = cleaned.substring(0, cleaned.length() - 4);
        }

        String path;
        if (cleaned.startsWith(GITHUB_SSH_PREFIX)) {
            path = cleaned.substring(GITHUB_SSH_PREFIX.length());
        }
        else {
            int marker = cleaned.toLowerCase(Locale.ROOT).indexOf(GITHUB_URL_MARKER);
            if (marker < 0) {
                return Optional.empty();
            }
            path = cleaned.substring(marker + GITHUB_URL_MARKER.length());
        }

        int slash = path.indexOf('/');
        if (slash <= 0 || slash == path.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(new GitHubRemote(path.substring(0, slash), path.substring(slash + 1)));
    }

    private record GitHubRemote(String owner, String repo) {}
}
