package io.agentscope.examples.payroll.profile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 抽取编排：按游标取窗口、唯一键认领、规则/模型抽取、事务写入与失败推进。
 *
 * <p>只在 runtime/all 节点装配；web 节点没有对话执行，不触发抽取。
 * 任一环节失败都只影响画像更新，不阻断对话主链路。
 */
public final class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    /** 未处理轮次累计达到该值才触发一次抽取。 */
    public static final int TRIGGER_TURNS = 5;
    /** 单次窗口最多包含的轮次。 */
    public static final int WINDOW_TURNS = 10;

    private final UserProfileStore store;
    private final UserProfileExtractor extractor;
    private final boolean enabled;
    private final AtomicLong claimed = new AtomicLong();
    private final AtomicLong done = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();

    public UserProfileService(
            UserProfileStore store, UserProfileExtractor extractor, boolean enabled) {
        this.store = store;
        this.extractor = extractor;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled && store != null;
    }

    public void process(long userId) {
        if (!enabled() || userId <= 0) {
            return;
        }
        List<UserProfileTurn> window;
        try {
            window = store.window(userId, WINDOW_TURNS);
        } catch (RuntimeException e) {
            log.warn(
                    "[工作偏好] 读取抽取窗口失败（已忽略本轮）：userId={}，原因={}",
                    userId,
                    e.getClass().getSimpleName());
            return;
        }
        if (window.size() < TRIGGER_TURNS) {
            return;
        }
        String targetTurnId = window.get(window.size() - 1).turnId();
        LocalDateTime now = LocalDateTime.now();
        UserProfileStore.Claim claim;
        try {
            claim = store.claim(userId, targetTurnId, now);
        } catch (RuntimeException e) {
            log.warn(
                    "[工作偏好] 认领失败（已忽略本轮）：userId={}，target={}，原因={}",
                    userId,
                    targetTurnId,
                    e.getClass().getSimpleName());
            return;
        }
        if (claim != UserProfileStore.Claim.CLAIMED) {
            log.debug(
                    "[工作偏好] 窗口已被其它节点处理或处于冷却：userId={}，target={}，claim={}",
                    userId,
                    targetTurnId,
                    claim);
            return;
        }
        claimed.incrementAndGet();
        try {
            UserProfileExtractor.Result result =
                    extractor.extract(window, store.departments(), store.cities());
            if (result.failed()) {
                boolean skip = store.fail(userId, targetTurnId, result.detail(), now);
                failed.incrementAndGet();
                if (skip) {
                    skipped.incrementAndGet();
                }
                log.warn(
                        "[工作偏好] 抽取失败：userId={}，target={}，detail={}，skipped={}",
                        userId,
                        targetTurnId,
                        result.detail(),
                        skip);
                return;
            }
            store.complete(userId, targetTurnId, result.items(), now);
            // 抽取在 runtime 进程内完成，注入读缓存必须立即刷新，否则新会话最长 60 分钟看不到新画像。
            ProfileMemory.invalidate(userId);
            done.incrementAndGet();
            log.info(
                    "[工作偏好] 抽取完成：userId={}，target={}，items={}，window={}",
                    userId,
                    targetTurnId,
                    result.items().size(),
                    window.size());
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            try {
                if (store.fail(userId, targetTurnId, e.getClass().getSimpleName(), now)) {
                    skipped.incrementAndGet();
                }
            } catch (RuntimeException ignored) {
                log.debug("[工作偏好] 失败状态回写失败", ignored);
            }
            log.warn(
                    "[工作偏好] 抽取异常：userId={}，target={}，原因={}",
                    userId,
                    targetTurnId,
                    e.getClass().getSimpleName());
        }
    }

    public long claimedCount() {
        return claimed.get();
    }

    public long doneCount() {
        return done.get();
    }

    public long failedCount() {
        return failed.get();
    }

    public long skippedCount() {
        return skipped.get();
    }
}
