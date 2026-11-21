package com.tora.qual;

import com.tora.qual.log.QuotaLogPage;
import com.tora.qual.log.QuotaLogQuery;

/**
 * 资格（次数额度）。
 * <p>
 * 资格由「名字 + 后缀」确定：后缀传空字符串 ""（传 null 也当成 ""）是所有人共用（抢）一个资格；传用户 id 等，就是每个人独享一个资格。
 * 可用 = 总资格 - 已用。
 * <p>
 * 每次请求都带上 {@link QuotaSpec}（总资格 + 有效期规则）：
 * <ul>
 *   <li>第一次请求时创建；过期交给 Redis key 的 TTL，过期后下一次请求重新创建（相当于开新一期）</li>
 *   <li>总资格和存的不一样时以请求的为准，已用不变；调小后已用超过总资格不报错：扣减报可用不足，查询照常返回</li>
 *   <li>规则和存的不一样时重新设置过期时间</li>
 * </ul>
 * 创建、更新（总资格 / 可用资格 / 有效期）、删除都会发布一条日志，由监听任务写库，可以用 {@link #logs} 分页查。
 */
public interface QuotaService {

    // ---------------- 查询 ----------------

    /** 查询资格。不存在时不创建，返回请求的总资格、已用 0；存在时会同步总资格和有效期 */
    QuotaInfo get(QuotaSpec spec, String suffix);

    /** 和 {@link #get(QuotaSpec, String)} 一样，规格平铺传入：{@code get("sign-in", 1, ExpireRule.minutes(5), userId)} */
    default QuotaInfo get(String name, long total, ExpireRule rule, String suffix) {
        return get(QuotaSpec.of(name, total, rule), suffix);
    }

    /** 只读查看 Redis 里现在的值：不同步总资格、不创建；不存在返回 null（查询页面、排查问题用） */
    QuotaInfo peek(String name, String suffix);

    /** 分页查询变更日志 */
    QuotaLogPage logs(QuotaLogQuery query);

    // ---------------- 更新可用资格 ----------------

    /** 扣 1 */
    QuotaInfo consume(QuotaSpec spec, String suffix);

    /** 扣减 amount；可用不够报 NOT_ENOUGH，指定时间过期的已过期报 EXPIRED */
    QuotaInfo consume(QuotaSpec spec, String suffix, long amount, String bizId);

    /** 和 consume 一样，可用不够、已过期时返回 false 而不是报错 */
    boolean tryConsume(QuotaSpec spec, String suffix, long amount, String bizId);

    /** 退回 amount（比如订单取消）：已用减少，可用增加；不能超过已用 */
    QuotaInfo restore(QuotaSpec spec, String suffix, long amount, String bizId);

    /** 直接把可用资格设成 available：已用 = 总资格 - available */
    QuotaInfo updateAvailable(QuotaSpec spec, String suffix, long available, String remark);

    // ---------------- 删除 ----------------

    /** 删除资格；不存在时返回 false。删除后下一次请求会重新创建 */
    boolean delete(String name, String suffix, String remark);
}
