package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 本地 blob 存储（T-02）：内容寻址幂等、读时校验、越界 key 拒绝、并发同内容写。 */
class LocalDiskSkillBlobStoreTest {

    @TempDir Path root;

    private LocalDiskSkillBlobStore store;

    @BeforeEach
    void setUp() {
        store = new LocalDiskSkillBlobStore(root.toString(), 1024);
    }

    @Test
    @DisplayName("同内容重复写入幂等，磁盘上只有一份")
    void putIsContentAddressedAndIdempotent() throws Exception {
        byte[] content = "个税测算脚本".getBytes(StandardCharsets.UTF_8);
        SkillBlobStore.BlobRef first = store.put(content, "text/x-shellscript");
        SkillBlobStore.BlobRef second = store.put(content, "application/octet-stream");

        assertEquals(sha256(content), first.sha256());
        assertEquals(first.sha256(), second.sha256());
        assertEquals(storageKeyOf(first.sha256()), first.storageKey());
        assertEquals(content.length, first.sizeBytes());
        assertEquals("text/x-shellscript", first.mediaType());
        assertEquals("application/octet-stream", second.mediaType(), "mediaType 由调用方给，介质不保存它");
        assertEquals(1, blobCount());
        assertArrayEquals(content, store.get(first.sha256()).orElseThrow());
        assertTrue(store.exists(first.sha256()));
    }

    @Test
    @DisplayName("大写指纹与空白 mediaType 都按同一份内容处理")
    void keysAreCaseInsensitiveAndMediaTypeHasDefault() {
        SkillBlobStore.BlobRef ref = store.put("body".getBytes(), "  ");
        String upper = ref.sha256().toUpperCase();

        assertArrayEquals("body".getBytes(), store.get(upper).orElseThrow());
        assertEquals("text/plain", ref.mediaType());
        assertTrue(store.exists(upper));
        assertEquals(1, store.getAll(List.of(ref.sha256(), upper, ref.sha256())).size());
    }

    @Test
    @DisplayName("超过单文件上限直接拒绝，不落半个文件")
    void oversizedContentIsRejectedBeforeWrite() throws Exception {
        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> store.put(new byte[1025], "text/plain"));

        assertTrue(error.getMessage().contains("1024"), error.getMessage());
        assertEquals(0, blobCount());
        assertEquals(0, tmpCount());
    }

    @Test
    @DisplayName("磁盘内容与指纹不符时读路径 fail-closed")
    void corruptedBlobIsNotServed() throws Exception {
        SkillBlobStore.BlobRef ref = store.put("original".getBytes(), "text/plain");
        Files.write(pathOf(ref.sha256()), "tampered".getBytes());

        assertThrows(PayrollException.class, () -> store.get(ref.sha256()));
        assertThrows(PayrollException.class, () -> store.getAll(List.of(ref.sha256())));
        assertTrue(store.exists(ref.sha256()), "损坏不等于缺失，对账要能看见它");
    }

    @Test
    @DisplayName("非 sha256 形态的 key 一律拒绝，不碰文件系统")
    void illegalKeysNeverReachTheFilesystem() {
        for (String key : List.of("", "  ", "../payroll", "0".repeat(63), "f".repeat(65),
                        "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbee!")) {
            assertThrows(PayrollException.class, () -> store.get(key), key);
            assertFalse(store.exists(key), key);
        }
        assertThrows(PayrollException.class, () -> store.get(null));
        assertFalse(store.exists(null));
        assertThrows(PayrollException.class, () -> store.delete("not-a-sha"));
    }

    @Test
    @DisplayName("未写入的指纹读为空，交由调用方报「资源缺失」")
    void missingBlobIsEmpty() {
        String absent = sha256("never written".getBytes());

        assertTrue(store.get(absent).isEmpty());
        assertFalse(store.exists(absent));
        assertEquals(Map.of(), store.getAll(List.of(absent)));
    }

    @Test
    @DisplayName("八个线程同时写同一内容：全部成功且只有一份")
    void concurrentPutOfSameContentConverges() throws Exception {
        byte[] content = "shared reference file".getBytes();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> store.put(content, "text/plain").sha256());
            }
            List<Future<String>> futures = pool.invokeAll(tasks);
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

            String sha = sha256(content);
            for (Future<String> future : futures) {
                assertEquals(sha, future.get());
            }
            assertEquals(1, blobCount());
            assertEquals(0, tmpCount());
            assertArrayEquals(content, store.get(sha).orElseThrow());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("批量读一次拿全，缺失的指纹从结果里缺席")
    void getAllReturnsOnlyPresentBlobs() {
        SkillBlobStore.BlobRef a = store.put("alpha".getBytes(), "text/plain");
        SkillBlobStore.BlobRef b = store.put("beta".getBytes(), "text/markdown");
        String absent = sha256("gamma".getBytes());

        Map<String, byte[]> loaded = store.getAll(List.of(a.sha256(), absent, b.sha256(), a.sha256()));

        assertEquals(List.of(a.sha256(), b.sha256()), List.copyOf(loaded.keySet()));
        assertArrayEquals("alpha".getBytes(), loaded.get(a.sha256()));
        assertArrayEquals("beta".getBytes(), loaded.get(b.sha256()));
    }

    @Test
    @DisplayName("delete 只清这一份，介质描述不含凭据")
    void deleteRemovesBlobAndLocationIsReadable() {
        SkillBlobStore.BlobRef ref = store.put("temp".getBytes(), "text/plain");

        store.delete(ref.sha256());

        assertFalse(store.exists(ref.sha256()));
        store.delete(ref.sha256());
        assertEquals("local:" + root.toAbsolutePath().normalize(), store.location());
    }

    private String storageKeyOf(String sha) {
        return sha.substring(0, 2) + "/" + sha;
    }

    private Path pathOf(String sha) {
        return root.resolve(sha.substring(0, 2)).resolve(sha);
    }

    private long blobCount() throws IOException {
        return count(false);
    }

    private long tmpCount() throws IOException {
        return count(true);
    }

    private long count(boolean temp) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".tmp") == temp)
                    .count();
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
