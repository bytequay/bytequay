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
package com.bytequay.app.web;

import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.runtime.UpstreamSyncCommands;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.SelectedCommit;
import com.bytequay.app.flow.upstream.UpstreamSyncViews;
import com.bytequay.app.flow.upstream.UpstreamSyncViews.SyncJob;
import com.bytequay.app.flow.upstream.UpstreamSyncViews.SyncRunDetail;
import com.bytequay.app.service.workspaces.WorkspaceRelationService;
import com.bytequay.app.service.workspaces.WorkspaceRelationService.ResolvedRelation;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * The upstream cherry-pick surface, on the greenfield flow.
 *
 * <p>The range picker is the only entry point: a run owns a range, so there is
 * deliberately no way to attach one to a pull request that already exists.
 *
 * <p>Runs started before this existed keep running on the retired path and are
 * listed by {@link WorkspaceRepositoryController}. Neither side translates the
 * other's records; the home page simply reads both until the older ones drain.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/upstream/syncs")
public final class WorkspaceUpstreamSyncController
{
    private static final int DEFAULT_LIST_LIMIT = 25;
    private static final int MAX_SELECTED_COMMITS = 2_000;
    private static final int MAX_REPAIR_TURNS = 500;

    private final WorkspaceRepositoryResolver resolver;
    private final WorkspaceRelationService relations;
    private final UpstreamSyncViews views;
    private final UpstreamSyncCommands commands;
    private final UserGates gates;

    public WorkspaceUpstreamSyncController(
            WorkspaceRepositoryResolver resolver,
            WorkspaceRelationService relations,
            UpstreamSyncViews views,
            UpstreamSyncCommands commands,
            UserGates gates)
    {
        this.resolver = requireNonNull(resolver, "resolver is null");
        this.relations = requireNonNull(relations, "relations is null");
        this.views = requireNonNull(views, "views is null");
        this.commands = requireNonNull(commands, "commands is null");
        this.gates = requireNonNull(gates, "gates is null");
    }

    @GetMapping
    public List<SyncJob> list(
            @PathVariable String workspaceId,
            @RequestParam(defaultValue = "" + DEFAULT_LIST_LIMIT) int limit)
    {
        return views.list(
                resolver.resolve(workspaceId).fullName(),
                bounded(limit));
    }

    @GetMapping("/{runId}")
    public SyncRunDetail run(
            @PathVariable String workspaceId,
            @PathVariable String runId)
    {
        return inWorkspace(workspaceId, views.detail(runId)
                .orElseThrow(WorkspaceUpstreamSyncController::unknownRun));
    }

    /**
     * Starts one run over the confirmed selection.
     *
     * <p>Nothing reaches GitHub from here: the run picks locally and stops at
     * its publish gate, which only {@link #authorizePublish} passes.
     */
    @PostMapping
    public ResponseEntity<SyncJob> start(
            @PathVariable String workspaceId,
            @RequestBody StartSyncBody body)
    {
        requireNonNull(body, "body is null");
        ResolvedRelation relation = relations.requireResolved(workspaceId);
        String repositoryId = relation.target().fullName();
        List<SelectedCommit> selected = selection(body);
        String goalText = body.goalText() == null || body.goalText().isBlank()
                ? "Sync " + selected.size() + " upstream commits"
                : body.goalText();
        String sourceRemote = relation.upstream().fullName();
        String sourceFromRef = text(
                body.sourceFromRef(), selected.getFirst().sha());
        String sourceToRef = text(
                body.sourceToRef(), selected.getLast().sha());
        String targetRef = text(body.targetRef(), "HEAD");
        int repairTurnBudget = repairTurns(body.repairTurnBudget());
        UpstreamSyncCommands.StartReceipt receipt = commands.startConfirmed(
                requestKey(
                        repositoryId, selected, goalText, sourceRemote,
                        sourceFromRef, sourceToRef, targetRef,
                        repairTurnBudget),
                repositoryId,
                goalText,
                sourceRemote,
                sourceFromRef,
                sourceToRef,
                targetRef,
                selected,
                null,
                repairTurnBudget,
                relation.upstreamClone(),
                relation.targetClone());
        return ResponseEntity.accepted().body(
                views.job(receipt.run().runId())
                        .orElseThrow(WorkspaceUpstreamSyncController::unknownRun));
    }

    /**
     * Reopens a parked run, optionally with more repair turns.
     *
     * <p>A park is a checkpoint, not a wall: the user resumes the same run and
     * the same selection rather than starting a second one over it. The grant
     * is additive, so a budget park resumes with room to continue.
     */
    @PostMapping("/{runId}/resume")
    public ResponseEntity<SyncRunDetail> resume(
            @PathVariable String workspaceId,
            @PathVariable String runId,
            @RequestBody(required = false) ResumeSyncBody body)
    {
        inWorkspace(workspaceId, views.detail(runId)
                .orElseThrow(WorkspaceUpstreamSyncController::unknownRun));
        int additionalTurns = body == null
                || body.additionalRepairTurns() == null
                ? 0 : body.additionalRepairTurns();
        if (additionalTurns < 0 || additionalTurns > MAX_REPAIR_TURNS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "additional repair turns are out of range");
        }
        try {
            commands.resume(runId, additionalTurns);
        }
        catch (IllegalStateException rejected) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, rejected.getMessage(), rejected);
        }
        return ResponseEntity.accepted().body(
                views.detail(runId)
                        .orElseThrow(WorkspaceUpstreamSyncController::unknownRun));
    }

    /**
     * The user's own authorization of the first push, against the exact gate
     * revision the surface displayed. A digest that no longer matches means
     * the run moved under the reader, and the gate refuses rather than
     * publishing something else.
     */
    @PostMapping("/{runId}/publish")
    public ResponseEntity<SyncRunDetail> authorizePublish(
            @PathVariable String workspaceId,
            @PathVariable String runId,
            @RequestBody AuthorizePublishBody body)
    {
        requireNonNull(body, "body is null");
        SyncRunDetail run = inWorkspace(workspaceId, views.detail(runId)
                .orElseThrow(WorkspaceUpstreamSyncController::unknownRun));
        UpstreamSyncViews.SyncGate gate = run.publishGate();
        if (gate == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "the run has no publish gate yet");
        }
        try {
            gates.authorizeInitialPublish(
                    gate.gateId(),
                    body.revision(),
                    body.subjectDigest(),
                    body.actionDigest(),
                    "sync-publish:" + gate.gateId() + ":" + body.revision());
        }
        catch (UserGates.AuthorizationRejectedException rejected) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, rejected.reasonCode(), rejected);
        }
        return ResponseEntity.accepted().body(
                views.detail(runId)
                        .orElseThrow(WorkspaceUpstreamSyncController::unknownRun));
    }

    private SyncRunDetail inWorkspace(String workspaceId, SyncRunDetail run)
    {
        // A run id is global; the surface is workspace-scoped. Serving another
        // workspace's run through this path would be a quiet scope leak.
        if (!resolver.resolve(workspaceId).fullName()
                .equals(run.job().repositoryId())) {
            throw unknownRun();
        }
        return run;
    }

    private static List<SelectedCommit> selection(StartSyncBody body)
    {
        List<SelectedCommitBody> commits = body.commits();
        if (commits == null || commits.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "no commit was selected");
        }
        if (commits.size() > MAX_SELECTED_COMMITS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "too many commits were selected");
        }
        return commits.stream()
                .map(commit -> new SelectedCommit(
                        commit.sha(), commit.subject()))
                .toList();
    }

    private static int repairTurns(Integer requested)
    {
        if (requested == null) {
            return UpstreamSyncCommands.DEFAULT_REPAIR_TURN_BUDGET;
        }
        if (requested < 0 || requested > MAX_REPAIR_TURNS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "repair budget is out of range");
        }
        return requested;
    }

    private static String text(String value, String fallback)
    {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * Idempotent on the command itself, so a double-submitted picker returns
     * the run it already started rather than a second one over the same range.
     */
    private static String requestKey(
            String repositoryId,
            List<SelectedCommit> selected,
            String goalText,
            String sourceRemote,
            String sourceFromRef,
            String sourceToRef,
            String targetRef,
            int repairTurnBudget)
    {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        update(digest, repositoryId);
        update(digest, goalText);
        update(digest, sourceRemote);
        update(digest, sourceFromRef);
        update(digest, sourceToRef);
        update(digest, targetRef);
        update(digest, Integer.toString(repairTurnBudget));
        for (SelectedCommit commit : selected) {
            update(digest, commit.sha());
        }
        return "upstream-sync-command:v2:"
                + HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value)
    {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length)
                .getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        digest.update(bytes);
    }

    private static int bounded(int limit)
    {
        if (limit < 1 || limit > UpstreamSyncViews.MAX_LIST_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and "
                            + UpstreamSyncViews.MAX_LIST_SIZE);
        }
        return limit;
    }

    private static ResponseStatusException unknownRun()
    {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND, "unknown sync run");
    }

    public record SelectedCommitBody(String sha, String subject) {}

    /**
     * @param repairTurnBudget conflict-repair turns for the whole range; the
     *         run's only bound, and null takes the default.
     */
    public record StartSyncBody(
            List<SelectedCommitBody> commits,
            String goalText,
            String sourceRemote,
            String sourceFromRef,
            String sourceToRef,
            String targetRef,
            Integer repairTurnBudget) {}

    public record AuthorizePublishBody(
            long revision, String subjectDigest, String actionDigest) {}

    /**
     * A parked run's resume request.
     *
     * @param additionalRepairTurns added to the run's budget; null adds none
     */
    public record ResumeSyncBody(Integer additionalRepairTurns) {}
}
