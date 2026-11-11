package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.kb.KbDtos.SaveTagRequest;
import io.agentscope.examples.payroll.kb.KbDtos.TagView;
import io.agentscope.examples.payroll.kb.KbModels.Tag;
import io.agentscope.examples.payroll.service.AuditService;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 知识库标签：库内唯一，颜色仅接受 #rrggbb。 */
@Service
public class KbTagService {

    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private final KbTagStore tagStore;
    private final KbPermissionService permissionService;
    private final AuditService auditService;

    public KbTagService(
            KbTagStore tagStore, KbPermissionService permissionService, AuditService auditService) {
        this.tagStore = tagStore;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    public List<TagView> list(long seriesId, AuthUser user) {
        permissionService.requireRead(seriesId, user);
        Map<Long, Integer> counts = tagStore.countDocumentsByTag(seriesId);
        return tagStore.findBySeries(seriesId).stream()
                .map(tag -> view(tag, counts.getOrDefault(tag.id(), 0)))
                .toList();
    }

    public TagView create(long seriesId, SaveTagRequest request, AuthUser user) {
        permissionService.requireMaintain(seriesId, user);
        String name = validateName(request);
        if (tagStore.findByName(seriesId, name).isPresent()) {
            throw new PayrollException("标签已存在：" + name);
        }
        Tag tag = tagStore.insert(seriesId, name, color(request == null ? null : request.color()), user.id());
        auditService.record(
                "kb.tag.create", "kb_tag", String.valueOf(tag.id()), null, view(tag, 0));
        return view(tag, 0);
    }

    public TagView update(long seriesId, long tagId, SaveTagRequest request, AuthUser user) {
        permissionService.requireMaintain(seriesId, user);
        Tag tag =
                tagStore
                        .findById(tagId, seriesId)
                        .orElseThrow(() -> new PayrollException("标签不存在"));
        String name = validateName(request);
        tagStore.findByName(seriesId, name)
                .filter(existing -> existing.id() != tagId)
                .ifPresent(
                        existing -> {
                            throw new PayrollException("标签已存在：" + name);
                        });
        tagStore.update(tagId, name, color(request == null ? null : request.color()));
        Tag updated = tagStore.findById(tagId, seriesId).orElseThrow();
        Map<Long, Integer> counts = tagStore.countDocumentsByTag(seriesId);
        TagView view = view(updated, counts.getOrDefault(tagId, 0));
        auditService.record("kb.tag.update", "kb_tag", String.valueOf(tagId), tag, view);
        return view;
    }

    public void delete(long seriesId, long tagId, AuthUser user) {
        permissionService.requireMaintain(seriesId, user);
        Tag tag =
                tagStore
                        .findById(tagId, seriesId)
                        .orElseThrow(() -> new PayrollException("标签不存在"));
        tagStore.delete(tagId);
        auditService.record("kb.tag.delete", "kb_tag", String.valueOf(tagId), tag, null);
    }

    private String validateName(SaveTagRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new PayrollException("标签名称不能为空");
        }
        String name = request.name().trim();
        if (name.length() > 20) {
            throw new PayrollException("标签名称不能超过 20 个字符");
        }
        return name;
    }

    private String color(String value) {
        if (value == null || value.isBlank()) {
            return "#1677ff";
        }
        String color = value.trim();
        if (!COLOR.matcher(color).matches()) {
            throw new PayrollException("标签颜色需为 #rrggbb 格式");
        }
        return color.toLowerCase(java.util.Locale.ROOT);
    }

    private TagView view(Tag tag, int documentCount) {
        return new TagView(tag.id(), tag.name(), tag.color(), documentCount);
    }
}
