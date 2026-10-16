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
package com.bytequay.app.agenda;

import com.bytequay.core.domain.AgendaTarget;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Everything one agenda run may read, gathered before its session starts so every page answers from the same moment.
 *
 * <p>Each kind's record is its page entry exactly: a field added here reaches the model on every run, so it must help pick
 * today's work or say why. Bodies, diffs, commits, logs, finding text and transcripts stay out on purpose.
 *
 * @param refs every ref a page can hand out, with what the app needs to keep an item the reply picks
 */
record AgendaSnapshot(
        Overview overview,
        List<Change> changes,
        List<Mine> mine,
        List<Task> tasks,
        List<Ask> asks,
        List<EpicEntry> epics,
        List<HistoryDay> history,
        Optional<Week> week,
        Map<String, RefInfo> refs)
{
    AgendaSnapshot
    {
        requireNonNull(overview, "overview is null");
        changes = ImmutableList.copyOf(changes);
        mine = ImmutableList.copyOf(mine);
        tasks = ImmutableList.copyOf(tasks);
        asks = ImmutableList.copyOf(asks);
        epics = ImmutableList.copyOf(epics);
        history = ImmutableList.copyOf(history);
        requireNonNull(week, "week is null");
        refs = ImmutableMap.copyOf(refs);
    }

    /** Where a ref opens, and since when its thing has waited: the row's age when the reply picks it. */
    record RefInfo(Optional<Instant> at, Optional<String> workspaceId, AgendaTarget target) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Overview(String today, String weekday, boolean recapDay, Instant since, Counts counts, List<String> newlyWatched) {}

    record Counts(int changes, int mine, int tasks, int asks, int epics) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Change(String ref, String title, List<String> facts, Optional<Last> last, Instant at) {}

    /** The newest comment by a person, cut short. */
    record Last(String by, String text) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Mine(String ref, String title, boolean draft, Review review, Optional<String> ci, List<String> failingChecks, Optional<Boolean> mergeable,
            Optional<Instant> quietSince, Optional<String> task) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Review(Optional<String> decision, List<String> by, List<String> pending) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Task(String ref, String kind, String title, String state, Optional<String> waitingFor, Optional<String> question, Optional<String> lastTurn,
            Optional<Instant> idleSince, Optional<String> pr, Optional<String> work, Optional<Findings> findings) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Findings(int open, int unanswered, Optional<String> highest) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Ask(String ref, String why, String title, Optional<String> from, boolean circle, Optional<Instant> since, Optional<Integer> lines, Optional<String> ci,
            Optional<Integer> commitsAfterYourReview, Optional<String> status) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record EpicEntry(String ref, String title, int done, int total, int blocked, int proposals, int newChildren, List<Working> working, List<Ready> ready) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Working(String ref, String title, Optional<String> pr, int unblocks) {}

    record Ready(String ref, String title, String priority, int unblocks) {}

    record HistoryDay(String day, List<HistoryItem> items) {}

    record HistoryItem(String ref, String action, String outcome) {}

    /** The recap day's look back: refs only. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Week(List<String> merged, List<String> done, List<String> stillOpen) {}
}
