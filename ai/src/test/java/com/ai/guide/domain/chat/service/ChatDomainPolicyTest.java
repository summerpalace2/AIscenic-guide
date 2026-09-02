package com.ai.guide.domain.chat.service;


import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatDomainPolicyTest {

    @Test
    void systemIdentityIsChongqingAndContainsNoLegacyProductIdentity() {
        assertTrue(ChatDomainPolicy.SYSTEM_PROMPT.contains("渝游智策"));
        assertTrue(ChatDomainPolicy.SYSTEM_PROMPT.contains("重庆智慧文旅"));
        assertFalse(ChatDomainPolicy.containsLegacyDomain(ChatDomainPolicy.SYSTEM_PROMPT));
    }

    @Test
    void goldenDemoQueryDoesNotProactivelyInjectLegacyTerms() {
        String promptSurface = ChatDomainPolicy.SYSTEM_PROMPT + "\n" + ChatDomainPolicy.GOLDEN_DEMO_QUERY;

        assertFalse(ChatDomainPolicy.containsLegacyDomain(promptSurface));
        assertEquals("", ChatDomainPolicy.protectRetrievedContext(""));
    }

    @Test
    void contaminatedRetrievalIsMarkedAndOldTextIsNotForwarded() {
        String guarded = ChatDomainPolicy.protectRetrievedContext("无锡旧景区资料：灵山相关说明");

        assertEquals(ChatDomainPolicy.LEGACY_CORPUS_MARKER, guarded);
        assertTrue(guarded.contains("LEGACY_CORPUS_CONTAMINATION"));
        assertFalse(guarded.contains("灵山"));
        assertFalse(guarded.contains("无锡"));
    }

    @Test
    void parallelResultsBecomeOneContaminationMarker() {
        List<String> guarded = ChatDomainPolicy.protectRetrievedFragments(List.of(
                "重庆城市人文资料",
                "旧项目无锡资料"));

        assertEquals(List.of(ChatDomainPolicy.LEGACY_CORPUS_MARKER), guarded);
    }

    @Test
    void legacyHistoryAndPreferenceMessagesAreNotAddedAsSystemContext() {
        List<Message> safe = ChatDomainPolicy.filterLegacyMessages(List.of(
                new UserMessage("我想看重庆夜景"),
                new SystemMessage("旧项目灵山偏好")));

        assertEquals(1, safe.size());
        assertEquals("我想看重庆夜景", safe.get(0).getContent());
    }
}
