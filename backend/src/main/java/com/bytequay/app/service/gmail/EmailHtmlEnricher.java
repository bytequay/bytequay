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
package com.bytequay.app.service.gmail;

import com.bytequay.app.domain.LinkedRef;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites email HTML so each {@code github.com/.../commit/SHA} link
 * gets a small inline "↗ ByteQuay" button next to it that opens the
 * PR diff page inside the app.
 *
 * <p>The button's {@code href} uses the {@code bytequay://} URL
 * scheme; {@code main.ts}'s {@code will-navigate} handler intercepts
 * it (the email iframe runs with {@code <base target="_top">}, so a
 * click navigates the top frame) and forwards the action to the
 * renderer over IPC.
 *
 * <p>A commit only gets enriched when the same email already links a
 * PR in the same repo — that PR number is what scoping the diff page
 * needs. Notification emails for "new commit on PR #N" always satisfy
 * this; one-off "FYI here's a commit" emails don't, and we leave
 * those alone rather than guess.
 */
@Component
public class EmailHtmlEnricher
{
    /** {@code <a … href="https://github.com/OWNER/REPO/commit/SHA…">…</a>}.
     *  Captures owner / repo / SHA. The lazy {@code .*?} on the body
     *  with {@link Pattern#DOTALL} lets the link span multiple lines
     *  (Gmail wraps long commit-message previews). */
    private static final Pattern COMMIT_ANCHOR = Pattern.compile(
            "<a\\b[^>]*href=\"https://github\\.com/([\\w.-]+)/([\\w.-]+)/commit/([a-f0-9]{7,40})[^\"]*\"[^>]*>.*?</a>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** Inline-styled because the iframe sandbox isolates from app CSS,
     *  and we don't ship a stylesheet alongside the email body. */
    private static final String BUTTON_TEMPLATE =
            "<a href=\"bytequay://pr-diff?owner=%s&repo=%s&pr=%s&sha=%s\""
                    + " style=\"display:inline-block;margin-left:6px;padding:1px 7px;"
                    + "border:1px solid #7C3AED;border-radius:4px;font-size:11px;"
                    + "line-height:1.4;color:#7C3AED;text-decoration:none;"
                    + "font-family:-apple-system,BlinkMacSystemFont,sans-serif;"
                    + "vertical-align:baseline;\">"
                    + "&#8599;&nbsp;ByteQuay</a>";

    public String enrich(String html, List<LinkedRef> linkedRefs)
    {
        if (html == null || html.isEmpty()) {
            return html;
        }
        Matcher matcher = COMMIT_ANCHOR.matcher(html);
        StringBuilder out = new StringBuilder(html.length() + 256);
        boolean anyMatch = false;
        while (matcher.find()) {
            anyMatch = true;
            String owner = matcher.group(1);
            String repo = matcher.group(2);
            String sha = matcher.group(3);
            Optional<LinkedRef> pr = findPrForRepo(linkedRefs, owner, repo);
            String replacement = matcher.group(0);
            if (pr.isPresent()) {
                replacement = replacement + buildButton(owner, repo, pr.get().slug(), sha);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        if (!anyMatch) {
            return html;
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Optional<LinkedRef> findPrForRepo(List<LinkedRef> refs, String owner, String repo)
    {
        for (LinkedRef ref : refs) {
            if (ref.kind() == LinkedRef.Kind.PR
                    && ref.owner().equalsIgnoreCase(owner)
                    && ref.repo().equalsIgnoreCase(repo)) {
                return Optional.of(ref);
            }
        }
        return Optional.empty();
    }

    private static String buildButton(String owner, String repo, String prSlug, String sha)
    {
        return String.format(BUTTON_TEMPLATE,
                encode(owner), encode(repo), encode(prSlug), encode(sha));
    }

    private static String encode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
