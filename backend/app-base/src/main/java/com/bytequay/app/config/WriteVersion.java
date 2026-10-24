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
package com.bytequay.app.config;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Changes after every committed transaction that wrote to the database, so a read built only from stored rows can answer "not
 * modified" until something writes again. Read the version before reading the rows: a write that lands in between then makes the
 * next request rebuild.
 */
@Component
public class WriteVersion
{
    // A restarted backend counts from zero again; the boot part keeps its versions from matching the old process's.
    private final String boot = UUID.randomUUID().toString();
    private final AtomicLong writes = new AtomicLong();
    private final TransactionSynchronization committed = new TransactionSynchronization()
    {
        @Override
        public void afterCommit()
        {
            writes.incrementAndGet();
        }
    };

    /** Marks the current transaction as one that writes; the version moves once it commits. */
    public void written()
    {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // Each transaction keeps a set, so one that sends many statements moves the version once.
            TransactionSynchronizationManager.registerSynchronization(committed);
        }
        else {
            writes.incrementAndGet();
        }
    }

    public String current()
    {
        return boot + "-" + writes.get();
    }
}
