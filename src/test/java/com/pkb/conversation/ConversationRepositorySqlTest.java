package com.pkb.conversation;

import com.pkb.testinfra.RequiresPostgres;

import com.pkb.search.Source;
import com.pkb.config.RagProperties;
import com.pkb.config.SchemaInitializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 使用真实 PostgreSQL 验证会话和带 JSON 引用的消息写入。 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ConversationRepository.class, SchemaInitializer.class, ConversationRepositorySqlTest.TestConfig.class})
@EnableConfigurationProperties(RagProperties.class)
@RequiresPostgres
class ConversationRepositorySqlTest {

    @Configuration
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    private ConversationRepository conversations;

    @Test
    void insertConversationAndMessage() {
        Conversation conversation = conversations.insert("SQL 回归测试");
        ChatMessage message = conversations.insertMessage(conversation.id(), "assistant", "回答内容", List.of());

        assertEquals("assistant", message.role());
        assertEquals("回答内容", message.content());
        assertEquals(List.of(), message.sources());
    }
}
