package com.tora.qual.aop;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法执行前扣减资格，方法抛异常时退回：
 * <pre>{@code
 * @ConsumeQuota(name = "lottery:prize", total = "7", expire = "NATURAL_DAY", suffix = "#userId", bizId = "#orderNo")
 * public Prize draw(Long userId, String orderNo) { ... }
 * }</pre>
 * 资格不够时直接抛 {@link com.tora.qual.QuotaException}，方法不执行。
 * 切面排在事务前面：资格先扣，事务回滚抛出的异常也会触发退回。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConsumeQuota {

    /** 资格名 */
    String name();

    /** 总资格：数字，或 SpEL（如 #user.level * 2），每次调用都会带上 */
    String total();

    /**
     * 有效期规则：NEVER、AT:到期毫秒、MINUTES:n、HOURS:n、DAYS:n、WEEKS:n、MONTHS:n、
     * NATURAL_HOUR、NATURAL_DAY、NATURAL_WEEK、NATURAL_MONTH
     */
    String expire() default "NEVER";

    /** 后缀的 SpEL，如 #userId、#req.shopId；不填是所有人共用的资格 */
    String suffix() default "";

    /** 扣减数量 */
    long amount() default 1;

    /** 业务单号的 SpEL，写进日志方便对账 */
    String bizId() default "";

    /** 方法抛异常时是否退回 */
    boolean restoreOnError() default true;
}
