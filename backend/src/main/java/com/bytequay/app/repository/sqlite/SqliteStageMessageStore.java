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

import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadScope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Component
public class SqliteStageMessageStore
{
    private final StageMessageJpaRepository messages;

    SqliteStageMessageStore(StageMessageJpaRepository messages)
    {
        this.messages = requireNonNull(messages, "messages is null");
    }

    @Transactional
    public void appendMessage(ThreadMessage message)
    {
        requireNonNull(message, "message is null");
        requireNonNull(message.stageId(), "stage message has no stageId");
        StageMessageEntity entity = new StageMessageEntity();
        entity.setId(message.id());
        entity.setStageId(message.stageId());
        entity.setTaskId(message.taskId());
        entity.setThreadId(message.threadId());
        entity.setSeq(message.seq());
        entity.setRole(message.role());
        entity.setType(message.type());
        entity.setContentJson(message.contentJson());
        entity.setDurationMs(message.durationMs());
        entity.setTokensIn(message.tokensIn());
        entity.setTokensOut(message.tokensOut());
        entity.setCostUsdMilli(message.costUsdMilli());
        entity.setTsMs(message.ts().toEpochMilli());
        messages.save(entity);
    }

    public List<ThreadMessage> listMessages(String stageId)
    {
        return messages.findByStageIdOrderBySeqAsc(stageId).stream()
                .map(SqliteStageMessageStore::toMessage)
                .toList();
    }

    public List<ThreadMessage> listMessagesByTask(String taskId)
    {
        return messages.findByTaskIdOrderBySeqAsc(taskId).stream()
                .map(SqliteStageMessageStore::toMessage)
                .toList();
    }

    public Optional<Long> maxMessageSeq(String stageId)
    {
        return Optional.ofNullable(messages.maxSeq(stageId));
    }

    public long sumTokensBetween(String stageId, long firstSeq, long lastSeq)
    {
        return messages.sumTokensBetween(stageId, firstSeq, lastSeq);
    }

    public List<ThreadMessage> listMessagesBetween(String stageId, long firstSeq, long lastSeq)
    {
        return messages.findByStageIdAndSeqBetween(stageId, firstSeq, lastSeq).stream()
                .map(SqliteStageMessageStore::toMessage)
                .toList();
    }

    @Transactional
    public void deleteByStage(String stageId)
    {
        messages.deleteByStageId(stageId);
    }

    /** A stage_messages row is STAGE-scoped by construction, so the scope is
     *  fixed rather than read from a column. */
    private static ThreadMessage toMessage(StageMessageEntity e)
    {
        return new ThreadMessage(
                e.getId(),
                e.getThreadId(),
                e.getTaskId(),
                e.getSeq(),
                e.getRole(),
                e.getType(),
                e.getContentJson(),
                e.getDurationMs(),
                e.getTokensIn(),
                e.getTokensOut(),
                e.getCostUsdMilli(),
                Instant.ofEpochMilli(e.getTsMs()),
                e.getStageId(),
                ThreadScope.STAGE);
    }
}
