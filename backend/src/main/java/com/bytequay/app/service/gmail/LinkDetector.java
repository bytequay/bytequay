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
 * Scans email bodies for GitHub PR / issue references and returns a
 * de-duplicated list of {@link LinkedRef}s. v1 uses regex; v2 swaps
 * this implementation for a Claude call that also extracts the
 * action-requested signal (per docs/mockups/design/email/SUMMARY.md).
 */
@Component
public class LinkDetector
{
    /** Matches the github.com URL form most embedded in notification
     *  bodies — covers both pull and issue paths. */
    private static final Pattern GITHUB_URL = Pattern.compile(
            "https://github\\.com/([\\w.-]+)/([\\w.-]+)/(pull|issues)/(\\d+)");

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
        Matcher m = GITHUB_URL.matcher(body);
        while (m.find()) {
            String owner = m.group(1);
            String repo = m.group(2);
            String kindStr = m.group(3);
            int number;
            try {
                number = Integer.parseInt(m.group(4));
            }
            catch (NumberFormatException e) {
                continue;
            }
            LinkedRef.Kind kind = "pull".equals(kindStr) ? LinkedRef.Kind.PR : LinkedRef.Kind.ISSUE;
            String dedupKey = kind + ":" + owner + "/" + repo + "#" + number;
            if (seen.add(dedupKey)) {
                out.add(new LinkedRef(kind, owner, repo, number, m.group(0)));
            }
        }
    }
}
