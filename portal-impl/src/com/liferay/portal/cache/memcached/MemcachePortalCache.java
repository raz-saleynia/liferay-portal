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

package com.liferay.portal.cache.memcached;

import com.endplay.portal.util.ExternalCacheUtil;
import com.liferay.portal.dao.orm.common.FinderExternalCacheUtil;
import com.liferay.portal.kernel.cache.CacheListener;
import com.liferay.portal.kernel.cache.CacheListenerScope;
import com.liferay.portal.kernel.cache.PortalCache;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import net.spy.memcached.MemcachedClientIF;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * @author Michael C. Han
 */
public class MemcachePortalCache implements PortalCache {

	public static final String HITS_SUFFIX = "?hits";
	public static final String MISSES_SUFFIX = "?misses";

	public MemcachePortalCache(
		String name, MemcachedClientIF memcachedClient, int timeout,
		TimeUnit timeoutTimeUnit) {

		String namespace = ExternalCacheUtil.getExternalCacheNamespace();

		_name = namespace.concat(name);
		// Use the namespace prepended version to put and get keys.
		// This will avoid key conflicts between environments.
		_namespacedName = namespace.concat(name);
		_memcachedClient = memcachedClient;
		_timeout = timeout;
		_timeoutTimeUnit = timeoutTimeUnit;

		_namespacedName = namespace.concat(name);
		_memcachedClient = memcachedClient;
		_timeout = timeout;
		_timeoutTimeUnit = timeoutTimeUnit;

		if(_log.isDebugEnabled()) {
			_log.debug(String.format("cache %s created, timeout=%d %s", _namespacedName, _timeout, _timeoutTimeUnit));
		}

		// for statistics
		int lastSlashIndex = _namespacedName.lastIndexOf("/");
		String _namespacedUnpartitionedName = (lastSlashIndex > 0) ? _namespacedName.substring(0, lastSlashIndex) : _namespacedName;

		_hitsKey = _namespacedUnpartitionedName.concat(HITS_SUFFIX);
		_missesKey = _namespacedUnpartitionedName.concat(MISSES_SUFFIX);

		// initialize counter if not set
		try {
			memcachedClient.add(_hitsKey, 0, "0").get();
		} catch (Exception ex) {
			_log.error(ex);
		}

		try {
			memcachedClient.add(_missesKey, 0, "0").get();
		} catch (Exception ex) {
			_log.error(ex);
		}

	}
	
	public boolean isActive() {
		return _connectionActive;
	}

	public void destroy() {
		_memcachedClient.shutdown();
	}

	public Collection<Object> get(Collection<Serializable> keys) {

		if(_log.isTraceEnabled()) {
			_log.trace("cache bulk get start "+keys.size());
		}
		
		List<String> processedKeys = new ArrayList<String>(keys.size());

		for (Serializable key : keys) {
			String processedKey = _namespacedName.concat(String.valueOf(key));

			processedKeys.add(processedKey);
		}

		Future<Map<String, Object>> future = null;

		try {
			future = _memcachedClient.asyncGetBulk(processedKeys);
		}
		catch (IllegalArgumentException iae) {
			if (_log.isWarnEnabled()) {
				_log.warn("Error retrieving with keys " + keys, iae);
			}

			return null;
		} catch (IllegalStateException ise) {
			if (_log.isWarnEnabled()) {
				_log.warn("Error retrieving due to illegal state", ise);
			}
			
			return null;
		}

		Map<String, Object> values = null;

		try {
			values = future.get(_timeout, _timeoutTimeUnit);
		}
		catch (Throwable t) {
			if (_log.isWarnEnabled()) {
				_log.warn("Memcache operation error", t);
			}

			future.cancel(true);
		}
		
		if(_log.isDebugEnabled()) {
			_log.debug("cache bulk get "+keys.size()+" keys,"+valueMessage(values != null && values.size() > 0));
		}

		return values.values();
	}

	public Object get(Serializable key) {

		if(_log.isTraceEnabled()) {
			_log.trace("cache get start, key: " + key);
		}

		String processedKey = _namespacedName.concat(String.valueOf(key));

		Future<Object> future = null;

		try {
			future = _memcachedClient.asyncGet(processedKey);
		}
		catch (IllegalArgumentException iae) {
			if (_log.isWarnEnabled()) {
				_log.warn("Error retrieving with key " + key, iae);
			}

			return null;
		} catch (IllegalStateException ise) {
			if (_log.isWarnEnabled()) {
				_log.warn("Error retrieving due to illegal state", ise);
			}
						
			return null;
		}

		Object value = null;

		try {
			value = future.get(_timeout, _timeoutTimeUnit);

			if (FinderExternalCacheUtil.isExternalCacheStatisticsEnabled()) {
				String statsKey = (value != null) ? _hitsKey : _missesKey;

				_memcachedClient.asyncIncr(statsKey, 1);

				if(_log.isDebugEnabled()) {
					_log.debug("increment stats: " + statsKey);
				}
			}
		}
		catch (Throwable t) {
			if (_log.isWarnEnabled()) {
				_log.warn("Memcache operation error", t);
			}

			future.cancel(true);
		}
		
		if(_log.isDebugEnabled()) {
			_log.debug("cache get: " + processedKey + valueMessage(value != null));
		}

		return value;
	}

	public String getName() {
		return _name;
	}

	public void put(Serializable key, Object value) {
		put(key, value, _timeToLive);
	}

	public void put(Serializable key, Object value, int timeToLive) {

		if(_log.isTraceEnabled()) {
			_log.trace("cache put start, key: " + key);
		}
		
		String processedKey = _namespacedName.concat(String.valueOf(key));

		try {
			_memcachedClient.set(processedKey, timeToLive, value);
		}
		catch (IllegalArgumentException iae) {
			if (_log.isWarnEnabled()) {
				_log.warn("Error storing value with key " + key, iae);
			}
		} catch (IllegalStateException ise) {
			if (_log.isWarnEnabled()) {
				_log.warn("Error retrieving due to illegal state", ise);
			}
		}
		
		if(_log.isDebugEnabled()) {
			_log.debug("cache put: " + processedKey + " ttl=" + timeToLive);
		}
	}

	public void put(Serializable key, Serializable value) {
		put(key, value, _timeToLive);
	}

	public void put(Serializable key, Serializable value, int timeToLive) {

		if(_log.isTraceEnabled()) {
			_log.trace("cache put2 start, key: " + key);
		}
		
		String processedKey = _namespacedName.concat(String.valueOf(key));

		try {
			_memcachedClient.set(processedKey, timeToLive, value);
		}
		catch (IllegalArgumentException iae) {
			if (_log.isWarnEnabled()) {
				_log.warn("Error storing value with key " + key, iae);
			}
		} catch (IllegalStateException ise) {
			if (_log.isWarnEnabled()) {
				_log.warn("Error retrieving due to illegal state", ise);
			}
		}
		
		if(_log.isDebugEnabled()) {
			_log.debug("cache put2: " + processedKey + " ttl=" + timeToLive);
		}
	}

	// methods required for region clearing
	public void add(Serializable key, Serializable value, int timeToLive) {
		if(_log.isTraceEnabled()) {
			_log.trace("cache add start, key: " + key);
		}

		String processedKey = _namespacedName.concat(String.valueOf(key));

		try {
			// set: can't do replace
			_memcachedClient.add(processedKey, timeToLive, value);
		}
		catch (IllegalArgumentException iae) {
			if (_log.isWarnEnabled()) {
				_log.warn("Error storing value with key " + key, iae);
			}
		} catch (IllegalStateException ise) {
			if (_log.isWarnEnabled()) {
				_log.warn("Error retrieving due to illegal state", ise);
			}
		}

		if(_log.isDebugEnabled()) {
			_log.debug("cache add: " + processedKey + " ttl=" + timeToLive);
		}
	}

	public long increment(Serializable key, int increment) {
		if(_log.isTraceEnabled()) {
			_log.trace("cache increment start, key: " + key);
		}

		String processedKey = _namespacedName.concat(String.valueOf(key));
		long value = 1;

		Future<Long> future = null;
		try {
			future = _memcachedClient.asyncIncr(processedKey, increment);

			value = future.get(_timeout, _timeoutTimeUnit);
		}
		catch (Throwable t) {
			if (_log.isWarnEnabled()) {
				_log.warn("Memcache increment error for "+processedKey, t);
			}

			future.cancel(true);
		}

		if(_log.isDebugEnabled()) {
			_log.debug("cache increment: " + processedKey);
		}

		return value;
	}

	private String valueMessage(boolean found) {
		return (found) ? " not null" : " is null";
	}

	public void registerCacheListener(CacheListener cacheListener) {
		registerCacheListener(cacheListener, CacheListenerScope.ALL);
	}

	public void registerCacheListener(
		CacheListener cacheListener, CacheListenerScope cacheListenerScope) {

		throw new UnsupportedOperationException();
	}

	public void remove(Serializable key) {

		if(_log.isTraceEnabled()) {
			_log.trace("cache remove start, key: " + key);
		}
		
		String processedKey = _namespacedName.concat(String.valueOf(key));

		try {
			_memcachedClient.delete(processedKey);
		}
		catch (IllegalArgumentException iae) {
			if (_log.isWarnEnabled()) {
				_log.warn("Error removing value with key " + key, iae);
			}
		} catch (IllegalStateException ise) {
			if (_log.isWarnEnabled()) {
				_log.warn("Error retrieving due to illegal state", ise);
			}
		}
		
		if(_log.isDebugEnabled()) {
			_log.debug("cache remove: " + processedKey);
		}
	}

	public void removeAll() {
	    //log the clear cache call. and do not do flush.
	    try {
	        throw new Exception("Log the stack trace of where called memcache cache clearing");
	    } catch(Exception e) {
	        _log.warn(e);
	    }
		//_memcachedClient.flush();
	}

	public String getNamespace() {
		return _namespacedName;
	}

	public void setTimeToLive(int timeToLive) {
		_timeToLive = timeToLive;
	}

	public void unregisterCacheListener(CacheListener cacheListener) {
	}

	public void unregisterCacheListeners() {
	}

	private static Log _log = LogFactoryUtil.getLog(MemcachePortalCache.class);

	private MemcachedClientIF _memcachedClient;
	private String _name;
	private String _namespacedName;
	private int _timeout;
	private TimeUnit _timeoutTimeUnit;
	private int _timeToLive;
	private int _reconnectCount;
	private boolean _connectionActive;

	private String _hitsKey;
	private String _missesKey;
}
