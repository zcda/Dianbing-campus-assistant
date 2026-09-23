package com.pkb.conversation;

import com.pkb.search.Source;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConversationMessageViewTest {
    @Test
    void reconstructsCitationWarningDataForStoredAssistantAnswer() {
        var source = new Source(1, 1, "培养方案", 221,
                "软件工程课程总学分不低于23学分。", 0.8);
        var message = new ChatMessage(10L, "assistant",
                "学位课至少14学分，课程总学分至少23学分。[1]", List.of(source), null);

        var view = ConversationController.MessageView.from(message);

        assertEquals(List.of("学位课 14学分"), view.numericCreditCitations().unsupported());
        assertEquals(message.content(), view.content());
    }

    @Test
    void userMessagesHaveNoCitationAudit() {
        var message = new ChatMessage(11L, "user", "学位课至少14学分吗？", List.of(), null);

        assertNull(ConversationController.MessageView.from(message).numericCreditCitations());
    }
}
