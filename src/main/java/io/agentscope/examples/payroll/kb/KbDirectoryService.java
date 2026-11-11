package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.kb.KbDtos.CreateDirectoryRequest;
import io.agentscope.examples.payroll.kb.KbDtos.DeleteDirectoryRequest;
import io.agentscope.examples.payroll.kb.KbDtos.DirectoryView;
import io.agentscope.examples.payroll.kb.KbDtos.MoveDirectoryRequest;
import io.agentscope.examples.payroll.kb.KbDtos.RenameDirectoryRequest;
import io.agentscope.examples.payroll.kb.KbModels.Directory;
import io.agentscope.examples.payroll.kb.KbModels.Document;
import io.agentscope.examples.payroll.service.AuditService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 知识库目录树：创建、重命名、移动与级联删除。 */
@Service
public class KbDirectoryService {

    private final KbDirectoryStore directoryStore;
    private final KbDocumentStore documentStore;
    private final KbChunkStore chunkStore;
    private final KbTagStore tagStore;
    private final KbSeriesService seriesService;
    private final KbPermissionService permissionService;
    private final AuditService auditService;
    private final int maxDepth;

    public KbDirectoryService(
            KbDirectoryStore directoryStore,
            KbDocumentStore documentStore,
            KbChunkStore chunkStore,
            KbTagStore tagStore,
            KbSeriesService seriesService,
            KbPermissionService permissionService,
            AuditService auditService,
            @Value("${payroll.kb.max-directory-depth:5}") int maxDepth) {
        this.directoryStore = directoryStore;
        this.documentStore = documentStore;
        this.chunkStore = chunkStore;
        this.tagStore = tagStore;
        this.seriesService = seriesService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.maxDepth = Math.max(1, maxDepth);
    }

    public List<DirectoryView> tree(long seriesId, AuthUser user) {
        permissionService.requireRead(seriesId, user);
        List<Directory> directories = directoryStore.findBySeries(seriesId);
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Document document : documentStore.findBySeries(seriesId, null, null, null)) {
            counts.merge(document.directoryId(), 1, Integer::sum);
        }
        Map<Long, List<Directory>> byParent = new LinkedHashMap<>();
        for (Directory directory : directories) {
            byParent.computeIfAbsent(directory.parentId(), key -> new ArrayList<>()).add(directory);
        }
        return byParent.getOrDefault(null, List.of()).stream()
                .map(root -> build(root, byParent, counts))
                .toList();
    }

    public DirectoryView create(long seriesId, CreateDirectoryRequest request, AuthUser user) {
        permissionService.requireMaintain(seriesId, user);
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new PayrollException("目录名称不能为空");
        }
        String name = request.name().trim();
        if (name.length() > 50) {
            throw new PayrollException("目录名称不能超过 50 个字符");
        }
        Directory root =
                directoryStore
                        .findRoot(seriesId)
                        .orElseThrow(() -> new PayrollException("知识库目录结构缺失，请联系管理员"));
        Directory parent = root;
        if (request.parentId() != null && request.parentId() != root.id()) {
            parent =
                    directoryStore
                            .findById(request.parentId(), seriesId)
                            .orElseThrow(() -> new PayrollException("上级目录不存在"));
            if (parent.root()) {
                parent = root;
            }
        }
        int depth = parent.depth() + 1;
        if (depth > maxDepth) {
            throw new PayrollException("目录层级不能超过 " + maxDepth + " 层");
        }
        if (directoryStore.existsSibling(seriesId, parent.id(), name, null)) {
            throw new PayrollException("同级目录下已存在同名目录：" + name);
        }
        int sortOrder = request.sortOrder() == null ? 0 : request.sortOrder();
        Directory created =
                directoryStore.insert(
                        seriesId, parent.id(), name, "", depth, false, sortOrder, user.id());
        String path = (parent.root() ? "/" : parent.path()) + created.id() + "/";
        directoryStore.move(created.id(), parent.id(), path, depth, sortOrder);
        DirectoryView view =
                new DirectoryView(
                        created.id(),
                        seriesId,
                        parent.id(),
                        name,
                        path,
                        depth,
                        false,
                        sortOrder,
                        0,
                        List.of());
        auditService.record(
                "kb.directory.create", "kb_directory", String.valueOf(created.id()), null, view);
        return view;
    }

    public DirectoryView rename(
            long seriesId, long directoryId, RenameDirectoryRequest request, AuthUser user) {
        permissionService.requireMaintain(seriesId, user);
        Directory directory =
                directoryStore
                        .findById(directoryId, seriesId)
                        .orElseThrow(() -> new PayrollException("目录不存在"));
        if (directory.root()) {
            throw new PayrollException("根目录不能重命名");
        }
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new PayrollException("目录名称不能为空");
        }
        String name = request.name().trim();
        if (name.length() > 50) {
            throw new PayrollException("目录名称不能超过 50 个字符");
        }
        if (directoryStore.existsSibling(seriesId, directory.parentId(), name, directoryId)) {
            throw new PayrollException("同级目录下已存在同名目录：" + name);
        }
        int sortOrder = request.sortOrder() == null ? directory.sortOrder() : request.sortOrder();
        directoryStore.rename(directoryId, name, sortOrder);
        Directory updated = directoryStore.findById(directoryId, seriesId).orElseThrow();
        DirectoryView view = singleView(seriesId, updated);
        auditService.record(
                "kb.directory.rename",
                "kb_directory",
                String.valueOf(directoryId),
                directory.name(),
                name);
        return view;
    }

    public DirectoryView move(
            long seriesId, long directoryId, MoveDirectoryRequest request, AuthUser user) {
        permissionService.requireMaintain(seriesId, user);
        Directory directory =
                directoryStore
                        .findById(directoryId, seriesId)
                        .orElseThrow(() -> new PayrollException("目录不存在"));
        if (directory.root()) {
            throw new PayrollException("根目录不能移动");
        }
        Directory root =
                directoryStore
                        .findRoot(seriesId)
                        .orElseThrow(() -> new PayrollException("知识库目录结构缺失"));
        Directory parent = root;
        if (request != null && request.parentId() != null && request.parentId() != root.id()) {
            parent =
                    directoryStore
                            .findById(request.parentId(), seriesId)
                            .orElseThrow(() -> new PayrollException("目标目录不存在"));
        }
        if (parent.id() == directoryId) {
            throw new PayrollException("不能把目录移动到自身下");
        }
        if (!parent.root() && parent.path().startsWith(directory.path())) {
            throw new PayrollException("不能把目录移动到其子目录下");
        }
        int depth = parent.depth() + 1;
        List<Directory> subtree =
                directoryStore.findByPathPrefix(seriesId, directory.path());
        int maxRelative = 0;
        for (Directory node : subtree) {
            maxRelative = Math.max(maxRelative, node.depth() - directory.depth());
        }
        if (depth + maxRelative > maxDepth) {
            throw new PayrollException("移动后目录层级将超过 " + maxDepth + " 层");
        }
        if (directoryStore.existsSibling(seriesId, parent.id(), directory.name(), directoryId)) {
            throw new PayrollException("目标目录下已存在同名目录：" + directory.name());
        }
        int sortOrder =
                request == null || request.sortOrder() == null
                        ? directory.sortOrder()
                        : request.sortOrder();
        String newPath = (parent.root() ? "/" : parent.path()) + directory.id() + "/";
        String oldPath = directory.path();
        directoryStore.move(directoryId, parent.id(), newPath, depth, sortOrder);
        int depthDelta = depth - directory.depth();
        for (Directory node : subtree) {
            if (node.id() == directoryId) {
                continue;
            }
            String path = newPath + node.path().substring(oldPath.length());
            directoryStore.updatePath(node.id(), path, node.depth() + depthDelta);
        }
        Directory updated = directoryStore.findById(directoryId, seriesId).orElseThrow();
        DirectoryView view = singleView(seriesId, updated);
        auditService.record(
                "kb.directory.move",
                "kb_directory",
                String.valueOf(directoryId),
                oldPath,
                newPath);
        return view;
    }

    public void delete(
            long seriesId, long directoryId, DeleteDirectoryRequest request, AuthUser user) {
        permissionService.requireAdmin(seriesId, user);
        Directory directory =
                directoryStore
                        .findById(directoryId, seriesId)
                        .orElseThrow(() -> new PayrollException("目录不存在"));
        if (directory.root()) {
            throw new PayrollException("根目录不能删除");
        }
        List<Directory> subtree = directoryStore.findByPathPrefix(seriesId, directory.path());
        List<Document> documents = new ArrayList<>();
        for (Directory node : subtree) {
            documents.addAll(documentStore.findByDirectory(seriesId, node.id()));
        }
        boolean nonEmpty = subtree.size() > 1 || !documents.isEmpty();
        if (nonEmpty) {
            String confirm = request == null ? null : request.confirmName();
            if (confirm == null || !confirm.trim().equals(directory.name())) {
                throw new PayrollException(
                        "目录非空，需输入目录名称「" + directory.name() + "」确认删除");
            }
        }
        for (Document document : documents) {
            documentStore.softDelete(document.id());
            chunkStore.deleteByDocument(document.id());
            tagStore.deleteDocumentTags(document.id());
        }
        directoryStore.delete(directoryId);
        for (Directory node : subtree) {
            if (node.id() != directoryId) {
                directoryStore.delete(node.id());
            }
        }
        seriesService.refreshCounters(seriesId);
        auditService.record(
                "kb.directory.delete",
                "kb_directory",
                String.valueOf(directoryId),
                directory,
                Map.of("documents", documents.size()));
    }

    private DirectoryView build(
            Directory directory,
            Map<Long, List<Directory>> byParent,
            Map<Long, Integer> counts) {
        List<DirectoryView> children =
                byParent.getOrDefault(directory.id(), List.of()).stream()
                        .map(child -> build(child, byParent, counts))
                        .toList();
        return new DirectoryView(
                directory.id(),
                directory.seriesId(),
                directory.parentId(),
                directory.name(),
                directory.path(),
                directory.depth(),
                directory.root(),
                directory.sortOrder(),
                counts.getOrDefault(directory.id(), 0),
                children);
    }

    private DirectoryView singleView(long seriesId, Directory directory) {
        int count = documentStore.findByDirectory(seriesId, directory.id()).size();
        return new DirectoryView(
                directory.id(),
                directory.seriesId(),
                directory.parentId(),
                directory.name(),
                directory.path(),
                directory.depth(),
                directory.root(),
                directory.sortOrder(),
                count,
                List.of());
    }
}
