package com.tora.qual.web;

import com.tora.qual.ExpireRule;
import com.tora.qual.MemoryPublisher;
import com.tora.qual.Migrations;
import com.tora.qual.QuotaSpec;
import com.tora.qual.RedisQuotaService;
import com.tora.qual.RedisSupport;
import com.tora.qual.log.JdbcQuotaLogStore;
import com.tora.qual.log.QuotaLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class QuotaLogControllerTest {

    MockMvc mvc;
    RedisQuotaService quota;

    @BeforeEach
    void setUp() {
        MemoryPublisher pub = new MemoryPublisher();
        JdbcQuotaLogStore store = new JdbcQuotaLogStore(new JdbcTemplate(Migrations.up("MySQL", "mysql")),
                "tora_qual_log", "mysql");
        quota = new RedisQuotaService(RedisSupport.template(), pub, store, Clock.system(ZoneId.of("Asia/Tokyo")),
                "web:qual:");
        quota.consume(QuotaSpec.of("prize", 7, ExpireRule.naturalDay()), "u1", 2, "order-1");
        quota.consume(QuotaSpec.of("prize", 10, ExpireRule.naturalDay()), "u1");
        quota.consume(QuotaSpec.of("prize", 100), null);
        store.saveAll(pub.logs);
        mvc = MockMvcBuilders.standaloneSetup(new QuotaLogController(quota, "")).build();
    }

    @Test
    void 分页查日志_条件_时间格式() throws Exception {
        mvc.perform(get("/tora-qual/logs").param("name", "prize").param("suffix", "u1").param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.pageNum").value(1))
                .andExpect(jsonPath("$.list.length()").value(2))
                .andExpect(jsonPath("$.list[0].type").value("UPDATE"))
                .andExpect(jsonPath("$.list[0].field").value("AVAILABLE"))
                .andExpect(jsonPath("$.list[0].availableBefore").value(8))
                .andExpect(jsonPath("$.list[0].availableAfter").value(7))
                .andExpect(jsonPath("$.list[1].field").value("TOTAL"))
                .andExpect(jsonPath("$.list[0].createdAt").value(org.hamcrest.Matchers.matchesPattern(
                        "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")));
        mvc.perform(get("/tora-qual/logs").param("shared", "1"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.list[0].suffix").value(""));
        mvc.perform(get("/tora-qual/logs").param("types", "create").param("fields", ""))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/tora-qual/logs").param("fields", "TOTAL,EXPIRE"))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/tora-qual/logs").param("bizId", "order-1"))
                .andExpect(jsonPath("$.list[0].bizId").value("order-1"));
        String today = LocalDateTime.now(ZoneId.of("Asia/Tokyo")).toLocalDate().toString();
        mvc.perform(get("/tora-qual/logs").param("from", today).param("to", today + "T23:59"))
                .andExpect(jsonPath("$.total").value(6));
        mvc.perform(get("/tora-qual/logs").param("from", "2000-01-01 00:00:00").param("to", "2000-01-02 00:00:00"))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/tora-qual/logs").param("from", "昨天"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("时间格式不对")));
        mvc.perform(get("/tora-qual/logs").param("pageNum", "0")).andExpect(status().isBadRequest());
    }

    @Test
    void 查当前资格() throws Exception {
        mvc.perform(get("/tora-qual/quota").param("name", "prize").param("suffix", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.total").value(10))
                .andExpect(jsonPath("$.used").value(3))
                .andExpect(jsonPath("$.available").value(7))
                .andExpect(jsonPath("$.overused").value(false))
                .andExpect(jsonPath("$.rule").value("NATURAL_DAY"));
        mvc.perform(get("/tora-qual/quota").param("name", "prize"))
                .andExpect(jsonPath("$.total").value(100))
                .andExpect(jsonPath("$.expireAt").value(nullValue()));
        mvc.perform(get("/tora-qual/quota").param("name", "nope"))
                .andExpect(jsonPath("$.exists").value(false));
    }

    @Test
    void 配了令牌要带请求头() throws Exception {
        MockMvc secured = MockMvcBuilders.standaloneSetup(new QuotaLogController(quota, "s3cret")).build();
        secured.perform(get("/tora-qual/logs")).andExpect(status().isUnauthorized());
        secured.perform(get("/tora-qual/quota").param("name", "prize").header("X-Qual-Token", "bad"))
                .andExpect(status().isUnauthorized());
        secured.perform(get("/tora-qual/logs").header("X-Qual-Token", "s3cret")).andExpect(status().isOk());
    }

    @Test
    void 时间解析() {
        assertThat(QuotaLogController.time("2026-11-21")).isEqualTo(LocalDateTime.of(2026, 11, 21, 0, 0));
        assertThat(QuotaLogController.time("2026-11-21T10:05")).isEqualTo(LocalDateTime.of(2026, 11, 21, 10, 5));
        assertThat(QuotaLogController.time("2026-11-21 10:05:06.789")).isEqualTo(LocalDateTime.of(2026, 11, 21, 10, 5, 6));
        assertThat(QuotaLogController.time(" ")).isNull();
        assertThatThrownBy(() -> QuotaLogController.time("2026/11/21")).isInstanceOf(IllegalArgumentException.class);
        QuotaLog l = new QuotaLog(1L, "a", null, com.tora.qual.log.QuotaLogType.DELETE, null, -1, 1, 0, 0, 0, null, null,
                "", "", LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        assertThat(QuotaLogController.row(l)).containsEntry("createdAt", "2026-01-02 03:04:05").containsEntry("field", null);
    }

    @Test
    void 没配令牌不检查_shared不是1按后缀查_类型列表忽略空项() throws Exception {
        MockMvc open = MockMvcBuilders.standaloneSetup(new QuotaLogController(quota, null)).build();
        open.perform(get("/tora-qual/logs").param("shared", "0").param("suffix", "u1").param("types", "UPDATE, ,"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3));
    }
}
