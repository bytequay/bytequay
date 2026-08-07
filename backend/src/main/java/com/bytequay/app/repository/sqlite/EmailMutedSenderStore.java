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
package com.bytequay.app.repository.sqlite;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

@Component
public class EmailMutedSenderStore
{
    private final EmailMutedSenderJpaRepository repo;

    EmailMutedSenderStore(EmailMutedSenderJpaRepository repo)
    {
        this.repo = requireNonNull(repo, "repo is null");
    }

    @Transactional
    /** Adds a mute. Idempotent — re-muting refreshes the timestamp. */
    public void mute(String accountEmail, String senderEmail, Instant mutedAt)
    {
        EmailMutedSenderEntity entity = new EmailMutedSenderEntity();
        entity.setId(new EmailMutedSenderEntity.EmailMutedSenderKey(accountEmail, senderEmail));
        entity.setMutedAtMs(mutedAt.toEpochMilli());
        repo.save(entity);
    }

    @Transactional
    /** Removes a mute. No-op when the row doesn't exist. */
    public void unmute(String accountEmail, String senderEmail)
    {
        repo.deleteById(new EmailMutedSenderEntity.EmailMutedSenderKey(accountEmail, senderEmail));
    }

    /** Returns the muted addresses for an account, undefined order. */
    public List<String> listMuted(String accountEmail)
    {
        return repo.findByIdAccountEmail(accountEmail).stream()
                .map(e -> e.getId().getSenderEmail())
                .toList();
    }
}
