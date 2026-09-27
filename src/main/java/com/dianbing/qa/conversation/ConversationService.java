package com.dianbing.qa.conversation;

import com.dianbing.qa.retrieval.Source;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Service
public class ConversationService {

    /** 新会话的占位标题，首条提问会把它替换掉 */
    public static final String DEFAULT_TITLE = "新会话";
    private static final int MAX_TITLE_LENGTH = 30;
    /** 改写历史窗口：助手回复保留条数（D6：被中断的半截消息不占名额） */
    private static final int MAX_ASSISTANT_HISTORY = 2;
    /** 历史扫描窗口：够覆盖"全部提问 + 2 条助手回复"的近期范围 */
    private static final int HISTORY_SCAN_SIZE = 16;

    private final ConversationRepository conversations;

    public ConversationService(ConversationRepository conversations) {
        this.conversations = conversations;
    }

    public List<Conversation> list() {
        return conversations.findAll();
    }

    public Conversation get(long id) {
        return conversations.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + id));
    }

    public Conversation create() {
        return conversations.insert(DEFAULT_TITLE);
    }

    @Transactional
    public void delete(long id) {
        if (!conversations.deleteById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + id);
        }
        // chat_message 通过外键 ON DELETE CASCADE 级联删除
    }

    public List<ChatMessage> messages(long id) {
        get(id);
        return conversations.findMessages(id);
    }

    /** 记录提问；会话还是占位标题时用首条提问命名 */
    @Transactional
    public void appendUserMessage(long conversationId, String question) {
        Conversation conversation = get(conversationId);
        if (DEFAULT_TITLE.equals(conversation.title())) {
            conversations.updateTitle(conversationId, titleFrom(question));
        }
        conversations.touch(conversationId);
        conversations.insertMessage(conversationId, "user", question, null);
    }

    /** 记录回答，同时把本次引用列表一起落库（前端刷新后仍可点回分块） */
    @Transactional
    public void appendAssistantMessage(long conversationId, String answer, List<Source> sources) {
        conversations.insertMessage(conversationId, "assistant", answer, sources);
        conversations.touch(conversationId);
    }

    /** 取最近若干条消息作为多轮上下文 */
    public List<ChatMessage> recentHistory(long conversationId, int limit) {
        return conversations.findRecentMessages(conversationId, limit);
    }

    /**
     * 改写与 Prompt 共用的历史窗口（设计文档 D6 第 6 点）：
     * 保留全部历史提问（指代的落点绝大多数是用户自己提过的主体）；
     * 助手回复只留最近 2 条，且被中断/被限流的半截消息（status != NORMAL）不占名额。
     */
    public List<ChatMessage> rewriteHistory(long conversationId) {
        List<ChatMessage> recent = conversations.findRecentMessages(conversationId, HISTORY_SCAN_SIZE);
        List<ChatMessage> result = new ArrayList<>();
        int assistantKept = 0;
        for (int i = recent.size() - 1; i >= 0; i--) {
            ChatMessage message = recent.get(i);
            if ("assistant".equals(message.role())) {
                if (message.normal() && assistantKept < MAX_ASSISTANT_HISTORY) {
                    result.add(0, message);
                    assistantKept++;
                }
            } else {
                result.add(0, message);
            }
        }
        return result;
    }

    private String titleFrom(String question) {
        String title = question.strip().replaceAll("\\s+", " ");
        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
    }
}