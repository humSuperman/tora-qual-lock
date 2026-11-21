package com.tora.qual.log;

/** 更新了资格的哪个值 */
public enum QuotaLogField {
    /** 总资格：请求带的总资格和存的不一样。已用不变，可用跟着变 */
    TOTAL,
    /** 可用资格：consume / restore / updateAvailable。总资格不变，已用跟着变 */
    AVAILABLE,
    /** 有效期：请求带的规则和存的不一样，重新设置了过期时间 */
    EXPIRE
}
