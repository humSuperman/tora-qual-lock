package com.tora.qual.web;

import com.tora.qual.QuotaInfo;
import com.tora.qual.QuotaService;
import com.tora.qual.log.QuotaLog;
import com.tora.qual.log.QuotaLogField;
import com.tora.qual.log.QuotaLogPage;
import com.tora.qual.log.QuotaLogQuery;
import com.tora.qual.log.QuotaLogType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资格查询接口，给查询页面用。默认关闭，tora.qual.web.enabled=true 才注册。
 * <ul>
 *   <li>GET {path}/logs：分页查日志，参数 name、suffix、shared(1 只看共用)、from、to、types、fields、bizId、pageNum、pageSize</li>
 *   <li>GET {path}/quota：查 Redis 里资格现在的值，参数 name、suffix</li>
 * </ul>
 * 接口挂在项目自己的 Spring MVC 下，项目的登录拦截、权限照样生效；另外可以配 tora.qual.web.token，
 * 请求头 X-Qual-Token 要带上。时间都返回「yyyy-MM-dd HH:mm:ss」字符串，不依赖项目的 Jackson 配置。
 */
@RestController
@RequestMapping("${tora.qual.web.path:/tora-qual}")
public class QuotaLogController {

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final QuotaService quota;
    private final String token;

    public QuotaLogController(QuotaService quota, String token) {
        this.quota = quota;
        this.token = token == null ? "" : token;
    }

    @GetMapping("/logs")
    public ResponseEntity<Object> logs(@RequestHeader(value = "X-Qual-Token", required = false) String t,
                                       @RequestParam(required = false) String name,
                                       @RequestParam(required = false) String suffix,
                                       @RequestParam(required = false) Integer shared,
                                       @RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to,
                                       @RequestParam(required = false) String types,
                                       @RequestParam(required = false) String fields,
                                       @RequestParam(required = false) String bizId,
                                       @RequestParam(defaultValue = "1") int pageNum,
                                       @RequestParam(defaultValue = "20") int pageSize) {
        if (!allowed(t)) {
            return denied();
        }
        QuotaLogQuery q;
        try {
            q = new QuotaLogQuery().name(blank(name)).bizId(blank(bizId)).from(time(from)).to(time(to))
                    .page(pageNum, Math.min(pageSize, QuotaLogQuery.MAX_PAGE_SIZE));
            if (shared != null && shared == 1) {
                q.sharedOnly();
            } else {
                q.suffix(blank(suffix));
            }
            q.types(parse(types, QuotaLogType.class).toArray(new QuotaLogType[0]));
            q.fields(parse(fields, QuotaLogField.class).toArray(new QuotaLogField[0]));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body((Object) error(e.getMessage()));
        }
        QuotaLogPage page = quota.logs(q);
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (QuotaLog l : page.getList()) {
            list.add(row(l));
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("total", page.getTotal());
        body.put("pageNum", page.getPageNum());
        body.put("pageSize", page.getPageSize());
        body.put("list", list);
        return ResponseEntity.ok((Object) body);
    }

    @GetMapping("/quota")
    public ResponseEntity<Object> quota(@RequestHeader(value = "X-Qual-Token", required = false) String t,
                                        @RequestParam String name,
                                        @RequestParam(required = false) String suffix) {
        if (!allowed(t)) {
            return denied();
        }
        String sfx = suffix == null ? "" : suffix.trim();
        QuotaInfo i = quota.peek(name, sfx);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("exists", i != null);
        body.put("name", name);
        body.put("suffix", sfx);
        if (i != null) {
            body.put("total", i.getTotal());
            body.put("used", i.getUsed());
            body.put("available", i.getAvailable());
            body.put("overused", i.isOverused());
            body.put("rule", i.getRule().format());
            body.put("expireAt", format(i.getExpireAt()));
        }
        return ResponseEntity.ok((Object) body);
    }

    private boolean allowed(String t) {
        return token.isEmpty() || token.equals(t);
    }

    private static ResponseEntity<Object> denied() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body((Object) error("X-Qual-Token 不正确"));
    }

    private static Map<String, Object> error(String msg) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("error", msg);
        return m;
    }

    static Map<String, Object> row(QuotaLog l) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", l.getId());
        m.put("name", l.getName());
        m.put("suffix", l.getSuffix());
        m.put("type", l.getType().name());
        m.put("field", l.getField() == null ? null : l.getField().name());
        m.put("amount", l.getAmount());
        m.put("totalBefore", l.getTotalBefore());
        m.put("totalAfter", l.getTotalAfter());
        m.put("usedBefore", l.getUsedBefore());
        m.put("usedAfter", l.getUsedAfter());
        m.put("availableBefore", l.getAvailableBefore());
        m.put("availableAfter", l.getAvailableAfter());
        m.put("expireBefore", format(l.getExpireBefore()));
        m.put("expireAfter", format(l.getExpireAfter()));
        m.put("bizId", l.getBizId());
        m.put("remark", l.getRemark());
        m.put("createdAt", format(l.getCreatedAt()));
        return m;
    }

    private static String format(LocalDateTime t) {
        return t == null ? null : TIME.format(t);
    }

    /** 支持「2026-11-21 10:00:00」「2026-11-21T10:00」「2026-11-21」 */
    static LocalDateTime time(String s) {
        String v = blank(s);
        if (v == null) {
            return null;
        }
        v = v.replace('T', ' ');
        if (v.length() == 10) {
            v += " 00:00:00";
        } else if (v.length() == 16) {
            v += ":00";
        }
        try {
            return LocalDateTime.parse(v.substring(0, 19), TIME);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("时间格式不对：" + s + "，要 yyyy-MM-dd HH:mm:ss");
        }
    }

    private static <E extends Enum<E>> List<E> parse(String csv, Class<E> type) {
        List<E> out = new ArrayList<E>();
        if (blank(csv) == null) {
            return out;
        }
        for (String s : csv.split(",")) {
            if (!s.trim().isEmpty()) {
                out.add(Enum.valueOf(type, s.trim().toUpperCase()));
            }
        }
        return out;
    }

    private static String blank(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
