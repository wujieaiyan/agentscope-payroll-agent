package io.agentscope.examples.payroll.db;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.examples.payroll.db.mapper.fixture.LeaseProbeMapper;
import io.agentscope.examples.payroll.db.support.PayrollMappers;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * P0 异常映射探针（规格 §5.0.4 / P0-8）：约束冲突必须落在 Spring 的 {@link DataAccessException}
 * 体系里，业务侧才能按类型分支而不是解析消息文本。
 */
class MapperExceptionMappingTest {

    private static final String PROBE_XML = "classpath:constraint-probe/LeaseProbeMapper.xml";

    @Test
    @DisplayName("主键冲突在 H2 下映射为 DuplicateKeyException")
    void duplicateKeyMapsToDuplicateKeyException() {
        PayrollDatabase database = PayrollDatabase.inMemory("mapper-ex-" + UUID.randomUUID());
        try {
            LeaseProbeMapper mapper =
                    PayrollMappers.forProbe(database, PROBE_XML).mapper(LeaseProbeMapper.class);
            LocalDateTime now = LocalDateTime.now();
            mapper.insert("probe-job", "owner-a", now, now.plusMinutes(5));

            DuplicateKeyException exception =
                    assertThrows(
                            DuplicateKeyException.class,
                            () -> mapper.insert("probe-job", "owner-b", now, now.plusMinutes(5)));

            assertInstanceOf(DataIntegrityViolationException.class, exception);
        } finally {
            database.close();
        }
    }
}