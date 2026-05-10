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
package com.bytequay.app.scheduler;

import com.bytequay.app.domain.Credential;
import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.gmail.EmailSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.bytequay.app.service.gmail.GmailOAuthService.GMAIL_ACCOUNT_NAME;
import static java.util.Objects.requireNonNull;

/**
 * Per-account incremental sync tick. Every 60 seconds, walks the
 * connected Gmail accounts and patches their local mirror via
 * {@link EmailSyncService#incrementalSync(String)}. Keeps the inbox
 * list view fresh without the user clicking refresh.
 *
 * <p>Re-entrancy guarded with an {@link AtomicBoolean} so a slow
 * Gmail call doesn't queue a backlog of overlapping ticks.
 */
@Component
public class GmailPollingJob
{
    private static final Logger log = LoggerFactory.getLogger(GmailPollingJob.class);

    private final CredentialService credentialService;
    private final EmailSyncService syncService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public GmailPollingJob(CredentialService credentialService, EmailSyncService syncService)
    {
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.syncService = requireNonNull(syncService, "syncService is null");
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void tick()
    {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            List<Credential> accounts = credentialService.listByTypeAndName(
                    CredentialType.ACCOUNT, GMAIL_ACCOUNT_NAME);
            for (Credential c : accounts) {
                String email = c.label() != null ? c.label() : c.instanceName();
                if (email == null || email.isBlank()) {
                    continue;
                }
                try {
                    syncService.incrementalSync(email);
                }
                catch (Exception e) {
                    log.warn("Gmail incremental sync for {} failed: {}", email, e.getMessage());
                }
            }
        }
        finally {
            running.set(false);
        }
    }
}
