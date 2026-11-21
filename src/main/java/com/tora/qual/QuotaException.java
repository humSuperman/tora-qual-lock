package com.tora.qual;

/** 资格操作失败，原因见 {@link #getReason()} */
public class QuotaException extends RuntimeException {

    public enum Reason {
        /** 可用资格不够（包括总资格调小后已用超过总资格） */
        NOT_ENOUGH,
        /** 已过期：「指定时间过期」的资格过了时间 */
        EXPIRED,
        /** 资格不存在（删除、退回时） */
        NOT_FOUND,
        /** 参数不对：数量小于等于 0、退回超过已用、可用超过总资格…… */
        INVALID
    }

    private final Reason reason;
    private final transient QuotaInfo info;

    public QuotaException(Reason reason, String message, QuotaInfo info) {
        super(message);
        this.reason = reason;
        this.info = info;
    }

    public Reason getReason() {
        return reason;
    }

    /** 失败时资格的状态；资格不存在时为 null */
    public QuotaInfo getInfo() {
        return info;
    }
}
