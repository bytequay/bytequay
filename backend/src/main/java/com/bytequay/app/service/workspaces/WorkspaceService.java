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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.Workspace;
import com.bytequay.app.domain.WorkspaceCardDto;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WorkspaceStore;
import com.bytequay.app.service.concepts.ConceptKind;
import com.bytequay.app.service.concepts.ConceptRegistry;
import com.bytequay.app.service.concepts.ConceptScope;
import com.bytequay.app.service.concepts.ConceptSpec;
import com.bytequay.app.service.concepts.WorkspaceGlossaryParser;
import com.bytequay.app.service.threads.ThreadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * Service surface for the Workspace tier. Owns the workspace memory
 * (a small markdown blob loaded into every thread's context), the
 * list of repos attached to each workspace, and per-(workspace, repo)
 * defaults used by ship-and-continue (notably the merge-target
 * branch).
 *
 * <p>v1 keeps a single ambient workspace ({@code ws-default} named
 * "ByteQuay"). Multi-workspace creation lands later; this service
 * already accepts the workspace id everywhere so the multi-workspace
 * path is just a routing problem.
 */
@Service
public class WorkspaceService
{
    /**
     * Id of the ambient workspace seeded by V73. The frontend treats
     * this as "the workspace" until multi-workspace switching ships.
     */
    public static final String DEFAULT_WORKSPACE_ID = "ws-default";

    /**
     * Soft upper bound on the workspace memory size — loaded into
     * every thread, so growth has a 1:N cost. Distillation keeps the
     * blob well under this; manual edits get a 4× hard limit so the
     * user can paste a large block while distillation re-condenses it.
     */
    public static final int MEMORY_MD_TARGET_CHARS = 8_000;
    public static final int MEMORY_MD_HARD_CAP_CHARS = 32_000;

    /** Token budget the landing card's memory strip renders against.
     *  Matches the design's "~4k cap" — the chars hard cap above is
     *  intentionally laxer so a paste-and-distill workflow doesn't
     *  bounce, but the surfaced budget stays at the design target. */
    public static final int MEMORY_TOKEN_CAP = 4_000;

    /** Coarse char-per-token estimate. English-language Markdown
     *  averages ~4 chars per BPE token; one significant digit is
     *  plenty for a budget bar. */
    private static final int CHARS_PER_TOKEN = 4;

    /** Palette the landing-card avatar picks from when no colour was
     *  set on the workspace row. Hand-mixed to read on the calm
     *  gradient-mesh background; ordering doesn't matter — the index
     *  is derived from the workspace name's stable hash. */
    private static final List<String> AVATAR_PALETTE = List.of(
            "#7c5cff", "#34c4a8", "#f59e0b", "#ef4444",
            "#3b82f6", "#ec4899", "#10b981", "#f97316");

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    private final WorkspaceStore store;
    private final WorkspaceGlossaryParser glossaryParser;
    private final ConceptRegistry concepts;
    private final ThreadStore threadStore;
    private final ThreadService threadService;

    public WorkspaceService(
            WorkspaceStore store,
            WorkspaceGlossaryParser glossaryParser,
            ConceptRegistry concepts,
            ThreadStore threadStore,
            // @Lazy breaks the cycle WorkspaceService → ThreadService →
            // ThreadRegistry → WorkspaceService (the registry reads workspace
            // context). The teardown only needs ThreadService at delete time.
            @Lazy ThreadService threadService)
    {
        this.store = requireNonNull(store, "store is null");
        this.glossaryParser = requireNonNull(glossaryParser, "glossaryParser is null");
        this.concepts = requireNonNull(concepts, "concepts is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.threadService = requireNonNull(threadService, "threadService is null");
    }

    public List<Workspace> list()
    {
        return store.listWorkspaces();
    }

    /**
     * Build the landing-grid view of every workspace — one card per
     * row, with the at-a-glance aggregates the user picks between on.
     * Single pass over workspaces; each card adds one stats query +
     * one repos query. Cheap for the handful of workspaces v1 owners
     * actually keep (the design caps "a handful ever").
     *
     * <p>Scratch workspaces are included with zeroed counts and
     * {@code isScratch: true} so the renderer can switch to the
     * muted-card variant without a second lookup.
     */
    public List<WorkspaceCardDto> listWithStats()
    {
        long sinceMs = todayLocalMidnightMs();
        List<Workspace> all = store.listWorkspaces();
        List<WorkspaceCardDto> cards = new ArrayList<>(all.size());
        for (Workspace w : all) {
            cards.add(toCard(w, sinceMs));
        }
        return List.copyOf(cards);
    }

    private WorkspaceCardDto toCard(Workspace workspace, long sinceMs)
    {
        // Scratch workspaces never accrue durable memory or tasks, so
        // skip the aggregate queries and hand back a zeroed card.
        // Cheaper, and matches the design's "no durable memory" copy.
        WorkspaceStore.WorkspaceStats stats = workspace.isScratch()
                ? new WorkspaceStore.WorkspaceStats(0, 0, 0, 0L, null)
                : store.fetchStats(workspace.id(), sinceMs);
        List<String> repos = store.listRepos(workspace.id()).stream()
                .map(this::shortRepoName)
                .toList();
        return new WorkspaceCardDto(
                workspace.id(),
                workspace.name(),
                avatarColor(workspace.name()),
                workspace.isScratch(),
                repos,
                stats.activeThreadCount(),
                stats.tasksInFlight(),
                stats.spendMilliUsd(),
                stats.needsAttentionCount(),
                summariseMemory(workspace.memoryMd()),
                stats.lastActivityMs());
    }

    /** Last segment of {@code owner/repo} — the landing card shows
     *  short repo chips ("backend") rather than the full slug
     *  ("chenjian2664/backend") so the row stays scannable. */
    private String shortRepoName(WorkspaceRepo repo)
    {
        String full = repo.repoFullName();
        if (full == null || full.isBlank()) {
            return "";
        }
        int slash = full.lastIndexOf('/');
        return slash >= 0 && slash < full.length() - 1 ? full.substring(slash + 1) : full;
    }

    /**
     * Counts {@code "- "} bullets under the {@code ## Decisions} and
     * {@code ## Blockers} H2 sections of the workspace memory. Surfaces
     * the same aggregates the design's memory strip references without
     * forcing the frontend to re-parse the markdown. Empty input
     * collapses to zeros.
     */
    static WorkspaceCardDto.MemorySummary summariseMemory(String memoryMd)
    {
        int chars = memoryMd == null ? 0 : memoryMd.length();
        int tokensUsed = chars / CHARS_PER_TOKEN;
        if (memoryMd == null || memoryMd.isBlank()) {
            return new WorkspaceCardDto.MemorySummary(0, 0, 0, MEMORY_TOKEN_CAP);
        }
        int decisions = 0;
        int blockers = 0;
        // Track which H2 section we're inside. Anything outside the
        // two named sections is ignored — the section is what gives
        // a bullet its meaning, not the bare "- " prefix.
        Section section = Section.NONE;
        for (String rawLine : memoryMd.split("\\R", -1)) {
            String line = rawLine.trim();
            if (line.startsWith("## ")) {
                String heading = line.substring(3).trim().toLowerCase(Locale.ROOT);
                section = switch (heading) {
                    case "decisions" -> Section.DECISIONS;
                    case "blockers" -> Section.BLOCKERS;
                    default -> Section.NONE;
                };
                continue;
            }
            // A top-level heading or H1 closes the section; anything
            // deeper (H3, etc.) leaves it alone since those are
            // sub-sections of the same decision/blocker.
            if (line.startsWith("# ")) {
                section = Section.NONE;
                continue;
            }
            if (!line.startsWith("- ")) {
                continue;
            }
            switch (section) {
                case DECISIONS -> decisions++;
                case BLOCKERS -> blockers++;
                case NONE -> { /* ignored — bullet outside a tracked section */ }
            }
        }
        return new WorkspaceCardDto.MemorySummary(decisions, blockers, tokensUsed, MEMORY_TOKEN_CAP);
    }

    private enum Section { NONE, DECISIONS, BLOCKERS }

    /** Stable per-name avatar colour. djb2-style hash keeps the math
     *  trivial and the result deterministic across restarts. */
    static String avatarColor(String name)
    {
        if (name == null || name.isEmpty()) {
            return AVATAR_PALETTE.get(0);
        }
        int hash = 5381;
        for (int i = 0; i < name.length(); i++) {
            hash = ((hash << 5) + hash) + name.charAt(i);
        }
        int idx = Math.floorMod(hash, AVATAR_PALETTE.size());
        return AVATAR_PALETTE.get(idx);
    }

    /** Epoch ms of today's local midnight — start of the day the card's
     *  {@code spendTodayMilliUsd} sums from. Uses the JVM's default
     *  zone, which matches the backend-on-the-user's-laptop topology. */
    private static long todayLocalMidnightMs()
    {
        return LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }

    public Workspace require(String workspaceId)
    {
        return store.findWorkspaceById(workspaceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no workspace: " + workspaceId));
    }

    /**
     * Delete a workspace and everything under it. Each thread in the
     * workspace is purged first ({@link ThreadService#purge}): its agent
     * sessions are stopped, queued turns cancelled, worktrees reaped, and
     * the row dropped — which DB-cascades the thread's tasks, stages,
     * messages, backlog, and review history. Only then is the workspace
     * row removed (taking its {@code workspace_repos} pins and
     * memory-proposal row via FK cascade).
     *
     * <p>Purging threads first is also required for correctness, not just
     * cleanup: {@code threads.workspace_id} has no {@code ON DELETE
     * CASCADE}, so with FK enforcement on, dropping a workspace that still
     * had threads would fail with a constraint violation.
     *
     * <p>Each thread purge is best-effort — one thread's teardown throwing
     * (a wedged git worktree, say) is logged and skipped so it can't strand
     * the rest of the cascade.
     */
    public void delete(String workspaceId)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        require(workspaceId);
        List<Thread> threads = threadStore.listThreadsByWorkspace(workspaceId);
        for (Thread thread : threads) {
            try {
                threadService.purge(thread.id());
            }
            catch (RuntimeException e) {
                log.warn("purge of thread {} during workspace {} delete failed: {}",
                        thread.id(), workspaceId, e.getMessage());
            }
        }
        store.deleteWorkspace(workspaceId);
        log.info("deleted workspace {} and purged {} thread(s)", workspaceId, threads.size());
    }

    /**
     * Create a new workspace. Name is required; an optional
     * {@code promptContext} block is appended to {@code memoryMd}
     * (it lands at the top of WORKSPACE.md so every thread in the
     * workspace reads it first), and the picked {@code repoFullNames}
     * are pinned via {@code workspace_repos}. The id is generated
     * server-side so concurrent creates can't collide.
     */
    public Workspace create(NewWorkspaceRequest request)
    {
        requireNonNull(request, "request is null");
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400), "name is required");
        }
        String trimmedName = request.name().trim();
        if (trimmedName.length() > 80) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(413),
                    "workspace name exceeds 80 chars");
        }
        String memoryMd = request.promptContext() == null
                ? ""
                : request.promptContext().trim();
        if (memoryMd.length() > MEMORY_MD_HARD_CAP_CHARS) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(413),
                    "prompt context exceeds " + MEMORY_MD_HARD_CAP_CHARS + " chars");
        }
        Instant now = Instant.now();
        String workspaceId = allocateWorkspaceId(request.slug(), trimmedName);
        Workspace workspace = new Workspace(
                workspaceId,
                trimmedName,
                memoryMd,
                request.isScratch(),
                /* workModel */ null,
                now,
                now);
        store.saveWorkspace(workspace);
        List<String> repos = request.repoFullNames() == null
                ? List.of()
                : request.repoFullNames();
        for (String repoFullName : repos) {
            if (repoFullName == null || repoFullName.isBlank()) {
                continue;
            }
            store.addRepo(new WorkspaceRepo(
                    workspace.id(),
                    repoFullName.trim(),
                    /* defaultBaseBranch */ null,
                    /* autoFixEnabled */ false,
                    now));
        }
        return store.findWorkspaceById(workspace.id()).orElse(workspace);
    }

    /**
     * Create-workspace inputs. {@code promptContext} is rendered
     * verbatim into {@code memoryMd} — the create dialog interpolates
     * the repo names + workspace name before sending so the backend
     * doesn't need to know the template.
     *
     * <p>{@code slug} is the user's chosen workspace id segment without
     * the {@code ws-} prefix (the dialog derives it live from the name
     * and lets the user override before commit). When null or blank the
     * service derives the slug from {@code name}. The final stored id
     * is {@code "ws-" + slug}; the slug is immutable for the lifetime
     * of the workspace.
     */
    public record NewWorkspaceRequest(
            String name,
            String slug,
            boolean isScratch,
            String promptContext,
            List<String> repoFullNames)
    {
        /** Back-compat constructor for callers that haven't migrated to
         *  passing an explicit slug — the service derives it from name. */
        public NewWorkspaceRequest(
                String name,
                boolean isScratch,
                String promptContext,
                List<String> repoFullNames)
        {
            this(name, null, isScratch, promptContext, repoFullNames);
        }
    }

    /** Maximum length of the slug portion (after the {@code ws-} prefix). */
    static final int SLUG_MAX_CHARS = 24;

    /** Validated slug character class — lowercase letters, digits, and
     *  internal dashes. Same alphabet the workspace-thread-task design
     *  doc spells out; matches URL-safe + branch-safe characters. */
    private static final Pattern SLUG_PATTERN =
            Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    /**
     * Derive a slug from a workspace display name.
     *
     * <p>Lowercases, replaces runs of non-alphanumerics with a single
     * dash, trims dashes from both ends, then truncates to
     * {@link #SLUG_MAX_CHARS} characters (without splitting a dash to a
     * trailing position). Returns the empty string when the name slugs
     * to nothing — caller decides the fallback.
     */
    static String deriveSlug(String name)
    {
        if (name == null) {
            return "";
        }
        String lowered = name.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lowered.length());
        boolean lastWasDash = true;
        for (int i = 0; i < lowered.length(); i++) {
            char c = lowered.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
                lastWasDash = false;
            }
            else if (!lastWasDash) {
                out.append('-');
                lastWasDash = true;
            }
        }
        String trimmed = out.toString();
        while (trimmed.endsWith("-")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.length() > SLUG_MAX_CHARS) {
            trimmed = trimmed.substring(0, SLUG_MAX_CHARS);
            while (trimmed.endsWith("-")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
        }
        return trimmed;
    }

    /** Soft cap on the display name. Trimmed; blank rejected. */
    private static final int NAME_MAX_CHARS = 80;

    /** Hard cap on the number of slug collisions we resolve with a
     *  numeric suffix before giving up — beyond this the user picked a
     *  slug that's almost certainly worth being explicit about. */
    private static final int MAX_SLUG_COLLISIONS = 9;

    /**
     * Resolve the slug for a new workspace and return the final
     * {@code "ws-<slug>"} id, ensuring uniqueness. The caller may
     * supply an explicit slug; when null/blank, the slug is derived
     * from the display name. Empty-after-derive falls back to a short
     * UUID stub so the workspace can still be created. Collisions are
     * disambiguated by appending {@code -2}, {@code -3}, ... up to
     * {@link #MAX_SLUG_COLLISIONS}; beyond that the create is rejected
     * with a 409.
     */
    private String allocateWorkspaceId(String requestedSlug, String fallbackName)
    {
        String slug = requestedSlug == null || requestedSlug.isBlank()
                ? deriveSlug(fallbackName)
                : normaliseSlug(requestedSlug.trim());
        if (slug.isEmpty()) {
            slug = "space-" + UUID.randomUUID().toString().substring(0, 6);
        }
        if (!SLUG_PATTERN.matcher(slug).matches() || slug.length() > SLUG_MAX_CHARS) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "slug must match [a-z0-9-] (max " + SLUG_MAX_CHARS + " chars): " + slug);
        }
        String candidate = "ws-" + slug;
        if (store.findWorkspaceById(candidate).isEmpty()) {
            return candidate;
        }
        for (int i = 2; i <= MAX_SLUG_COLLISIONS; i++) {
            String next = candidate + "-" + i;
            if (store.findWorkspaceById(next).isEmpty()) {
                return next;
            }
        }
        throw new ResponseStatusException(
                HttpStatusCode.valueOf(409),
                "slug already in use (" + MAX_SLUG_COLLISIONS + " variants taken): "
                        + candidate);
    }

    /** User-supplied slug normalisation — strip a leading {@code ws-}
     *  if the caller typed one, then route through {@link #deriveSlug}
     *  so the remaining validation rules apply uniformly. */
    private static String normaliseSlug(String supplied)
    {
        String stripped = supplied.startsWith("ws-") ? supplied.substring("ws-".length()) : supplied;
        return deriveSlug(stripped);
    }

    /**
     * Rename a workspace. The display name surfaces on the landing
     * card and the rail; the id is stable. Trims the value and
     * rejects blank or oversized payloads up front.
     */
    public Workspace rename(String workspaceId, String newName)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        if (newName == null || newName.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400), "name is required");
        }
        String trimmed = newName.trim();
        if (trimmed.length() > NAME_MAX_CHARS) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(413),
                    "workspace name exceeds " + NAME_MAX_CHARS + " chars");
        }
        Workspace current = require(workspaceId);
        if (trimmed.equals(current.name())) {
            return current;
        }
        Workspace next = new Workspace(
                current.id(), trimmed, current.memoryMd(),
                current.isScratch(), current.workModel(),
                current.createdAt(), Instant.now());
        store.saveWorkspace(next);
        return next;
    }

    public String getMemory(String workspaceId)
    {
        return require(workspaceId).memoryMd();
    }

    /**
     * Writes the markdown body wholesale. Rejects oversized payloads
     * outright; distillation is responsible for keeping the memory
     * within the soft target.
     */
    public Workspace setMemory(String workspaceId, String memoryMd)
    {
        requireNonNull(memoryMd, "memoryMd is null");
        if (memoryMd.length() > MEMORY_MD_HARD_CAP_CHARS) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(413),
                    "workspace memory exceeds hard cap of " + MEMORY_MD_HARD_CAP_CHARS + " chars");
        }
        Workspace current = require(workspaceId);
        Workspace next = new Workspace(
                current.id(), current.name(), memoryMd,
                current.isScratch(), current.workModel(),
                current.createdAt(), Instant.now());
        store.saveWorkspace(next);
        syncGlossaryConcepts(next);
        return next;
    }

    /**
     * Re-parse the workspace's {@code ## Glossary} section and
     * publish the entries to the concept registry under
     * {@link ConceptScope#WORKSPACE}. Replaces any prior workspace-
     * scoped concepts wholesale — the .md is the source of truth.
     *
     * <p>v1 supports one active workspace at a time, so we
     * {@link ConceptRegistry#clearScope clearScope(WORKSPACE)} before
     * loading the new entries. Multi-workspace memory simultaneously
     * visible to the registry lands when the broader switcher does.
     */
    private void syncGlossaryConcepts(Workspace workspace)
    {
        try {
            concepts.clearScope(ConceptScope.WORKSPACE);
            List<WorkspaceGlossaryParser.Entry> entries = glossaryParser.parse(workspace.memoryMd());
            for (WorkspaceGlossaryParser.Entry entry : entries) {
                ConceptSpec spec = new ConceptSpec(
                        entry.name(),
                        entry.aka(),
                        // Workspace glossary entries default to NOUN
                        // (a vocabulary term) — kind=FILTER user
                        // concepts come in via Saved Views, not the
                        // brain glossary parser.
                        ConceptKind.NOUN,
                        entry.definition(),
                        List.of(),
                        List.of(),
                        List.of(),
                        ConceptScope.WORKSPACE,
                        "workspace://" + workspace.id() + "/memory.md#" + entry.name());
                concepts.registerRuntime(spec);
            }
            if (!entries.isEmpty()) {
                log.info("Loaded {} workspace glossary concept(s) for {}",
                        entries.size(), workspace.id());
            }
        }
        catch (RuntimeException e) {
            // A malformed glossary section shouldn't tank setMemory —
            // log and continue; the registry just keeps its previous
            // workspace-scoped entries cleared.
            log.warn("Failed to sync workspace glossary for {}: {}",
                    workspace.id(), e.getMessage());
        }
    }

    /**
     * Set (or clear) the workspace's default pick on the work-model
     * cascade. Pass {@code null} to remove the override, after which
     * the resolver falls back to the global default.
     */
    public Workspace setWorkModel(String workspaceId, WorkModel workModel)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        Workspace current = require(workspaceId);
        Workspace next = new Workspace(
                current.id(), current.name(), current.memoryMd(),
                current.isScratch(), workModel,
                current.createdAt(), Instant.now());
        store.saveWorkspace(next);
        return next;
    }

    public List<WorkspaceRepo> listRepos(String workspaceId)
    {
        require(workspaceId);
        return store.listRepos(workspaceId);
    }

    /**
     * Attach a repo to the workspace. Idempotent on
     * {@code (workspaceId, repoFullName)}.
     */
    public WorkspaceRepo addRepo(String workspaceId, String repoFullName, String defaultBaseBranch)
    {
        require(workspaceId);
        requireNonNull(repoFullName, "repoFullName is null");
        WorkspaceRepo repo = new WorkspaceRepo(
                workspaceId, repoFullName.trim(),
                trimToNull(defaultBaseBranch),
                /* autoFixEnabled */ false,
                Instant.now());
        store.addRepo(repo);
        return repo;
    }

    /**
     * Flip the headless auto-fix opt-in for a repo. Off by default,
     * per CLAUDE.md. AutomationCoordinator reads this when deciding
     * whether a failing-CI candidate triggers a notification only or
     * also a headless run.
     */
    public WorkspaceRepo setAutoFixEnabled(String workspaceId, String repoFullName, boolean enabled)
    {
        require(workspaceId);
        WorkspaceRepo existing = store.findRepo(workspaceId, repoFullName)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404),
                        repoFullName + " not attached to workspace " + workspaceId));
        WorkspaceRepo next = new WorkspaceRepo(
                existing.workspaceId(),
                existing.repoFullName(),
                existing.defaultBaseBranch(),
                enabled,
                existing.addedAt());
        store.addRepo(next);
        return next;
    }

    /**
     * Resolve the per-repo merge-target branch for ship-and-continue.
     * Used as the "from main" base when cutting the next task. Returns
     * empty when no override is configured; the caller then falls back
     * to {@code git defaultBranch}.
     */
    public Optional<String> findDefaultBaseBranch(String workspaceId, String repoFullName)
    {
        return store.findRepo(workspaceId, repoFullName)
                .map(WorkspaceRepo::defaultBaseBranch)
                .filter(s -> s != null && !s.isBlank());
    }

    private static String trimToNull(String s)
    {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
