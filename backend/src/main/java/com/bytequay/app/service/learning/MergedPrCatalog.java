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
package com.bytequay.app.service.learning;

import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestHistoryPage;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.PullRequestRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Walks the complete merged-PR history of a repository, partitioning by
 * merge-date so it can cross GitHub search's 1,000-result-per-query cap, and
 * feeds each PR into the catalog. It deliberately does <em>not</em> use the
 * open-PR dashboard list method, which is scoped to dashboard data.
 *
 * <p>Every page checkpoints its {@link CatalogCursor} through the {@link
 * Sink}, so an interrupted run (rate limit, truncation, process restart)
 * resumes the unfinished window at its saved page rather than restarting the
 * repository from page one. A fetch failure ends the pass in the {@code
 * partial} state, leaving the cursor intact and the run retryable.
 *
 * <p>Clock-free by design: the caller supplies the initial cursor (its upper
 * bound is "today"), keeping enumeration deterministic under test.
 */
@Component
public class MergedPrCatalog
{
    private static final Logger log = LoggerFactory.getLogger(MergedPrCatalog.class);

    /** GitHub returns at most 1,000 search results per query. */
    static final int SEARCH_RESULT_CAP = 1000;
    /** GitHub caps search page size at 100. */
    static final int PER_PAGE = 100;
    /** Merged history cannot predate this floor for any real repository. */
    static final String HISTORY_FLOOR = "2008-01-01";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final PullRequestRepository gitHub;
    private final ObjectMapper json;

    public MergedPrCatalog(PullRequestRepository gitHub, ObjectMapper json)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.json = requireNonNull(json, "json is null");
    }

    /** A single merge-date window spanning the floor to {@code today}. */
    public static CatalogCursor initialCursor(LocalDate today)
    {
        return new CatalogCursor(List.of(new CatalogCursor.Partition(
                HISTORY_FLOOR, today.format(DAY), 1, false)));
    }

    /** Receives cataloged PRs and cursor checkpoints as the walk proceeds. */
    public interface Sink
    {
        void record(RepoPrSource source);

        void checkpoint(CatalogCursor cursor);
    }

    /** Terminal state of a catalog pass. */
    public enum State { CAUGHT_UP, PARTIAL }

    public record Outcome(CatalogCursor cursor, State state, String error) {}

    /**
     * Resume enumeration from {@code start} until every window is exhausted
     * ({@link State#CAUGHT_UP}) or a fetch fails ({@link State#PARTIAL}).
     */
    public Outcome catalog(
            String workspaceId,
            String repoFullName,
            String pat,
            int extractorVersion,
            CatalogCursor start,
            Sink sink)
    {
        RepoRef ref = RepoRef.parse(repoFullName);
        CatalogCursor cursor = start;
        while (true) {
            int idx = cursor.firstPending();
            if (idx < 0) {
                return new Outcome(cursor, State.CAUGHT_UP, null);
            }
            CatalogCursor.Partition p = cursor.partitions().get(idx);
            String query = "repo:%s is:pr is:merged merged:%s..%s"
                    .formatted(ref.fullName(), p.from(), p.to());
            PullRequestHistoryPage page;
            try {
                page = gitHub.searchPullRequestsPaged(
                        pat, query, p.nextPage(), PER_PAGE, "created", "asc");
            }
            catch (RuntimeException e) {
                // Rate limit / truncation / transient failure: leave the
                // cursor where it is so a retry resumes this exact window.
                log.warn("merged-PR catalog paused for {} window {}..{}: {}",
                        ref.fullName(), p.from(), p.to(), e.getMessage());
                return new Outcome(cursor, State.PARTIAL,
                        e.getMessage() == null ? e.toString() : e.getMessage());
            }

            // A window whose total still exceeds the cap must be subdivided
            // before we page it — otherwise its tail is unreachable. Only
            // split a fresh (page 1) multi-day window.
            if (page.totalCount() > SEARCH_RESULT_CAP
                    && !p.singleDay() && p.nextPage() == 1) {
                cursor = cursor.replace(idx, split(p));
                sink.checkpoint(cursor);
                continue;
            }

            for (PullRequest pr : page.items()) {
                sink.record(toSource(workspaceId, ref.fullName(), pr, extractorVersion));
            }

            boolean capReached = p.nextPage() * PER_PAGE >= SEARCH_RESULT_CAP;
            boolean lastPage = !page.hasMore() || capReached;
            CatalogCursor.Partition advanced = lastPage
                    ? new CatalogCursor.Partition(p.from(), p.to(), p.nextPage(), true)
                    : new CatalogCursor.Partition(p.from(), p.to(), p.nextPage() + 1, false);
            cursor = cursor.replace(idx, advanced);
            sink.checkpoint(cursor);
        }
    }

    /** Split a multi-day window into two half-open-by-day halves. */
    private static CatalogCursor.Partition[] split(CatalogCursor.Partition p)
    {
        LocalDate from = LocalDate.parse(p.from());
        LocalDate to = LocalDate.parse(p.to());
        long days = to.toEpochDay() - from.toEpochDay();
        LocalDate mid = from.plusDays(days / 2);
        if (mid.isEqual(to)) {
            mid = to.minusDays(1);
        }
        return new CatalogCursor.Partition[] {
                new CatalogCursor.Partition(from.format(DAY), mid.format(DAY), 1, false),
                new CatalogCursor.Partition(mid.plusDays(1).format(DAY), to.format(DAY), 1, false)};
    }

    private RepoPrSource toSource(
            String workspaceId, String repoFullName, PullRequest pr, int extractorVersion)
    {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", pr.title());
        metadata.put("author", pr.author());
        metadata.put("labels", pr.labels());
        metadata.put("additions", pr.additions());
        metadata.put("deletions", pr.deletions());
        metadata.put("commentCount", pr.commentCount());
        metadata.put("htmlUrl", pr.htmlUrl());
        metadata.put("headRef", pr.headRef());
        metadata.put("createdAt", pr.createdAt() == null ? null : pr.createdAt().toString());
        String mergedAt = pr.mergedAt() == null ? null : pr.mergedAt().toString();
        String metadataJson = write(metadata);
        String digest = sha256(repoFullName + "#" + pr.number() + "|" + mergedAt
                + "|" + metadataJson + "|v" + extractorVersion);
        return new RepoPrSource(
                workspaceId,
                repoFullName,
                pr.number(),
                mergedAt,
                null,                              // merge_sha needs PR detail (later phase)
                metadataJson,
                "{\"catalog\":\"complete\"}",
                digest,
                null,                              // deterministic ranking is a later phase
                "cataloged",
                extractorVersion,
                null,
                null);
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize PR metadata", e);
        }
    }

    static String sha256(String value)
    {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
