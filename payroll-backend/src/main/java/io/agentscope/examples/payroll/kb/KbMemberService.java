package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.db.H2AdminRepository;
import io.agentscope.examples.payroll.domain.AppUser;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.Department;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.kb.KbDtos.GrantMembersRequest;
import io.agentscope.examples.payroll.kb.KbDtos.MemberView;
import io.agentscope.examples.payroll.kb.KbEnums.KbRole;
import io.agentscope.examples.payroll.kb.KbEnums.SubjectType;
import io.agentscope.examples.payroll.kb.KbModels.Member;
import io.agentscope.examples.payroll.service.AuditService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 知识库成员与角色授予；用户和部门都按库级角色授权。 */
@Service
public class KbMemberService {

    private final KbMemberStore memberStore;
    private final KbPermissionService permissionService;
    private final H2AdminRepository adminRepository;
    private final AuditService auditService;

    public KbMemberService(
            KbMemberStore memberStore,
            KbPermissionService permissionService,
            H2AdminRepository adminRepository,
            AuditService auditService) {
        this.memberStore = memberStore;
        this.permissionService = permissionService;
        this.adminRepository = adminRepository;
        this.auditService = auditService;
    }

    public List<MemberView> list(long seriesId, AuthUser user) {
        permissionService.requireRead(seriesId, user);
        return memberStore.findBySeries(seriesId).stream().map(this::view).toList();
    }

    public List<MemberView> grant(long seriesId, GrantMembersRequest request, AuthUser user) {
        permissionService.requireAdmin(seriesId, user);
        if (request == null || request.subjectIds() == null || request.subjectIds().isEmpty()) {
            throw new PayrollException("请选择要添加的用户或部门");
        }
        if (request.subjectIds().size() > 100) {
            throw new PayrollException("单次最多添加 100 个成员");
        }
        SubjectType subjectType =
                request.subjectType() == null ? SubjectType.USER : request.subjectType();
        KbRole role = request.role() == null ? KbRole.READER : request.role();
        if (role == KbRole.OWNER) {
            throw new PayrollException("不能直接授予所有者角色");
        }
        for (Long subjectId : request.subjectIds()) {
            if (subjectId == null || subjectId <= 0) {
                throw new PayrollException("成员标识不合法");
            }
            if (subjectType == SubjectType.USER) {
                AppUser target =
                        adminRepository
                                .findUser(subjectId)
                                .orElseThrow(() -> new PayrollException("用户不存在：" + subjectId));
                if (!target.active()) {
                    throw new PayrollException("用户已停用：" + target.displayName());
                }
            } else {
                Department department =
                        adminRepository
                                .findDepartment(subjectId)
                                .orElseThrow(() -> new PayrollException("部门不存在：" + subjectId));
                if (!department.active()) {
                    throw new PayrollException("部门已停用：" + department.name());
                }
            }
        }
        for (Long subjectId : request.subjectIds()) {
            memberStore.upsert(seriesId, subjectType, subjectId, role, user.id());
        }
        auditService.record(
                "kb.member.grant",
                "kb_series",
                String.valueOf(seriesId),
                null,
                new GrantMembersRequest(subjectType, request.subjectIds(), role));
        return list(seriesId, user);
    }

    public MemberView updateRole(
            long seriesId, long memberId, KbRole role, AuthUser user) {
        permissionService.requireAdmin(seriesId, user);
        Member member =
                memberStore
                        .findById(memberId, seriesId)
                        .orElseThrow(() -> new PayrollException("成员不存在"));
        if (member.role() == KbRole.OWNER) {
            throw new PayrollException("不能修改所有者角色");
        }
        if (role == null || role == KbRole.OWNER) {
            throw new PayrollException("不能将成员提升为所有者");
        }
        if (member.role() == KbRole.ADMIN
                && !role.atLeast(KbRole.ADMIN)
                && memberStore.countAdmins(seriesId) <= 1) {
            throw new PayrollException("至少需要保留一名管理员或所有者");
        }
        memberStore.updateRole(memberId, role);
        auditService.record(
                "kb.member.update",
                "kb_series",
                String.valueOf(seriesId),
                member,
                new KbDtos.UpdateMemberRoleRequest(role));
        return view(memberStore.findById(memberId, seriesId).orElseThrow());
    }

    public void remove(long seriesId, long memberId, AuthUser user) {
        permissionService.requireAdmin(seriesId, user);
        Member member =
                memberStore
                        .findById(memberId, seriesId)
                        .orElseThrow(() -> new PayrollException("成员不存在"));
        if (member.role() == KbRole.OWNER) {
            throw new PayrollException("不能移除知识库所有者");
        }
        if (member.role() == KbRole.ADMIN && memberStore.countAdmins(seriesId) <= 1) {
            throw new PayrollException("至少需要保留一名管理员或所有者");
        }
        memberStore.delete(memberId);
        auditService.record(
                "kb.member.remove", "kb_series", String.valueOf(seriesId), member, null);
    }

    private MemberView view(Member member) {
        String subjectName;
        String subjectExtra;
        if (member.subjectType() == SubjectType.USER) {
            Optional<AppUser> target = adminRepository.findUser(member.subjectId());
            subjectName = target.map(AppUser::displayName).orElse("用户#" + member.subjectId());
            subjectExtra =
                    target.map(user -> user.username() + " · " + user.roleName())
                            .orElse("用户已不存在");
        } else {
            subjectName =
                    adminRepository
                            .findDepartment(member.subjectId())
                            .map(Department::name)
                            .orElse("部门#" + member.subjectId());
            subjectExtra = "部门";
        }
        String grantedByName =
                adminRepository
                        .findUser(member.grantedBy())
                        .map(AppUser::displayName)
                        .orElse("用户#" + member.grantedBy());
        return new MemberView(
                member.id(),
                member.seriesId(),
                member.subjectType(),
                member.subjectId(),
                subjectName,
                subjectExtra,
                member.role(),
                member.grantedBy(),
                grantedByName,
                member.createdAt());
    }

    /** 供前端选择器使用的候选用户与部门；只返回启用状态。 */
    public List<MemberCandidate> candidates(long seriesId, AuthUser user) {
        permissionService.requireAdmin(seriesId, user);
        List<MemberCandidate> candidates = new ArrayList<>();
        for (AppUser target : adminRepository.users()) {
            if (target.active()) {
                candidates.add(
                        new MemberCandidate(
                                "USER",
                                target.id(),
                                target.displayName(),
                                target.username() + " · " + target.departmentName()));
            }
        }
        for (Department department : adminRepository.departments(false)) {
            candidates.add(
                    new MemberCandidate(
                            "DEPT", department.id(), department.name(), "部门成员"));
        }
        return candidates;
    }

    @KbCamel
    public record MemberCandidate(String subjectType, long subjectId, String name, String extra) {
    }
}
