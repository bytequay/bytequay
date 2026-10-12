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
package com.bytequay.app.google;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/google")
public class GoogleWorkspaceController
{
    private final GoogleWorkspaceService google;
    private final GoogleInboxNotices notices;

    public GoogleWorkspaceController(GoogleWorkspaceService google, GoogleInboxNotices notices)
    {
        this.google = requireNonNull(google, "google is null");
        this.notices = requireNonNull(notices, "notices is null");
    }

    @GetMapping("/notices")
    public List<GoogleMailNotice> notices()
    {
        return notices.current();
    }

    @GetMapping
    public GoogleSnapshot.Status status()
    {
        return google.status();
    }

    @PostMapping("/install")
    public GoogleSnapshot.Status install()
    {
        return google.install();
    }

    @PostMapping("/connect")
    public GoogleSnapshot.Status connect()
    {
        return google.connect();
    }

    @DeleteMapping
    public GoogleSnapshot.Status disconnect()
    {
        return google.disconnect();
    }

    @PostMapping("/cancel")
    public GoogleSnapshot.Status cancel()
    {
        return google.cancel();
    }

    @PostMapping("/mail")
    public GoogleSnapshot.Status mail(@RequestBody MailRequest request)
    {
        return google.refreshMail(request.query(), request.pageToken());
    }

    @PostMapping("/calendar")
    public GoogleSnapshot.Status calendar(@RequestBody CalendarRequest request)
    {
        return google.refreshCalendar(request.calendarId(), request.timeMin(), request.timeMax());
    }

    @PutMapping("/agent-access")
    public GoogleSnapshot.Status agentAccess(@RequestBody AgentAccess request)
    {
        return google.agentAccess(request.enabled());
    }

    @PostMapping("/accounts")
    public GoogleSnapshot.Status addAccount()
    {
        return google.connect(true);
    }

    @PutMapping("/account")
    public GoogleSnapshot.Status selectAccount(@RequestBody AccountRequest request)
    {
        return google.select(request.profile());
    }

    @PostMapping("/chat-command")
    public List<String> chatCommand(@RequestBody AccountRequest request)
    {
        return google.chatCommand(request.profile());
    }

    @PostMapping("/archive")
    public GoogleSnapshot.Status archive(@RequestBody ArchiveRequest request)
    {
        return google.archive(request.profile(), request.messageId());
    }

    @PostMapping("/send")
    public GoogleSnapshot.Status send(@RequestBody SendRequest request)
    {
        return google.send(request.profile(), request.to(), request.subject(), request.body(), request.replyId());
    }

    public record AccountRequest(String profile) {}

    public record ArchiveRequest(String profile, String messageId) {}

    public record SendRequest(String profile, String to, String subject, String body, String replyId) {}

    public record MailRequest(String query, String pageToken) {}

    public record CalendarRequest(String calendarId, String timeMin, String timeMax) {}

    public record AgentAccess(boolean enabled) {}
}
