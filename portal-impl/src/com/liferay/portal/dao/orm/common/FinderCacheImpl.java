/**
 * Copyright (c) 2000-2012 Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.portal.dao.orm.common;

import com.endplay.portal.cache.external.ExternalCachePoolUtil;
import com.liferay.portal.kernel.cache.CacheRegistryItem;
import com.liferay.portal.kernel.cache.CacheRegistryUtil;
import com.liferay.portal.kernel.cache.MultiVMPool;
import com.liferay.portal.kernel.cache.PortalCache;
import com.liferay.portal.kernel.dao.orm.EntityCacheUtil;
import com.liferay.portal.kernel.dao.orm.FinderCache;
import com.liferay.portal.kernel.dao.orm.FinderPath;
import com.liferay.portal.kernel.dao.orm.SessionFactory;
import com.liferay.portal.kernel.util.AutoResetThreadLocal;
import com.liferay.portal.kernel.util.PropsUtil;
import com.liferay.portal.kernel.util.StringPool;
import com.liferay.portal.model.BaseModel;
import com.liferay.portal.util.PropsValues;
import org.apache.commons.collections.map.LRUMap;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author Brian Wing Shun Chan
 * @author Shuyang Zhou
 */
public class FinderCacheImpl implements CacheRegistryItem, FinderCache {

    public static final String CACHE_NAME = FinderCache.class.getName();

    public void afterPropertiesSet() {
        _useExternalCache = Boolean.valueOf(PropsUtil.get(_USE_EXTERNAL_CACHE));
        _externalCacheNames = new HashSet<String>();
        String[] names = PropsUtil.getArray(_EXTERNAL_CACHE_NAMES);
        if (names != null) {
            _externalCacheNames.addAll(Arrays.asList(names));
        }
        CacheRegistryUtil.register(this);
    }

    public void clearCache() {
        clearLocalCache();

        // Don't clear external caches indiscriminately.
        for (String className : _portalCaches.keySet()) {
            if (!_useExternalCache || _externalCache == null || !_useExternalCacheForClass(className)) {
                clearCache(className);
            }
        }
    }

    public void clearCache(String className) {
        clearLocalCache();

        PortalCache portalCache = _getPortalCache(className, false);

        if (portalCache != null) {
            portalCache.removeAll();
        }
    }

    public void clearLocalCache() {
        if (_localCacheAvailable) {
            _localCache.remove();
        }
    }

    public String getRegistryName() {
        return CACHE_NAME;
    }

    public Object getResult(
            FinderPath finderPath, Object[] args, SessionFactory sessionFactory) {

        if (!PropsValues.VALUE_OBJECT_FINDER_CACHE_ENABLED ||
                !finderPath.isFinderCacheEnabled() ||
                !CacheRegistryUtil.isActive()) {

            return null;
        }

        Object primaryKey = null;

        Map<Serializable, Object> localCache = null;

        Serializable localCacheKey = null;

        if (_localCacheAvailable) {
            localCache = _localCache.get();

            localCacheKey = finderPath.encodeLocalCacheKey(args);

            primaryKey = localCache.get(localCacheKey);
        }

        if (primaryKey == null) {
            PortalCache portalCache = _getPortalCache(
                    finderPath.getCacheName(), true);

            Serializable cacheKey = finderPath.encodeCacheKey(args);

            primaryKey = portalCache.get(cacheKey);

            if (primaryKey != null) {
                if (_localCacheAvailable) {
                    localCache.put(localCacheKey, primaryKey);
                }
            }
        }

        if (primaryKey != null) {
            return _primaryKeyToResult(finderPath, sessionFactory, primaryKey);
        }
        else {
            return null;
        }
    }

    public void invalidate() {
        clearCache();
    }

    public void putResult(FinderPath finderPath, Object[] args, Object result) {
        if (!PropsValues.VALUE_OBJECT_FINDER_CACHE_ENABLED ||
                !finderPath.isFinderCacheEnabled() ||
                !CacheRegistryUtil.isActive() ||
                (result == null)) {

            return;
        }

        Object primaryKey = _resultToPrimaryKey(result);

        if (_localCacheAvailable) {
            Map<Serializable, Object> localCache = _localCache.get();

            Serializable localCacheKey = finderPath.encodeLocalCacheKey(args);

            localCache.put(localCacheKey, primaryKey);
        }

        PortalCache portalCache = _getPortalCache(
                finderPath.getCacheName(), true);

        Serializable cacheKey = finderPath.encodeCacheKey(args);

        portalCache.put(cacheKey, primaryKey);
    }

    public void removeCache(String className) {
        _portalCaches.remove(className);

        String groupKey = _GROUP_KEY_PREFIX.concat(className);

        _multiVMPool.removeCache(groupKey);

        if (_externalCache != null) {
            _externalCache.removeCache(groupKey);
        }
    }

    public void removeResult(FinderPath finderPath, Object[] args) {
        if (!PropsValues.VALUE_OBJECT_FINDER_CACHE_ENABLED ||
                !finderPath.isFinderCacheEnabled() ||
                !CacheRegistryUtil.isActive()) {

            return;
        }

        if (_localCacheAvailable) {
            Map<Serializable, Object> localCache = _localCache.get();

            Serializable localCacheKey = finderPath.encodeLocalCacheKey(args);

            localCache.remove(localCacheKey);
        }

        PortalCache portalCache = _getPortalCache(
                finderPath.getCacheName(), true);

        Serializable cacheKey = finderPath.encodeCacheKey(args);

        portalCache.remove(cacheKey);
    }

    public void setMultiVMPool(MultiVMPool multiVMPool) {
        _multiVMPool = multiVMPool;
    }

    private MultiVMPool _getCachePool(String className) {
        if (_useExternalCache && _externalCache == null) {
            if (ExternalCachePoolUtil.getExternalCachePool() == null) return _multiVMPool;
            _externalCache = ExternalCachePoolUtil.getExternalCachePool();
        }

        if (_externalCache != null && _useExternalCacheForClass(className)) {
            return _externalCache;
        }
        return _multiVMPool;
    }

    private boolean _useExternalCacheForClass(String className) {
        if (_externalCacheNames == null || _externalCacheNames.isEmpty()) return true;
        else return _externalCacheNames.contains(className);
    }

    private PortalCache _getPortalCache(
            String className, boolean createIfAbsent) {

        PortalCache portalCache = _portalCaches.get(className);

        if ((portalCache == null) && createIfAbsent) {
            String groupKey = _GROUP_KEY_PREFIX.concat(className);

            portalCache = _getCachePool(className).getCache(
                    groupKey, PropsValues.VALUE_OBJECT_FINDER_BLOCKING_CACHE);

            PortalCache previousPortalCache = _portalCaches.putIfAbsent(
                    className, portalCache);

            if (previousPortalCache != null) {
                portalCache = previousPortalCache;
            }
        }

        return portalCache;
    }

    private Object _primaryKeyToResult(
            FinderPath finderPath, SessionFactory sessionFactory,
            Object primaryKey) {

        if (primaryKey instanceof List<?>) {
            List<Object> cachedList = (List<Object>)primaryKey;

            if (cachedList.isEmpty()) {
                return Collections.emptyList();
            }

            List<Object> list = new ArrayList<Object>(cachedList.size());

            for (Object curPrimaryKey : cachedList) {
                Object result = _primaryKeyToResult(
                        finderPath, sessionFactory, curPrimaryKey);

                list.add(result);
            }

            return list;
        }
        else if (BaseModel.class.isAssignableFrom(
                finderPath.getResultClass())) {

            return EntityCacheUtil.loadResult(
                    finderPath.isEntityCacheEnabled(), finderPath.getResultClass(),
                    (Serializable)primaryKey, sessionFactory);
        }
        else {
            return primaryKey;
        }
    }

    private Object _resultToPrimaryKey(Object result) {
        if (result instanceof BaseModel<?>) {
            BaseModel<?> model = (BaseModel<?>)result;

            return model.getPrimaryKeyObj();
        }
        else if (result instanceof List<?>) {
            List<Object> list = (List<Object>)result;

            if (list.isEmpty()) {
                return Collections.emptyList();
            }

            List<Object> cachedList = new ArrayList<Object>(list.size());

            for (Object curResult : list) {
                Object primaryKey = _resultToPrimaryKey(curResult);

                cachedList.add(primaryKey);
            }

            return cachedList;
        }
        else {
            return result;
        }
    }

    private static final String _GROUP_KEY_PREFIX = CACHE_NAME.concat(
            StringPool.PERIOD);

    private static ThreadLocal<LRUMap> _localCache;
    private static boolean _localCacheAvailable;
    private static final String  _USE_EXTERNAL_CACHE = "endplay.external.cache.use.for.FinderCache";
    private static final String  _EXTERNAL_CACHE_NAMES = "endplay.external.cache.FinderCache.list";
    private static boolean _useExternalCache;
    private static Set<String> _externalCacheNames;

    static {
        if (PropsValues.VALUE_OBJECT_FINDER_THREAD_LOCAL_CACHE_MAX_SIZE > 0) {
            _localCache = new AutoResetThreadLocal<LRUMap>(
                    FinderCacheImpl.class + "._localCache",
                    new LRUMap(
                            PropsValues.
                                    VALUE_OBJECT_FINDER_THREAD_LOCAL_CACHE_MAX_SIZE));
            _localCacheAvailable = true;
        }
    }

    private MultiVMPool _multiVMPool;
    private MultiVMPool _externalCache = null;
    private ConcurrentMap<String, PortalCache> _portalCaches =
            new ConcurrentHashMap<String, PortalCache>();

}