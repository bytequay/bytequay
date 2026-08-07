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

import com.bytequay.app.domain.AgentQuestion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class AgentQuestionStore
{
    private static final TypeReference<List<AgentQuestion.Option>> OPTION_LIST = new TypeReference<>() { };

    private final AgentQuestionJpaRepository repository;
    private final ObjectMapper mapper;

    AgentQuestionStore(AgentQuestionJpaRepository repository, ObjectMapper mapper)
    {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional
    /** Insert or update a question; returns the persisted row. */
    public AgentQuestion save(AgentQuestion question)
    {
        AgentQuestionEntity entity = new AgentQuestionEntity();
        entity.setId(question.id());
        entity.setThreadId(question.threadId());
        entity.setTaskId(question.taskId());
        entity.setToolCallId(question.toolCallId());
        entity.setQuestion(question.question());
        entity.setContext(question.context());
        entity.setOptionsJson(writeOptions(question.options()));
        entity.setAllowFreeForm(question.allowFreeForm());
        entity.setStatus(question.status());
        entity.setAnswerOptionId(question.answerOptionId());
        entity.setAnswerFreeForm(question.answerFreeForm());
        entity.setCreatedAtMs(question.createdAt().toEpochMilli());
        entity.setAnsweredAtMs(question.answeredAt() == null ? null : question.answeredAt().toEpochMilli());
        return toDomain(repository.save(entity));
    }

    @Transactional(readOnly = true)
    /** One question by id. */
    public Optional<AgentQuestion> findById(String id)
    {
        return repository.findById(id).map(this::toDomain);
    }

    @Transactional(readOnly = true)
    /** Open (unanswered) questions on a thread, oldest-first. */
    public List<AgentQuestion> findOpenByThread(String threadId)
    {
        return repository.findByThreadIdAndStatusOrderByCreatedAtMsAsc(threadId, AgentQuestion.STATUS_OPEN)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private AgentQuestion toDomain(AgentQuestionEntity e)
    {
        return new AgentQuestion(
                e.getId(),
                e.getThreadId(),
                e.getTaskId(),
                e.getToolCallId(),
                e.getQuestion(),
                e.getContext(),
                readOptions(e.getOptionsJson()),
                e.isAllowFreeForm(),
                e.getStatus(),
                e.getAnswerOptionId(),
                e.getAnswerFreeForm(),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                e.getAnsweredAtMs() == null ? null : Instant.ofEpochMilli(e.getAnsweredAtMs()));
    }

    private String writeOptions(List<AgentQuestion.Option> options)
    {
        try {
            return mapper.writeValueAsString(options);
        }
        catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private List<AgentQuestion.Option> readOptions(String json)
    {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, OPTION_LIST);
        }
        catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
