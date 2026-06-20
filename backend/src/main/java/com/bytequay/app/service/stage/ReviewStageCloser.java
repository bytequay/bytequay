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
package com.bytequay.app.service.stage;

import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewFindingStatus;
import com.bytequay.app.domain.ReviewParticipant;
import com.bytequay.app.domain.ReviewParticipantKind;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.service.review.ReviewPassTerminatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Closes a callable {@code REVIEW_STAGE} when its review pass settles. The
 * panel summary — the reviewer seats and the agreed-vs-total finding tally
 * — rides the closing event so the brain feed can surface a "panel review
 * complete" entry without re-querying the review subsystem at render time.
 */
@Component
public class ReviewStageCloser
{
    private static final Logger log = LoggerFactory.getLogger(ReviewStageCloser.class);

    private final StageStore stageStore;
    private final ReviewStore reviewStore;

    public ReviewStageCloser(StageStore stageStore, ReviewStore reviewStore)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.reviewStore = requireNonNull(reviewStore, "reviewStore is null");
    }

    @EventListener
    @Transactional
    public void onReviewPassTerminated(ReviewPassTerminatedEvent event)
    {
        UUID stageId;
        try {
            stageId = UUID.fromString(event.taskStageId());
        }
        catch (IllegalArgumentException e) {
            log.warn("Review pass {} carries an unparseable stage id {}; not closing",
                    event.passId(), event.taskStageId());
            return;
        }

        List<ReviewParticipant> panel = reviewStore.listParticipantsForPass(event.passId());
        List<ReviewFinding> findings = reviewStore.listFindingsForPass(event.passId());
        List<String> seatNames = panel.stream()
                .filter(p -> p.kind() == ReviewParticipantKind.REVIEWER)
                .map(ReviewParticipant::personaLabel)
                .filter(label -> label != null && !label.isBlank())
                .toList();
        long agreed = findings.stream()
                .filter(f -> f.status() == ReviewFindingStatus.AGREED)
                .count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("seatNames", seatNames);
        summary.put("findingCount", findings.size());
        summary.put("agreedCount", (int) agreed);
        stageStore.closeStage(stageId, "review_pass_terminated", summary);
        log.debug("Closed review stage {} for pass {}: {} seat(s), {}/{} agreed",
                stageId, event.passId(), seatNames.size(), agreed, findings.size());
    }
}
