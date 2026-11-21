# tora-qual-lock

资格（次数额度）插件：Redis Hash + Lua 原子扣减，变更日志经 Redis Stream 异步写数据库。

完整的使用示例（签到、领奖、抽奖、手动调整、管理后台）见 [demo.md](demo.md)。

## 兼容性

jar 是 **Java 8 字节码**（`--release 8`，源码只用 Java 8 语法），Spring 相关依赖都是 `provided`，运行时用宿主项目自己的版本。

| 宿主项目 | JDK |
|---|---|
| Spring Boot 3.x | 17+ |
| Spring Boot 2.3 – 2.7 | 8+ |

更老的 Spring Boot（2.0 – 2.2）没有 Redis Stream 消费组 API，不支持，启动时会直接报错提示。

- Redis 5+（日志用 Redis Stream）。
- Redis 客户端 Lettuce、Jedis 都可以；单机、哨兵、集群都可以（集群不支持 `tora.qual.redis.database`）。
- 自动配置同时写了 `spring.factories`（Boot 2）和 `AutoConfiguration.imports`（Boot 2.7+ / 3）。
- 已验证：Spring Boot 3.3.5 全量测试。Spring Boot 2.x 还没有实际跑过测试，可用 `-Pboot2`（2.7）构建验证。

## 引入

```xml
<dependency>
    <groupId>com.tora</groupId>
    <artifactId>tora-qual-lock</artifactId>
    <version>1.0.0</version>
</dependency>
```

宿主项目有 `spring-boot-starter-data-redis` 就自动生效，**直接用项目已有的 Redis 和数据源连接**，不用再配一遍连接信息。

## 概念

- **总资格**、**已用**、**可用 = 总资格 - 已用**。扣减时已用 +1，可用不够报「资格不足」。
- 资格由「名字 + 后缀」确定：
  - 后缀传空字符串 `""`：所有人共用（抢）一个资格，比如全场限量 100 份。传 `null` 也会转成 `""`，返回的 `getSuffix()` 不会是 null。
  - 后缀传用户 id：每人一个独享资格，比如每人每天 7 次。
- **每次请求都带上总资格和有效期规则**（`QuotaSpec`）：
  - 第一次扣减（`consume` / `tryConsume` / `updateAvailable`）时创建；`get`、`peek` 只查不创建，`restore` 遇到不存在报 NOT_FOUND。
  - 带的总资格和 Redis 里的不一样时以这次为准，已用不变（7 → 10，可用多 3），并记日志。
  - 所以同一个资格的所有调用（注解、`get`、`consume`……）要带一样的总资格和规则，不然会被来回改，每次还多记一条日志。
  - 总资格调小到已用以下不算异常：扣减报「不足」，查询照常返回，`available` 为负、`isOverused()` 为 true，怎么处理由业务决定。
  - 带的规则和存的不一样时重设过期时间，并记日志。
- **过期交给 Redis key 的 TTL**：到期自动删除，下次请求重新创建，相当于开了新一期。

## 有效期规则

| 规则 | 写法（注解里的文本） | 到期时间 |
|---|---|---|
| 永不过期 | `ExpireRule.never()`（`NEVER`） | 没有 TTL |
| 指定时间 | `ExpireRule.at(LocalDateTime)`（`AT:毫秒`） | 到点后不能再用，报 EXPIRED |
| N 分钟 / 小时 / 天 / 周 / 月 | `minutes(n)` `hours(24)` `days(n)` `weeks(n)` `months(n)`（`HOURS:24`） | 从创建时间往后算 |
| 自然小时 | `naturalHour()`（`NATURAL_HOUR`） | 下一个整点，14:35 创建 15:00 到期 |
| 自然天 / 周 / 月 | `naturalDay()` `naturalWeek()` `naturalMonth()` | 第二天 0 点 / 下周一 0 点 / 下月 1 号 0 点 |

## 使用

```java
@Autowired
QuotaService quota;

// 每次请求都带上总资格和规则；总资格可以按用户算（比如会员等级）
QuotaSpec spec = QuotaSpec.of("lottery:prize", 7, ExpireRule.naturalDay());

// 查询
QuotaInfo q = quota.get(spec, userId);        // getTotal / getUsed / getAvailable / isOverused / getExpireAt
quota.get(QuotaSpec.of("flash-sale", 100), "");     // 共用资格
quota.get("lottery:prize", 7, ExpireRule.naturalDay(), userId);  // 规格平铺传入，和传 QuotaSpec 一样
QuotaInfo p = quota.peek("lottery:prize", userId);  // 只读，不同步总资格；不存在返回 null

// 更新可用资格
quota.consume(spec, userId);                           // 扣 1，不够抛 QuotaException(NOT_ENOUGH)
quota.consume(spec, userId, 2, orderNo);               // 扣 2，带业务单号
boolean ok = quota.tryConsume(spec, "", 1, orderNo);   // 不够返回 false
quota.restore(spec, userId, 1, orderNo);               // 退回（订单取消）
quota.updateAvailable(spec, userId, 5, "客服调整");     // 可用直接设成 5

// 更新总资格：请求时带新的总资格即可
quota.get(QuotaSpec.of("lottery:prize", 10, ExpireRule.naturalDay()), userId);

// 删除
quota.delete("lottery:prize", userId, "注销");

// 分页查日志：条件都可选
QuotaLogPage page = quota.logs(new QuotaLogQuery()
        .name("lottery:prize").suffix(userId)          // 只看共用资格用 .sharedOnly()
        .from(LocalDateTime.of(2026, 11, 1, 0, 0)).to(LocalDateTime.now())
        .types(QuotaLogType.UPDATE)                    // 创建 / 更新 / 删除，可多选
        .fields(QuotaLogField.TOTAL, QuotaLogField.EXPIRE)
        .page(1, 20));                                 // 页码从 1 开始，每页最多 1000
page.getTotal(); page.getList();                       // 新的在前
```

注解方式：方法执行前扣减，抛异常自动退回；资格不够时方法不执行。

| 属性 | 说明 |
|---|---|
| `name` | 资格名 |
| `total` | 总资格：数字或 SpEL（如 `#level * 2`） |
| `expire` | 有效期规则文本，见上面「有效期规则」，默认 `NEVER` |
| `suffix` | 后缀的 SpEL（如 `#userId`）；不填是共用资格 |
| `amount` | 每次扣几次，固定数字，默认 1；数量要按参数算时改用 `consume` 方法 |
| `bizId` | 业务单号的 SpEL，写进日志方便对账 |
| `restoreOnError` | 方法抛异常时是否退回，默认 true |

```java
@ConsumeQuota(name = "lottery:prize", total = "#level * 2", expire = "NATURAL_DAY",
              suffix = "#userId", bizId = "#orderNo")
public Prize draw(Long userId, int level, String orderNo) { ... }
```

失败统一抛 `QuotaException`，`getReason()`：

| 原因 | 说明 |
|---|---|
| NOT_ENOUGH | 可用不够（包括总资格调小后已用超过总资格） |
| EXPIRED | 「指定时间」的资格已经过了时间 |
| NOT_FOUND | 退回时资格不存在（`delete` 不存在时返回 false，不报错） |
| INVALID | 数量不对：小于等于 0、退回超过已用、可用超过总资格 |

## 建表：用 migrate 命令，启动时不检查

插件**不会在启动时建表或查表**。建表脚本在 `migrations/{数据库}/` 下，是 MyBatis Migrations 格式（带 `-- //@UNDO`）：

```bash
# 1. 把对应数据库的脚本复制到项目自己的 migrations 目录
cp migrations/mysql/20261121000000_create_tora_qual_log.sql  你的项目/migrations/scripts/

# 2. 和项目其他表结构变更一起执行
migrate up --path=你的项目/migrations --env=production
migrate down    # 回滚：DROP TABLE tora_qual_log
```

- 用 Flyway / Liquibase 的项目：把脚本中 `-- //@UNDO` 以上的 `CREATE TABLE` 部分复制成一个版本脚本即可。
- 表名要改的话，脚本里的表名和 `tora.qual.log.table` 一起改。
- 文本列（`suffix`、`log_field`、`biz_id`、`remark`）不存 NULL，建表 `NOT NULL DEFAULT ''`，共用资格的后缀存空字符串。
  Oracle 会把 `''` 当成 NULL，所以 Oracle 脚本里这几列允许 NULL，查共用资格时插件自动改用 `IS NULL`。
- 到期时间列（`expire_before`、`expire_after`）是时间类型，没有空字符串，用 NULL 表示永不过期，不在索引里。

| 数据库 | 目录 | 分页 |
|---|---|---|
| MySQL 5.7+ | mysql | LIMIT |
| MariaDB 10.3+ | mariadb | LIMIT |
| PostgreSQL 10+（人大金仓、openGauss 等兼容库） | postgresql | LIMIT |
| Oracle 12c+ | oracle | OFFSET … FETCH |
| SQL Server 2012+ | sqlserver | OFFSET … FETCH |
| H2 | h2 | LIMIT |

> 以上脚本和分页写法在 H2 的各兼容模式下测过，真实数据库上线前请先执行一次确认。达梦等其他数据库参照最接近的脚本改写，并设置 `tora.qual.log.platform`。

## 存储与日志

**Redis**：一个资格一个 Hash，key = `{key-prefix}{资格名}[:{后缀}]`，字段 `total`、`used`、`rule`；过期用 key 的 TTL。读改写都在一个 Lua 脚本里，单 key 原子执行，不用加锁。

> 资格数据在 Redis 里，Redis 数据丢了资格就会重置。生产环境请开启 AOF 持久化。

```
资格操作 ──XADD──▶ Redis Stream (tora:qual:log) ──消费组──▶ 监听任务 ──批量 INSERT──▶ tora_qual_log
```

- 资格操作只把日志发到 Stream，不碰数据库。
- 有数据源的实例启动监听，按批写库，成功后 ACK 并删除消息；多台机器同一消费组，每条只写一次。
- 写库失败不 ACK 下轮重试；实例挂了留下的消息超过 `claim-idle` 被其他实例认领；Stream 被删会自动重建消费组。
- 「至少一次」投递：写库成功但 ACK 前进程被杀，这批会再写一遍。
- 发布失败（Redis 不可用）不影响业务，日志内容打到 ERROR 日志。

| log_type | log_field | 场景 |
|---|---|---|
| CREATE | | 第一次扣减 / 设置可用；过期被删后再次扣减 |
| UPDATE | AVAILABLE | consume（amount 为负）/ restore / updateAvailable |
| UPDATE | TOTAL | 请求带的总资格和存的不一样 |
| UPDATE | EXPIRE | 请求带的规则和存的不一样 |
| DELETE | | delete |

## 配置

连接信息统一用项目自己的 `spring.redis.*`（Boot 2）/ `spring.data.redis.*`（Boot 3）和 `spring.datasource.*`，插件只有下面这些可选项：

```yaml
tora:
  qual:
    key-prefix: "tora:qual:"      # Redis key 前缀
    zone: Asia/Tokyo              # 算自然小时/天/周/月的时区，默认系统时区
    aspect-enabled: true          # @ConsumeQuota 是否生效
    redis:
      database: 3                 # 资格放到 Redis 的哪个库；不填和项目共用同一个库
    log:
      enabled: true               # 是否记录变更日志
      data-source:                # 多数据源时指定 bean 名；只有一个数据源时不用填
      table: tora_qual_log
      platform:                   # 数据库类型，不填自动识别
      stream-key: "tora:qual:log"
      group: tora-qual-log        # 写库的消费组
      listener-enabled: true      # 本机是否监听写库
      batch-size: 200
      poll-interval: 500ms
      claim-idle: 5m
    web:
      enabled: false              # 开启日志查询接口
      path: /tora-qual
      token:                      # 配了就要求请求头 X-Qual-Token 一致
```

- `redis.database`：复制项目的 Lettuce / Jedis 连接工厂（主机、密码、SSL、哨兵都沿用），只换库号；这个连接不注册成 bean，不影响项目自己的 `RedisTemplate`。
- 没有数据源时只发 Stream 不写库（可以由另一个有数据源的服务来消费）。

## 查询接口与页面

开启 `tora.qual.web.enabled=true` 后提供两个 GET 接口（需要 spring-webmvc）：

| 接口 | 参数 | 返回 |
|---|---|---|
| `{path}/logs` | name、suffix、shared=1（只看共用）、from、to（`yyyy-MM-dd HH:mm:ss`）、types、fields（逗号分隔）、bizId、pageNum、pageSize | `{total, pageNum, pageSize, list}` |
| `{path}/quota` | name、suffix | `{exists, name, suffix, total, used, available, overused, rule, expireAt}`；`exists` 为 false 时只有前三项 |

- 共用资格的 `suffix` 返回空字符串 `""`。
- 参数错误返回 400 `{error}`，token 不对返回 401。
- 接口会经过项目自己的拦截器 / Spring Security，权限按项目原有方式控制即可。

`pages/` 下准备了几种现成页面，按项目前端框架挑一个复制过去，详见 [pages/README.md](pages/README.md)：

| 目录 | 适用 |
|---|---|
| html | 纯 HTML，无依赖，PC 后台直接放 static 目录 |
| h5 | 纯 HTML 手机版，卡片 + 下拉加载 |
| vue2 | Vue 2 + Element UI 2.15+ |
| vue3 | Vue 3 + Element Plus |
| react | React 16.8+ + antd 4 / 5 |
| uniapp | uni-app（H5 / 小程序 / App） |

## 构建

pom 是独立的（不继承上级 pom），在本目录直接构建：

```bash
mvn install             # 默认 Spring Boot 3.3，需要 JDK 17+
mvn install -Pboot2     # 用 Spring Boot 2.7 依赖编译测试，JDK 8 也能跑
```

在 `~/works/java/mvn-package` 里用 `mvn install -pl tora-qual-lock -am` 也可以，效果一样。

两种方式产出的 jar 都是 Java 8 字节码。

只想装到本地仓库给项目用、本机又没有 Redis / Docker 时，加 `-DskipTests`（否则 Redis 测试跳过，覆盖率不到 100%，构建失败）：

```bash
mvn install -DskipTests
```

测试要用 Redis，按顺序自动找，本机不用装 Redis：
1. 环境变量 `TORA_QUAL_TEST_REDIS=host:port`：用指定的 Redis（会清空 14、15 号库，别指向有数据的）；
2. 本机有 `redis-server`：起一个临时进程；
3. 本机有 Docker（Docker Desktop、OrbStack 等）：起一个临时 `redis:7-alpine` 容器，测完删掉；镜像可用 `TORA_QUAL_TEST_REDIS_IMAGE` 换。

都没有时 Redis 相关测试跳过。

故意制造故障（写库失败、Redis 连不上、消息格式不对……）的测试标了 `@ExpectedErrorLogs`，这些预期内的报错日志不打到控制台；测试失败时会补打出来，方便排查。

**测试报告**：`mvn verify` 后生成两份 HTML：
- JUnit 测试结果：`target/site/surefire-report.html`
- 覆盖率（JaCoCo）：`target/site/jacoco/index.html`。行、分支覆盖率都要 100%，不到构建失败。
Redis 测试被跳过时覆盖率达不到，构建会失败，所以跑 `mvn verify` 前要先开着 Docker（或满足上面任意一种）。
