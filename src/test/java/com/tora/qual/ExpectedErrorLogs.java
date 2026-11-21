package com.tora.qual;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在故意制造故障的测试上：这些类在测试期间打的日志是预期内的，不输出到控制台。
 * 测试失败时会把收起来的日志补打出来，方便排查。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(ExpectedErrorLogsExtension.class)
public @interface ExpectedErrorLogs {

    /** 会打出预期报错日志的类（按类名对应 logger） */
    Class<?>[] value();
}
