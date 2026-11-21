package com.tora.qual;

import org.junit.jupiter.api.Assumptions;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 测试用 Redis，按顺序找：
 * <ol>
 *     <li>环境变量 TORA_QUAL_TEST_REDIS=host:port：用指定的 Redis（会清空 14、15 号库，别指向有数据的）；</li>
 *     <li>本机有 redis-server：起一个临时进程；</li>
 *     <li>本机有 Docker：起一个临时的 redis 容器（镜像用 TORA_QUAL_TEST_REDIS_IMAGE，默认 redis:7-alpine），测完删掉。</li>
 * </ol>
 * 都没有就跳过（Assumptions）。
 */
public final class RedisSupport {

    public static final int DB = 15;

    private static String host;
    private static int port;
    private static String unavailable;
    /** 测试里建的连接；JVM 退出时先关连接再停 Redis，免得 Lettuce 发现连接断了不停重连、打 WARN */
    private static final List<LettuceConnectionFactory> FACTORIES = new ArrayList<LettuceConnectionFactory>();
    private static Runnable stopRedis;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread("tora-qual-test-redis-stop") {
            @Override
            public void run() {
                synchronized (RedisSupport.class) {
                    for (LettuceConnectionFactory f : FACTORIES) {
                        try {
                            f.destroy();
                        } catch (Exception ignored) {
                            // 退出时尽力关
                        }
                    }
                    if (stopRedis != null) {
                        stopRedis.run();
                    }
                }
            }
        });
    }

    private RedisSupport() {
    }

    public static synchronized String[] hostPort() {
        if (host == null && unavailable == null) {
            start();
        }
        Assumptions.assumeTrue(host != null, unavailable);
        return new String[]{host, String.valueOf(port)};
    }

    private static void start() {
        String env = System.getenv("TORA_QUAL_TEST_REDIS");
        if (env != null && !env.trim().isEmpty()) {
            String[] p = env.trim().split(":");
            host = p[0];
            port = Integer.parseInt(p[1]);
            return;
        }
        String server = find("redis-server");
        if (server != null) {
            startProcess(server);
            return;
        }
        String docker = find("docker");
        if (docker != null) {
            startContainer(docker);
            return;
        }
        unavailable = "没有 redis-server，也没有 Docker，也没有设置 TORA_QUAL_TEST_REDIS，跳过 Redis 测试";
    }

    private static void startProcess(String bin) {
        try {
            ServerSocket s = new ServerSocket(0);
            int p = s.getLocalPort();
            s.close();
            final Process process = new ProcessBuilder(bin, "--port", String.valueOf(p), "--save", "", "--appendonly", "no")
                    .redirectErrorStream(true)
                    .redirectOutput(new File(System.getProperty("java.io.tmpdir"), "tora-qual-test-redis.log"))
                    .start();
            stopRedis = new Runnable() {
                @Override
                public void run() {
                    process.destroy();
                }
            };
            waitReady("127.0.0.1", p, "redis-server");
        } catch (Exception e) {
            throw new IllegalStateException("启动 redis-server 失败", e);
        }
    }

    private static void startContainer(final String docker) {
        String image = System.getenv("TORA_QUAL_TEST_REDIS_IMAGE");
        image = image == null || image.trim().isEmpty() ? "redis:7-alpine" : image.trim();
        final String id;
        try {
            // 端口映射到本机随机端口；第一次会拉镜像，所以等久一点
            id = exec(300, docker, "run", "-d", "--rm", "-p", "127.0.0.1::6379", image,
                    "redis-server", "--save", "", "--appendonly", "no").trim().replaceAll("(?s).*\\R", "");
        } catch (Exception e) {
            unavailable = "Docker 起不来 Redis 容器（Docker 没启动？），跳过 Redis 测试：" + e.getMessage();
            return;
        }
        stopRedis = new Runnable() {
            @Override
            public void run() {
                try {
                    exec(30, docker, "rm", "-f", id);
                } catch (Exception ignored) {
                    // 容器带 --rm，停掉就会删
                }
            }
        };
        try {
            // 输出形如 127.0.0.1:55012，可能还有一行 IPv6
            String mapped = exec(30, docker, "port", id, "6379/tcp").trim().split("\\R")[0].trim();
            int p = Integer.parseInt(mapped.substring(mapped.lastIndexOf(':') + 1));
            waitReady("127.0.0.1", p, "Redis 容器 " + id);
        } catch (Exception e) {
            unavailable = "Redis 容器 " + id + " 没有启动起来：" + e.getMessage();
            throw new IllegalStateException(unavailable, e);
        }
    }

    /** 能 PING 通才算好（Docker 的端口代理在 Redis 起来前就能连上） */
    private static void waitReady(String h, int p, String what) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (ping(h, p)) {
                host = h;
                port = p;
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException(what + " 没有启动起来");
    }

    private static boolean ping(String h, int p) {
        try (Socket s = new Socket(h, p)) {
            s.setSoTimeout(1000);
            OutputStream out = s.getOutputStream();
            out.write("PING\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            byte[] buf = new byte[16];
            int n = s.getInputStream().read(buf);
            return n > 0 && new String(buf, 0, n, StandardCharsets.US_ASCII).startsWith("+PONG");
        } catch (Exception e) {
            return false;
        }
    }

    /** 跑命令，返回标准输出；错误输出单独收（docker run 拉镜像的进度在错误输出里，不能混进容器 id） */
    private static String exec(int timeoutSeconds, String... cmd) throws Exception {
        File err = File.createTempFile("tora-qual-exec", ".log");
        try {
            Process p = new ProcessBuilder(cmd).redirectError(err).start();
            InputStream in = p.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException(String.join(" ", cmd) + " 超时");
            }
            if (p.exitValue() != 0) {
                throw new IllegalStateException(new String(Files.readAllBytes(err.toPath()), StandardCharsets.UTF_8).trim());
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            err.delete();
        }
    }

    /** Maven 从 IDE 启动时 PATH 可能不全，常见安装位置也找一遍 */
    private static String find(String name) {
        List<String> dirs = new ArrayList<String>(Arrays.asList("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin",
                System.getProperty("user.home") + "/.orbstack/bin",
                "/Applications/Docker.app/Contents/Resources/bin"));
        String path = System.getenv("PATH");
        if (path != null) {
            dirs.addAll(Arrays.asList(path.split(File.pathSeparator)));
        }
        for (String dir : dirs) {
            File f = new File(dir, name);
            if (f.canExecute()) {
                return f.getPath();
            }
        }
        return null;
    }

    public static LettuceConnectionFactory factory(int db) {
        String[] hp = hostPort();
        RedisStandaloneConfiguration c = new RedisStandaloneConfiguration(hp[0], Integer.parseInt(hp[1]));
        c.setDatabase(db);
        LettuceConnectionFactory f = new LettuceConnectionFactory(c);
        f.afterPropertiesSet();
        synchronized (RedisSupport.class) {
            FACTORIES.add(f);
        }
        return f;
    }

    /** 连库 15 并清空 */
    public static StringRedisTemplate template() {
        StringRedisTemplate t = new StringRedisTemplate(factory(DB));
        t.afterPropertiesSet();
        flush(t);
        return t;
    }

    public static void flush(StringRedisTemplate t) {
        t.getConnectionFactory().getConnection().flushDb();
    }
}
