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

import com.bytequay.app.domain.EmailMessageDetail;
import com.bytequay.app.domain.EmailThreadDetail;
import com.bytequay.app.domain.LinkedRef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans email bodies for GitHub PR / issue / commit references and
 * returns a de-duplicated list of {@link LinkedRef}s. v1 uses regex;
 * v2 swaps this implementation for a Claude call that also extracts
 * the action-requested signal (per docs/mockups/design/email/SUMMARY.md).
 */
@Component
public class LinkDetector
{
    /** PR / issue URL — most embedded in notification bodies. */
    private static final Pattern GITHUB_PR_OR_ISSUE = Pattern.compile(
            "https://github\\.com/([\\w.-]+)/([\\w.-]+)/(pull|issues)/(\\d+)");

    // Commit URLs were briefly surfaced as panel chips, but for PR
    // notifications that touch many commits the panel got noisy.
    // Now we let the body's inline commit links carry the click —
    // the renderer rewrites <a href> to open in the system browser.

    public List<LinkedRef> detect(EmailThreadDetail thread)
    {
        Set<String> seen = new LinkedHashSet<>();
        List<LinkedRef> out = new ArrayList<>();
        for (EmailMessageDetail msg : thread.messages()) {
            collectFrom(msg.bodyText(), seen, out);
            collectFrom(msg.bodyHtml(), seen, out);
        }
        return List.copyOf(out);
    }

    private void collectFrom(String body, Set<String> seen, List<LinkedRef> out)
    {
        if (body == null || body.isEmpty()) {
            return;
        }
        Matcher prMatcher = GITHUB_PR_OR_ISSUE.matcher(body);
        while (prMatcher.find()) {
            String owner = prMatcher.group(1);
            String repo = prMatcher.group(2);
            String kindStr = prMatcher.group(3);
            String number = prMatcher.group(4);
            LinkedRef.Kind kind = "pull".equals(kindStr) ? LinkedRef.Kind.PR : LinkedRef.Kind.ISSUE;
            String dedupKey = kind + ":" + owner + "/" + repo + "#" + number;
            if (seen.add(dedupKey)) {
                out.add(new LinkedRef(kind, owner, repo, number, prMatcher.group(0)));
            }
        }
    }
}
