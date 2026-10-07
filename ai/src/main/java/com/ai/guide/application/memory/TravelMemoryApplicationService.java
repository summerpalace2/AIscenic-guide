package com.ai.guide.application.memory;

import com.ai.guide.domain.memory.model.TravelMemory;
import com.ai.guide.domain.memory.service.TravelMemoryService;

import java.util.List;

/**
 * 旅行记忆与长期偏好画像应用服务门面 (Travel Memory Application Service)
 *
 * <p>所属架构层级：COLA 4.0 Application Layer (应用层)</p>
 * <p>核心职责：统筹游客个人旅行记忆生命周期，涵盖即时对话规则/大模型槽位提炼 (Candidate)、
 * 后台线索异步沉淀 (Observation)、用户显式确认/编辑/删除以及前置个性化 Prompt 上下文动态合成。</p>
 */
public interface TravelMemoryApplicationService {

    /**
     * 查询指定用户 7 天滑动窗口内已确认生效的旅行记忆列表
     *
     * @param userId 已鉴权用户的唯一标识
     * @return 状态为 CONFIRMED 的旅行记忆列表
     */
    List<TravelMemory> list(String userId);

    List<TravelMemory> listPage(String userId, int limit, int offset);

    /**
     * 查询系统基于对话线索为当前用户挖掘的待确认记忆候选建议列表
     *
     * @param userId 用户 ID
     * @return 状态为 PENDING 的候选建议列表
     */
    List<TravelMemoryService.MemoryCandidate> candidates(String userId);

    /**
     * 基于单句对话文本即时提取潜在的长期旅行偏好（免登录开放）
     *
     * @param message 游客输入的自然语言句子
     * @return 提取出的候选偏好（含 category、content、confidence）；若无偏好特征则返回 null
     */
    TravelMemoryService.Candidate suggest(String message);

    /**
     * 接收前台对话中捕获的记忆线索，放入后台整理队列
     *
     * @param userId    用户 ID
     * @param message   会话中的关键陈述文本
     * @param sessionId 关联的规划会话 ID
     */
    void captureObservation(String userId, String message, String sessionId);

    /**
     * 显式确认并持久化一条新的旅行记忆
     *
     * @param userId     用户 ID
     * @param content    规范化偏好陈述内容
     * @param category   偏好分类（如 DIET、PACE、CUSTOM 等）
     * @param sourceType 来源渠道类型（如 CHAT_CONFIRMED）
     * @param sourceRef  来源关联引用
     * @return 持久化成功的旅行记忆实体
     */
    TravelMemory confirm(String userId, String content, String category, String sourceType, String sourceRef);

    /**
     * 修改已存在的旅行记忆条目
     *
     * @param userId   用户 ID
     * @param id       旅行记忆唯一主键
     * @param content  更新后的陈述内容
     * @param category 更新后的偏好分类
     * @return 更新后的旅行记忆实体
     */
    TravelMemory update(String userId, String id, String content, String category);

    /**
     * 删除指定的旅行记忆
     *
     * @param userId 用户 ID
     * @param id     旅行记忆主键
     */
    void delete(String userId, String id);

    /**
     * 采纳并确认一条候选记忆建议，将其转化为已生效的旅行记忆
     *
     * @param userId 用户 ID
     * @param id     候选建议主键
     * @return 转正后的旅行记忆实体
     */
    TravelMemory confirmCandidate(String userId, String id);

    /**
     * 忽略并丢弃一条候选记忆建议
     *
     * @param userId 用户 ID
     * @param id     候选建议主键
     */
    void dismissCandidate(String userId, String id);

    /**
     * 获取用户当前生效的动态旅行偏好前置提示词上下文
     *
     * @param userId 用户 ID
     * @return 前置提示词文本字符串
     */
    String getPromptContext(String userId);

    /**
     * 强制重新合成用户偏好前置提示词（基于已确认的全部记忆聚类）
     *
     * @param userId 用户 ID
     * @return 新生成的个性化前置提示词
     */
    String resynthesizePrependPrompt(String userId);

    /**
     * 保存用户手动定制或修正后的偏好前置提示词
     *
     * @param userId       用户 ID
     * @param customPrompt 用户自定义的提示词内容
     * @return 保存成功后的提示词内容
     */
    String saveUserCustomizedPrompt(String userId, String customPrompt);

    java.util.Map<String, Object> promptDetails(String userId);
}
