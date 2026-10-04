package com.ai.guide.domain.admin.gateway;

import java.util.Map;

/**
 * 全局系统与应用动态配置仓储领域端口 (App Config Repository Port)
 *
 * <p>所属架构层级：COLA 4.0 Domain Gateway Layer（领域层网关端口）</p>
 * <p>架构职责：依据依赖倒置原则 (DIP)，为系统设置、数字人配置等键值型持久化提供与具体数据库技术解耦的访问契约。</p>
 */
public interface AppConfigRepositoryPort {

    /**
     * 根据前缀批量读取配置项，返回剔除前缀后的属性键值对
     *
     * @param prefix 配置项前缀（如 "digital_human_"、"settings_"）
     * @return 剔除前缀后的配置键值 Map，若读取失败返回空 Map
     */
    Map<String, String> readConfig(String prefix);

    /**
     * 批量持久化配置项（若已存在则更新，若不存在则新增）
     *
     * @param prefix 配置项前缀，写入时自动补齐
     * @param values 待保存的配置属性键值对 Map
     */
    void writeConfig(String prefix, Map<String, Object> values);
}
