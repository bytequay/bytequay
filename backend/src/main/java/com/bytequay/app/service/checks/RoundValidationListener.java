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
package com.bytequay.app.service.checks;

import com.bytequay.app.service.threads.TaskTurnFinishedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

/**
 * Claims the local CI pass at the end of every code-touching agent round —
 * local CI is a step of the code-change operation, not a one-off phase gate.
 *
 * <p>The listener only records the durable, fingerprinted claim (the owed
 * validation) and admits an executor; the checks themselves run on the
 * validation pool with no transaction and no event thread held. The
 * finished event fires when the owned pass completes, and the phase
 * machine's existing listener consumes it exactly as before.
 */
@Component
public class RoundValidationListener
{
    private static final Logger log = LoggerFactory.getLogger(RoundValidationListener.class);

    private final ValidationClaimService validation;

    public RoundValidationListener(ValidationClaimService validation)
    {
        this.validation = requireNonNull(validation, "validation is null");
    }

    // Lowest precedence so a minute-long verify runs after the other
    // turn-finished listeners rather than delaying them (they're fast and
    // mostly no-op for a code-editing turn).
    @EventListener
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onTurnFinished(TaskTurnFinishedEvent event)
    {
        if (event.failed() || !event.codeChanged()) {
            return;
        }
        // ponytail: runs the whole local CI script every code-touching round.
        //   Upgrade path: scope to the changed component/module instead of a
        //   full verify. The claim's fingerprint + owner lease already dedupe
        //   concurrent runs of the same code state.
        try {
            validation.claimAndRunDevRound(event.taskId());
        }
        catch (RuntimeException e) {
            log.warn("per-round local CI failed to run for task {}: {}", event.taskId(), e.getMessage());
        }
    }
}
