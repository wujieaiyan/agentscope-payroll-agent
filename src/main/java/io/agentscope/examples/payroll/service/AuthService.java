package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.db.H2AdminRepository;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 基于 HttpSession 的登录与权限解析；关闭认证时返回拥有全部权限的内置管理员。 */
public final class AuthService {

    static final String SESSION_USER_ID = "payroll.auth.userId";
    private static final String REQUEST_USER = "payroll.auth.user";

    private final H2AdminRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;

    public AuthService(
            H2AdminRepository repository, PasswordEncoder passwordEncoder, boolean enabled) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public Optional<AuthUser> authenticate(String username, String password) {
        UserAccount account;
        try {
            account = repository.findAccountByUsername(username).orElse(null);
        } catch (PayrollException e) {
            return Optional.empty();
        }
        if (account == null
                || !account.active()
                || password == null
                || !passwordEncoder.matches(password, account.passwordHash())) {
            return Optional.empty();
        }
        repository.touchLastLogin(account.id());
        return repository.findAuthUser(account.id());
    }

    public void startSession(HttpServletRequest request, AuthUser user) {
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        request.getSession(true).setAttribute(SESSION_USER_ID, user.id());
        request.setAttribute(REQUEST_USER, user);
    }

    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        request.removeAttribute(REQUEST_USER);
    }

    public boolean hasSession(HttpServletRequest request) {
        if (!enabled) {
            return true;
        }
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(SESSION_USER_ID) != null;
    }

    public Optional<AuthUser> resolve(HttpServletRequest request) {
        if (!enabled) {
            return Optional.of(AuthUser.system());
        }
        Object cached = request.getAttribute(REQUEST_USER);
        if (cached instanceof AuthUser user) {
            return Optional.of(user);
        }
        if (!hasSession(request)) {
            return Optional.empty();
        }
        Long userId = (Long) request.getSession(false).getAttribute(SESSION_USER_ID);
        Optional<AuthUser> user = repository.findAuthUser(userId);
        user.ifPresent(value -> request.setAttribute(REQUEST_USER, value));
        if (user.isEmpty()) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        }
        return user;
    }

    public AuthUser currentUser(HttpServletRequest request) {
        return resolve(request).orElseThrow(() -> new PayrollException("未登录或会话已过期"));
    }

    public void changePassword(long userId, String oldPassword, String newPassword) {
        if (!enabled) {
            throw new PayrollException("当前未启用登录认证，无需修改密码");
        }
        UserAccount account =
                repository
                        .findAccount(userId)
                        .orElseThrow(() -> new PayrollException("用户不存在"));
        if (oldPassword == null
                || !passwordEncoder.matches(oldPassword, account.passwordHash())) {
            throw new PayrollException("原密码不正确");
        }
        H2AdminRepository.validatePassword(newPassword);
        if (passwordEncoder.matches(newPassword, account.passwordHash())) {
            throw new PayrollException("新密码不能与原密码相同");
        }
        repository.updatePassword(userId, passwordEncoder.encode(newPassword));
    }
}
