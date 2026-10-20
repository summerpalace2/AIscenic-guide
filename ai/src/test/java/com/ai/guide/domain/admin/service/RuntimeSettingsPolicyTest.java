package com.ai.guide.domain.admin.service;
import com.ai.guide.domain.admin.gateway.AppConfigRepositoryPort;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RuntimeSettingsPolicyTest {
 @Test void strictPatchesRejectUnknownNullAndWrongTypeWithoutMutation() {
  assertThrows(IllegalArgumentException.class,()->RuntimeSettingsPolicy.validatePatch(Map.of("ttsEnabled","false")));
  assertThrows(IllegalArgumentException.class,()->RuntimeSettingsPolicy.validatePatch(Map.of("unknown",true)));
  assertThrows(IllegalArgumentException.class,()->RuntimeSettingsPolicy.validatePatch(Map.of("maxUploadSizeMb",1.5)));
  var patch=new HashMap<String,Object>();patch.put("ttsEnabled",null);
  assertThrows(IllegalArgumentException.class,()->RuntimeSettingsPolicy.validatePatch(patch));assertTrue(patch.containsKey("ttsEnabled"));
 }
 @Test void savedValuesAreReadOnEveryOperationAndSizeIsEnforced() {
  var stored=new HashMap<String,String>();var repository=mock(AppConfigRepositoryPort.class);
  when(repository.readConfig("settings_")).thenAnswer(call->Map.copyOf(stored));
  var policy=new RuntimeSettingsPolicy(repository,"测试站点","zh",true,true,50);assertTrue(policy.isTtsEnabled());
  stored.put("ttsEnabled","false");stored.put("knowledgeSyncEnabled","false");stored.put("maxUploadSizeMb","1");
  assertFalse(policy.isTtsEnabled());assertFalse(policy.isKnowledgeSyncEnabled());
  assertThrows(IllegalStateException.class,policy::requireKnowledgeSyncEnabled);
  policy.checkUploadSize(1024*1024);assertThrows(IllegalArgumentException.class,()->policy.checkUploadSize(1024*1024+1));
  stored.put("ttsEnabled","broken");assertThrows(IllegalStateException.class,policy::isTtsEnabled);
  assertThrows(IllegalStateException.class,()->policy.settingsSnapshot(stored));
 }
}
