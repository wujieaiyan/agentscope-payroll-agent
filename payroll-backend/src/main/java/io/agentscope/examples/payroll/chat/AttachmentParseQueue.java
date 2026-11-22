package io.agentscope.examples.payroll.chat;

import io.agentscope.examples.payroll.db.ChatAttachmentStore;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 附件解析线程池：上传只负责落盘并入队，OCR/Office 解析在这里异步执行。 */
@Component
public class AttachmentParseQueue {

    private static final Logger log = LoggerFactory.getLogger(AttachmentParseQueue.class);

    private final ChatAttachmentStore store;
    private final ThreadPoolExecutor executor;

    public AttachmentParseQueue(
            ChatAttachmentStore store,
            @Value("${payroll.chat.attachment.parse-threads:2}") int threads,
            @Value("${payroll.chat.attachment.parse-capacity:100}") int capacity) {
        this.store = store;
        int size = Math.max(1, threads);
        this.executor =
                new ThreadPoolExecutor(
                        size,
                        size,
                        60,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(Math.max(1, capacity)),
                        runnable -> {
                            Thread thread = new Thread(runnable, "attachment-parse");
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
    }

    /** 入队失败时直接把附件置为失败，避免长期停留在 PARSING。 */
    public void submit(long sessionId, long attachmentId, Runnable task) {
        try {
            executor.execute(
                    () -> {
                        try {
                            task.run();
                        } catch (RuntimeException e) {
                            log.warn(
                                    "[附件] 解析任务异常：attachmentId={}，原因={}",
                                    attachmentId,
                                    e.getMessage());
                            store.markFailed(sessionId, attachmentId, "附件解析异常，请重新识别");
                        }
                    });
        } catch (RejectedExecutionException e) {
            log.warn("[附件] 解析队列已满，拒绝 attachmentId={}", attachmentId);
            store.markFailed(sessionId, attachmentId, "解析队列已满，请稍后重新识别");
        }
    }

    /** 测试与关停用：等待队列中的解析任务全部结束。 */
    public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(1, timeoutMillis);
        while (System.currentTimeMillis() < deadline) {
            if (executor.getActiveCount() == 0 && executor.getQueue().isEmpty()) {
                return true;
            }
            Thread.sleep(20);
        }
        return executor.getActiveCount() == 0 && executor.getQueue().isEmpty();
    }

    public int queued() {
        return executor.getQueue().size() + executor.getActiveCount();
    }
}
