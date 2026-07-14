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
package com.bytequay.app.service.localpr;

import com.bytequay.app.domain.PR;
import com.bytequay.app.repository.PRStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * One-time-per-boot backfill that reconciles PRs left as two rows before the
 * unification landed: a task's own {@code origin='task'} row and the
 * dashboard-synced {@code origin='external'} row for the same GitHub PR. See
 * docs/mockups/pr-record-unification-design.md.
 *
 * <p>Runs the same fold ({@link PRService#foldExternalTwinIntoTask}) the live
 * push path uses, so there is no duplicated merge logic. It is idempotent: on
 * a database with no duplicates both queries return empty and the sweep is a
 * no-op, so it is safe to run on every startup rather than as a versioned
 * migration.
 *
 * <p>First it repairs legacy "half-pushed" task rows — pushed rows that carry a
 * remote URL and number but a null {@code repo} (written before the push path
 * recorded {@code repo}). Their repo is recoverable from the URL, and setting
 * it makes them eligible for the fold and for the {@code (repo, number)} unique
 * index.
 */
@Component
public class PrDuplicateReconciler
{
    private static final Logger log = LoggerFactory.getLogger(PrDuplicateReconciler.class);

    private final PRStore store;
    private final PRService prService;

    public PrDuplicateReconciler(PRStore store, PRService prService)
    {
        this.store = requireNonNull(store, "store is null");
        this.prService = requireNonNull(prService, "prService is null");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup()
    {
        int repaired = 0;
        for (PR pr : store.findPushedTaskPrsMissingRepo()) {
            String repo = repoFromUrl(pr.remotePrUrl());
            if (repo != null) {
                store.setRepo(pr.id(), repo);
                repaired++;
            }
        }

        // ponytail: O(N) serial folds on the ready-event thread. N is the count
        // of pre-unification duplicate pairs — a handful on a real DB, zero after
        // the first boot. If a migrated DB ever carries many, batch or offload.
        List<String> dupTaskPrIds = store.findTaskPrIdsWithExternalTwin();
        for (String taskPrId : dupTaskPrIds) {
            prService.foldExternalTwinIntoTask(taskPrId);
        }

        if (repaired > 0 || !dupTaskPrIds.isEmpty()) {
            log.info("PR reconcile: repaired {} half-pushed task row(s), folded {} external twin(s)",
                    repaired, dupTaskPrIds.size());
        }
    }

    /**
     * The {@code owner/repo} slug from a GitHub PR URL like
     * {@code https://github.com/owner/repo/pull/123}, or null if it doesn't
     * parse.
     */
    static String repoFromUrl(String url)
    {
        if (url == null) {
            return null;
        }
        int host = url.indexOf("github.com/");
        if (host < 0) {
            return null;
        }
        String[] parts = url.substring(host + "github.com/".length()).split("/");
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }
        return parts[0] + "/" + parts[1];
    }
}
