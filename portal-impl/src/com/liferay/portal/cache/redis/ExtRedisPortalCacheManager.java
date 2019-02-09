package com.liferay.portal.cache.redis;

import com.endplay.portal.util.ExternalCacheUtil;
import com.endplay.portal.util.PortalContextClassLoader;
import com.lakana.platform.redis.cache.provider.RedisClient;
import com.lakana.platform.redis.cache.provider.RedisClientFactory;
import com.lakana.platform.redis.cache.provider.util.RedisCacheUtil;
import com.liferay.portal.cache.MultiVMPoolImpl;
import com.liferay.portal.kernel.cache.MultiVMPoolUtil;
import com.liferay.portal.kernel.cache.PortalCache;
import com.liferay.portal.kernel.cache.PortalCacheException;
import com.liferay.portal.kernel.cache.PortalCacheManager;
import com.liferay.portal.kernel.util.PropsUtil;
import org.apache.commons.lang.StringUtils;
import org.redisson.codec.FstCodec;
import org.redisson.codec.LZ4Codec;
import org.redisson.codec.SnappyCodec;
import org.redisson.config.Config;

import java.io.File;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ExtRedisPortalCacheManager
        implements PortalCacheManager {
    private final Map<String, RedisPortalCache> portalCacheMap =
            new ConcurrentHashMap<String, RedisPortalCache>();

    private static final String  _REDISSON_CONFIG_PATH = "redisson.config.path";
    private static final String  _USE_FOR_MULTIVM_POOL = "endplay.external.cache.use.for.MultiVMPool";
    private RedisClient redisClient;

    public void clearAll()
            throws PortalCacheException {
        portalCacheMap.clear();
    }

    public PortalCache getCache(final String name) throws PortalCacheException {
        return getCache(name, false);
    }

    public PortalCache getCache(final String name, final boolean isBlocking) throws PortalCacheException {

        String namespace = ExternalCacheUtil.getExternalCacheNamespace();

        String myName = name;
        if (StringUtils.isNotEmpty(namespace)) {
            myName = namespace + "." + name;
        }

        RedisPortalCache redisPortalCache = portalCacheMap.get(myName);

        if (redisPortalCache == null) {
            try {
               redisPortalCache = new RedisPortalCache(myName, getRedisClient());

                portalCacheMap.put(myName, redisPortalCache);
            }
            catch (final Exception e) {
                throw new IllegalStateException("Unable to initialize Redis connection", e);
            }
        }

        return redisPortalCache;
    }

    public void reconfigureCaches(final URL url) {

    }

    public void removeCache(final String name) {
        String namespace = ExternalCacheUtil.getExternalCacheNamespace();

        String myName = name;
        if (StringUtils.isNotEmpty(namespace)) {
            myName = namespace + "." + name;
        }

        portalCacheMap.remove(myName);
    }

    public void afterPropertiesSet()
    {
        String useForMultiVmPool = PropsUtil.get(_USE_FOR_MULTIVM_POOL);
        if  (Boolean.valueOf(useForMultiVmPool) && MultiVMPoolUtil.getMultiVMPool() instanceof MultiVMPoolImpl) {
            ((MultiVMPoolImpl) MultiVMPoolUtil.getMultiVMPool()).setPortalCacheManager(this);
        }
    }

    private RedisClient getRedisClient() throws Exception {
        if (redisClient != null) {
            return redisClient;
        }

        String path;
        URL url;

        String redissonConfigPath = PropsUtil.get(_REDISSON_CONFIG_PATH);
        if (redissonConfigPath == null) {
            redissonConfigPath = RedisCacheUtil.DEFAULT_REDISSON_CONFIG_PATH;
        }

        if (redissonConfigPath.startsWith("classpath:")) {
            path = redissonConfigPath.substring("classpath:".length());
            url = RedisClientFactory.class.getClassLoader().getResource(path);
        } else {
            path = redissonConfigPath;
            if (path.startsWith("file:")) {
                path = path.substring("file:".length());
            }
            url = new File(path).toURI().toURL();
        }


        // Set portal class loader
        final Config config = Config.fromJSON(url);
        if (config.getCodec() instanceof FstCodec) {
            config.setCodec(new FstCodec(new PortalContextClassLoader(this.getClass().getClassLoader())));
        }else if (config.getCodec() instanceof SnappyCodec) {
            config.setCodec(new SnappyCodec(new PortalContextClassLoader(this.getClass().getClassLoader())));
        }else if (config.getCodec() instanceof LZ4Codec) {
            config.setCodec(new LZ4Codec(new PortalContextClassLoader(this.getClass().getClassLoader())));
        }

        redisClient = RedisClientFactory.createRedisClient(config);

        return redisClient;
    }
}
