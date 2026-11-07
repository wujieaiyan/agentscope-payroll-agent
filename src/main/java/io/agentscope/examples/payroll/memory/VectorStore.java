package io.agentscope.examples.payroll.memory;

import java.util.List;

/**
 * 向量存储端口。检索签名强制携带 userId，从接口层面保证不会跨租户召回。
 *
 * <p>实现可以是 Elasticsearch，也可以是本地实现；调用方只依赖本接口。
 */
public interface VectorStore extends AutoCloseable {

    /** 创建索引与 mapping；已存在时直接返回。 */
    void ensureIndex();

    boolean ping();

    /** 幂等写入：同一用户、会话、来源、轮次与内容会覆盖同一条文档。 */
    void index(VectorRecord record);

    List<VectorHit> search(long userId, float[] queryVector, int topK, double minCosine);

    void deleteSession(long userId, long sessionId);

    /** 删除某会话的历史摘要文档，用于摘要替换。 */
    void deleteSummaries(long sessionId);

    /** 清空索引文档，用于全量重建。 */
    void clearAll();

    @Override
    default void close() {
    }
}
