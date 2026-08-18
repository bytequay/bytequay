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
package com.bytequay.app.pr;

import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.store.PrDetailStore;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Service
public class DefaultPullRequestTimelineService
        implements PullRequestTimelineService
{
    private static final int MAXIMUM_BODY_CHARACTERS = 16 * 1024;

    private final PrDetailStore store;

    public DefaultPullRequestTimelineService(PrDetailStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    @Override
    public LocalPrTimelineEvent record(RecordPrTimelineEventCommand command)
    {
        requireNonNull(command, "command is null");
        PrTimelineEvent event = store.recordLocalTimelineEvent(
                command.pullRequest(),
                new PrTimelineEvent(
                        null,
                        "ci_autofix",
                        command.actor(),
                        command.timestamp(),
                        body(command),
                        Optional.of(command.eventKey())));
        return new LocalPrTimelineEvent(
                event.localEventKey().orElseThrow(),
                event.timestamp(),
                event.body());
    }

    private static String body(RecordPrTimelineEventCommand command)
    {
        StringBuilder body = new StringBuilder("CI Autofix pushed `")
                .append(command.pushedSha())
                .append("` over `")
                .append(command.expectedRemoteSha())
                .append("`.");
        appendList(body, "Commits", command.commitSubjects());
        appendList(body, "Changed paths", command.changedPaths());
        if (body.length() <= MAXIMUM_BODY_CHARACTERS) {
            return body.toString();
        }
        return body.substring(0, MAXIMUM_BODY_CHARACTERS - 1) + "…";
    }

    private static void appendList(StringBuilder body, String title, Iterable<String> values)
    {
        body.append("\n\n").append(title).append(':');
        for (String value : values) {
            body.append("\n- ").append(value.replace('\r', ' ').replace('\n', ' '));
        }
    }
}
