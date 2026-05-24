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

import com.bytequay.app.domain.Workspace;
import com.bytequay.app.domain.WorkspaceCardDto;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.WorkspaceStore;
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

    private final WorkspaceStore store;

    public WorkspaceService(WorkspaceStore store)
    {
        this.store = requireNonNull(store, "store is null");
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

    /** Soft cap on the display name. Trimmed; blank rejected. */
    private static final int NAME_MAX_CHARS = 80;

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
                current.isScratch(), current.createdAt(), Instant.now());
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
                current.isScratch(), current.createdAt(), Instant.now());
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

    public void removeRepo(String workspaceId, String repoFullName)
    {
        require(workspaceId);
        store.removeRepo(workspaceId, repoFullName);
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

    public WorkspaceRepo setDefaultBaseBranch(String workspaceId, String repoFullName, String branch)
    {
        require(workspaceId);
        String normalised = trimToNull(branch);
        store.setDefaultBaseBranch(workspaceId, repoFullName, normalised);
        return store.findRepo(workspaceId, repoFullName)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404),
                        repoFullName + " not attached to workspace " + workspaceId));
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
