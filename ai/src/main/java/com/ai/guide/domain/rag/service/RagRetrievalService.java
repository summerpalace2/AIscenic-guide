package com.ai.guide.domain.rag.service;

import com.ai.guide.domain.attraction.service.AttractionIdentityResolver;
import com.ai.guide.domain.rag.gateway.ScenicKnowledgePort;
import com.ai.guide.domain.rag.gateway.SemanticEvidencePort;
import com.ai.guide.domain.rag.model.SemanticEvidence.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/** Semantic evidence decisions and background retrieval have separate authority. */
@Service
public class RagRetrievalService {
    private final ScenicKnowledgePort scenic;
    private final KnowledgeDocumentService local;
    private final RagQueryExpander expander;
    private final SemanticEvidencePort semantic;
    private final AttractionIdentityResolver identities;
    private record Candidate(String id, String text, String title, Map<String,Object> citation) {}
    private record Retrieved(List<Candidate> candidates, String source) {}

    @Autowired
    public RagRetrievalService(ScenicKnowledgePort scenic, KnowledgeDocumentService local,
            RagQueryExpander expander, SemanticEvidencePort semantic, AttractionIdentityResolver identities) {
        this.scenic = scenic; this.local = local; this.expander = expander;
        this.semantic = semantic; this.identities = identities;
    }
    /** Compatibility callers without a semantic model fail closed, including tests. */
    public RagRetrievalService(ScenicKnowledgePort scenic, KnowledgeDocumentService local, RagQueryExpander expander) {
        this(scenic, local, expander, null, null);
    }
    public RagRetrievalService(ScenicKnowledgePort scenic, KnowledgeDocumentService local) {
        this(scenic, local, new RagQueryExpander(com.ai.guide.domain.taxonomy.gateway.RagTaxonomyPort.empty()));
    }

    public Map<String,Object> retrieve(String query, String city, Map<String,Object> constraints, boolean deep) {
        if (query == null || query.isBlank() || query.length() > 8000) return failure("检索问题不能为空或过长");
        long started = System.nanoTime(), phase = started;
        var measurements = new ArrayList<StageMeasurement>();
        long analyzeMs, retrievalMs = 0, assessMs = 0;
        Analysis analysis = null;
        String reason = "SEMANTIC_UNAVAILABLE";
        List<CatalogIdentity> catalogIdentities = List.of();
        try {
            if (semantic != null) analysis = semantic.analyze(query, city == null ? "" : city);
            if (analysis != null) {
                validateAnalysis(analysis, query);
                if (identities != null) {
                    var metadata = new LinkedHashMap<String, CatalogIdentity>();
                    for (Requirement r : analysis.requirements()) {
                        var identity = identities.resolve(city, r.subject());
                        if (!List.of("EXACT", "ALIAS").contains(identity.status())) continue;
                        var names = identity.names().stream()
                                .filter(name -> identity.id().equals(identities.resolve(city, name).id())).limit(8).toList();
                        metadata.putIfAbsent(identity.id(), new CatalogIdentity(city, identity.id(), r.subject(), identity.canonicalName(), names));
                    }
                    catalogIdentities = List.copyOf(metadata.values());
                }
                measurements.addAll(analysis.measurements());
                reason = "INSUFFICIENT_EVIDENCE";
            }
        } catch (SemanticEvidencePort.Failure ex) { analysis = null; measurements.addAll(ex.measurements()); }
        catch (RuntimeException ex) { analysis = null; }
        analyzeMs = elapsed(phase);
        if (analysis != null && "NON_FACTUAL".equals(analysis.intent())) {
            var response = unknown("NO_FACTUAL_CONCLUSION", new Retrieved(List.of(), "NOT_QUERIED"));
            response.put("retrievalStatus", "NON_FACTUAL"); response.put("intent", "NON_FACTUAL");
            response.put("claims", List.of()); response.put("reason", "这条消息不需要景区事实核验，可正常对话。");
            response.put("message", response.get("reason"));
            return finish(response, measurements, started, analyzeMs, 0, 0, 0);
        }
        phase = System.nanoTime();
        // Empty city authorizes GLOBAL documents only; the chat adapter separately asks for a destination.
        Retrieved retrieved = retrieveBackground(query, city, constraints, deep);
        if (analysis != null && !catalogIdentities.isEmpty()) {
            var candidates = new ArrayList<>(retrieved.candidates());
            for (int i = 0; i < catalogIdentities.size(); i++) {
                CatalogIdentity identity = catalogIdentities.get(i);
                String text = "目录城市：" + identity.city() + "；目录编号：" + identity.catalogId()
                        + "；规范名称：" + identity.canonicalName() + "；同一实体已核验名称：" + String.join("、", identity.aliases()) + "。";
                candidates.add(new Candidate("catalog-identity-" + i, text, "目录身份关系",
                        Map.of("title", "同城目录身份关系", "status", "只证明名称身份，不证明其他属性")));
            }
            retrieved = new Retrieved(List.copyOf(candidates), retrieved.source());
        }
        retrievalMs = elapsed(phase);
        var result = unknown(reason, retrieved);
        result.put("intent", "FACTUAL");
        String unassessedReason = reason;
        result.put("claims", analysis == null ? List.of() : analysis.requirements().stream().map(r -> unknownClaim(r, unassessedReason)).toList());
        if (analysis == null || retrieved.candidates().isEmpty())
            return finish(result, measurements, started, analyzeMs, retrievalMs, 0, retrieved.candidates().size());
        if (analysis.requirements().stream().allMatch(r -> List.of("ACTUAL", "GUARANTEE").contains(r.conclusionType()))) {
            result.put("claims", analysis.requirements().stream().map(r -> unknownClaim(r,
                    "GUARANTEE".equals(r.conclusionType()) ? "FUTURE_CERTAINTY_UNSUPPORTED" : "LIVE_STATE_UNVERIFIED")).toList());
            result.put("reasonCode", "TIME_SPECIFIC_EVIDENCE_REQUIRED");
            return finish(result, measurements, started, analyzeMs, retrievalMs, 0, retrieved.candidates().size());
        }
        phase = System.nanoTime();
        try {
            Analysis resolved = resolveSubjects(analysis, city);
            var evidence = retrieved.candidates().stream().map(c -> new Evidence(c.id(), c.text())).toList();
            Verdict verdict = catalogIdentities.isEmpty() ? semantic.assess(query, resolved, evidence)
                    : semantic.assess(query, resolved, evidence, catalogIdentities);
            measurements.addAll(verdict.measurements());
            List<Map<String,Object>> claims = decideClaims(resolved, verdict, retrieved.candidates(), city);
            List<Map<String,Object>> facts = claims.stream().filter(c -> "RELEVANT".equals(c.get("decision"))).toList();
            String decision = facts.isEmpty() ? "UNKNOWN" : facts.size() == claims.size() ? "RELEVANT" : "PARTIAL";
            result.put("claims", claims); result.put("retrievalStatus", decision);
            result.put("reasonCode", "RELEVANT".equals(decision) ? "EVIDENCE_SUPPORTED" : "PARTIAL".equals(decision) ? "PARTIAL_EVIDENCE_SUPPORTED" : "SEMANTIC_EVIDENCE_NOT_SUPPORTED");
            result.put("message", "仅回答逐项得到支持的结论；规则不等于个人资格，公告不等于现场核验。"); result.put("reason", result.get("message"));
            result.put("facts", facts); result.put("evidenceCount", facts.size());
            result.put("citations", facts.stream().flatMap(f -> ((List<?>)f.get("citations")).stream()).distinct().toList());
        } catch (SemanticEvidencePort.Failure ex) { measurements.addAll(ex.measurements()); result.put("reasonCode", "SEMANTIC_EVIDENCE_NOT_SUPPORTED"); }
        catch (RuntimeException ex) { result.put("reasonCode", "SEMANTIC_UNAVAILABLE"); }
        assessMs = elapsed(phase);
        return finish(result, measurements, started, analyzeMs, retrievalMs, assessMs, retrieved.candidates().size());
    }

    private Analysis resolveSubjects(Analysis analysis, String city) {
        if (identities == null) return analysis;
        List<Requirement> resolved = new ArrayList<>();
        for (Requirement r : analysis.requirements()) {
            var identity = identities.resolve(city, r.subject());
            // An ambiguous entity withholds its own claim; it must not erase other subjects' evidence.
            String subject = List.of("EXACT", "ALIAS").contains(identity.status()) ? identity.canonicalName() : r.subject();
            resolved.add(new Requirement(r.id(), subject, r.attribute(), r.timeScope(), r.conclusionType()));
        }
        return new Analysis(analysis.intent(), resolved, analysis.userFacts(), analysis.measurements());
    }
    private static void validateAnalysis(Analysis analysis, String query) {
        if (!List.of("FACTUAL", "NON_FACTUAL").contains(analysis.intent()) || analysis.requirements().size() > 8
                || ("FACTUAL".equals(analysis.intent()) && analysis.requirements().isEmpty())
                || ("NON_FACTUAL".equals(analysis.intent()) && (!analysis.requirements().isEmpty() || !analysis.userFacts().isEmpty())))
            throw new SemanticEvidencePort.Failure("Invalid requirements");
        var userIds = new HashSet<String>();
        for (UserFact fact : analysis.userFacts())
            if (fact.id() == null || fact.id().isBlank() || !userIds.add(fact.id()) || fact.quote() == null || fact.quote().isBlank() || !query.contains(fact.quote()))
                throw new SemanticEvidencePort.Failure("Invalid user fact grounding");
        Set<String> ids = new HashSet<>();
        for (Requirement r : analysis.requirements()) {
            if (r == null || r.id() == null || r.id().isBlank() || !ids.add(r.id()) || r.subject() == null || !query.contains(r.subject())
                    || r.attribute() == null || r.attribute().isBlank() || !List.of("STATIC", "CURRENT", "DATED").contains(r.timeScope())
                    || !List.of("REGULAR", "RULE", "ELIGIBILITY", "ANNOUNCEMENT", "ACTUAL", "GUARANTEE", "IDENTITY").contains(r.conclusionType())) throw new SemanticEvidencePort.Failure("Invalid requirements");
        }
    }
    private List<Map<String,Object>> decideClaims(Analysis analysis, Verdict verdict, List<Candidate> candidates, String city) {
        if (verdict == null) throw new SemanticEvidencePort.Failure("Missing support");
        var byId = new HashMap<String,Candidate>(); candidates.forEach(c -> byId.put(c.id(), c));
        var requirements = new HashMap<String,Requirement>(); analysis.requirements().forEach(r -> requirements.put(r.id(), r));
        var seen = new HashSet<String>(); var supports = new HashMap<String,Support>();
        for (Support s : verdict.supports()) {
            if (s == null || !requirements.containsKey(s.requirementId()) || !seen.add(s.requirementId())) throw new SemanticEvidencePort.Failure("Invalid support identity");
            supports.put(s.requirementId(), s);
        }
        List<Map<String,Object>> claims = new ArrayList<>();
        for (Requirement r : analysis.requirements()) {
            var claim = unknownClaim(r, "INSUFFICIENT_EVIDENCE");
            Support s = supports.get(r.id());
            if (List.of("ACTUAL", "GUARANTEE").contains(r.conclusionType())) {
                claim.put("reasonCode", "GUARANTEE".equals(r.conclusionType()) ? "FUTURE_CERTAINTY_UNSUPPORTED" : "LIVE_STATE_UNVERIFIED");
                claims.add(claim); continue;
            }
            if (s != null && "ELIGIBILITY".equals(r.conclusionType()) && !"CONJUNCTION".equals(s.policyShape())) {
                claim.put("reasonCode", "ALTERNATIVES".equals(s.policyShape()) ? "ALTERNATIVE_POLICY_UNSUPPORTED" : "POLICY_STRUCTURE_UNKNOWN");
                claims.add(claim); continue;
            }
            if (s != null && "ELIGIBILITY".equals(r.conclusionType())) {
                var missing = s.conditions().stream().filter(condition -> "UNKNOWN".equals(condition.state()))
                        .filter(condition -> candidates.stream().anyMatch(candidate -> !candidate.id().startsWith("catalog-identity-") && candidate.id().equals(condition.evidenceId())
                                && condition.ruleQuote() != null && !condition.ruleQuote().isBlank()
                                && candidate.text().contains(condition.ruleQuote()) && (r.subject().isBlank()
                                || (identities == null ? candidate.text().contains(r.subject()) : identities.matches(city, r.subject(), candidate.text())))))
                        .map(Condition::ruleQuote).distinct().toList();
                if (!missing.isEmpty()) claim.put("missingConditions", missing);
                claim.put("reasonCode", "QUALIFICATION_PREMISES_MISSING");
            }
            if (s == null || !"SUPPORTED".equals(s.decision())) { claims.add(claim); continue; }
            claim.put("evidenceState", s.evidenceState());
            if (!List.of("SUPPORTED_VALUE", "STATED_NEGATIVE").contains(s.evidenceState())) {
                claim.put("reasonCode", "VALUE_NOT_STATED"); claims.add(claim); continue;
            }
            if (s.evidenceId() != null && s.evidenceId().startsWith("catalog-identity-") && !"IDENTITY".equals(r.conclusionType())) {
                claim.put("reasonCode", "IDENTITY_ONLY_EVIDENCE"); claims.add(claim); continue;
            }
            Candidate c = byId.get(s.evidenceId());
            if (!validQuote(c, s.quote(), s.start(), s.end())) { claim.put("reasonCode", "INVALID_QUOTATION"); claims.add(claim); continue; }
            if (!r.subject().isBlank()) {
                boolean match = identities == null ? c.text().contains(r.subject()) : identities.matches(city, r.subject(), c.text());
                if (!match) { claim.put("reasonCode", "SUBJECT_NOT_SUPPORTED"); claims.add(claim); continue; }
            }
            if ("ANNOUNCEMENT".equals(r.conclusionType())) {
                if (s.announcementDateQuote() == null || s.announcementDateQuote().isBlank() || !s.quote().contains(s.announcementDateQuote())) {
                    claim.put("reasonCode", "ANNOUNCEMENT_DATE_NOT_GROUNDED"); claims.add(claim); continue;
                }
                claim.put("announcementDateQuote", s.announcementDateQuote());
            }
            if ("ELIGIBILITY".equals(r.conclusionType())) {
                String qualification = qualification(analysis, s, byId, city, r.subject());
                if (!verdict.coverageComplete() || "UNKNOWN".equals(qualification)) {
                    claim.put("reasonCode", "QUALIFICATION_PREMISES_MISSING"); claims.add(claim); continue;
                }
                claim.put("qualificationDecision", qualification);
            }
            claim.put("decision", "RELEVANT"); claim.put("reasonCode", "EVIDENCE_SUPPORTED");
            claim.put("label", r.attribute()); claim.put("value", s.quote()); claim.put("status", "证据支持，非实时核验");
            claim.put("requirementId", r.id()); claim.put("evidenceId", c.id()); claim.put("quoteStart", s.start()); claim.put("quoteEnd", s.end());
            claim.put("citations", List.of(c.citation())); claims.add(claim);
        }
        if (!verdict.coverageComplete()) claims.add(Map.of("id", "uncovered", "type", "UNRESOLVED", "subject", "", "attribute", "尚未完整覆盖的问题", "decision", "UNKNOWN", "reasonCode", "INCOMPLETE_QUESTION_COVERAGE"));
        return claims;
    }

    private String qualification(Analysis analysis, Support support, Map<String,Candidate> candidates, String city, String subject) {
        if (!"CONJUNCTION".equals(support.policyShape()) || !support.conditionsComplete() || support.conditions().isEmpty()) return "UNKNOWN";
        var userFacts = new HashMap<String,UserFact>(); analysis.userFacts().forEach(f -> userFacts.put(f.id(), f));
        var ids = new HashSet<String>(); boolean unknown = false, contradicted = false;
        for (Condition condition : support.conditions()) {
            Candidate c = candidates.get(condition.evidenceId());
            if (condition.evidenceId() == null || condition.evidenceId().startsWith("catalog-identity-")
                    || condition.id() == null || condition.id().isBlank() || !ids.add(condition.id()) || c == null
                    || condition.ruleQuote() == null || condition.ruleQuote().isBlank() || c.text().indexOf(condition.ruleQuote()) < 0
                    || c.text().indexOf(condition.ruleQuote()) != c.text().lastIndexOf(condition.ruleQuote())
                    || (!subject.isBlank() && !(identities == null ? c.text().contains(subject) : identities.matches(city, subject, c.text())))) return "UNKNOWN";
            if ("UNKNOWN".equals(condition.state())) { if (condition.userFactId() != null) return "UNKNOWN"; unknown = true; }
            else if (List.of("SATISFIED", "CONTRADICTED").contains(condition.state()) && userFacts.containsKey(condition.userFactId()))
                contradicted |= "CONTRADICTED".equals(condition.state());
            else return "UNKNOWN";
        }
        return contradicted ? "INELIGIBLE" : unknown ? "UNKNOWN" : "ELIGIBLE";
    }
    private static boolean validQuote(Candidate c, String quote, int start, int end) {
        return c != null && quote != null && !quote.isBlank() && start >= 0 && end <= c.text().length() && end > start
                && c.text().substring(start, end).equals(quote) && c.text().indexOf(quote) == c.text().lastIndexOf(quote);
    }
    private static Map<String,Object> unknownClaim(Requirement r, String reason) {
        var claim = new LinkedHashMap<String,Object>();
        claim.put("id", r.id()); claim.put("type", r.conclusionType()); claim.put("subject", r.subject());
        claim.put("attribute", r.attribute()); claim.put("decision", "UNKNOWN"); claim.put("reasonCode", reason); return claim;
    }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private Map<String,Object> finish(Map<String,Object> result, List<StageMeasurement> calls, long started, long analyzeMs, long retrievalMs, long assessMs, int candidates) {
        result.put("vectorSearch", Map.of("provider", result.get("retrievalSource"), "resultCount", candidates,
                "retrievalStatus", result.get("retrievalStatus"), "reasonCode", result.get("reasonCode")));
        var metrics = new LinkedHashMap<String,Object>(); metrics.put("analysisMs", analyzeMs); metrics.put("retrievalMs", retrievalMs);
        metrics.put("assessmentMs", assessMs); metrics.put("totalMs", elapsed(started)); metrics.put("modelAttempts", List.copyOf(calls));
        metrics.put("attemptCount", calls.size()); metrics.put("retryCount", calls.stream().filter(c -> c.attempt() > 1).count());
        metrics.put("promptTokens", sumKnown(calls, StageMeasurement::promptTokens));
        metrics.put("completionTokens", sumKnown(calls, StageMeasurement::completionTokens));
        metrics.put("totalTokens", sumKnown(calls, StageMeasurement::totalTokens));
        metrics.put("usageStatus", calls.isEmpty() || calls.stream().anyMatch(c -> c.totalTokens() == null) ? "INCOMPLETE_OR_UNAVAILABLE" : "SDK_METADATA");
        result.put("semanticMetrics", metrics); return result;
    }
    private static Long sumKnown(List<StageMeasurement> calls, java.util.function.Function<StageMeasurement,Long> field) {
        if (calls.isEmpty() || calls.stream().anyMatch(c -> field.apply(c) == null)) return null;
        return calls.stream().map(field).mapToLong(Long::longValue).sum();
    }

    private Retrieved retrieveBackground(String query, String city, Map<String,Object> constraints, boolean deep) {
        StringBuilder raw = new StringBuilder(query);
        if (city != null && !city.isBlank()) raw.append("\n城市：").append(city);
        if (constraints != null && !constraints.isEmpty()) raw.append("\n约束：").append(constraints);
        String expanded = expander.profile(raw.toString()).expandedQuery(); var scope = expander.queryScope(city);
        List<Candidate> candidates = new ArrayList<>();
        try {
            var fragments = scenic.searchScoped(expanded, 25, 12, deep, scope);
            if (fragments != null) for (var f : fragments) {
                if (f != null && scope.permits(f.applicability()) && f.content() != null && !f.content().isBlank()) add(candidates, f.content(), title(f.content()), f.sourceUrl());
            }
            if (!candidates.isEmpty()) return new Retrieved(List.copyOf(candidates), "QDRANT");
        } catch (RuntimeException ex) { /* Structured local fallback owns degraded retrieval. */ }
        try {
            var documents = local.searchLocalKnowledge(expanded, 5, scope);
            if (documents != null) for (var d : documents) {
                if (d.get("scope") != null && !scope.permits(new com.ai.guide.domain.rag.model.KnowledgeScope(String.valueOf(d.get("scope")), String.valueOf(d.getOrDefault("city", ""))))) continue;
                String title = text(d.get("title")), content = text(d.get("content"));
                if (!content.isBlank()) add(candidates, title + "\n" + content, title, text(d.get("source_url")));
            }
            return new Retrieved(List.copyOf(candidates), "LOCAL_FALLBACK");
        } catch (RuntimeException ex) { return new Retrieved(List.of(), "UNAVAILABLE"); }
    }
    private static void add(List<Candidate> candidates, String content, String title, String url) {
        if (candidates.size() >= 6) return;
        String bounded = content.substring(0, Math.min(content.length(), 4000));
        var citation = new LinkedHashMap<String,Object>(); citation.put("title", title); citation.put("status", "来源参考，非实时核验");
        try {
            var uri = java.net.URI.create(url == null ? "" : url);
            if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null && uri.getUserInfo() == null) citation.put("url", uri.toString());
        } catch (RuntimeException ignored) { }
        candidates.add(new Candidate("e" + candidates.size(), bounded, title, citation));
    }
    private Map<String,Object> unknown(String code, Retrieved retrieved) {
        var result = new LinkedHashMap<String,Object>(); result.put("ok", true); result.put("provider", "Java Core Backend");
        result.put("retrievalStatus", "UNKNOWN"); result.put("reasonCode", code); result.put("gateSource", "SEMANTIC");
        result.put("retrievalSource", retrieved.source()); result.put("backgroundSource", retrieved.source());
        result.put("mode", "Java 语义证据门禁"); result.put("source", "Java Semantic Evidence Gate");
        result.put("verified", false); result.put("evidenceCount", 0); result.put("facts", List.of()); result.put("citations", List.of()); result.put("claims", List.of());
        result.put("message", "当前无法确认所问结论；相关资料仅供背景参考，请通过资料中提供的官方来源核实。"); result.put("reason", result.get("message"));
        result.put("backgroundFacts", retrieved.candidates().stream().map(c -> Map.of(
                "label", "相关资料条目", "purpose", "SOURCE_REFERENCE_ONLY", "evidenceId", c.id())).toList());
        result.put("backgroundCitations", retrieved.candidates().stream().map(c -> {
            var citation = new LinkedHashMap<String,Object>();
            citation.put("title", "相关资料来源"); citation.put("status", "仅供核实来源，不支撑所问结论");
            if (c.citation().containsKey("url")) citation.put("url", c.citation().get("url"));
            return citation;
        }).toList());
        result.put("vectorSearch", Map.of("provider", retrieved.source(), "resultCount", retrieved.candidates().size(), "retrievalStatus", "UNKNOWN", "reasonCode", code));
        result.put("vectorStore", "NOT_QUERIED".equals(retrieved.source()) ? Map.of("provider", "NOT_QUERIED", "verified", false) : status().get("vectorStore"));
        result.put("embedding", Map.of("directFromNode", false)); result.put("fallbackOwner", "Java"); return result;
    }
    public Map<String,Object> status() {
        var status = new LinkedHashMap<String,Object>(); status.put("configured", true); status.put("owner", "Java"); status.put("provider", "Java Core Backend");
        var vectorStore = new LinkedHashMap<String,Object>();
        vectorStore.put("provider", "Java-managed Qdrant"); vectorStore.put("collection", collection()); vectorStore.put("configured", true);
        vectorStore.put("directFromNode", false); vectorStore.put("verified", false); vectorStore.put("indexedDocuments", null);
        status.put("vectorStore", vectorStore);
        status.put("embedding", Map.of("provider", "Java EmbeddingModel", "directFromNode", false));
        status.put("rerank", Map.of("provider", "Java RerankService", "cacheOwner", "Java", "directFromNode", false));
        status.put("fallback", Map.of("provider", "Java kb_document", "mode", "关键词检索", "directFromNode", false));
        status.put("semanticGate", Map.of("failureDecision", "UNKNOWN", "backgroundMayRemainAvailable", true));
        status.put("contract", Map.of("request", "{ query, city, constraints, deep? }", "response", "{ ok, retrievalStatus, facts, citations, backgroundFacts, backgroundCitations }", "verified", true));
        return status;
    }
    private String collection() { try { return Objects.requireNonNullElse(scenic.collectionName(), "Java-managed collection"); } catch (RuntimeException ex) { return "Java-managed collection"; } }
    private Map<String,Object> failure(String message) { return Map.of("ok", false, "retrievalStatus", "UNAVAILABLE", "reasonCode", "INVALID_QUERY", "reason", message, "facts", List.of(), "citations", List.of()); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String title(String text) { String line = text.split("\\R", 2)[0]; return line.substring(0, Math.min(80, line.length())); }
}
