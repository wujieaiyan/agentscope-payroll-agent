package io.agentscope.examples.payroll.skill;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 技能伴生文件的字节存储端口（{@code docs/skill-storage-mysql-oss-spec.md} §6）。
 *
 * <p>与 {@link SkillStore} 的分工：{@code SkillStore} 管元数据与版本（权威在库），本端口只管不透明的
 * 字节（权威在介质）。业务侧只依赖本接口，P0 用本地磁盘，P3 换对象存储时不动读写路径。
 *
 * <p>内容寻址：调用方只交出字节，键由实现按 sha256 派生，不接受任何来自路径、文件名或请求体的键，
 * 因此没有路径穿越面，同一份字节天然去重。
 */
public interface SkillBlobStore {

    /** 写入字节；同内容已存在时幂等返回，不重写。 */
    BlobRef put(byte[] content, String mediaType);

    /** 按指纹读取。缺失返回 {@link Optional#empty()} 交给调用方决定报错文案；校验不过必须抛异常。 */
    Optional<byte[]> get(String sha256);

    /**
     * 批量读：装配一个技能的全部资源必须一次拿全（§2.2-10），逐文件往返不可接受。
     *
     * @return 以归一化后的 sha256 为键，缺失的指纹不出现在结果里，由调用方比对数量
     */
    Map<String, byte[]> getAll(Collection<String> sha256s);

    /** 指纹不合法一律按不存在处理：对账巡检不能被一行脏数据打断。 */
    boolean exists(String sha256);

    /** 删除无引用的字节；实现应尽量干净，删不掉只留孤儿，由保留期巡检回收。 */
    void delete(String sha256);

    /** 介质描述，供健康检查与 {@code repositoryInfo} 展示，不含凭据。 */
    String location();

    /**
     * @param sha256 内容指纹，同时是 {@code skill_blob} 主键
     * @param storageKey 介质内相对定位，落 {@code skill_blob.storage_key}；与运行期路径无关，永不拼成文件系统路径
     * @param sizeBytes 字节数
     * @param mediaType 展示用类型，介质本身不保存
     */
    record BlobRef(String sha256, String storageKey, long sizeBytes, String mediaType) {}
}
