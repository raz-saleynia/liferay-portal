package com.liferay.portal.dao.orm.common;

import com.endplay.portlet.epdata.media.configuration.ConfigurationPropertyUtil;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.model.Company;
import com.liferay.portal.model.Group;
import com.liferay.portal.service.CompanyLocalServiceUtil;
import com.liferay.portal.service.GroupLocalServiceUtil;

import java.util.List;
import java.util.TimerTask;


public class FinderExternalCacheConfigTask extends TimerTask  {
	private String propertyMemcacheEnabled;
	private String propertyFinderMemcacheEnabled;
	private String propertyFinderMemcacheStatisticsEnabled;
	private String propertyFinderMemcacheTTL;

	public FinderExternalCacheConfigTask(String memcacheEnabled, String finderCacheEnabled, String propertyFinderMemcacheStatisticsEnabled, String propertyFinderMemcacheTTL) {
		this.propertyMemcacheEnabled = memcacheEnabled;
		this.propertyFinderMemcacheEnabled = finderCacheEnabled;
		this.propertyFinderMemcacheStatisticsEnabled = propertyFinderMemcacheStatisticsEnabled;
		this.propertyFinderMemcacheTTL = propertyFinderMemcacheTTL;
	}

	@Override
	public void run() {
		try {
			// check if any company has this set
			List<Company> companies = CompanyLocalServiceUtil.getCompanies();
			
			// stays null unless the values can be read
			Boolean enableMemcache = null;
			Boolean enableStats = null;
			Integer maxTTL = null;
			
			for (Company company : companies) {
				long companyId = company.getCompanyId();
				Group globalGroup = GroupLocalServiceUtil.getCompanyGroup(companyId);
				String memcacheEnabled =  ConfigurationPropertyUtil.getInstance().getString(companyId, globalGroup.getGroupId(), propertyMemcacheEnabled);
				String finderMemcacheEnabled =  ConfigurationPropertyUtil.getInstance().getString(companyId, globalGroup.getGroupId(), propertyFinderMemcacheEnabled);
				String finderMemcacheStatisticsEnabled = ConfigurationPropertyUtil.getInstance().getString(companyId, globalGroup.getGroupId(), propertyFinderMemcacheStatisticsEnabled);
				String finderMemcacheTTL = ConfigurationPropertyUtil.getInstance().getString(companyId, globalGroup.getGroupId(), propertyFinderMemcacheTTL); 
				
				// if any of the tenants has it set to true, then it is enabled for all
				// otherwise if all are false or null, then set to false
				if (memcacheEnabled != null && finderMemcacheEnabled != null) {
					if (enableMemcache == null) {
						enableMemcache = Boolean.parseBoolean(memcacheEnabled) & Boolean.parseBoolean(finderMemcacheEnabled);
					}
					else {
						enableMemcache |= Boolean.parseBoolean(memcacheEnabled) & Boolean.parseBoolean(finderMemcacheEnabled);
					}
				}
				
				if (finderMemcacheStatisticsEnabled != null) {
					if (enableStats == null) {
						enableStats = Boolean.parseBoolean(finderMemcacheStatisticsEnabled);
					}
					else {
						enableStats |= Boolean.parseBoolean(finderMemcacheStatisticsEnabled);
					}
				}
				
				if (finderMemcacheTTL != null) {
					try {
						int ttl = Integer.parseInt(finderMemcacheTTL);
						
						if (maxTTL == null || ttl > maxTTL) {
							maxTTL = ttl;
						}
					}
					catch (Exception ex) {
						// ignore
					}
				}
			}

			// check if setting has a value, it will not if there was an error fetching the property
			if (enableMemcache != null) {
				enableMemcache = enableMemcache.equals(Boolean.TRUE); 
				
				FinderExternalCacheUtil.enableExternalCache(enableMemcache);
			}

			if (enableStats != null) {
				enableStats = enableStats.equals(Boolean.TRUE); 
				
				FinderExternalCacheUtil.enableExternalCacheStatistics(enableStats);
			}
			
			if (maxTTL != null) {
				FinderExternalCacheUtil.setFinderExternalCacheTTLSeconds(maxTTL);
			}
		}
		catch (Exception ex) {
			_log.error(String.format("Error retrieving configuration property '%s', '%s' or '%s':  %s", propertyMemcacheEnabled, propertyFinderMemcacheEnabled, propertyFinderMemcacheStatisticsEnabled, ex));
		}
	}
	
	private static Log _log = LogFactoryUtil.getLog(FinderExternalCacheConfigTask.class);
}
