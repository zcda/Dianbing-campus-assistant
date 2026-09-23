package com.pkb.conversation;

import com.pkb.rag.NumericCitationCheck;
import com.pkb.search.Source;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService service;

    public ConversationController(ConversationService service) {
        this.service = service;
    }

    @GetMapping
    public List<Conversation> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<Conversation> create() {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create());
    }

    @GetMapping("/{id}/messages")
    public List<MessageView> messages(@PathVariable long id) {
        return service.messages(id).stream().map(MessageView::from).toList();
    }

    public record MessageView(Long id, String role, String content, List<Source> sources,
                              LocalDateTime createdAt, String status,
                              NumericCitationCheck.Result numericCreditCitations) {
        static MessageView from(ChatMessage message) {
            var audit = "assistant".equals(message.role())
                    ? NumericCitationCheck.check(message.content(), message.sources()) : null;
            return new MessageView(message.id(), message.role(), message.content(), message.sources(),
                    message.createdAt(), message.status(), audit != null && audit.total() > 0 ? audit : null);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
