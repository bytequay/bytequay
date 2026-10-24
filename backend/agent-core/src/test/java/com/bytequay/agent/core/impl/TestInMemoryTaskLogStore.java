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
package com.bytequay.agent.core.impl;

import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.TaskId;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestInMemoryTaskLogStore
{
    @Test
    void copiesBlobContentAndKeepsReadsTaskScoped()
    {
        InMemoryTaskLogStore store = new InMemoryTaskLogStore();
        TaskId taskId = TaskId.random();
        ByteBuffer content = ByteBuffer.wrap(new byte[] {0, 1, 2});
        content.position(1);
        BlobRef blob = store.writeBlob(taskId, content, "application/octet-stream");
        content.put(1, (byte) 9);

        assertThat(content.position()).isEqualTo(1);
        ByteBuffer read = store.readBlob(taskId, blob);
        assertThat(read.isReadOnly()).isTrue();
        assertThat(read.get()).isEqualTo((byte) 1);
        assertThat(read.get()).isEqualTo((byte) 2);
        assertThat(store.readBlob(taskId, blob).remaining()).isEqualTo(2);
        assertThatThrownBy(() -> store.readBlob(TaskId.random(), blob)).isInstanceOf(AgentStorageException.class);
        assertThatThrownBy(() -> store.readBlob(taskId, new BlobRef(blob.relativePath(), blob.mediaType(), 3))).isInstanceOf(AgentStorageException.class);
    }
}
