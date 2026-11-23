package io.agentscope.examples.payroll.model;

import io.agentscope.examples.payroll.domain.PayrollException;

/** 模型连接相关错误，携带稳定错误码供前端与管理接口使用。 */
public class ModelConfigException extends PayrollException {

    public static final String CONNECTION_UNAVAILABLE = "MODEL_CONNECTION_UNAVAILABLE";
    public static final String CREDENTIAL_MISSING = "MODEL_CREDENTIAL_MISSING";
    public static final String CREDENTIAL_UNREADABLE = "MODEL_CREDENTIAL_UNREADABLE";
    public static final String SECRET_KEY_MISSING = "MODEL_SECRET_KEY_MISSING";
    public static final String ENV_LOCKED = "MODEL_ENV_LOCKED";
    public static final String UNREACHABLE = "MODEL_UNREACHABLE";
    public static final String CAPABILITY_UNSUPPORTED = "MODEL_CAPABILITY_UNSUPPORTED";
    public static final String REVISION_CONFLICT = "MODEL_REVISION_CONFLICT";
    public static final String OUTBOUND_BLOCKED = "MODEL_OUTBOUND_BLOCKED";
    public static final String INVALID_CONNECTION = "MODEL_CONNECTION_INVALID";

    private final String code;

    public ModelConfigException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ModelConfigException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
