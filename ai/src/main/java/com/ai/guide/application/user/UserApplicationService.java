package com.ai.guide.application.user;

import com.ai.guide.common.model.Result;
import java.util.Map;

/**
 * 用户身份认证与账户应用服务契约
 */
public interface UserApplicationService {

    Result<Map<String, Object>> register(Map<String, String> body);

    Result<Map<String, Object>> login(Map<String, String> body);

    Result<Void> logout(String authorization);

    Result<?> getCurrentUser();
}
