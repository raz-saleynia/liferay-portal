package com.liferay.portal.cache.redis;

import com.lakana.platform.redis.cache.provider.RedisClient;
import com.liferay.portal.kernel.cache.CacheListener;
import com.liferay.portal.kernel.cache.CacheListenerScope;
import com.liferay.portal.kernel.cache.PortalCache;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.PropsUtil;
import org.springframework.util.StopWatch;

import java.io.Serializable;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class RedisPortalCache
        implements PortalCache {
    private String name;
    private RedisClient redisClient;
    private CacheListener cacheListener;
    private int defaultTTL = 0;
    private final String REDIS_DEFAULT_TTL_PROPERTY = "endplay.redis.default.ttl";
    private final String REDIS_PARTITION_COUNT_PROPERTY = "endplay.redis.partition.count";
    private final String PARTITION_SEPARATOR = "/";
    private final static Log _log = LogFactoryUtil.getLog(RedisPortalCache.class.getName());
    private final static Log _logEPPerfPut = LogFactoryUtil.getLog("ep.time.log." + RedisPortalCache.class.getName()+".put");
    private final static Log _logEPPerfGet = LogFactoryUtil.getLog("ep.time.log." + RedisPortalCache.class.getName()+".get");
    private int partitionCount = 32;

    public RedisPortalCache(final String name, final RedisClient redisClient) {
        this.name = name;
        this.redisClient = redisClient;
        try {
            String ttlString = PropsUtil.get(REDIS_DEFAULT_TTL_PROPERTY);
            if (ttlString != null) defaultTTL = Integer.valueOf(ttlString);
        } catch (NumberFormatException e) {
            _log.info("Unable to set default TTL from property, using default.");
        }
        try {
            String partitionCountString = PropsUtil.get(REDIS_PARTITION_COUNT_PROPERTY);
            if (partitionCountString != null) partitionCount = Integer.valueOf(partitionCountString);
        } catch (NumberFormatException e) {
            _log.info("Unable to set partition count from property, using default.");
        }
    }

    public void destroy() {
        redisClient.shutdown();
    }

    public Collection<Object> get(final Collection<Serializable> keys) {
        // Unused or rarely used. Going per key to get with partition.
        List<Object> collection = new ArrayList<Object>();
        Object val;
        for (final Serializable key : keys) {
            val = get(key);
            if (val != null) {
                collection.add(val);
            }
        }
        return collection;
    }

    public Object get(final Serializable key) {
        try {
            StopWatch s = new StopWatch("RedisPortalCache");
            s.start(String.format("GET: cacheName [%s] key [%s]", name, key));
            Object value = redisClient.get(getName(key), getKey(key));
            s.stop();
            if (_logEPPerfGet.isInfoEnabled()) {
                for (StopWatch.TaskInfo info : s.getTaskInfo()) {
                    _logEPPerfGet.info(info.getTaskName() + " [millis]: " + info.getTimeMillis());
                }
            }
            return value;
        } catch (Exception e) {
            _log.error("Failed to get data from cache", e);
        }
        return null;
    }

    public String getName() {
        return name;
    }

    public String getName(Serializable key) {
        return  name + getKeyPartition(key);
    }

    public void put(final Serializable key, final Object value) {
        redisClient.set(getName(key), getKey(key), value, defaultTTL);
    }

    public void put(final Serializable key, final Object value, int timeToLive) {
        StopWatch s = new StopWatch("RedisPortalCache");
        s.start(String.format("Put: cacheName [%s] key [%s]", name, key));
        try {
            redisClient.set(getName(key), getKey(key), value, timeToLive);
        } catch (Exception e) {
            _log.error("Failed to set data", e);
        }
        s.stop();
        if (_logEPPerfPut.isInfoEnabled()) {
            for (StopWatch.TaskInfo info : s.getTaskInfo()) {
                _logEPPerfPut.info(info.getTaskName() + " [millis]: " + info.getTimeMillis());
            }
        }
    }

    public void put(final Serializable key, final Serializable value) {
        redisClient.set(getName(key), getKey(key), value, defaultTTL);
    }

    public void put(final Serializable key, final Serializable value, final int timeToLive) {
        StopWatch s = new StopWatch("RedisPortalCache");
        s.start(String.format("SET: cacheName [%s] key [%s]", name, key));
        try {
            redisClient.set(getName(key), getKey(key), value, timeToLive);
        } catch (Exception e) {
            _log.error("Failed to set data", e);
        }
        s.stop();
        if (_logEPPerfPut.isInfoEnabled()) {
            for (StopWatch.TaskInfo info : s.getTaskInfo()) {
                _logEPPerfPut.info(info.getTaskName() + " [millis]: " + info.getTimeMillis());
            }
        }
    }

    public void remove(final Serializable key) {
        redisClient.del(getName(key), getKey(key));
    }

    public void removeAll() {
        // Remove all partitions
        if (partitionCount == 0) {
            redisClient.deleteRegion(name);
        } else {
            for (long partition = 0; partition < partitionCount; partition++) {
                redisClient.deleteRegion(name + PARTITION_SEPARATOR + Long.toHexString(partition));
            }
        }
    }

    public void registerCacheListener(final CacheListener cacheListener) {
        this.cacheListener = cacheListener;
    }

    public void registerCacheListener(
            final CacheListener cacheListener,
            final CacheListenerScope cacheListenerScope) {
    }

    public void unregisterCacheListener(final CacheListener cacheListener) {
    }

    public void unregisterCacheListeners() {
    }

    private String getKey(final Serializable key) {
        return String.valueOf(key);
    }

    private String getKeyPartition(final Serializable key) {
        if (partitionCount == 0) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA1");
            long digest = new BigInteger(md.digest(String.valueOf(key).getBytes())).longValue();
            long partition = Math.abs(digest % partitionCount);
            return PARTITION_SEPARATOR + Long.toHexString(partition);
        } catch (NoSuchAlgorithmException e) {
            _log.error("Unable to encode partition");
        }

        return "";
    }
}
