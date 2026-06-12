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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.ReviewParticipant;
import com.bytequay.app.domain.ReviewParticipantKind;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.repository.ReviewStore;
import org.springframework.stereotype.Component;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Meters review-pass spend against the two persistent budgets: the
 * per-pass cost cap and the per-seat slice stamped on each reviewer
 * participant at kickoff (pass cap / panel size by default). Spend is
 * read from and written back to the store so the caps survive a
 * backend restart mid-pass and the UI's cost meters read live values.
 *
 * <p>Caps are enforced, not advisory: callers consult
 * {@link #seatHasBudget} / {@link #passExhausted} before spending and
 * surface a structured error instead of overspending.
 */
@Component
public class ReviewBudgetMeter
{
    private final ReviewStore reviewStore;

    public ReviewBudgetMeter(ReviewStore reviewStore)
    {
        this.reviewStore = requireNonNull(reviewStore, "reviewStore is null");
    }

    /** Stamp the per-seat slices at kickoff: the pass cap split evenly
     *  across the reviewer seats. Non-reviewer seats (lead, human)
     *  carry no slice — the pass cap still bounds them. */
    public void initSeatSlices(ReviewPass pass, List<ReviewParticipant> participants)
    {
        List<ReviewParticipant> reviewers = participants.stream()
                .filter(p -> p.kind() == ReviewParticipantKind.REVIEWER)
                .toList();
        if (reviewers.isEmpty()) {
            return;
        }
        long slice = pass.costCapMilli() / reviewers.size();
        for (ReviewParticipant seat : reviewers) {
            reviewStore.saveParticipant(new ReviewParticipant(
                    seat.id(), seat.reviewPassId(), seat.kind(), seat.credentialId(),
                    seat.personaLabel(), seat.model(), seat.color(), seat.createdAt(),
                    slice, seat.budgetMilliUsdSpent()));
        }
    }

    /** True when the seat still has slice budget left. Seats without
     *  a slice (cap 0 — legacy rows) are bounded by the pass cap only. */
    public boolean seatHasBudget(String participantId)
    {
        ReviewParticipant seat = reviewStore.findParticipantById(participantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no review participant: " + participantId));
        return seat.budgetMilliUsdCap() == 0
                || seat.budgetMilliUsdSpent() < seat.budgetMilliUsdCap();
    }

    /** Remaining slice for a seat, or {@link Long#MAX_VALUE} when the
     *  seat carries no slice. */
    public long seatRemaining(String participantId)
    {
        ReviewParticipant seat = reviewStore.findParticipantById(participantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no review participant: " + participantId));
        if (seat.budgetMilliUsdCap() == 0) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, seat.budgetMilliUsdCap() - seat.budgetMilliUsdSpent());
    }

    /** Charge a seat turn: advances the seat's slice spend AND the
     *  pass's running total. */
    public void chargeSeat(String passId, String participantId, long costMilliUsd)
    {
        if (costMilliUsd <= 0) {
            return;
        }
        ReviewParticipant seat = reviewStore.findParticipantById(participantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no review participant: " + participantId));
        reviewStore.saveParticipant(seat.withBudgetSpent(
                seat.budgetMilliUsdSpent() + costMilliUsd));
        chargePass(passId, costMilliUsd);
    }

    /** Charge the pass total without a seat slice — Lead turns. */
    public void chargePass(String passId, long costMilliUsd)
    {
        if (costMilliUsd <= 0) {
            return;
        }
        ReviewPass pass = reviewStore.findPassById(passId)
                .orElseThrow(() -> new IllegalArgumentException("no review pass: " + passId));
        reviewStore.savePass(new ReviewPass(
                pass.id(), pass.threadId(), pass.repoFullName(), pass.prNumber(),
                pass.headSha(), pass.phase(), pass.round(), pass.roundCap(),
                pass.costCapMilli(), pass.costUsdMilli() + costMilliUsd,
                pass.verdict(), pass.createdAt(), pass.endedAt(),
                pass.spawnedBuildThreadId(), pass.agendaJson()));
    }

    /** True once the pass's running spend has reached its cap. */
    public boolean passExhausted(String passId)
    {
        ReviewPass pass = reviewStore.findPassById(passId)
                .orElseThrow(() -> new IllegalArgumentException("no review pass: " + passId));
        return pass.costUsdMilli() >= pass.costCapMilli();
    }

    /** Remaining pass budget in milli-USD (never negative). */
    public long passRemaining(String passId)
    {
        ReviewPass pass = reviewStore.findPassById(passId)
                .orElseThrow(() -> new IllegalArgumentException("no review pass: " + passId));
        return Math.max(0L, pass.costCapMilli() - pass.costUsdMilli());
    }
}
