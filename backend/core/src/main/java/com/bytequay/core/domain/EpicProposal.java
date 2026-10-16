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
package com.bytequay.core.domain;

import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNullElse;

/**
 * Work the development model proposed for an Epic, which waits until the user accepts or dismisses it; nothing exists before that.
 *
 * @param doneWhen the one check that tells the work is done
 * @param description what the work is and the context an agent needs to start it; a proposal stored before proposals carried one has none
 * @param background why the Epic needs the work
 * @param evidence what in the Epic supports the work
 * @param reproduceSteps how to see the defect the work fixes
 */
public record EpicProposal(String id, String title, String doneWhen, String description, Optional<String> background, Optional<String> evidence,
        Optional<String> reproduceSteps)
{
    public EpicProposal
    {
        checkText(id, "id");
        checkText(title, "title");
        checkText(doneWhen, "doneWhen");
        description = requireNonNullElse(description, "");
        background = requireNonNullElse(background, Optional.empty());
        evidence = requireNonNullElse(evidence, Optional.empty());
        reproduceSteps = requireNonNullElse(reproduceSteps, Optional.empty());
    }

    /** The accepted work item's context, which the agent reads before it starts: each part the proposal has, under its own heading. */
    public String context()
    {
        StringBuilder context = new StringBuilder();
        section(context, "Background", background.orElse(""));
        section(context, "Description", description);
        section(context, "Done when", doneWhen);
        section(context, "Evidence", evidence.orElse(""));
        section(context, "Steps to reproduce", reproduceSteps.orElse(""));
        return context.toString().strip();
    }

    private static void section(StringBuilder context, String heading, String text)
    {
        if (!text.isBlank()) {
            context.append("### ").append(heading).append('\n').append(text.strip()).append("\n\n");
        }
    }
}
