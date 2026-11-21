package com.tora.qual.aop;

import com.tora.qual.ExpireRule;
import com.tora.qual.QuotaException;
import com.tora.qual.QuotaService;
import com.tora.qual.QuotaSpec;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/** {@link ConsumeQuota} 的切面：排在事务（最低优先级）前面 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class ConsumeQuotaAspect {

    private static final Logger log = LoggerFactory.getLogger(ConsumeQuotaAspect.class);
    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final DefaultParameterNameDiscoverer NAMES = new DefaultParameterNameDiscoverer();

    private final QuotaService quota;

    public ConsumeQuotaAspect(QuotaService quota) {
        this.quota = quota;
    }

    @Around("@annotation(cq)")
    public Object around(ProceedingJoinPoint pjp, ConsumeQuota cq) throws Throwable {
        MethodBasedEvaluationContext ctx = new MethodBasedEvaluationContext(pjp.getTarget(),
                ((MethodSignature) pjp.getSignature()).getMethod(), pjp.getArgs(), NAMES);
        Long total = PARSER.parseExpression(cq.total()).getValue(ctx, Long.class);
        if (total == null) {
            throw new IllegalArgumentException("@ConsumeQuota 的总资格算出来是空的：" + cq.total());
        }
        QuotaSpec spec = QuotaSpec.of(cq.name(), total, ExpireRule.parse(cq.expire()));
        String suffix = eval(cq.suffix(), ctx);
        String bizId = eval(cq.bizId(), ctx);
        quota.consume(spec, suffix, cq.amount(), bizId);
        try {
            return pjp.proceed();
        } catch (Throwable e) {
            if (cq.restoreOnError()) {
                try {
                    quota.restore(spec, suffix, cq.amount(), bizId);
                } catch (QuotaException re) {
                    // 比如期间刚好过期被 Redis 删掉了，退不回去；不能盖住原来的异常
                    log.warn("退回资格失败 {}:{} x{}：{}", cq.name(), suffix, cq.amount(), re.getMessage());
                }
            }
            throw e;
        }
    }

    private static String eval(String expr, MethodBasedEvaluationContext ctx) {
        if (expr.trim().isEmpty()) {
            return null;
        }
        Object v = PARSER.parseExpression(expr).getValue(ctx);
        return v == null ? null : String.valueOf(v);
    }
}
