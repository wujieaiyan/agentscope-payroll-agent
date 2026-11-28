package io.agentscope.examples.payroll.service.fixture;

import io.agentscope.examples.payroll.db.mapper.fixture.ServiceDependentMapperFixture;

/** 负例：服务层直接依赖 Mapper（违反 SERVICE_AND_WEB_MUST_NOT_DEPEND_ON_MAPPER）。 */
public final class MapperDependentServiceFixture {

    private final ServiceDependentMapperFixture mapper;

    public MapperDependentServiceFixture(ServiceDependentMapperFixture mapper) {
        this.mapper = mapper;
    }

    public ServiceDependentMapperFixture mapper() {
        return mapper;
    }
}