package com.ai.guide.application.admin;

import java.util.List;
import java.util.Map;

/**
 * 管理后台与系统全局治理应用服务门面 (Admin Application Service)
 *
 * <p>所属架构层级：COLA 4.0 Application Layer (应用层)</p>
 * <p>核心职责：统筹用户角色调整与冻结、数字人音色及问候语设置、全站参数治理（如文件上传限制、知识同步开关等）等系统管理员用例编排。</p>
 */
public interface AdminApplicationService {

    /**
     * 分页查询系统全量注册用户列表
     *
     * @param page 分页页码（从 1 开始）
     * @param size 每页记录条数限制
     * @return 包含用户列表与分页元数据的对象（通常为 Map 或 Page 结构）
     */
    Object listUsers(int page, int size);

    /**
     * 更新指定用户的权限角色或账户状态
     *
     * @param userId 目标用户 ID
     * @param role   新的角色权限（如 USER、ADMIN）
     * @param status 新的账户状态（如 active、disabled）
     */
    void updateUserRole(String userId, String role, String status);

    /**
     * 禁用指定用户账户（软冻结）
     *
     * @param userId 目标用户 ID
     */
    void disableUser(String userId);

    /**
     * 查询指定用户完整资料及注册属性详情
     *
     * @param userId 目标用户 ID
     * @return 用户详情 Map；若不存在则返回 null
     */
    Map<String, Object> getUserDetail(String userId);

    /**
     * 查询数字人导游的默认及持久化配置项（名称、发音人、问候语、启用状态等）
     *
     * @return 合并默认值与持久化配置后的数字人属性 Map
     */
    Map<String, Object> getDigitalHumanConfig();

    /**
     * 更新数字人导游配置
     *
     * @param config 包含更新字段的配置项 Map
     */
    void updateDigitalHumanConfig(Map<String, Object> config);

    /**
     * 查询系统预设支持的数字人发音人声音模型列表（与百度语音发音人保持一致）
     *
     * @return 发音人元数据列表（包含 ID、名称、性别、风格与默认标识）
     */
    List<Map<String, Object>> listDigitalHumanVoices();

    /**
     * 查询全站系统设置（站点名称、默认语言、TTS/知识库开关、最大上传限制等）
     *
     * @return 合并默认值与持久化配置后的系统设置 Map
     */
    Map<String, Object> getSettings();

    /**
     * 更新全站系统设置
     *
     * @param settings 包含待更新设置属性的 Map
     */
    void updateSettings(Map<String, Object> settings);
}
