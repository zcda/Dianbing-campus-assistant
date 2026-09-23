package com.pkb.note;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 使用真实 PostgreSQL 验证三条 RETURNING 语句；JdbcTest 事务在测试后回滚。 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(NoteRepository.class)
class NoteRepositorySqlTest {

    @Autowired
    private NoteRepository notes;

    @Test
    void insertUpdateAndMetadataReturnNote() {
        Note created = notes.insert("SQL 回归测试", "正文", "manual", null);
        assertEquals("SQL 回归测试", created.title());

        Note updated = notes.update(created.id(), "更新标题", "更新正文").orElseThrow();
        assertEquals("更新标题", updated.title());
        assertEquals("更新正文", updated.content());

        Note withMetadata = notes.updateMetadata(created.id(), RuleMetadata.draft());
        assertEquals("draft", withMetadata.status());
    }
}
