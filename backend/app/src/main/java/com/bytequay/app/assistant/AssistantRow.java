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
package com.bytequay.app.assistant;

import com.bytequay.core.domain.AgendaTarget;

import java.time.Instant;
import java.util.Optional;

/**
 * One ref of a list the assistant showed, as the panel draws it, read from what ByteQuay stores whenever the day is read. Its facts are
 * icons and counts by kind: a pull request's checks, approvals and time; a task's agent, phase and since; an issue's or ticket's key,
 * status and time; a work item's Epic and state; an Epic's done and total.
 *
 * @param key a pull request's or issue's number, or a ticket's key
 * @param state a pull request's open, draft, merged or closed; a task's phase; an issue's or ticket's status; a work item's state
 * @param ci a pull request's checks: passing, failing or pending
 * @param review a pull request's review decision
 * @param approvals how many reviewers approve a pull request, from its cached reviews
 * @param agent the agent a task runs with
 * @param epic the title of the Epic a work item belongs to
 * @param done an Epic's finished work items
 * @param total an Epic's work items that are not dropped
 * @param at when it last moved; for a task, since when it has been in its phase
 * @param latest what happened last, which a card shows in its body
 * @param target where it opens
 */
public record AssistantRow(
        String ref,
        Kind kind,
        String title,
        Optional<String> key,
        Optional<String> state,
        Optional<String> ci,
        Optional<String> review,
        Optional<Integer> approvals,
        Optional<String> agent,
        Optional<String> epic,
        Optional<Integer> done,
        Optional<Integer> total,
        Optional<Instant> at,
        Optional<Latest> latest,
        AgendaTarget target)
{
    public enum Kind
    {
        PULL, ISSUE, JIRA, TASK, REVIEW, WORK, EPIC
    }

    /**
     * The latest event as one line, in the source's own words, clipped.
     *
     * @param by who did it, when the source names someone
     */
    public record Latest(Optional<String> by, String text, Instant at) {}
}
