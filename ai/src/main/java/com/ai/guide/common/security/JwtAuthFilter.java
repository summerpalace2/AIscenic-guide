package com.ai.guide.common.security;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.user.service.UserService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/**
 * JWT 统一身份认证与安全拦截过滤器
 *
 * 所属领域：common.security（安全与鉴权底座）
 * 架构职责：拦截所有进入服务端的 HTTP 请求，解析 Authorization Bearer 令牌，校验有效期与黑名单状态，并将用户信息绑定至 UserContext 线程上下文。
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final JwtRevocationService revocationService;

    public JwtAuthFilter(JwtUtil jwtUtil,
                         UserService userService,
                         JwtRevocationService revocationService) {
        this.jwtUtil = jwtUtil;
        this.userService = userService;
        this.revocationService = revocationService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        UserContext.clear();
        try {
            String header = request.getHeader("Authorization");
            if (header != null) {
                if (!header.startsWith("Bearer ")) {
                    unauthorized(response);
                    return;
                }
                String token = header.substring("Bearer ".length()).trim();
                Claims claims = jwtUtil.parse(token);
                if (claims == null || revocationService.isRevoked(token, claims)) {
                    unauthorized(response);
                    return;
                }
                String userId = claims.getSubject();
                if (userId == null || userId.isBlank()) {
                    unauthorized(response);
                    return;
                }
                UserService.AuthenticatedPrincipal principal;
                try {
                    principal = userService.findActivePrincipal(userId);
                } catch (RuntimeException unavailable) {
                    unauthorized(response);
                    return;
                }
                if (principal == null) {
                    unauthorized(response);
                    return;
                }
                // Current DB authority wins over stale role/username claims.
                UserContext.set(principal.id(), principal.username(), principal.role());
            }

            if (UserContext.isAnonymous() && !allowsAnonymous(request)) {
                unauthorized(response);
                return;
            }
            if (isAdminPath(request) && !UserContext.isAdmin()) {
                forbidden(response);
                return;
            }
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    private boolean allowsAnonymous(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String method = request.getMethod().toUpperCase();

        if ("POST".equals(method) &&
                ("/ai/auth/register".equals(path) || "/ai/auth/login".equals(path))) {
            return true;
        }
        if ("GET".equals(method) && isPublicAttractionPath(path)) {
            return true;
        }
        // 游客体验页只提供普通旅行问答；会话由 BFF 生成的匿名 chat id 隔离，
        // 不拥有规划、偏好写入、行程保存或管理能力。
        if ("GET".equals(method) && "/ai/chat/stream".equals(path)) return true;
        if ("POST".equals(method) && "/ai/planner/v1/plan".equals(path)) return true;
        if ("POST".equals(method) && (
                "/ai/planner/v1/shadow".equals(path)
                        || "/ai/planner/v1/shadow/replan".equals(path)
                        || "/ai/planner/v1/shadow/stops".equals(path))) return true;
        if ("POST".equals(method) && "/ai/rag/retrieve".equals(path)) return true;
        return ("GET".equals(method) || "POST".equals(method)) &&
                path.startsWith("/ai/planner/v1/sessions/");
    }

    private boolean isPublicAttractionPath(String path) {
        if ("/ai/attractions".equals(path)) return true;
        if (!path.startsWith("/ai/attractions/") || path.length() <= "/ai/attractions/".length()) {
            return false;
        }
        String remainder = path.substring("/ai/attractions/".length());
        if (remainder.endsWith("/facts")) {
            remainder = remainder.substring(0, remainder.length() - "/facts".length());
        }
        return !remainder.isBlank() && !remainder.contains("/");
    }

    private boolean isAdminPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return "/ai/admin".equals(path) || path.startsWith("/ai/admin/");
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) return;
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"请先登录或提供有效凭据\",\"data\":null,\"success\":false}");
    }

    private void forbidden(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) return;
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"需要管理员权限\",\"data\":null,\"success\":false}");
    }
}
