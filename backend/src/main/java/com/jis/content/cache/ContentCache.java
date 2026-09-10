package com.jis.content.cache;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jis.common.RedisLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 内容缓存。围绕 Redis 的三个经典问题各做了一件事：
 *
 * <ul>
 *   <li><b>穿透</b>：查不到的 key 也写入一个空值哨兵（短 TTL），挡住反复打库的无效查询</li>
 *   <li><b>击穿</b>：回源前抢 Redis 锁，同一时刻只有一个请求真的查库，其余短暂重试</li>
 *   <li><b>雪崩</b>：TTL 加随机抖动，避免大批 key 同时失效</li>
 * </ul>
 *
 * <p>失效策略用的是**版本号**而不是遍历删除：内容导入后把版本号 +1，
 * 缓存 key 前缀随之变化，旧 key 交给 TTL 自然淘汰。
 * 这样一次失效是 O(1)，不需要 {@code KEYS}／{@code SCAN} 全库扫描
 * （{@code KEYS} 在大实例上会阻塞 Redis，是线上事故的常见来源）。
 *
 * <p>序列化刻意不用 RedisTemplate 的多态反序列化：缓存的对象多是 record
 * （final 类型），多态类型信息根本写不进去，读回来会退化成 Map；
 * 而且多态反序列化本身是攻击面。这里显式传 {@link TypeReference}，类型由调用方确定。
 */
@Slf4j
@Component
public class ContentCache {

    private static final String VERSION_KEY = "jis:content:version";
    private static final String KEY_PREFIX = "jis:cache:v";
    private static final String NULL_SENTINEL = "\u0000NULL";

    private static final Duration NULL_TTL = Duration.ofMinutes(5);
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final int RETRY_TIMES = 3;
    private static final long RETRY_INTERVAL_MS = 50L;

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisLock redisLock;
    private final ObjectMapper cacheMapper;

    public ContentCache(StringRedisTemplate stringRedisTemplate, RedisLock redisLock) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisLock = redisLock;

        // 独立构造，不注册为 Spring Bean：一个 ObjectMapper Bean 会让
        // Spring Boot 的 Jackson 自动配置退让，进而影响 Web 层的 JSON 行为
        this.cacheMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    /**
     * 读缓存，未命中则回源并写回。
     *
     * @param type 显式类型，避免多态反序列化
     */
    public <T> T getOrLoad(
            String namespace,
            String key,
            Duration ttl,
            TypeReference<T> type,
            Supplier<T> loader
    ) {
        String redisKey = buildKey(namespace, key);

        ReadResult<T> cached = tryRead(redisKey, type);
        if (cached.hit()) {
            return cached.value();
        }

        String lockKey = redisKey + ":lock";
        String lockToken = redisLock.tryLock(lockKey, LOCK_TTL);
        boolean locked = lockToken != null;

        try {
            if (!locked) {
                // 没抢到锁说明别的请求正在回源，等它把缓存填上
                ReadResult<T> retried = retryRead(redisKey, type);
                if (retried.hit()) {
                    return retried.value();
                }

                // 等待期间仍未填上，宁可自己回源也不让请求失败
                log.debug("缓存重建等待超时，转为直接回源 key={}", redisKey);

                return loader.get();
            }

            // 双重检查：等锁期间可能已被上一个持有者写入
            ReadResult<T> rechecked = tryRead(redisKey, type);
            if (rechecked.hit()) {
                return rechecked.value();
            }

            T loaded = loader.get();
            writeCache(redisKey, loaded, ttl);

            return loaded;
        } finally {
            if (locked) {
                redisLock.unlock(lockKey, lockToken);
            }
        }
    }

    /**
     * 让当前所有缓存立即失效。版本号 +1 即可，旧 key 由 TTL 兜底清理。
     */
    public void evictAll() {
        Long version = stringRedisTemplate.opsForValue()
                .increment(VERSION_KEY);
        log.info("内容缓存已失效，当前版本号 v{}", version);
    }

    private <T> ReadResult<T> retryRead(String redisKey, TypeReference<T> type) {
        for (int attempt = 0; attempt < RETRY_TIMES; attempt++) {
            try {
                Thread.sleep(RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread()
                        .interrupt();
                return ReadResult.miss();
            }

            ReadResult<T> result = tryRead(redisKey, type);
            if (result.hit()) {
                return result;
            }
        }

        return ReadResult.miss();
    }

    /**
     * 读一次缓存。未命中与"有内容但结构不匹配"都算未命中（{@code hit=false}），
     * 由调用方回源重建。
     */
    private <T> ReadResult<T> tryRead(String redisKey, TypeReference<T> type) {
        String payload = stringRedisTemplate.opsForValue()
                .get(redisKey);
        if (payload == null) {
            return ReadResult.miss();
        }

        if (NULL_SENTINEL.equals(payload)) {
            // 穿透保护：之前查过且确实不存在
            return ReadResult.hit(null);
        }

        try {
            return ReadResult.hit(cacheMapper.readValue(payload, type));
        } catch (Exception e) {
            log.warn("缓存反序列化失败，按未命中处理 key={}: {}", redisKey, e.getMessage());

            return ReadResult.miss();
        }
    }

    private void writeCache(String redisKey, Object value, Duration ttl) {
        if (value == null) {
            stringRedisTemplate.opsForValue()
                    .set(redisKey, NULL_SENTINEL, NULL_TTL);
            return;
        }

        stringRedisTemplate.opsForValue()
                .set(redisKey, serialize(value), jitter(ttl));
    }

    /**
     * 给 TTL 加 ±10% 的随机抖动，避免同一批写入的缓存同时过期造成雪崩。
     */
    private Duration jitter(Duration ttl) {
        long base = ttl.toMillis();
        if (base <= 0) {
            return ttl;
        }

        long offset = ThreadLocalRandom.current()
                .nextLong(base / 10 + 1);

        return Duration.ofMillis(base + offset);
    }

    private String serialize(Object value) {
        try {
            return cacheMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("缓存序列化失败: " + value.getClass()
                    .getName(), e);
        }
    }

    private String buildKey(String namespace, String key) {
        String version = stringRedisTemplate.opsForValue()
                .get(VERSION_KEY);
        String resolvedVersion = version == null ? "0" : version;

        return KEY_PREFIX + resolvedVersion + ":" + namespace + ":" + key;
    }

    /**
     * 读缓存的结果。必须区分"未命中"和"命中了一个 null"——
     * 后者是穿透保护的哨兵值，表示数据确实不存在，可以直接返回；
     * 前者要回源。两者混为一谈会导致查不到的数据每次都打库。
     */
    private record ReadResult<T>(boolean hit, T value) {

        static <T> ReadResult<T> hit(T value) {
            return new ReadResult<>(true, value);
        }

        static <T> ReadResult<T> miss() {
            return new ReadResult<>(false, null);
        }
    }
}
