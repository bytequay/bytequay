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

import com.bytequay.app.domain.EmailMessageMeta;
import com.bytequay.app.service.gmail.EmailService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * REST surface for the read-only Gmail inbox view (slice 2 of the
 * email feature). Lists messages for one account; body / archive /
 * mark-read are deferred to the next slice.
 */
@RestController
@RequestMapping("/api/email")
public class EmailController
{
    /** Default page size — matches the design doc's "render the
     *  most recent 50 messages" target for the master-detail list. */
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final EmailService emailService;

    public EmailController(EmailService emailService)
    {
        this.emailService = requireNonNull(emailService, "emailService is null");
    }

    /**
     * GET /api/email/messages?account={email}&pageSize={n}
     *
     * <p>Returns the inbox for the requested account, newest first.
     * {@code pageSize} defaults to 50 and is capped at 500 (Gmail's
     * own limit).
     */
    @GetMapping("/messages")
    public List<EmailMessageMeta> listMessages(
            @RequestParam String account,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_PAGE_SIZE) int pageSize)
    {
        return emailService.listInbox(account, pageSize);
    }
}
