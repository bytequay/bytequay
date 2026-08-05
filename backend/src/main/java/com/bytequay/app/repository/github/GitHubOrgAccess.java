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
package com.bytequay.app.repository.github;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers GitHub orgs that reject the configured token outright, so we stop
 * re-asking. An org with "classic PAT access" disabled answers every REST and
 * GraphQL call with the same 403 — the failure is permanent for that token, and
 * without this the dashboard sync re-hits it for every watched PR on every
 * cycle, burning rate limit and filling the log with identical warnings.
 *
 * <p>Keyed by org + token, so pasting a new PAT in Settings clears the block
 * without a restart.
 */
public final class GitHubOrgAccess
{
    private static final Logger log = LoggerFactory.getLogger(GitHubOrgAccess.class);
    /** GitHub's wording when an org policy forbids classic PATs. Matched as a
     *  substring: the full message names the org and links its docs. */
    private static final String CLASSIC_PAT_DENIAL = "forbids access via a personal access token";

    private final Set<String> denied = ConcurrentHashMap.newKeySet();

    /**
     * Whether this text is GitHub's classic-PAT denial — matches both a raw
     * error body and the message we rethrow it under, so log sites downstream
     * can stay quiet about a failure this class already reported once.
     */
    public static boolean isClassicPatDenial(String text)
    {
        return text != null && text.contains(CLASSIC_PAT_DENIAL);
    }

    /**
     * Stable short id for a token, so the block lifts when the token changes.
     * Accepts a bare PAT or an {@code Authorization} header value.
     */
    // ponytail: hashCode, not a real digest — a collision would only keep a
    // rotated token blocked until restart, and there is one token in play.
    public static String tokenId(String token)
    {
        if (token == null || token.isBlank()) {
            return "none";
        }
        String bare = token;
        for (String scheme : new String[] {"Bearer ", "token "}) {
            if (bare.startsWith(scheme)) {
                bare = bare.substring(scheme.length());
            }
        }
        return Integer.toHexString(bare.strip().hashCode());
    }

    public boolean isDenied(String owner, String tokenId)
    {
        return owner != null && denied.contains(key(owner, tokenId));
    }

    /** Records the block if {@code responseBody} is the classic-PAT denial. */
    public void recordIfClassicPatDenial(String owner, String tokenId, String responseBody)
    {
        if (owner == null || !isClassicPatDenial(responseBody)) {
            return;
        }
        if (denied.add(key(owner, tokenId))) {
            log.warn("{} rejects the configured GitHub token (its org policy forbids classic personal access "
                            + "tokens), so its GitHub API calls will be skipped until the token changes. "
                            + "Fix: replace the token with a fine-grained PAT or GitHub App token in Settings.",
                    owner);
        }
    }

    private static String key(String owner, String tokenId)
    {
        return owner.toLowerCase(Locale.ROOT) + "@" + tokenId;
    }
}
