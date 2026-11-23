package io.agentscope.examples.payroll.profile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 画像存储端口：条目 CRUD、按用户取窗口、认领式抽取凭证。
 *
 * <p>多节点并发靠 {@code uq_profile_extraction} 唯一键认领，不引入外部锁；所有时间判断使用
 * 数据库写入的 {@code updated_at}，避免节点时钟漂移放大租约窗口。
 */
public interface UserProfileStore {

    List<UserProfileItem> items(long userId, boolean includeInactive);

    List<UserProfileItem> active(long userId, int limit);

    /** 当前画像指纹：无 ACTIVE 条目返回空，用于语义缓存追加用户维度。 */
    Optional<String> revision(long userId);

    Optional<UserProfileItem> item(long itemId, long userId);

    UserProfileItem upsert(UserProfileItem item);

    boolean updateStatus(long itemId, long userId, String status);

    boolean delete(long itemId, long userId);

    int deleteAll(long userId);

    /** 合规删除：条目与抽取凭证一并物理删除。 */
    int purge(long userId);

    int touchInjected(long userId, List<Long> itemIds, LocalDateTime now);

    List<String> departments();

    List<String> cities();

    /** 游标之后最多 {@code maxTurns} 轮 SUCCEEDED 用户问题，按 (started_at, turn_id) 升序。 */
    List<UserProfileTurn> window(long userId, int maxTurns);

    Claim claim(long userId, String targetTurnId, LocalDateTime now);

    /** 条目写入与 DONE 翻转同一事务；值域在写入前再次校验。 */
    void complete(long userId, String targetTurnId, List<UserProfileItem> items, LocalDateTime now);

    /** FAILED 计数 +1；attempts 达到 3 时直接转 SKIPPED，推进游标避免用户被永久卡死。 */
    boolean fail(long userId, String targetTurnId, String detail, LocalDateTime now);

    Optional<UserProfileExtraction> extraction(long userId, String targetTurnId);

    /** 清理早于给定时间的抽取凭证，与 chat_turns 同周期。 */
    int cleanup(LocalDateTime before);

    enum Claim {
        CLAIMED,
        DUPLICATE,
        COOLDOWN
    }
}
