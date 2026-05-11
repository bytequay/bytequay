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

import com.bytequay.app.domain.EmailThreadDetail;
import com.bytequay.app.domain.EmailThreadMeta;
import com.bytequay.app.service.gmail.EmailMuteService;
import com.bytequay.app.service.gmail.EmailService;
import com.google.common.collect.ImmutableMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * REST surface for the Gmail inbox. Operates on Gmail's
 * <strong>thread</strong> abstraction — one row per conversation, not
 * per individual message — so multi-message threads (PR notifications,
 * email back-and-forth) collapse into a single inbox card the way
 * Gmail's web UI shows them.
 */
@RestController
@RequestMapping("/api/email")
public class EmailController
{
    /** Default page size — matches the design doc's "render the
     *  most recent 50 threads" target for the master-detail list. */
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final EmailService emailService;
    private final EmailMuteService muteService;

    public EmailController(EmailService emailService, EmailMuteService muteService)
    {
        this.emailService = requireNonNull(emailService, "emailService is null");
        this.muteService = requireNonNull(muteService, "muteService is null");
    }

    /**
     * GET /api/email/threads?account={email}&pageSize={n}
     *
     * <p>Returns the inbox grouped by thread, newest first. {@code pageSize}
     * defaults to 50 and is capped at 500 (Gmail's own limit).
     */
    @GetMapping("/threads")
    public List<EmailThreadMeta> listThreads(
            @RequestParam String account,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_PAGE_SIZE) int pageSize)
    {
        return emailService.listInboxThreads(account, pageSize);
    }

    /** POST /api/email/threads/refresh?account={email} — force an
     *  incremental sync against Gmail and return the resulting cached
     *  inbox. The Refresh button in the UI calls this. */
    @PostMapping("/threads/refresh")
    public List<EmailThreadMeta> refresh(
            @RequestParam String account,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_PAGE_SIZE) int pageSize)
    {
        return emailService.refresh(account, pageSize);
    }

    /**
     * GET /api/email/threads/{id}?account={email} — full thread
     * including every message with parsed body.
     */
    @GetMapping("/threads/{id}")
    public EmailThreadDetail getThread(@PathVariable String id, @RequestParam String account)
    {
        return emailService.getThread(account, id);
    }

    /** POST /api/email/threads/{id}/archive?account={email} — removes
     *  INBOX label from every message in the thread. */
    @PostMapping("/threads/{id}/archive")
    public Map<String, String> archive(@PathVariable String id, @RequestParam String account)
    {
        emailService.archiveThread(account, id);
        return ImmutableMap.of("result", "archived", "id", id);
    }

    /** POST /api/email/threads/{id}/mark-read?account={email} — removes
     *  UNREAD label from every message in the thread. */
    @PostMapping("/threads/{id}/mark-read")
    public Map<String, String> markRead(@PathVariable String id, @RequestParam String account)
    {
        emailService.markThreadRead(account, id);
        return ImmutableMap.of("result", "read", "id", id);
    }

    /** POST /api/email/threads/{id}/mark-unread?account={email} — adds
     *  UNREAD label to every message in the thread. */
    @PostMapping("/threads/{id}/mark-unread")
    public Map<String, String> markUnread(@PathVariable String id, @RequestParam String account)
    {
        emailService.markThreadUnread(account, id);
        return ImmutableMap.of("result", "unread", "id", id);
    }

    /** POST /api/email/threads/{id}/read-and-archive?account={email} —
     *  removes both INBOX and UNREAD in one Gmail call. Drives the
     *  open-an-unread-thread auto action. */
    @PostMapping("/threads/{id}/read-and-archive")
    public Map<String, String> readAndArchive(@PathVariable String id, @RequestParam String account)
    {
        emailService.readAndArchiveThread(account, id);
        return ImmutableMap.of("result", "read-and-archived", "id", id);
    }

    /** POST /api/email/threads/{id}/keep-in-inbox?account={email} —
     *  re-adds INBOX (and clears UNREAD), reversing an auto-archive. */
    @PostMapping("/threads/{id}/keep-in-inbox")
    public Map<String, String> keepInInbox(@PathVariable String id, @RequestParam String account)
    {
        emailService.keepThreadInInbox(account, id);
        return ImmutableMap.of("result", "kept-in-inbox", "id", id);
    }

    /** POST /api/email/threads/{id}/reply?account={email} body=
     *  {@code {"body": "..."}} — sends a plain-text reply to the
     *  latest message in the thread. */
    @PostMapping("/threads/{id}/reply")
    public Map<String, String> reply(
            @PathVariable String id,
            @RequestParam String account,
            @RequestBody ReplyRequest payload)
    {
        emailService.sendReply(account, id, payload.body());
        return ImmutableMap.of("result", "sent", "id", id);
    }

    /** Body shape for {@link #reply}. Only the message body for now;
     *  Reply-All / custom To / attachments come later. */
    public record ReplyRequest(String body) {}

    /** GET /api/email/muted-senders?account={email} — returns the
     *  currently-muted sender addresses for an account, sorted. */
    @GetMapping("/muted-senders")
    public Map<String, List<String>> listMuted(@RequestParam String account)
    {
        return ImmutableMap.of("senders", muteService.listMuted(account));
    }

    /** POST /api/email/muted-senders?account={email} body=
     *  {@code {"sender": "..."}} — adds a sender to the mute list.
     *  Accepts both raw addresses and {@code "Name <addr>"} headers. */
    @PostMapping("/muted-senders")
    public Map<String, String> mute(
            @RequestParam String account,
            @RequestBody MuteRequest payload)
    {
        muteService.mute(account, payload.sender());
        return ImmutableMap.of("result", "muted");
    }

    /** DELETE /api/email/muted-senders/{sender}?account={email} —
     *  removes a sender from the mute list. */
    @DeleteMapping("/muted-senders/{sender}")
    public Map<String, String> unmute(
            @PathVariable String sender,
            @RequestParam String account)
    {
        muteService.unmute(account, sender);
        return ImmutableMap.of("result", "unmuted");
    }

    /** Body shape for {@link #mute}. */
    public record MuteRequest(String sender) {}
}
