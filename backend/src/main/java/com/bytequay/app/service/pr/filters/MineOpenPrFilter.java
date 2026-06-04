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
package com.bytequay.app.service.pr.filters;

import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.service.concepts.Concept;
import com.bytequay.app.service.concepts.ConceptKind;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Every PR I authored that's still open — the "what's in flight"
 *  view. */
@Component
@Concept(
        name = "mine_open",
        aka = {"my-open", "mine"},
        kind = ConceptKind.FILTER,
        definition = "A PR I authored that is still open — neither merged nor closed. "
                + "The broad \"what's in flight\" view.",
        relatedTools = "list_prs",
        relatedConcepts = "pr")
public class MineOpenPrFilter
        implements NamedFilter<PullRequest>
{
    @Override
    public String name()
    {
        return "mine_open";
    }

    @Override
    public boolean matches(PullRequest pr, Instant now)
    {
        if (pr.origin() != PullRequest.Origin.AUTHORED) {
            return false;
        }
        return pr.mergedAt() == null && !"closed".equalsIgnoreCase(pr.state());
    }
}
