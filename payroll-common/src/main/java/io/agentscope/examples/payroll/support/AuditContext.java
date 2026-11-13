package io.agentscope.examples.payroll.support;

import io.agentscope.examples.payroll.domain.AuthUser;
import java.util.UUID;

/**
 * 单次请求的审计上下文，由 {@code AuditFilter} 建立并清理。
 *
 * <p>控制器通过 {@code AuditService} 写入显式事件后，过滤器不再补记通用请求事件；
 * 抛异常时由异常处理器写入失败原因，保证失败请求也有可读信息。
 */
public final class AuditContext {

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private AuditContext() {
    }

    public static State begin(AuthUser actor, String ip, String userAgent) {
        State state = new State(UUID.randomUUID().toString(), ip, userAgent, actor);
        CURRENT.set(state);
        return state;
    }

    public static State current() {
        return CURRENT.get();
    }

    public static void setActor(AuthUser actor) {
        State state = CURRENT.get();
        if (state != null) {
            state.actor = actor;
            state.actorName = actor == null ? state.actorName : actor.username();
        }
    }

    public static void setActorName(String actorName) {
        State state = CURRENT.get();
        if (state != null) {
            state.actorName = actorName;
        }
    }

    public static void markFailure(String message, int status) {
        State state = CURRENT.get();
        if (state != null) {
            state.failureMessage = message;
            state.failureStatus = status;
        }
    }

    public static void markRecorded() {
        State state = CURRENT.get();
        if (state != null) {
            state.recorded = true;
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static final class State {

        private final String traceId;
        private final String ip;
        private final String userAgent;
        private AuthUser actor;
        private String actorName;
        private String failureMessage;
        private Integer failureStatus;
        private boolean recorded;

        private State(String traceId, String ip, String userAgent, AuthUser actor) {
            this.traceId = traceId;
            this.ip = ip;
            this.userAgent = userAgent;
            this.actor = actor;
            this.actorName = actor == null ? null : actor.username();
        }

        public String traceId() {
            return traceId;
        }

        public String ip() {
            return ip;
        }

        public String userAgent() {
            return userAgent;
        }

        public AuthUser actor() {
            return actor;
        }

        public String actorName() {
            return actorName;
        }

        public String failureMessage() {
            return failureMessage;
        }

        public Integer failureStatus() {
            return failureStatus;
        }

        public boolean recorded() {
            return recorded;
        }
    }
}
