package com.ai.guide.domain.planner.service;

import com.ai.guide.infrastructure.planner.proposal.CaffeineRedisProposalRepository;
import com.ai.guide.domain.planner.model.*;
import com.ai.guide.domain.planner.service.*;
import com.ai.guide.domain.planner.engine.*;
import com.ai.guide.domain.planner.gateway.*;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.common.context.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;import java.nio.file.*;
import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
class CancellationProposalLifecycleTest {
 @org.junit.jupiter.api.Test void cancelledGeneratedProposalCannotBeApplied()throws Exception {
  var before=Map.<String,Object>of("version",1,"days",List.of(Map.of("day",1,"stops",List.of(Map.of("id","a","name","景点A","time","10:00","duration","60分钟"),Map.of("id","b","name","景点B","time","12:00","duration","60分钟")))));
  var after=Map.<String,Object>of("version",1,"days",List.of(Map.of("day",1,"stops",List.of(Map.of("id","a","name","景点A","time","10:00","duration","60分钟")))));
  var old=new PlannerSessionRepositoryPort.StoredSession("s","USER","owner","","fp","prompt",Map.of(),AppliedPreferencesSnapshot.empty(),before,1,1,"ACTIVE",1,1);
  var next=new PlannerSessionRepositoryPort.StoredSession("s","USER","owner","","fp","prompt",Map.of(),AppliedPreferencesSnapshot.empty(),after,2,2,"ACTIVE",1,1);
  var repo=mock(PlannerSessionRepositoryPort.class);when(repo.find(anyString(),anyString(),anyString(),anyString())).thenReturn(old);
  when(repo.update(any(),anyMap(),anyInt(),anyString(),anyString(),anyString(),anyList())).thenReturn(new PlannerSessionRepositoryPort.MutationResult("UPDATED",next));
  var verifier=mock(PlanVerifier.class);when(verifier.verify(anyList(),any())).thenReturn(new PlanVerifier.VerificationResult(true,List.of(),Map.of()));
  var proposals=new CaffeineRedisProposalRepository();var service=new PlanAdjustmentService(repo,mock(ItineraryBuilderPort.class),mock(AttractionService.class),new RouteCostProvider(null),verifier,mock(RouteAwarePlanner.class),proposals,new ConversationIntentClassifier(),new ObjectMapper());
  UserContext.set("owner","owner","USER");
  try {
   var initial=service.preview("s",PlanConversationRequestDto.builder().message("删除这个景点")
           .context(PlanPageContext.builder().activeDay(1).selectedStopId("b").build()).build());
   String generatedId=String.valueOf(initial.body().get("proposalId"));
   var cancel=service.preview("s",PlanConversationRequestDto.builder().message("取消这次调整").build());
   var apply=service.apply("s",ApplyAdjustmentRequestDto.builder().proposalId(generatedId).baseRevision(1).build());
   var out=Map.of("initialPreviewStatus",initial.status(),"initialProposalGenerated",initial.body().containsKey("proposalId"),"cancelPreviewStatus",cancel.status(),"cancelBody",cancel.body(),"applyAfterCancelStatus",apply.status(),"applyAfterCancelBody",apply.body(),"repositoryMutationCalls",mockingDetails(repo).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")).count());
   org.junit.jupiter.api.Assertions.assertEquals(200,initial.status());
   org.junit.jupiter.api.Assertions.assertTrue(initial.body().containsKey("proposalId"));
   org.junit.jupiter.api.Assertions.assertEquals(true,cancel.body().get("cancelled"));
   org.junit.jupiter.api.Assertions.assertEquals(409,apply.status());
   verify(repo,never()).update(any(),anyMap(),anyInt(),anyString(),anyString(),anyString(),anyList());
  }finally{UserContext.clear();}
 }

 @org.junit.jupiter.api.Test void proposalCleanupFailureAfterCasStillRotatesEpochAndReplaysCommittedReceipt()throws Exception {
  var before=Map.<String,Object>of("version",1,"days",List.of(Map.of("day",1,"stops",List.of(Map.of("id","a","name","景点A","time","10:00","duration","60分钟"),Map.of("id","b","name","景点B","time","12:00","duration","60分钟")))));
  var next=new java.util.concurrent.atomic.AtomicReference<PlannerSessionRepositoryPort.StoredSession>();
  var old=new PlannerSessionRepositoryPort.StoredSession("s","USER","owner","","fp","prompt",Map.of(),AppliedPreferencesSnapshot.empty(),before,1,1,"ACTIVE",1,1);
  var repo=mock(PlannerSessionRepositoryPort.class);when(repo.find(anyString(),anyString(),anyString(),anyString())).thenAnswer(i->next.get()==null?old:next.get());
  when(repo.update(any(),anyMap(),anyInt(),anyString(),anyString(),anyString(),anyList())).thenAnswer(i->{
    var committed=new PlannerSessionRepositoryPort.StoredSession("s","USER","owner","","fp","prompt",Map.of(),AppliedPreferencesSnapshot.empty(),i.getArgument(1),2,2,"ACTIVE",1,2);
    next.set(committed);return new PlannerSessionRepositoryPort.MutationResult("UPDATED",committed);
  });
  var proposals=mock(ProposalRepositoryPort.class);var proposal=new java.util.concurrent.atomic.AtomicReference<PlanAdjustmentProposal>();
  doAnswer(i->{proposal.set(i.getArgument(0));return null;}).when(proposals).put(any());
  when(proposals.get(anyString())).thenAnswer(i->proposal.get());
  when(proposals.remove(anyString())).thenThrow(new IllegalStateException("simulated cache outage after CAS"));
  var verifier=mock(PlanVerifier.class);when(verifier.verify(anyList(),any())).thenReturn(new PlanVerifier.VerificationResult(true,List.of(),Map.of()));
  var service=new PlanAdjustmentService(repo,mock(ItineraryBuilderPort.class),mock(AttractionService.class),new RouteCostProvider(null),verifier,mock(RouteAwarePlanner.class),proposals,new ConversationIntentClassifier(),new ObjectMapper());
  UserContext.set("owner","owner","USER");
  try {
   var preview=service.preview("s",PlanConversationRequestDto.builder().message("删除这个景点")
           .context(PlanPageContext.builder().activeDay(1).selectedStopId("b").build()).build());
   String key="cleanup-replay-"+java.util.UUID.randomUUID();
   var request=ApplyAdjustmentRequestDto.builder().proposalId(String.valueOf(preview.body().get("proposalId"))).baseRevision(1).idempotencyKey(key).build();
   var applied=service.apply("s",request);var retry=service.apply("s",request);
   org.junit.jupiter.api.Assertions.assertEquals(200,applied.status());
   org.junit.jupiter.api.Assertions.assertEquals(200,retry.status());
   org.junit.jupiter.api.Assertions.assertEquals(applied.body(),retry.body());
   org.junit.jupiter.api.Assertions.assertEquals(2,next.get().currentVersion());
   verify(repo,times(1)).update(any(),anyMap(),anyInt(),anyString(),anyString(),anyString(),anyList());
  }finally{UserContext.clear();}
 }
 @org.junit.jupiter.api.Test void negatedCancellationKeepsGeneratedProposalApplicable()throws Exception {
  var before=Map.<String,Object>of("version",1,"days",List.of(Map.of("day",1,"stops",List.of(Map.of("id","a","name","景点A","time","10:00","duration","60分钟"),Map.of("id","b","name","景点B","time","12:00","duration","60分钟")))));
  var after=Map.<String,Object>of("version",1,"days",List.of(Map.of("day",1,"stops",List.of(Map.of("id","a","name","景点A","time","10:00","duration","60分钟")))));
  var old=new PlannerSessionRepositoryPort.StoredSession("s","USER","owner","","fp","prompt",Map.of(),AppliedPreferencesSnapshot.empty(),before,1,1,"ACTIVE",1,1);
  var next=new PlannerSessionRepositoryPort.StoredSession("s","USER","owner","","fp","prompt",Map.of(),AppliedPreferencesSnapshot.empty(),after,2,2,"ACTIVE",1,1);
  var repo=mock(PlannerSessionRepositoryPort.class);when(repo.find(anyString(),anyString(),anyString(),anyString())).thenReturn(old);
  when(repo.update(any(),anyMap(),anyInt(),anyString(),anyString(),anyString(),anyList())).thenReturn(new PlannerSessionRepositoryPort.MutationResult("UPDATED",next));
  var verifier=mock(PlanVerifier.class);when(verifier.verify(anyList(),any())).thenReturn(new PlanVerifier.VerificationResult(true,List.of(),Map.of()));
  var proposals=new CaffeineRedisProposalRepository();var service=new PlanAdjustmentService(repo,mock(ItineraryBuilderPort.class),mock(AttractionService.class),new RouteCostProvider(null),verifier,mock(RouteAwarePlanner.class),proposals,new ConversationIntentClassifier(),new ObjectMapper());
  UserContext.set("owner","owner","USER");
  try {
   var initial=service.preview("s",PlanConversationRequestDto.builder().message("删除这个景点")
           .context(PlanPageContext.builder().activeDay(1).selectedStopId("b").build()).build());
   String generatedId=String.valueOf(initial.body().get("proposalId"));
   var cancel=service.preview("s",PlanConversationRequestDto.builder().message("不要取消这次调整").build());
   var apply=service.apply("s",ApplyAdjustmentRequestDto.builder().proposalId(generatedId).baseRevision(1).build());
   var out=Map.of("initialPreviewStatus",initial.status(),"initialProposalGenerated",initial.body().containsKey("proposalId"),"cancelPreviewStatus",cancel.status(),"cancelBody",cancel.body(),"applyAfterCancelStatus",apply.status(),"applyAfterCancelBody",apply.body(),"repositoryMutationCalls",mockingDetails(repo).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")).count());
   org.junit.jupiter.api.Assertions.assertEquals(200,initial.status());
   org.junit.jupiter.api.Assertions.assertTrue(initial.body().containsKey("proposalId"));
   org.junit.jupiter.api.Assertions.assertNotEquals(true,cancel.body().get("cancelled"));
   org.junit.jupiter.api.Assertions.assertEquals(200,apply.status());
   verify(repo,times(1)).update(any(),anyMap(),anyInt(),anyString(),anyString(),anyString(),anyList());
  }finally{UserContext.clear();}
 }
 @org.junit.jupiter.api.Test void cancellationWaitsForSubmittedApplyAndRejectsStaleRevision()throws Exception {
  var before=Map.<String,Object>of("version",1,"days",List.of(Map.of("day",1,"stops",List.of(Map.of("id","a","name","景点A","time","10:00","duration","60分钟"),Map.of("id","b","name","景点B","time","12:00","duration","60分钟")))));
  var after=Map.<String,Object>of("version",1,"days",List.of(Map.of("day",1,"stops",List.of(Map.of("id","a","name","景点A","time","10:00","duration","60分钟")))));
  var old=new PlannerSessionRepositoryPort.StoredSession("s","USER","owner","","fp","prompt",Map.of(),AppliedPreferencesSnapshot.empty(),before,1,1,"ACTIVE",1,1);
  var next=new PlannerSessionRepositoryPort.StoredSession("s","USER","owner","","fp","prompt",Map.of(),AppliedPreferencesSnapshot.empty(),after,2,2,"ACTIVE",1,1);
  var repo=mock(PlannerSessionRepositoryPort.class);when(repo.find(anyString(),anyString(),anyString(),anyString())).thenReturn(old);
  when(repo.update(any(),anyMap(),anyInt(),anyString(),anyString(),anyString(),anyList())).thenReturn(new PlannerSessionRepositoryPort.MutationResult("UPDATED",next));
  var verifier=mock(PlanVerifier.class);when(verifier.verify(anyList(),any())).thenReturn(new PlanVerifier.VerificationResult(true,List.of(),Map.of()));
  var proposals=new CaffeineRedisProposalRepository();var service=new PlanAdjustmentService(repo,mock(ItineraryBuilderPort.class),mock(AttractionService.class),new RouteCostProvider(null),verifier,mock(RouteAwarePlanner.class),proposals,new ConversationIntentClassifier(),new ObjectMapper());
  UserContext.set("owner","owner","USER");
  try {
   var initial=service.preview("s",PlanConversationRequestDto.builder().message("删除这个景点")
           .context(PlanPageContext.builder().activeDay(1).selectedStopId("b").build()).build());
   String generatedId=String.valueOf(initial.body().get("proposalId"));
   var stored=new java.util.concurrent.atomic.AtomicReference<>(old);
   var updateEntered=new java.util.concurrent.CountDownLatch(1);
   var releaseUpdate=new java.util.concurrent.CountDownLatch(1);
   var cancelRead=new java.util.concurrent.CountDownLatch(1);
   when(repo.find(anyString(),anyString(),anyString(),anyString())).thenAnswer(i->{
     var value=stored.get(); if(Thread.currentThread().getName().equals("cancel-worker"))cancelRead.countDown(); return value;
   });
   when(repo.update(any(),anyMap(),anyInt(),anyString(),anyString(),anyString(),anyList())).thenAnswer(i->{
     updateEntered.countDown();
     if(!releaseUpdate.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("update release timeout");
     stored.set(next);return new PlannerSessionRepositoryPort.MutationResult("UPDATED",next);
   });
   var applied=new java.util.concurrent.CompletableFuture<PlannerService.ServiceResult>();
   var cancelled=new java.util.concurrent.CompletableFuture<PlannerService.ServiceResult>();
   Thread applyThread=new Thread(()->{UserContext.set("owner","owner","USER");try{
     applied.complete(service.apply("s",ApplyAdjustmentRequestDto.builder().proposalId(generatedId).baseRevision(1).build()));
   }catch(Throwable t){applied.completeExceptionally(t);}finally{UserContext.clear();}},"apply-worker");
   Thread cancelThread=new Thread(()->{UserContext.set("owner","owner","USER");try{
     cancelled.complete(service.preview("s",PlanConversationRequestDto.builder().message("取消这次调整").build()));
   }catch(Throwable t){cancelled.completeExceptionally(t);}finally{UserContext.clear();}},"cancel-worker");
   applyThread.start();
   try {
     org.junit.jupiter.api.Assertions.assertTrue(updateEntered.await(3,java.util.concurrent.TimeUnit.SECONDS));
     cancelThread.start();
     org.junit.jupiter.api.Assertions.assertTrue(cancelRead.await(3,java.util.concurrent.TimeUnit.SECONDS));
   } finally {releaseUpdate.countDown();}
   org.junit.jupiter.api.Assertions.assertEquals(200,applied.get(3,java.util.concurrent.TimeUnit.SECONDS).status());
   var result=cancelled.get(3,java.util.concurrent.TimeUnit.SECONDS);
   org.junit.jupiter.api.Assertions.assertEquals(409,result.status());
   org.junit.jupiter.api.Assertions.assertNotEquals(true,result.body().get("cancelled"));
   verify(repo,times(1)).update(any(),anyMap(),anyInt(),anyString(),anyString(),anyString(),anyList());
  }finally{UserContext.clear();}
 }

 @org.junit.jupiter.api.Test void cancellationFirstWhileApplyIsQueuedRejectsApplyWithoutMutation()throws Exception {
  var before=Map.<String,Object>of("version",1,"days",List.of(Map.of("day",1,"stops",List.of(Map.of("id","a","name","景点A","time","10:00","duration","60分钟"),Map.of("id","b","name","景点B","time","12:00","duration","60分钟")))));
  var old=new PlannerSessionRepositoryPort.StoredSession("s","USER","owner","","fp","prompt",Map.of(),AppliedPreferencesSnapshot.empty(),before,1,1,"ACTIVE",1,1);
  var repo=mock(PlannerSessionRepositoryPort.class);when(repo.find(anyString(),anyString(),anyString(),anyString())).thenReturn(old);
  var verifier=mock(PlanVerifier.class);when(verifier.verify(anyList(),any())).thenReturn(new PlanVerifier.VerificationResult(true,List.of(),Map.of()));
  var proposals=new CaffeineRedisProposalRepository();var service=new PlanAdjustmentService(repo,mock(ItineraryBuilderPort.class),mock(AttractionService.class),new RouteCostProvider(null),verifier,mock(RouteAwarePlanner.class),proposals,new ConversationIntentClassifier(),new ObjectMapper());
  UserContext.set("owner","owner","USER");
  try {
   var initial=service.preview("s",PlanConversationRequestDto.builder().message("删除这个景点")
           .context(PlanPageContext.builder().activeDay(1).selectedStopId("b").build()).build());
   String proposalId=String.valueOf(initial.body().get("proposalId"));
   var cancelEnteredReset=new java.util.concurrent.CountDownLatch(1);
   var releaseCancelReset=new java.util.concurrent.CountDownLatch(1);
   var applyAttempted=new java.util.concurrent.CountDownLatch(1);
   var cancelReads=new java.util.concurrent.atomic.AtomicInteger();
   when(repo.find(anyString(),anyString(),anyString(),anyString())).thenAnswer(i->{
     if(Thread.currentThread().getName().equals("cancel-first-worker") && cancelReads.incrementAndGet()==2){
       cancelEnteredReset.countDown();
       if(!releaseCancelReset.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("cancel reset release timeout");
     }
     return old;
   });
   var cancelled=new java.util.concurrent.CompletableFuture<PlannerService.ServiceResult>();
   var applied=new java.util.concurrent.CompletableFuture<PlannerService.ServiceResult>();
   Thread cancelThread=new Thread(()->{UserContext.set("owner","owner","USER");try{
     cancelled.complete(service.preview("s",PlanConversationRequestDto.builder().message("取消这次调整").build()));
   }catch(Throwable t){cancelled.completeExceptionally(t);}finally{UserContext.clear();}},"cancel-first-worker");
   Thread applyThread=new Thread(()->{UserContext.set("owner","owner","USER");try{
     applyAttempted.countDown();
     applied.complete(service.apply("s",ApplyAdjustmentRequestDto.builder().proposalId(proposalId).baseRevision(1).build()));
   }catch(Throwable t){applied.completeExceptionally(t);}finally{UserContext.clear();}},"apply-waiting-worker");
   cancelThread.start();
   try {
     org.junit.jupiter.api.Assertions.assertTrue(cancelEnteredReset.await(3,java.util.concurrent.TimeUnit.SECONDS));
     applyThread.start();
     org.junit.jupiter.api.Assertions.assertTrue(applyAttempted.await(3,java.util.concurrent.TimeUnit.SECONDS));
   } finally { releaseCancelReset.countDown(); }
   var cancelResult=cancelled.get(3,java.util.concurrent.TimeUnit.SECONDS);
   var applyResult=applied.get(3,java.util.concurrent.TimeUnit.SECONDS);
   org.junit.jupiter.api.Assertions.assertEquals(200,cancelResult.status());
   org.junit.jupiter.api.Assertions.assertEquals(true,cancelResult.body().get("cancelled"));
   org.junit.jupiter.api.Assertions.assertEquals(409,applyResult.status());
   org.junit.jupiter.api.Assertions.assertEquals(1,old.currentVersion());
   verify(repo,never()).update(any(),anyMap(),anyInt(),anyString(),anyString(),anyString(),anyList());
  }finally{UserContext.clear();}
 }
}
