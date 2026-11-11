package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SessionLockServiceTest {

    @Test
    @DisplayName("无 Redis 时退化为进程内锁，释放后可重新获取")
    void localFallbackLocksAndReleases() {
        SessionLockService locks = new SessionLockService(null, 15);

        Optional<String> first = locks.acquire(42L);
        assertTrue(first.isPresent());
        assertTrue(locks.acquire(42L).isEmpty());

        locks.release(42L, first.get());
        assertTrue(locks.acquire(42L).isPresent());
    }

    @Test
    @DisplayName("不同会话互不阻塞")
    void differentSessionsDoNotBlock() {
        SessionLockService locks = new SessionLockService(null, 15);

        assertTrue(locks.acquire(1L).isPresent());
        assertTrue(locks.acquire(2L).isPresent());
        locks.release(1L, "token");
        assertFalse(locks.acquire(2L).isPresent());
    }
}
