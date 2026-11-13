package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 知识库文件存储：按 seriesId/uuid.ext 存放，路径访问做逃逸校验。 */
@Service
public class KbStorageService {

    private static final Logger log = LoggerFactory.getLogger(KbStorageService.class);

    private final Path root;

    public KbStorageService(@Value("${payroll.kb.storage-dir:./data/kb-uploads}") String storageDir) {
        this.root = Paths.get(storageDir == null ? "./data/kb-uploads" : storageDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("创建知识库存储目录失败：" + e.getMessage(), e);
        }
    }

    public record StoredFile(String storageKey, String sha256, long sizeBytes, String ext) {
    }

    public StoredFile store(long seriesId, MultipartFile file) {
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String ext = extension(original);
        if (!KbParserService.SUPPORTED.contains(ext)) {
            throw new PayrollException("暂不支持的文档类型：" + (ext.isEmpty() ? original : ext));
        }
        String key = seriesId + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size;
            try (InputStream raw = file.getInputStream();
                    DigestInputStream input = new DigestInputStream(raw, digest)) {
                size = Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StoredFile(key, HexFormat.of().formatHex(digest.digest()), size, ext);
        } catch (IOException e) {
            throw new PayrollException("保存文件失败：" + e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            throw new PayrollException("SHA-256 不可用");
        }
    }

    public Path resolve(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new PayrollException("文件路径为空");
        }
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new PayrollException("文件路径不合法");
        }
        return resolved;
    }

    public boolean exists(String storageKey) {
        try {
            return Files.isRegularFile(resolve(storageKey));
        } catch (PayrollException e) {
            return false;
        }
    }

    public void deleteQuietly(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException | PayrollException e) {
            log.warn("[知识库] 删除文件失败：key={}，原因={}", storageKey, e.getMessage());
        }
    }

    public void deleteSeriesDirectory(long seriesId) {
        Path directory = root.resolve(String.valueOf(seriesId)).normalize();
        if (!directory.startsWith(root) || !Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException e) {
                                    log.warn("[知识库] 清理文件失败：{}", path);
                                }
                            });
        } catch (IOException e) {
            log.warn("[知识库] 清理知识库目录失败：series={}，原因={}", seriesId, e.getMessage());
        }
    }

    public static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.length() <= 16 ? ext : "";
    }
}
