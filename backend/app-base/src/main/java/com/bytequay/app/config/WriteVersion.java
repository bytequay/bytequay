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

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Changes after every committed transaction that wrote to the database, so a read built only from stored rows can answer "not
 * modified" until something writes again; each table also keeps its own version. Read a version before reading the rows: a
 * write that lands in between then makes the next request rebuild.
 */
@Component
public class WriteVersion
{
    /** Where a write whose statement names no table is counted; every set of tables moves with it. */
    public static final String UNNAMED = "?";

    // A restarted backend counts from zero again; the boot part keeps its versions from matching the old process's.
    private final String boot = UUID.randomUUID().toString();
    private final AtomicLong writes = new AtomicLong();
    private final Map<String, AtomicLong> tables = new ConcurrentHashMap<>();
    private final TransactionSynchronization committed = new TransactionSynchronization()
    {
        @Override
        public void afterCommit()
        {
            writes.incrementAndGet();
        }
    };
    private final Map<String, TransactionSynchronization> tableCommits = new ConcurrentHashMap<>();

    /** Marks the current transaction as one that writes; the version moves once it commits. */
    public void written()
    {
        register(committed, writes);
    }

    /** Marks the current transaction as one that writes {@code table}; that table's version moves once it commits. */
    public void written(String table)
    {
        AtomicLong counter = counter(table);
        register(tableCommits.computeIfAbsent(table, ignored -> new TransactionSynchronization()
        {
            @Override
            public void afterCommit()
            {
                counter.incrementAndGet();
            }
        }), counter);
    }

    public String current()
    {
        return boot + "-" + writes.get();
    }

    public String current(String table)
    {
        return boot + "-" + counter(table).get();
    }

    /** Moves with a write to any of {@code tables}, or to a table a statement did not name. */
    public String current(Collection<String> tables)
    {
        StringBuilder version = new StringBuilder(boot);
        tables.forEach(table -> version.append('-').append(counter(table).get()));
        return version.append('-').append(counter(UNNAMED).get()).toString();
    }

    private AtomicLong counter(String table)
    {
        return tables.computeIfAbsent(table, ignored -> new AtomicLong());
    }

    private static void register(TransactionSynchronization synchronization, AtomicLong counter)
    {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // Each transaction keeps a set, so one that sends many statements moves the version once.
            TransactionSynchronizationManager.registerSynchronization(synchronization);
        }
        else {
            counter.incrementAndGet();
        }
    }
}
