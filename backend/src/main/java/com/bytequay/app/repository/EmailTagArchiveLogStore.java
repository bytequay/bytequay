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
package com.bytequay.app.repository;

import com.bytequay.app.domain.EmailTagArchiveEntry;

import java.util.List;

/**
 * Persistence boundary for the per-account audit log of tag-driven
 * Gmail archives. Each entry captures enough thread metadata for the
 * "Archived" view to render without an IMAP round-trip.
 */
public interface EmailTagArchiveLogStore
{
    /** Inserts or replaces an entry keyed by (account, gmailThreadId). */
    void save(EmailTagArchiveEntry entry);

    /** Removes a single log entry — the user clicked "Keep in inbox"
     *  on a previously-archived thread. */
    void delete(String accountEmail, String gmailThreadId);

    /** All log entries for an account, newest archive first. */
    List<EmailTagArchiveEntry> listByAccount(String accountEmail);
}
