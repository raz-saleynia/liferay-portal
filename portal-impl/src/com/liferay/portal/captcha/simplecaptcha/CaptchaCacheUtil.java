package com.liferay.portal.captcha.simplecaptcha;

import com.liferay.portal.kernel.cache.MultiVMPoolUtil;
import com.liferay.portal.kernel.cache.PortalCache;
import org.apache.commons.lang.StringUtils;


class CaptchaCacheUtil {
    
    private CaptchaCacheUtil(){}

    //cache configuration constants
    private static final String CAPTCHA_CACHE_NAME = CaptchaCacheUtil.class.getName();
    
	private static PortalCache _portalCache = MultiVMPoolUtil.getCache(CAPTCHA_CACHE_NAME);
	
    static void put(String key, String value) {
        if (StringUtils.isNotBlank(key)) {
        	_portalCache.put(key, value);
        } else {
            throw new IllegalArgumentException("Cannot accept a blank key.");
        }
    }
    
    static String get(String key) {
        if (StringUtils.isNotBlank(key)) {
            return (String)_portalCache.get(key);
        } else {
            throw new IllegalArgumentException("Cannot accept a blank key.");
        }
    }
    
    static void remove(String key) {
        if (StringUtils.isNotBlank(key)) {
        	_portalCache.remove(key);
        } else {
            throw new IllegalArgumentException("Cannot accept a blank key.");
        }
    }

}
