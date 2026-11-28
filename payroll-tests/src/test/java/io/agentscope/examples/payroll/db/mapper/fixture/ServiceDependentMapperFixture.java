package io.agentscope.examples.payroll.db.mapper.fixture;

import io.agentscope.examples.payroll.service.AuditService;

/** 负例：Mapper 回指业务服务（违反 MAPPER_MUST_NOT_DEPEND_ON_SERVICE）。 */
public final class ServiceDependentMapperFixture {

    private final AuditService auditService;

    public ServiceDependentMapperFixture(AuditService auditService) {
        this.auditService = auditService;
    }

    public AuditService auditService() {
        return auditService;
    }
}