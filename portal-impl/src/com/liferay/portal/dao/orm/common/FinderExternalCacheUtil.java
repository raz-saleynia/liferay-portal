package com.liferay.portal.dao.orm.common;

import com.endplay.portal.util.ExternalCacheUtil;

import java.util.Date;
import java.util.Timer;


public class FinderExternalCacheUtil {
	public static final String STATISTICS_ENABLED = "endplay.external.cache.statistics.enabled";
	public static final String FINDER_EXTERNAL_CACHE_TTL = "endplay.external.cache.findercache.ttl.seconds";
	
	protected static boolean enableFinderExternalCache = false;
	protected static boolean enableExternalCacheStatistics = false;
	protected static int finderExternalCacheTTLSeconds = 600; // default to 10 minutes
	
	private static int POLL_INTERVAL = 5000;  // 5 seconds
	private static FinderExternalCacheConfigTask configPollTask = null;
	
	
	static {
		System.out.format("%tT FinderExternalCacheUtil loaded, finder external cache=%b\n", new Date(), enableFinderExternalCache);
		
		// start daemon thread to check for changes to partition configuration	
		configPollTask = new FinderExternalCacheConfigTask(ExternalCacheUtil._CACHE_ENABLED_DDL, ExternalCacheUtil.CACHE_TARGETED_FINDER_CACHE, STATISTICS_ENABLED, FINDER_EXTERNAL_CACHE_TTL);
        Timer timer = new Timer("finder-external-cache-config-thread", true);

		// start on the next interval
		long nextInterval = ((System.currentTimeMillis() / POLL_INTERVAL)*POLL_INTERVAL + POLL_INTERVAL) - System.currentTimeMillis();
        
		timer.schedule(configPollTask, nextInterval, POLL_INTERVAL);
	}
	
	protected static void enableExternalCache(boolean setting) {
		if (setting != enableFinderExternalCache) {
			enableFinderExternalCache = setting;
			
			System.out.format("%tT Finder external cache changed to %b\n", new Date(), enableFinderExternalCache);
		}
	}
	
	public static boolean isExternalCacheEnabled() {
		return enableFinderExternalCache;
	}
	
	protected static void enableExternalCacheStatistics(boolean setting) {
		if (setting != enableExternalCacheStatistics) {
			enableExternalCacheStatistics = setting;
			
			System.out.format("%tT External cache statistics changed to %b\n", new Date(), enableExternalCacheStatistics);
		}
	}
	
	public static boolean isExternalCacheStatisticsEnabled() {
		return enableExternalCacheStatistics;
	}
	
	public static int getFinderExternalCacheTTLSeconds() {
		return finderExternalCacheTTLSeconds;
	}
	
	public static void setFinderExternalCacheTTLSeconds(int setting) {
		finderExternalCacheTTLSeconds = setting;
	}
}