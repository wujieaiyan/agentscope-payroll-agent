package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.PayrollForbiddenException;
import io.agentscope.examples.payroll.kb.KbEnums.KbRole;
import io.agentscope.examples.payroll.kb.KbEnums.SubjectType;
import io.agentscope.examples.payroll.support.Permissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 库级权限：平台管理员即 OWNER；普通用户取用户成员与部门成员中的较高者。 */
@Service
public class KbPermissionService {

    private final KbSeriesStore seriesStore;
    private final KbMemberStore memberStore;

    public KbPermissionService(KbSeriesStore seriesStore, KbMemberStore memberStore) {
        this.seriesStore = seriesStore;
        this.memberStore = memberStore;
    }

    public boolean platformAdmin(AuthUser user) {
        return user != null
                && (user.id() == 0
                        || Permissions.ROLE_ADMIN.equals(user.roleCode())
                        || user.can(Permissions.KB_MANAGE));
    }

    public Optional<KbRole> roleIn(long seriesId, AuthUser user) {
        if (platformAdmin(user)) {
            return Optional.of(KbRole.OWNER);
        }
        if (user == null) {
            return Optional.empty();
        }
        KbRole role = null;
        Optional<KbModels.Member> direct =
                memberStore.findOne(seriesId, SubjectType.USER, user.id());
        if (direct.isPresent()) {
            role = direct.get().role();
        }
        if (user.departmentId() != null) {
            Optional<KbModels.Member> department =
                    memberStore.findOne(seriesId, SubjectType.DEPT, user.departmentId());
            if (department.isPresent()) {
                role = KbRole.max(role, department.get().role());
            }
        }
        return Optional.ofNullable(role);
    }

    public KbRole require(long seriesId, AuthUser user, KbRole minimum) {
        KbRole role =
                roleIn(seriesId, user)
                        .orElseThrow(() -> new PayrollForbiddenException("无权访问该知识库"));
        if (!role.atLeast(minimum)) {
            throw new PayrollForbiddenException("当前角色（" + label(role) + "）无权执行该操作");
        }
        return role;
    }

    public KbRole requireRead(long seriesId, AuthUser user) {
        return require(seriesId, user, KbRole.READER);
    }

    public KbRole requireMaintain(long seriesId, AuthUser user) {
        return require(seriesId, user, KbRole.EDITOR);
    }

    public KbRole requireAdmin(long seriesId, AuthUser user) {
        return require(seriesId, user, KbRole.ADMIN);
    }

    public KbRole requireOwner(long seriesId, AuthUser user) {
        return require(seriesId, user, KbRole.OWNER);
    }

    /** 当前用户可见的知识库及角色；平台管理员返回空 Map 表示全部可见。 */
    public Map<Long, KbRole> visibleRoles(AuthUser user) {
        if (platformAdmin(user)) {
            return Map.of();
        }
        Map<Long, KbRole> roles = new LinkedHashMap<>();
        if (user == null) {
            return roles;
        }
        List<KbModels.Member> memberships = new java.util.ArrayList<>();
        memberships.addAll(memberStore.findBySubject(SubjectType.USER, user.id()));
        if (user.departmentId() != null) {
            memberships.addAll(memberStore.findBySubject(SubjectType.DEPT, user.departmentId()));
        }
        for (KbModels.Member member : memberships) {
            roles.merge(member.seriesId(), member.role(), KbRole::max);
        }
        return roles;
    }

    public static String label(KbRole role) {
        return switch (role) {
            case READER -> "只读成员";
            case EDITOR -> "编辑成员";
            case ADMIN -> "管理员";
            case OWNER -> "所有者";
        };
    }
}
