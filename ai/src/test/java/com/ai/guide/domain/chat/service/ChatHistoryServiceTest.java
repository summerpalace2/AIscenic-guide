package com.ai.guide.domain.chat.service;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.chat.gateway.ChatHistoryStorePort;
import com.ai.guide.domain.chat.model.ChatHistoryNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatHistoryServiceTest {
    private final ChatHistoryStorePort store = mock(ChatHistoryStorePort.class);
    private final ChatHistoryService service = new ChatHistoryService(store, new ObjectMapper());

    @AfterEach
    void clearIdentity() { UserContext.clear(); }

    @Test
    void listsOnlyCurrentUsersConversationsAndMapsSummary() {
        UserContext.set("user-1", "USER");
        when(store.listRecentSessions("user-1", 10)).thenReturn(List.of(
                new ChatHistoryStorePort.ConversationSummary("user-1:s1",
                        "{\"type\":\"user\",\"content\":\"你好\"}", 3, 1_700_000_000_000L)));

        var sessions = service.getAllSessions(10);

        assertEquals(1, sessions.size());
        assertEquals("user-1:s1", sessions.get(0).getSessionId());
        assertEquals("你好", sessions.get(0).getTitle());
        assertEquals(3, sessions.get(0).getMessageCount());
        verify(store).listRecentSessions("user-1", 10);
    }

    @Test
    void rejectsCrossUserSessionBeforeReadingMessagesOrDeleting() {
        UserContext.set("user-1", "USER");
        when(store.ownsSession("user-1", "user-1:foreign")).thenReturn(false);

        assertThrows(ChatHistoryNotFoundException.class,
                () -> service.getMessages("foreign"));
        assertThrows(ChatHistoryNotFoundException.class,
                () -> service.deleteSession("foreign"));

        verify(store, never()).getMessages("user-1:foreign");
        verify(store, never()).deleteSession("user-1", "user-1:foreign");
    }
}
