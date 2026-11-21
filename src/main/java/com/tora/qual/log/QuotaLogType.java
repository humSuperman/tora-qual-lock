package com.tora.qual.log;

/** 资格变更的类型 */
public enum QuotaLogType {
    /** 创建：第一次请求时创建，到期（key 被 Redis 删掉）后的下一次请求也会重新创建 */
    CREATE,
    /** 更新：总资格、可用资格、有效期 */
    UPDATE,
    /** 删除 */
    DELETE
}
