# tora-qual-lock 使用示例（tora-test）

一个最小的 Spring Boot 项目，演示引入插件后怎么用：**注解**扣减、**调用方法**查询 / 扣减 / 调整、管理后台看日志。没有权限和用户系统，代码只有一个 Controller。

示例项目在 `~/works/java/tora-test`，下面贴的是完整代码，照着建一个项目也能跑起来。

## 场景

| 资格 | 资格名 | 总资格 | 区分用户 | 有效期 | 写法 |
|---|---|---|---|---|---|
| 每日签到 | `sign-in` | 1 | 是 | 5 分钟（模拟每天） | 注解 |
| 领奖：每人限领 | `prize-user` | 1 | 是 | 30 天 | 注解 |
| 领奖：奖池 | `prize-pool` | 5 | 否，先到先得 | 30 天 | 调用方法 |
| 参与抽奖 | `lottery` | 3 | 是 | 1 周 | 注解 |

领奖要同时扣两个资格：注解先扣每人限领，方法里再扣奖池；奖池抢光时抛异常，**注解自动把个人那次退回**，没领到的人个人资格不受影响。

10 个测试用户的 id 是固定的 uuid，写在 Controller 里。

## 启动

```bash
# 1. 安装插件到本地仓库（没有 Redis / Docker 时加 -DskipTests）
cd ~/works/java/mvn-package/tora-qual-lock && mvn install

# 2. 启动：JDK 17，本地 MySQL（root/123456）、Redis（6379）
cd ~/works/java/tora-test && mvn spring-boot:run
```

- 数据库 `tora_test` 不存在时自动创建，日志表启动时由 `db/schema.sql` 建（`IF NOT EXISTS`）。
- 管理后台（不鉴权）：http://localhost:8090/tora-qual-log.html

## 项目结构

```
tora-test
├── pom.xml
├── requests.http                       # 接口示例，IDEA / VS Code REST Client 直接点
└── src/main
    ├── java/com/tora/test
    │   ├── ToraTestApplication.java
    │   ├── R.java                      # 统一返回 {code, msg, data}
    │   └── DemoController.java         # 全部业务代码
    └── resources
        ├── application.yml
        ├── db/schema.sql               # 复制自 migrations/mysql，加了 IF NOT EXISTS
        └── static/tora-qual-log.html   # 复制自 pages/html，管理后台日志页
```

## pom.xml

插件的 Spring 依赖都是 `provided`，下面几个由项目自己提供：

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
</parent>

<dependencies>
    <dependency>
        <groupId>com.tora</groupId>
        <artifactId>tora-qual-lock</artifactId>
        <version>1.0.0</version>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>       <!-- 日志查询接口 -->
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis</artifactId> <!-- 必须 -->
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-jdbc</artifactId>       <!-- 日志写库、查库 -->
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-aop</artifactId>        <!-- @ConsumeQuota -->
    </dependency>
    <dependency>
        <groupId>com.mysql</groupId>
        <artifactId>mysql-connector-j</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

## application.yml

```yaml
server:
  port: 8090

spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/tora_test?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Tokyo&useSSL=false&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true
    username: root
    password: 123456
  data:
    redis:
      host: 127.0.0.1
      port: 6379
  sql:
    init:
      # demo 省事：启动时建日志表。正式项目用 migrate 命令，见 README「建表」
      mode: always
      schema-locations: classpath:db/schema.sql

tora:
  qual:
    key-prefix: "tora-test:qual:"
    zone: Asia/Tokyo
    log:
      stream-key: "tora-test:qual:log"
      group: tora-test-qual-log
    web:
      enabled: true          # 开启日志查询接口 /tora-qual/logs、/tora-qual/quota
      path: /tora-qual
```

## R.java

```java
/** 统一返回：{code, msg, data}，code 0 成功 */
public record R(int code, String msg, Object data) {

    public static R ok(Object data) {
        return new R(0, "success", data);
    }

    public static R fail(String msg) {
        return new R(1, msg, null);
    }
}
```

## DemoController.java

```java
@RestController
@RequestMapping("/api")
public class DemoController {

    /** 10 个测试用户 */
    private static final List<String> USERS = List.of(
            "178e6a7a-30a5-4ec6-9e58-982e0a807eda", "35edde14-025f-4d7e-b067-f4e6cabfb728",
            "a78731f2-7c42-42a0-80e2-1383e73cf1ec", "77af5f83-328d-47e8-a637-160388cae72e",
            "dca808c1-fe51-4c7e-9c07-a8ef9a12398e", "438eba1e-8aae-4f55-a4a8-1b9e422999b4",
            "7d727a60-04dd-4e72-a985-49a0b468a7c8", "ac692bbc-b8ab-4a14-b314-042c7b685096",
            "6da260cd-82f9-4138-ae53-f12bf205d5b1", "8c5d5f69-4800-4ffc-bdf4-0781f7b76e2b");

    // ---------------- 资格规格：注解、查询、手动调整都用这一份，改规则只改这里 ----------------
    // 注解属性只能用编译期常量，所以总资格、有效期写成字符串

    static final String SIGN_IN = "sign-in", SIGN_IN_TOTAL = "1", SIGN_IN_EXPIRE = "MINUTES:5";
    static final String PRIZE_USER = "prize-user", PRIZE_USER_TOTAL = "1", PRIZE_USER_EXPIRE = "DAYS:30";
    /** 奖池是所有人共用的资格，后缀传 "" */
    static final String PRIZE_POOL = "prize-pool", PRIZE_POOL_TOTAL = "5", PRIZE_POOL_EXPIRE = "DAYS:30";
    static final String LOTTERY = "lottery", LOTTERY_TOTAL = "3", LOTTERY_EXPIRE = "WEEKS:1";

    /** 资格名 → 规格，由上面的常量生成 */
    private static final Map<String, QuotaSpec> SPECS;

    static {
        Map<String, QuotaSpec> m = new LinkedHashMap<>();
        m.put(SIGN_IN, spec(SIGN_IN, SIGN_IN_TOTAL, SIGN_IN_EXPIRE));
        m.put(PRIZE_USER, spec(PRIZE_USER, PRIZE_USER_TOTAL, PRIZE_USER_EXPIRE));
        m.put(PRIZE_POOL, spec(PRIZE_POOL, PRIZE_POOL_TOTAL, PRIZE_POOL_EXPIRE));
        m.put(LOTTERY, spec(LOTTERY, LOTTERY_TOTAL, LOTTERY_EXPIRE));
        SPECS = Collections.unmodifiableMap(m);
    }

    private static QuotaSpec spec(String name, String total, String expire) {
        return QuotaSpec.of(name, Long.parseLong(total), ExpireRule.parse(expire));
    }

    /** 所有人共用的资格（后缀是 ""）；其他是每人一份（后缀是用户 id） */
    private static boolean isShared(String name) {
        return PRIZE_POOL.equals(name);
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private QuotaService quotaService;

    @GetMapping("/users")
    public R users() {
        return R.ok(USERS);
    }

    /** 查询资格：get 存在返回真实值，没用过（或已过期）返回默认值（已用 0），不会创建 */
    @GetMapping("/users/{userId}/quals")
    public R quals(@PathVariable String userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        SPECS.forEach((name, spec) -> data.put(name, view(quotaService.get(spec, isShared(name) ? "" : userId))));
        return R.ok(data);
    }

    /** 每日签到：每人 1 次，5 分钟一期（模拟每天） */
    @PostMapping("/users/{userId}/sign-in")
    @ConsumeQuota(name = SIGN_IN, total = SIGN_IN_TOTAL, expire = SIGN_IN_EXPIRE, suffix = "#userId")
    public R signIn(@PathVariable String userId) {
        return R.ok(view(quotaService.peek(SIGN_IN, userId)));
    }

    /**
     * 领奖：注解扣每人限领 1 次，方法里扣共用奖池 5 份（先到先得），30 天。
     * 奖池扣失败抛异常时，注解会自动把个人那次退回。
     */
    @PostMapping("/users/{userId}/prize")
    @ConsumeQuota(name = PRIZE_USER, total = PRIZE_USER_TOTAL, expire = PRIZE_USER_EXPIRE, suffix = "#userId")
    public R prize(@PathVariable String userId) {
        return R.ok(view(quotaService.consume(SPECS.get(PRIZE_POOL), "")));
    }

    /** 参与抽奖：每人 3 次，一周 */
    @PostMapping("/users/{userId}/lottery")
    @ConsumeQuota(name = LOTTERY, total = LOTTERY_TOTAL, expire = LOTTERY_EXPIRE, suffix = "#userId")
    public R lottery(@PathVariable String userId) {
        return R.ok(view(quotaService.peek(LOTTERY, userId)));
    }

    /**
     * 手动调整资格
     * op：consume 扣 value 次 / restore 退回 value 次 / available 把可用直接设成 value
     * userId 不传是共用资格
     */
    @PostMapping("/quals/{name}/adjust")
    public R adjust(@PathVariable String name, @RequestParam(defaultValue = "") String userId,
                    @RequestParam String op, @RequestParam long value) {
        QuotaSpec spec = SPECS.get(name);
        if (spec == null) {
            return R.fail("资格不存在：" + name + "，可选 " + SPECS.keySet());
        }
        // 个人资格漏传 userId 会被当成共用的
        if (isShared(name) != userId.isEmpty()) {
            return R.fail(PRIZE_POOL + " 不传 userId，其他资格必须传 userId");
        }
        QuotaInfo q = switch (op) {
            case "consume" -> quotaService.consume(spec, userId, value, "");
            case "restore" -> quotaService.restore(spec, userId, value, "");
            case "available" -> quotaService.updateAvailable(spec, userId, value, "手动调整");
            default -> null;
        };
        return q == null ? R.fail("op 只能是 consume / restore / available") : R.ok(view(q));
    }

    /** 返回给前端的字段：总资格、已用、可用、到期时间 */
    private static Map<String, Object> view(QuotaInfo q) {
        if (q == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", q.getName());
        m.put("suffix", q.getSuffix());
        m.put("total", q.getTotal());
        m.put("used", q.getUsed());
        m.put("available", q.getAvailable());
        m.put("expireAt", q.getExpireAt() == null ? null : q.getExpireAt().format(TIME));
        return m;
    }

    /** 资格不足 / 过期等 */
    @ExceptionHandler(QuotaException.class)
    public R quotaError(QuotaException e) {
        return R.fail(e.getMessage());
    }
}
```

## 接口

返回格式统一 `{"code": 0, "msg": "success", "data": {}}`；资格不足等 `code` 为 1，`msg` 是原因。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/users` | 10 个测试用户 id |
| GET | `/api/users/{userId}/quals` | 查询全部资格（没用过的也返回：已用 0） |
| POST | `/api/users/{userId}/sign-in` | 每日签到 |
| POST | `/api/users/{userId}/prize` | 领奖 |
| POST | `/api/users/{userId}/lottery` | 参与抽奖 |
| POST | `/api/quals/{name}/adjust?userId=&op=&value=` | 手动调整：`consume` 扣 / `restore` 退回 / `available` 可用设成 value；`prize-pool` 不传 userId |
| GET | `/tora-qual/logs`、`/tora-qual/quota` | 插件自带的日志查询接口，见 README「查询接口与页面」 |

```bash
U=178e6a7a-30a5-4ec6-9e58-982e0a807eda
curl localhost:8090/api/users/$U/quals
curl -X POST localhost:8090/api/users/$U/sign-in
curl -X POST "localhost:8090/api/quals/lottery/adjust?userId=$U&op=consume&value=2"
```

返回示例：

```jsonc
// 签到成功
{"code":0,"msg":"success","data":{"name":"sign-in","suffix":"178e6a7a-…","total":1,"used":1,"available":0,"expireAt":"2026-11-21 18:13:58"}}
// 再签到：不足
{"code":1,"msg":"资格「sign-in:178e6a7a-…」不足：可用 0，需要 1","data":null}
// 领奖：返回奖池的状态（共用资格 suffix 是 ""）
{"code":0,"msg":"success","data":{"name":"prize-pool","suffix":"","total":5,"used":1,"available":4,"expireAt":"2026-12-21 17:15:22"}}
```

## 测试结果

| 操作 | 结果 |
|---|---|
| 没用过的用户查询 | 4 个资格都返回，已用 0、可用 = 总资格；Redis 里不会多出 key |
| 签到 2 次 | 成功 / 不足 |
| 抽奖 4 次 | 成功 ×3 / 不足 |
| 同一人领奖 2 次 | 成功 / `prize-user` 不足 |
| 10 人各领 1 次（奖池剩 4） | 4 人成功，其余 `prize-pool` 不足，他们的 `prize-user` 已自动退回（已用 0） |
| 手动调整抽奖：扣 2 → 再扣 2 → 退回 1 → 可用设 3 | 可用 1 → 不足（可用 1，需要 2）→ 可用 2 → 已用 0 / 可用 3 |
| 可用设成 5（超过总资格 3）、value = 0、退回没用过的资格 | 失败，返回原因 |
| 个人资格不传 userId、奖池传了 userId | 失败：`prize-pool 不传 userId，其他资格必须传 userId` |

日志表里每次创建、扣减、退回、调整都有一条。没领到奖的人，`prize-user` 有「扣减 -1」「退回 +1」两条更新日志（第一次请求前面还有一条创建）。

## 注意

- **同一个资格的所有调用要带一样的总资格和规则**。插件以每次调用带的值为准：注解里写 1、查询时写 3，总资格会在 1 和 3 之间来回改，每次还多记一条「更新·总资格」日志。所以示例把规格提成常量，注解、查询、调整都引用同一份。
- **查询用 `get`，不用 `peek`**：`peek` 只看 Redis，没用过或过期了返回 null；`get` 按传入的规格返回默认值（已用 0），也不会创建。没用过的资格 `expireAt` 是「假如现在创建」算出来的，真正从第一次扣减开始计时。
- **注解的 `amount` 只能写固定数字**；扣几次要按请求参数决定时，像 `adjust` 那样直接调 `consume`。
- **手动调整没有「改总资格」**：每次签到 / 抽奖都会带上代码里的总资格，手动改了也会被下一次调用改回去。要能调总资格，得把总资格放到配置或数据库里，所有调用都从那里读。
- 签到 5 分钟过期后 Redis 删掉 key，再查就是新一期（已用 0），这是正常的「过了一天」。
