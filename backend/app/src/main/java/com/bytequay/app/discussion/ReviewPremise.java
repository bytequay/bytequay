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
package com.bytequay.app.discussion;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.core.discussion.TaskDiscussion;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvestigation;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewSummary;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static com.bytequay.app.discussion.ReviewCommentReader.head;

/**
 * What a review saved, as the premise a private thread it hosts starts from. A review reports through its tools, which the
 * task history leaves out, so its conversation alone would carry little but ByteQuay's review requests.
 */
final class ReviewPremise
{
    private static final int PURPOSE_CHARACTERS = 4_000;
    private static final int EXPLANATION_CHARACTERS = 2_000;
    private static final int TITLE_CHARACTERS = 300;
    private static final int BODY_CHARACTERS = 4_000;
    private static final int CLAIM_CHARACTERS = 1_000;
    private static final int CONCLUSION_CHARACTERS = 1_000;
    private static final String TRUNCATED = " [truncated]";

    private ReviewPremise() {}

    /**
     * The newest summary, every finding, and the newest investigation's candidates with their latest assessment, as JSON
     * under its own delimiter. A review with no results yet still renders, because the code is a thread's real premise.
     *
     * @throws AgentConflictException when the premise is over the seed budget
     */
    static String render(List<ReviewRun> runs, List<ReviewFinding> findings)
    {
        ObjectNode premise = JsonNodeFactory.instance.objectNode();
        newest(runs, run -> run.summary().isPresent()).ifPresent(run -> {
            ReviewSummary summary = run.summary().orElseThrow();
            premise.put("head", run.headSha()).put("purpose", head(summary.purpose(), PURPOSE_CHARACTERS, TRUNCATED));
            ArrayNode commits = premise.putArray("commits");
            summary.commits().forEach(commit -> commits.addObject()
                    .put("sha", commit.sha())
                    .put("explanation", head(commit.explanation(), EXPLANATION_CHARACTERS, TRUNCATED)));
        });
        ArrayNode saved = premise.putArray("findings");
        findings.stream().sorted(Comparator.comparing(ReviewFinding::createdAt)).forEach(finding -> {
            ObjectNode item = saved.addObject().put("title", head(finding.title(), TITLE_CHARACTERS, TRUNCATED));
            finding.severity().ifPresent(severity -> item.put("severity", severity));
            item.put("state", finding.state().name());
            if (finding.outdated()) {
                item.put("outdated", true);
            }
            finding.anchor().ifPresent(anchor -> item.put("path", anchor.path()).put("range", anchor.label()));
            item.put("body", head(finding.body(), BODY_CHARACTERS, TRUNCATED));
        });
        ArrayNode candidates = premise.putArray("candidates");
        newest(runs, run -> run.investigation().isPresent()).flatMap(ReviewRun::investigation)
                .ifPresent(investigation -> addCandidates(candidates, investigation));
        String json = premise.toString();
        if (json.length() > TaskDiscussion.MAX_SEED_CHARACTERS) {
            throw new AgentConflictException("This review is too long to ask about");
        }
        return "--- review so far ---\n\n" + json;
    }

    private static void addCandidates(ArrayNode candidates, ReviewInvestigation investigation)
    {
        for (int index = 0; index < investigation.candidates().size(); index++) {
            int number = index + 1;
            ObjectNode item = candidates.addObject().put("claim", head(investigation.candidates().get(index), CLAIM_CHARACTERS, TRUNCATED));
            // A later assessment of the same candidate supersedes the earlier one.
            investigation.validations().stream().filter(validation -> validation.candidate() == number).reduce((earlier, later) -> later)
                    .ifPresent(validation -> item.put("outcome", validation.outcome().name())
                            .put("conclusion", head(validation.conclusion(), CONCLUSION_CHARACTERS, TRUNCATED)));
        }
    }

    private static Optional<ReviewRun> newest(List<ReviewRun> runs, Predicate<ReviewRun> having)
    {
        return runs.stream().filter(having).max(Comparator.comparingInt(ReviewRun::ordinal));
    }
}
