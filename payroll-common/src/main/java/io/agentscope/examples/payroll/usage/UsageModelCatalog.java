package io.agentscope.examples.payroll.usage;

import io.agentscope.core.model.Model;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 模型实例 → 使用面/提供方 的登记表。
 *
 * <p>模型按「使用面 + 连接版本」缓存，实例复用于多个智能体；中间件只能看到
 * {@link Model} 本身，因此由工厂在构建时登记，看板据此还原「控制面/轻量面/编码面」。
 */
public final class UsageModelCatalog {

    private static final Map<Model, Meta> MODELS =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private UsageModelCatalog() {
    }

    public record Meta(String plane, String provider) {
    }

    public static void register(Model model, String plane, String provider) {
        if (model == null) {
            return;
        }
        MODELS.put(model, new Meta(label(plane), label(provider)));
    }

    public static Meta describe(Model model) {
        if (model == null) {
            return new Meta(label(null), label(null));
        }
        Meta meta = MODELS.get(model);
        return meta == null ? new Meta(label(null), label(null)) : meta;
    }

    public static void clear() {
        MODELS.clear();
    }

    private static String label(String value) {
        return value == null || value.isBlank() ? "未标注" : value;
    }
}
