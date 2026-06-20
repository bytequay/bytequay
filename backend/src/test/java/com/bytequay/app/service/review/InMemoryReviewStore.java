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

import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewMessage;
import com.bytequay.app.domain.ReviewParticipant;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPassHostKind;
import com.bytequay.app.domain.ReviewPassKind;
import com.bytequay.app.repository.ReviewStore;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stateful in-memory {@link ReviewStore} for the panel tests. The
 * lead-driven flow does many reload → mutate → save round-trips
 * (agenda marks, budget charges, phase transitions), which a
 * stubbed mock can't follow — this fake just behaves like the table.
 * Message order is insertion order (the SQLite store orders by
 * created-at, which test fixtures can't make strictly monotonic).
 */
class InMemoryReviewStore
        implements ReviewStore
{
    private final Map<String, ReviewPass> passes = new ConcurrentHashMap<>();
    private final Map<String, ReviewParticipant> participants = new ConcurrentHashMap<>();
    private final Map<String, ReviewMessage> messages = new ConcurrentHashMap<>();
    private final Map<String, ReviewFinding> findings = new ConcurrentHashMap<>();
    private final Map<String, Long> insertionOrder = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    /** Every savePass in order, so tests can assert on the phase walk. */
    final List<ReviewPass> passHistory = new CopyOnWriteArrayList<>();

    @Override
    public void savePass(ReviewPass pass)
    {
        // Mirror SqliteReviewStore: a full-row save never overwrites the
        // host (it's written once via setPassHost). Preserve the stored
        // host so a reconstructed (default-THREAD) pass can't clobber a
        // TASK_PHASE host mid-run.
        ReviewPass existing = passes.get(pass.id());
        ReviewPass toStore = existing == null ? pass : new ReviewPass(
                pass.id(), pass.threadId(), pass.repoFullName(), pass.prNumber(), pass.headSha(),
                pass.phase(), pass.round(), pass.roundCap(), pass.costCapMilli(), pass.costUsdMilli(),
                pass.verdict(), pass.createdAt(), pass.endedAt(), pass.spawnedBuildThreadId(),
                pass.agendaJson(), existing.hostKind(), existing.hostId(), existing.kind(),
                existing.taskStageId());
        passHistory.add(toStore);
        passes.put(pass.id(), toStore);
    }

    @Override
    public void setPassHost(String passId, ReviewPassHostKind hostKind, String hostId, ReviewPassKind kind)
    {
        ReviewPass p = passes.get(passId);
        if (p == null) {
            return;
        }
        passes.put(passId, new ReviewPass(
                p.id(), p.threadId(), p.repoFullName(), p.prNumber(), p.headSha(), p.phase(),
                p.round(), p.roundCap(), p.costCapMilli(), p.costUsdMilli(), p.verdict(),
                p.createdAt(), p.endedAt(), p.spawnedBuildThreadId(), p.agendaJson(),
                hostKind, hostId, kind, p.taskStageId()));
    }

    @Override
    public void setPassTaskStage(String passId, String taskStageId)
    {
        ReviewPass p = passes.get(passId);
        if (p == null) {
            return;
        }
        passes.put(passId, new ReviewPass(
                p.id(), p.threadId(), p.repoFullName(), p.prNumber(), p.headSha(), p.phase(),
                p.round(), p.roundCap(), p.costCapMilli(), p.costUsdMilli(), p.verdict(),
                p.createdAt(), p.endedAt(), p.spawnedBuildThreadId(), p.agendaJson(),
                p.hostKind(), p.hostId(), p.kind(), taskStageId));
    }

    @Override
    public Optional<ReviewPass> findPassById(String id)
    {
        return Optional.ofNullable(passes.get(id));
    }

    @Override
    public List<ReviewPass> listPassesByThread(String threadId)
    {
        return passes.values().stream()
                .filter(p -> p.threadId().equals(threadId))
                .sorted(Comparator.comparing(ReviewPass::createdAt))
                .toList();
    }

    @Override
    public List<ReviewPass> listPassesForPr(String repoFullName, int prNumber)
    {
        return passes.values().stream()
                .filter(p -> p.repoFullName().equals(repoFullName) && p.prNumber() == prNumber)
                .sorted(Comparator.comparing(ReviewPass::createdAt).reversed())
                .toList();
    }

    @Override
    public long sumPassCostSince(Instant since)
    {
        return passes.values().stream()
                .filter(p -> !p.createdAt().isBefore(since))
                .mapToLong(ReviewPass::costUsdMilli)
                .sum();
    }

    @Override
    public void deletePass(String id)
    {
        passes.remove(id);
    }

    @Override
    public void saveParticipant(ReviewParticipant participant)
    {
        insertionOrder.putIfAbsent(participant.id(), sequence.incrementAndGet());
        participants.put(participant.id(), participant);
    }

    @Override
    public Optional<ReviewParticipant> findParticipantById(String id)
    {
        return Optional.ofNullable(participants.get(id));
    }

    @Override
    public List<ReviewParticipant> listParticipantsForPass(String reviewPassId)
    {
        return participants.values().stream()
                .filter(p -> p.reviewPassId().equals(reviewPassId))
                .sorted(Comparator.comparing(p -> insertionOrder.get(p.id())))
                .toList();
    }

    @Override
    public void saveMessage(ReviewMessage message)
    {
        insertionOrder.putIfAbsent(message.id(), sequence.incrementAndGet());
        messages.put(message.id(), message);
    }

    @Override
    public Optional<ReviewMessage> findMessageById(String id)
    {
        return Optional.ofNullable(messages.get(id));
    }

    @Override
    public List<ReviewMessage> listMessagesForPass(String reviewPassId)
    {
        return messages.values().stream()
                .filter(m -> m.reviewPassId().equals(reviewPassId))
                .sorted(Comparator.comparing(m -> insertionOrder.get(m.id())))
                .toList();
    }

    @Override
    public void saveFinding(ReviewFinding finding)
    {
        insertionOrder.putIfAbsent(finding.id(), sequence.incrementAndGet());
        findings.put(finding.id(), finding);
    }

    @Override
    public Optional<ReviewFinding> findFindingById(String id)
    {
        return Optional.ofNullable(findings.get(id));
    }

    @Override
    public List<ReviewFinding> listFindingsForPass(String reviewPassId)
    {
        return findings.values().stream()
                .filter(f -> f.reviewPassId().equals(reviewPassId))
                .sorted(Comparator.comparing(f -> insertionOrder.get(f.id())))
                .toList();
    }
}
