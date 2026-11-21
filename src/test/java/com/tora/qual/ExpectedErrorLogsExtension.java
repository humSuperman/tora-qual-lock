package com.tora.qual;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** {@link ExpectedErrorLogs} 的实现：测试期间把指定 logger 的日志收进内存，不往上传给控制台 */
public class ExpectedErrorLogsExtension implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(ExpectedErrorLogsExtension.class);

    /** 一个 logger 的原状态和收到的日志 */
    private static final class Captured {
        final Logger logger;
        final boolean additive;
        final ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();

        Captured(Logger logger) {
            this.logger = logger;
            this.additive = logger.isAdditive();
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        ExpectedErrorLogs ann = AnnotationSupport.findAnnotation(context.getElement(), ExpectedErrorLogs.class)
                .orElseGet(() -> AnnotationSupport.findAnnotation(context.getRequiredTestClass(), ExpectedErrorLogs.class)
                        .orElse(null));
        if (ann == null) {
            return;
        }
        List<Captured> list = new ArrayList<Captured>();
        for (Class<?> c : ann.value()) {
            Captured cap = new Captured((Logger) LoggerFactory.getLogger(c));
            cap.appender.start();
            cap.logger.addAppender(cap.appender);
            cap.logger.setAdditive(false);
            list.add(cap);
        }
        context.getStore(NS).put("captured", list);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void afterEach(ExtensionContext context) {
        List<Captured> list = context.getStore(NS).remove("captured", List.class);
        if (list == null) {
            return;
        }
        boolean failed = context.getExecutionException().isPresent();
        for (Captured cap : list) {
            cap.logger.detachAppender(cap.appender);
            cap.logger.setAdditive(cap.additive);
            cap.appender.stop();
            if (failed) {
                for (ILoggingEvent e : cap.appender.list) {
                    System.out.println("[预期日志] " + e.getLevel() + " " + e.getLoggerName() + " -- " + e.getFormattedMessage());
                    if (e.getThrowableProxy() != null) {
                        System.out.println(ThrowableProxyUtil.asString(e.getThrowableProxy()));
                    }
                }
            }
        }
    }
}
