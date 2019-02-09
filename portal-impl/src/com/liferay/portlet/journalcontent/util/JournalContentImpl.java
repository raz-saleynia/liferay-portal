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

package com.liferay.portlet.journalcontent.util;

import com.endplay.portal.util.CacheManager;
import com.endplay.portal.util.EntryType;
import com.endplay.portal.util.ExternalCacheUtil;
import com.endplay.portal.util.Parms;
import com.endplay.portlet.custom.CustomFieldDataConstants;
import com.endplay.portlet.journal.service.ConfigurationPropertyLocalServiceUtil;
import com.endplay.portlet.journal.service.EPSwitchableCachePoolLocalServiceUtil;
import com.endplay.portlet.journal.util.JavascriptWriter;
import com.endplay.portlet.journal.util.JournalContentThreadLocal;
import com.endplay.portlet.util.RenderConstants;
import com.liferay.portal.kernel.cache.PortalCache;
import com.liferay.portal.kernel.dao.orm.QueryUtil;
import com.liferay.portal.kernel.lar.ImportExportThreadLocal;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.*;
import com.liferay.portal.kernel.xml.Document;
import com.liferay.portal.kernel.xml.DocumentException;
import com.liferay.portal.kernel.xml.Element;
import com.liferay.portal.kernel.xml.SAXReaderUtil;
import com.liferay.portal.model.*;
import com.liferay.portal.service.*;
import com.liferay.portal.theme.ThemeDisplay;
import com.liferay.portlet.expando.model.ExpandoTableConstants;
import com.liferay.portlet.expando.model.ExpandoValue;
import com.liferay.portlet.expando.service.ExpandoValueLocalServiceUtil;
import com.liferay.portlet.journal.model.JournalArticle;
import com.liferay.portlet.journal.model.JournalArticleDisplay;
import com.liferay.portlet.journal.model.JournalTemplate;
import com.liferay.portlet.journal.service.JournalArticleLocalServiceUtil;
import com.liferay.portlet.journal.service.JournalTemplateLocalServiceUtil;
import com.liferay.portlet.journal.util.EPJournalDisplayUtil;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.StopWatch;

import javax.servlet.http.HttpServletRequest;
import java.io.Serializable;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Brian Wing Shun Chan
 * @author Raymond Aug�
 * @author Michael Young
 */
public class JournalContentImpl implements ExtJournalContent {

	public void clearCache() {
		if (ImportExportThreadLocal.isImportInProcess()) {
			return;
		}

		for (CacheManager<Entry, MyParms> cacheManager : cacheManagerMap.values()) {
			cacheManager.clearCache();
		}
		
		if (defaultCacheManager != null)
			defaultCacheManager.clearCache();
	}

	public void clearCache(long groupId, String articleId, String templateId) {
		CacheManager<Entry, MyParms> cacheManager = getCacheManager(groupId);
		
		if (cacheManager == null) return;

		// clear cache for the groupId
		if (articleId == null && templateId == null) {
			cacheManager.clearCache();
			return;
		}

		boolean useMasterKey = true;
		
		try {
			// get company from the group
			Group group = GroupLocalServiceUtil.getGroup(groupId);
			
			useMasterKey =
				!ConfigurationPropertyLocalServiceUtil.getBoolean(
						group.getCompanyId(), groupId,
						CLEAR_ALL_CACHE_ENABLED_KEY);
		} catch (Exception e) {}
		
		if (useMasterKey) {
			PortalCache portalCache = getPortalCache(groupId); 
			
			String masterKey = encodeMasterKey(groupId, articleId);
			List<String> keys = 
				(List<String>) portalCache.get(masterKey);
			
			if (keys != null) {
				for (String key : keys) {
					cacheManager.remove(key);
				}
			}
			
			portalCache.remove(masterKey);
		}
		else {
			clearCache();
		}
	}

	public String getContent(
		long groupId, String articleId, String viewMode, String languageId,
		String xmlRequest) {

		return getContent(
			groupId, articleId, null, viewMode, languageId, null, xmlRequest);
	}

	public String getContent(
		long groupId, String articleId, String templateId, String viewMode,
		String languageId, String xmlRequest) {

		return getContent(
			groupId, articleId, templateId, viewMode, languageId, null,
			xmlRequest);
	}

	public String getContent(
		long groupId, String articleId, String templateId, String viewMode,
		String languageId, ThemeDisplay themeDisplay) {

		return getContent(
			groupId, articleId, templateId, viewMode, languageId, themeDisplay,
			null);
	}

	public String getContent(
		long groupId, String articleId, String templateId, String viewMode,
		String languageId, ThemeDisplay themeDisplay, String xmlRequest) {

		JournalArticleDisplay articleDisplay = getDisplay(
			groupId, articleId, templateId, viewMode, languageId, themeDisplay,
			1, xmlRequest);


		if (articleDisplay != null) {
			return articleDisplay.getContent();
		}
		else {
			return null;
		}
	}

	public String getContent(
		long groupId, String articleId, String viewMode, String languageId,
		ThemeDisplay themeDisplay) {

		return getContent(
			groupId, articleId, null, viewMode, languageId, themeDisplay);
	}
	
	public JournalArticleDisplay getDisplay(
			long groupId, String articleId, double version, String templateId,
			String viewMode, String languageId, ThemeDisplay themeDisplay, int page,
			String xmlRequest, Element requestElement) {

		Document requestDocument = null;
		if(requestElement == null && StringUtils.isNotEmpty(xmlRequest)) {
			try {
				requestDocument = SAXReaderUtil.read(xmlRequest);
				if(requestDocument != null) {
					requestElement = requestDocument.getRootElement();
				}
			} catch (DocumentException e) {
				_log.warn("Unable create xml document from xmlRequest. " + e);
			}
			
		}
		
		if(requestDocument == null && requestElement != null) {
			requestDocument = SAXReaderUtil.createDocument();
			requestDocument.add(requestElement);
		}
		
		StopWatch stopWatch = null;

		if (_log.isDebugEnabled()) {
			stopWatch = new StopWatch();

			stopWatch.start();
		}

		// Adding the article id to the surrogate key header
		if (Validator.isNotNull(articleId)) {
			JournalContentThreadLocal.addArticleId(articleId);
		}

		articleId = GetterUtil.getString(articleId).toUpperCase();
		templateId = GetterUtil.getString(templateId).toUpperCase();

		long layoutSetId = 0;
		boolean secure = false;

		Layout layout = null;
		
		if (themeDisplay != null) {
			try {
				layout = themeDisplay.getLayout();

				LayoutSet layoutSet = layout.getLayoutSet();

				layoutSetId = layoutSet.getLayoutSetId();
				
			}
			catch (Exception e) {
			}

			secure = themeDisplay.isSecure();
		}
		
		JournalArticle article = null;
		JournalTemplate template = null;
		Long articleModifyTime = null;
		if (Validator.isNotNull(articleId)) {
			try {
				article = JournalArticleLocalServiceUtil.getArticle(groupId, articleId);
				articleModifyTime = article.getModifiedDate().getTime();
			} catch (Exception e) {
				_log.error("Can't find article: articleId=" + articleId + ",  groupId=" + groupId + ",  layoutSetId=" + layoutSetId);
			}
		}
		
		if (StringUtils.isNotBlank(templateId) || (article != null && StringUtils.isNotBlank(article.getTemplateId()))) {
			String tempTemplateId = templateId;
			if(StringUtils.isBlank(tempTemplateId)) {
				tempTemplateId = article.getTemplateId();
			}
			try {
				template = JournalTemplateLocalServiceUtil.getTemplate(themeDisplay.getCompanyGroupId(), tempTemplateId);
			} catch (Exception e) {
				if (template == null) {
					try {
						template = JournalTemplateLocalServiceUtil.getTemplate(groupId, tempTemplateId);
					} catch (Exception e1) {
						_log.error("Can't find template: templateId=" + tempTemplateId+", groupId=" + groupId + ".  " + e);
					}
				}
			}
				
		}
		
		int entryTTL = getCacheTTLFromCustomFields(template);
		Integer defalutTTL = null;
		
		try {
			defalutTTL = ConfigurationPropertyLocalServiceUtil.getInteger(themeDisplay.getCompanyId(), groupId, CACHE_TTL_KEY);
		} catch (Exception e) {}
		
		if (defalutTTL == null)
			defalutTTL = DEFAULT_TTL_MINS;
		
		boolean isCacheEnabled = false;
		try {
			isCacheEnabled =
				ConfigurationPropertyLocalServiceUtil.getBoolean(
						themeDisplay.getCompanyId(),
						groupId,
						CACHE_ENABLED_KEY);
		} catch (Exception e) {}
		
		CacheManager<Entry, MyParms> cacheManager = getCacheManager(groupId);
		
		// Cache managers not initialized yet
		if (cacheManager == null) {
			synchronized(this) {
				if (cacheManager == null) {
					initSiteCaches();
				}

				cacheManager = getCacheManager(groupId);
			}
		}
		
		if (isCacheEnabled) 
			cacheManager.enableWorkers();
		else
			cacheManager.disableWorkers();
		
		String key = null;
		String sessionId = this.getSessionId(requestElement);

		String cacheLevel = getCacheLevelFromCustomFields(template);
		
		if (CACHE_LEVEL_PAGE.equals(cacheLevel)) {
			key = encodeKeyElement(
				groupId, articleId, version, templateId, layoutSetId, viewMode,
				languageId, page, secure, isVirtualHost(requestElement), 
				layout.getUuid(), xmlRequest, requestElement, articleModifyTime);
		}  else if (CACHE_LEVEL_REQUEST_PARAMETER.equals(cacheLevel)) {
			String requestParameters = getRequestParametersFromCustomFields(template, cacheLevel);
			
			key = encodeKey(groupId, articleId, version, templateId, null,
					layoutSetId, viewMode, languageId, page, secure,
					isVirtualHost(requestElement), requestElement,
					requestParameters, articleModifyTime);
		} else if (CACHE_LEVEL_PAGE_WITH_REQUEST_PARAMETER.equals(cacheLevel)) {
			// CMG-2336
			String pageRequestParameters = getRequestParametersFromCustomFields(template, cacheLevel);
			key = encodeKey(groupId, articleId, version, templateId, layout.getUuid(), layoutSetId, viewMode, languageId, page, secure, isVirtualHost(requestElement), requestElement, pageRequestParameters, articleModifyTime);
		} else {
			key = encodeKey(
				groupId, articleId, version, templateId, layoutSetId, viewMode,
				languageId, page, secure, isVirtualHost(requestElement), articleModifyTime);
		}

		/*
		 * ADKS-16 to make sure service context is available when this process
		 * is called from back-end worker thread.
		 */
		ServiceContext serviceContext = ServiceContextThreadLocal.getServiceContext();
		/*
		 * since attributes in request will be cleared, need to rebuild
		 * request attributes map and put it into service context
		 */
		this.processRequestAttributesForServiceContext(serviceContext);
		
		String structureId = (article != null) ? article.getStructureId() : "";
		
		/*
		/* NXSSOL-215: make sure the cache different from source page module when render the share by Reference page
		*/
		if (themeDisplay != null && themeDisplay.getScopeGroupId() != 0 && groupId != themeDisplay.getScopeGroupId()) {
			key = key + "_" + themeDisplay.getScopeGroupId();
		}

		MyParms parms = new MyParms(
				key, groupId, articleId, templateId, structureId,
				viewMode, languageId, page,
				xmlRequest, requestDocument, themeDisplay, sessionId, entryTTL, serviceContext);


		if (_log.isDebugEnabled()) {
			_log.debug("Check cache for " + parms.getDescription());
		}

//		boolean lifecycleRender = isLifecycleRender(themeDisplay, xmlRequest);
//		_log.debug(parms.getDescription() + " lifecycleRender = " + (lifecycleRender ? "true" : "false"));
		
		boolean isDisableRemoveCache = GetterUtil.get(getAttributeValue(requestElement, RenderConstants.DISABLE_REMOVE_CACHE),false); //EMDA-4990
        if (!isLifecycleRender(themeDisplay, xmlRequest) && !isDisableRemoveCache)
			cacheManager.remove(parms);
		
		boolean disableCache = GetterUtil.getBoolean(PropsUtil.get(DISABLE_JOURNAL_CONTENT_CACHE));
		// LAKPLAT-293 use getNoCache if the template is not cacheable
		boolean templateCacheDisabled = template != null && !template.isCacheable();
		Entry cacheEntry = null;
		if(disableCache || templateCacheDisabled) {
			cacheEntry = cacheManager.getNoCache(parms);
		} else {
			cacheEntry = cacheManager.get(parms);
		}
		
		JournalArticleDisplay articleDisplay = null;
		
		if (cacheEntry != null) {
			if (cacheEntry.isCacheable()) {
				String masterKey = encodeMasterKey(groupId, articleId);
				PortalCache portalCache = getPortalCache(groupId);			
				
				List<String> keys = (List<String>) portalCache.get(masterKey);
				
				if (keys == null) {
					keys = new ArrayList<String>();
				}
				
				String cacheKey = parms.getClassLoaderUniqueKey();
				
				if (!keys.contains(cacheKey)) {
					keys.add(cacheKey);
				}
				
				portalCache.put(masterKey, keys);
			}
			
			articleDisplay = cacheEntry.getValue();
			

			Boolean enableJavascriptWriter = Boolean.FALSE;
			try {
				enableJavascriptWriter = ConfigurationPropertyLocalServiceUtil.getBoolean(themeDisplay.getCompanyId(),
						groupId, RenderConstants.ENABLE_JAVASCRIPT_WRITER);
			} catch (Exception e) {
				// do nothing
			}
			if (Boolean.TRUE.equals(enableJavascriptWriter)) {
				if (Validator.isNotNull(sessionId)) {
					for (String link : cacheEntry.getLinks()) {
						if (_log.isDebugEnabled()) {
							_log.debug("Session ID: " + sessionId + ", link '" + link + "'");
						}
						JavascriptWriter.addLink(sessionId, link);
					}
					for (String script : cacheEntry.getScripts()) {
						if (_log.isDebugEnabled()) {
							_log.debug("Session ID: " + sessionId + " add script");
						}
						JavascriptWriter.addScript(sessionId, script);
					}
					
					if (cacheEntry.getRefreshRate() > 0)
						JavascriptWriter.setRefresh(sessionId, cacheEntry.getRefreshRate());
				}
				else {
					_log.debug("No session ID - cannot process cached javascript");
				}
			}
		}

		if (_log.isDebugEnabled()) {
			_log.debug(
				"getDisplay for " + parms.getDescription() + " takes " + stopWatch.getTime() + " ms");
		}


		return articleDisplay;
	}

	public JournalArticleDisplay getDisplay(
		long groupId, String articleId, double version, String templateId,
		String viewMode, String languageId, ThemeDisplay themeDisplay, int page,
		String xmlRequest) {
		
		return getDisplay(
				groupId, articleId, version, templateId,
				viewMode, languageId, themeDisplay, page,
				xmlRequest, null);
	}
	
	private int getCacheTTLFromCustomFields(JournalTemplate template){
		int cacheTTL = 0;
		ExpandoValue cacheTTLExpando =  this.getTemplateCustomFieldsValue(template, CustomFieldDataConstants.TEMPLATE_CACHE_TTL);
		if (cacheTTLExpando != null) {
			try {
				cacheTTL = Integer.parseInt(cacheTTLExpando.getData());
			} catch (NumberFormatException e) {}
		}
		return cacheTTL;
	}
	
	private String getCacheLevelFromCustomFields(JournalTemplate template){
		String cacheLevel = "";
		ExpandoValue cacheTTLExpando =  this.getTemplateCustomFieldsValue(template, CustomFieldDataConstants.TEMPLATE_CACHE_LEVEL);
		if (cacheTTLExpando != null) {
			cacheLevel = cacheTTLExpando.getData();
		}
		return cacheLevel;
	}
	
	private String getRequestParametersFromCustomFields(JournalTemplate template, String cacheLevel){
		String requestParameters = "";
		String parameterFieldName = "";

		// CMG-2336
		if (CACHE_LEVEL_REQUEST_PARAMETER.equals(cacheLevel)) {
			parameterFieldName = CustomFieldDataConstants.REQUEST_PARAMETERS;
		} else if (CACHE_LEVEL_PAGE_WITH_REQUEST_PARAMETER.equals(cacheLevel)) {
			parameterFieldName = CustomFieldDataConstants.PAGE_REQUEST_PARAMETERS;
		} else {
			return "";
		}

		ExpandoValue requestParametersExpando =  this.getTemplateCustomFieldsValue(template, parameterFieldName);
		if (requestParametersExpando != null) {
			requestParameters = requestParametersExpando.getData();
		}
		return requestParameters;
	}
	
	private ExpandoValue getTemplateCustomFieldsValue(JournalTemplate template, String customField){
		ExpandoValue  expandoValue = null;
		
		try {
			if (template != null) {
				expandoValue = ExpandoValueLocalServiceUtil.getValue(template.getCompanyId(), JournalTemplate.class.getName(), 
					ExpandoTableConstants.DEFAULT_TABLE_NAME, customField, template.getId());
			}
		} catch (Exception e) {}
		
		return expandoValue;
	}

	public JournalArticleDisplay getDisplay(
		long groupId, String articleId, String viewMode, String languageId,
		String xmlRequest) {

		return getDisplay(
			groupId, articleId, null, viewMode, languageId, null, 1,
			xmlRequest);
	}

	public JournalArticleDisplay getDisplay(
		long groupId, String articleId, String templateId, String viewMode,
		String languageId, String xmlRequest) {

		return getDisplay(
			groupId, articleId, templateId, viewMode, languageId, null, 1,
			xmlRequest);
	}

	public JournalArticleDisplay getDisplay(
		long groupId, String articleId, String templateId, String viewMode,
		String languageId, ThemeDisplay themeDisplay) {

		return getDisplay(
			groupId, articleId, templateId, viewMode, languageId, themeDisplay,
			1, null);
	}

	public JournalArticleDisplay getDisplay(
		long groupId, String articleId, String templateId, String viewMode,
		String languageId, ThemeDisplay themeDisplay, int page,
		String xmlRequest) {

		return getDisplay(
			groupId, articleId, 0, templateId, viewMode, languageId,
			themeDisplay, 1, xmlRequest);
	}

	public JournalArticleDisplay getDisplay(
		long groupId, String articleId, String viewMode, String languageId,
		ThemeDisplay themeDisplay) {

		return getDisplay(
			groupId, articleId, viewMode, languageId, themeDisplay, 1);
	}

	public JournalArticleDisplay getDisplay(
		long groupId, String articleId, String viewMode, String languageId,
		ThemeDisplay themeDisplay, int page) {

		return getDisplay(
			groupId, articleId, null, viewMode, languageId, themeDisplay, page,
			null);
	}
	
	protected String encodeKey(
		long groupId, String articleId, double version, String templateId,
		long layoutSetId, String viewMode, String languageId, int page,
		boolean secure, boolean virtualHost, Long modifyTimeMillis) {

		return encodeKey(groupId, articleId, version, templateId, layoutSetId, 
				viewMode, languageId, page, secure, virtualHost, "", null, modifyTimeMillis);
	}
	
	protected String encodeKey(long groupId, String articleId, double version,
			String templateId, String layoutUuid, long layoutSetId, String viewMode,
			String languageId, int page, boolean secure, boolean virtualHost,
			Element requestElement, String requestParameters, Long modifyTimeMillis) {

		StringBuilder key = new StringBuilder(encodeKeyElement(groupId,
				articleId, version, templateId, layoutSetId, viewMode,
				languageId, page, secure, virtualHost, layoutUuid, null,
				requestElement, modifyTimeMillis));

		if (StringUtils.isNotBlank(requestParameters)) {
			String[] parameters = StringUtils.split(requestParameters, ",");
			for (String parameter : parameters) {
				parameter = StringUtils.trim(parameter);
				key.append("_"+ parameter + "_" + getParameterValue(requestElement, parameter));
			}
		}

		return key.toString();
	}
	
	
	protected String encodeKey(
			long groupId, String articleId, double version, String templateId,
			long layoutSetId, String viewMode, String languageId, int page,
			boolean secure, boolean virtualHost, String layoutUuid,
			String xmlRequest, Long modifyTimeMillis) {
		return encodeKeyElement(
				groupId, articleId, version, templateId,
				layoutSetId, viewMode, languageId, page,
				secure, virtualHost, layoutUuid, xmlRequest,
				null, modifyTimeMillis);
	}
	
	protected String encodeKeyElement(
			long groupId, String articleId, double version, String templateId,
			long layoutSetId, String viewMode, String languageId, int page,
			boolean secure, boolean virtualHost, String layoutUuid, String xmlRequest,
			Element requestElement, Long modifyTimeMillis) {

		StringBundler sb = new StringBundler();
		
		sb.append(StringUtil.toHexString(groupId));
		sb.append("_");
		sb.append(articleId);
		sb.append("_");
		sb.append(version);
		sb.append("_");
		sb.append(templateId);
		
		if (modifyTimeMillis != null) {
			sb.append("_");
			sb.append(modifyTimeMillis.toString());
		}

		if (layoutSetId > 0) {
			sb.append("_");
			sb.append(StringUtil.toHexString(layoutSetId));
		}

		if (Validator.isNotNull(viewMode)) {
			sb.append("_");
			sb.append(viewMode);
		}

		if (Validator.isNotNull(languageId)) {
			sb.append("_");
			sb.append(languageId);
		}

		if (page > 0) {
			sb.append("_");
			sb.append(StringUtil.toHexString(page));
		}

		sb.append("_");
		sb.append(secure);
		
		sb.append("_");
		sb.append(virtualHost);
		
		if (Validator.isNotNull(layoutUuid)) {
			sb.append("_");
			sb.append(layoutUuid);			
		}
		
		
		if(requestElement == null  && Validator.isNotNull(xmlRequest)) {
			Document doc = null;
			try {
				doc = SAXReaderUtil.read(xmlRequest);
				if(doc != null) {
					requestElement = doc.getRootElement();
				}
			} catch (DocumentException e) {
				_log.warn("Unable to parse xmlRequest. " + e);
			}
		}
		
		if (requestElement != null) {
			
			String storyId = requestElement.valueOf("attributes/attribute[name='articleId']/value");
			String storyVersion = requestElement.valueOf("attributes/attribute[name='storyArticleVersion']/value");
			if (Validator.isNotNull(storyId) && Validator.isNotNull(storyVersion)) {
				sb.append("_");
				sb.append(storyId);
				sb.append("_");
				sb.append(storyVersion);
			}
			
//			try {
				
//				List<Element> attributes = requestElement.element("attributes").elements("attribute");
//				
//				for (Element attribute : attributes) {
//					String name = GetterUtil.getString(
//							attribute.element("name").getData());
//					
//					if (name.equals("article")) {
//						String data = GetterUtil.getString(
//								attribute.element("value").getData());
//						
////						JSONObject obj = JSONFactoryUtil.createJSONObject(data);
////						String storyId = obj.getString("articleId");
////						String storyVersion = obj.getString("version");
//						
//						//TODO: Hacking to get it working
//						int i = data.indexOf("articleId");
//						String storyId = data.substring(i, data.indexOf(",", i));
//						i = data.indexOf("version");
//						String storyVersion = data.substring(i, data.indexOf(",", i));
//						
//						if (Validator.isNotNull(storyId) && Validator.isNotNull(storyVersion)) {
//							sb.append("_STORY_ID_SEPARATOR_");
//							sb.append(storyId);
//							sb.append("_STORY_VERSION_SEPARATOR_");
//							sb.append(storyVersion);
//						}
//					}
//				}
//			
//			} catch (Exception e) {
//				//TODO : log
//			}
		}
		
		return sb.toString();	
	}
	
	protected String encodeMasterKey(long groupId, String articleId) {
		StringBundler sb = new StringBundler();

		sb.append(StringUtil.toHexString(groupId));
		sb.append(ARTICLE_SEPARATOR);
		sb.append(articleId);
		
		return sb.toString();
	}
	
	protected JournalArticleDisplay getArticleDisplay(
		long groupId, String articleId, String templateId, String viewMode,
		String languageId, int page, String xmlRequest,
		ThemeDisplay themeDisplay) {

		try {
			if (_log.isDebugEnabled()) {
				_log.debug(
					"Get article display {" + groupId + ", " + articleId +
						", " + templateId + "}");
			}

			return JournalArticleLocalServiceUtil.getArticleDisplay(
				groupId, articleId, templateId, viewMode, languageId, page,
				xmlRequest, themeDisplay);
		}
		catch (Exception e) {
			if (_log.isWarnEnabled()) {
				_log.warn(
					"Unable to get display for " + groupId + " " +
						articleId + " " + languageId + ". " + e);
			}

			return null;
		}
	}

	protected boolean isLifecycleRender(
		ThemeDisplay themeDisplay, String xmlRequest) {

		if (themeDisplay != null) {
			return themeDisplay.isLifecycleRender();
		}
		else if (Validator.isNotNull(xmlRequest)) {
			Matcher matcher = lifecycleRenderPhasePattern.matcher(xmlRequest);

			return matcher.find();
		}
		else {
			return false;
		}
	}
	
//	private String getSessionId(String xmlRequest) {
//		
//		long freeBefore = Runtime.getRuntime().freeMemory();
//		String sessionId = StringPool.BLANK;
//
//		try {
//			Document document = SAXReaderUtil.read(xmlRequest);
//			Element rootElement = document.getRootElement();
//			Element portletSessionId = rootElement.element("portlet-session-id");
//			sessionId = portletSessionId.getText();
//		} catch (Exception e) {
//			_log.warn("Unable to retrieve session ID from xmlRequest. " + e);
////			_log.debug("xmlRequest = \n" + xmlRequest);
//		}
//		
//		long freeAfter = Runtime.getRuntime().freeMemory();
//		float memUsed = ((float)freeBefore - (float)freeAfter)/1024f/1024f;
//		
//		_log.debug("getSessionId Mem stats: " + memUsed);
//		return sessionId;
//	}
	
	private String getSessionId(Element request) {
		
		String sessionId = StringPool.BLANK;

		try {
			if(request != null) {
				Element portletSessionId = request.element("portlet-session-id");
				sessionId = portletSessionId.getText();
			}
		} catch (Exception e) {
			_log.warn("Unable to retrieve session ID from xmlRequest. " + e);
//			_log.debug("xmlRequest = \n" + xmlRequest);
		}
		
		return sessionId;
	}
	
	private String getParameterValue(Element request, String key) {
		String value = StringPool.BLANK;

		try {
			if (request != null && StringUtils.isNotBlank(key)) {
				String path = "parameters/parameter[name='" + key + "']/value";
				value = request.valueOf(path);
				
				/*
				 *  for original request parameter
				 *  if can not get it from parameters, then get it from attributes
				 *  @see WebContentHandler.java
				 */
				if(StringUtils.isBlank(value)) {
					path = "attributes/attribute[name='" + "param_" + key + "']/value";
					value = request.valueOf(path);
				}
			}
		} catch (Exception e) {
			_log.warn("Unable to retrieve" + key + " from xmlRequest. " + e);
		}

		return value;
	}
	
	/**
    * @Title: getAttributeValue
    * @Description: if you do: request.setAttribute(key, value),then use this method can get the value.
    * @param request
    * @param key
    * @return
    * @return String
    */ 
    private String getAttributeValue(Element request, String key) {
        String value = StringPool.BLANK;

        try {
            if (request != null && StringUtils.isNotBlank(key)) {
                String path = "attributes/attribute[name='" + key + "']/value";
                value = request.valueOf(path);
            }
        } catch (Exception e) {
            _log.warn("Unable to retrieve" + key + " from xmlRequest. " + e);
        }

        return value;
    }
	
	private boolean isVirtualHost(String xmlRequest) {
		try {
			Document document = SAXReaderUtil.read(xmlRequest);
			Element rootElement = document.getRootElement();
			Element attributes = rootElement.element("attributes");
			String friendlyUrl = "";
			String currentUrl = "";
			if (attributes != null) {
				for (Element attribute: attributes.elements()) {
					if ("FRIENDLY_URL".equals(attribute.element("name").getText()))
						friendlyUrl = attribute.element("value").getText();
					else if ("CURRENT_URL".equals(attribute.element("name").getText()))
						currentUrl = attribute.element("value").getText();
				}
			}
			return friendlyUrl.equals(currentUrl);
		} catch (Exception e) {
			_log.warn("Unable to parse xmlRequest. " + e);
		}
		return false;
	}
	
	private boolean isVirtualHost(Element rootElement) {
		try {
			if(rootElement != null) {
				Element attributes = rootElement.element("attributes");
				String friendlyUrl = "";
				String currentUrl = "";
				if (attributes != null) {
					for (Element attribute: attributes.elements()) {
						//if ("FRIENDLY_URL".equals(attribute.element("name").getText()))
						if ("friendlyURL".equals(attribute.element("name").getText())) //EMDA-5238
							friendlyUrl = attribute.element("value").getText();
						else if ("CURRENT_URL".equals(attribute.element("name").getText()))
							currentUrl = attribute.element("value").getText();
					}
				}
				return !currentUrl.startsWith("/web".concat(friendlyUrl));
			}
		} catch (Exception e) {
			_log.warn("Unable to parse xmlRequest. " + e);
		}
		return false;
	}

	/**
	 * Rebuild request attributes map and put it into service context
	 */
	private void processRequestAttributesForServiceContext(ServiceContext context) {
		if (context == null) {
			if (_log.isInfoEnabled()) {
				_log.info("Service Context is null");
			}
			return;
		}
		
		// to avoid possible ConcurrentModificationException FOX-887, do not copy the attributes when called from cache worker thread
		// the requestAttributes should already be set in the ServiceContext.  However, take a chance if  request attributes is not set
		if (!Thread.currentThread().getName().startsWith(CacheManager.THREAD_PREFIX) || context.getAttribute("requestAttributes") == null) {
			if (_log.isDebugEnabled()) {
				if (context.getAttribute("requestAttributes") == null) {
					_log.debug("requestAttributes missing for "+Thread.currentThread().getName());
				}
			}
			
			HttpServletRequest request = context.getRequest();
			if (request != null) {
				Enumeration<String> names = request.getAttributeNames();
				HashMap<String, Object> requestAttributes = new HashMap<String, Object>();
				while (names.hasMoreElements()) {
					String name = names.nextElement();
					requestAttributes.put(name, request.getAttribute(name));
				}
				context.setAttribute("requestAttributes", requestAttributes);
			}
		}
		else {
			if (_log.isDebugEnabled()) {
				_log.debug("Skipping attributes copy for "+Thread.currentThread().getName());
			}
		}
	}
	
	private static class Entry extends EntryType implements Serializable {
		private JournalArticleDisplay value;
		private Collection<String> links;
		private Collection<String> scripts;
		private int refreshRate = 0;
		
		public Entry(
				JournalArticleDisplay value, 
				Collection<String> links, 
				Collection<String> scripts, 
				int refreshRate) {
			
			if (_log.isDebugEnabled()) {
				_log.debug("Create a cache entry with " + links.size() + " links, " + scripts.size() + " scripts and a page refresh of " + refreshRate);
			}
			
			this.value = value;
			this.links = links;
			this.scripts = scripts;
			this.refreshRate = refreshRate;
		}
		
		public JournalArticleDisplay getValue() {
			return this.value;
		}
		
		public Collection<String> getLinks() {
			return this.links;
		}
		
		public Collection<String> getScripts() {
			return this.scripts;
		}
		
		public int getRefreshRate() {
			return this.refreshRate;
		}
		
		@Override
		public boolean isCacheable() {
			if (this.value == null) {
				return true;
			}
			
			return this.value.isCacheable();
		}
		
	}
	
	private static class MyParms extends Parms  {
		public MyParms(String key, long groupId, String articleId, String templateId, String structureId,
				String viewMode, String languageId, int page,
				String xmlRequest, Document document, ThemeDisplay themeDisplay,
				String sessionID, int entryTTL, ServiceContext serviceContext) {
			this.key = key;
			this.groupId = groupId;
			this.articleId = articleId;
			this.templateId = templateId;
			this.structureId = structureId;
			this.viewMode = viewMode;
			this.languageId = languageId;
			this.page = page;
			this.xmlRequest = xmlRequest;
			this.themeDisplay = themeDisplay;
			this.sessionID = sessionID;
			this.entryTTL = entryTTL;
			this.document = document;
			this.serviceContext = serviceContext;
		}

		/**
		 * @return the key
		 */
		public String getKey() {
			// ENG-375
			if (key != null) {
				try {
					MessageDigest md = MessageDigest.getInstance("SHA1");
					return Hex.encodeHexString(md.digest(key.getBytes("UTF-8")));
				} catch (Exception e) {
					_log.warn("Unable to hash key for cache.", e);
				}
			}
			return key;
		}

		@Override
		public long getCompanyId() {
			return this.themeDisplay.getCompanyId();
		}

		/**
		 * @return the groupId
		 */
		public long getGroupId() {
			return groupId;
		}

		@Override
		public String getDescription() {
			return StringPool.OPEN_CURLY_BRACE + 
				   this.groupId + StringPool.COMMA + StringPool.SPACE + 
				   this.articleId + StringPool.COMMA + StringPool.SPACE + 
				   this.entryTTL + StringPool.COMMA + StringPool.SPACE + 
				   this.languageId + StringPool.CLOSE_CURLY_BRACE;
		}
		/**
		 * @return the articleId
		 */
		public String getArticleId() {
			return articleId;
		}

		/**
		 * @return the templateId
		 */
		public String getTemplateId() {
			return templateId;
		}
		
		@Override
		public String getStructureId() {
			return structureId;
		}

		/**
		 * @return the viewMode
		 */
		public String getViewMode() {
			return viewMode;
		}

		/**
		 * @return the languageId
		 */
		public String getLanguageId() {
			return languageId;
		}

		/**
		 * @return the page
		 */
		public int getPage() {
			return page;
		}

		/**
		 * @return the xmlRequest
		 */
		public String getXmlRequest() {
			return xmlRequest;
		}

		/**
		 * @return the themeDisplay
		 */
		public ThemeDisplay getThemeDisplay() {
			return themeDisplay;
		}

		/**
		 * @return the sessionID
		 */
		public String getSessionID() {
			return sessionID;
		}

		
		public int getEntryTTL() {
		
			return entryTTL;
		}
		

		public Document getDocument() {
			return document;
		}

		public void setDocument(Document document) {
			this.document = document;
		}

		public ServiceContext getServiceContext() {
			return serviceContext;
		}

		private String key;
		private long groupId;
		private String articleId;
		private String templateId;
		private String structureId;
		private String viewMode;
		private String languageId;
		private int page;
		private String xmlRequest;
		private ThemeDisplay themeDisplay;
		private String sessionID;
		private int entryTTL;
		private Document document;
		private ServiceContext serviceContext;
	}
	
	private static class MyWorker implements com.endplay.portal.util.CacheManager.Worker {
		
		public Entry process(Parms parms) {
			Entry entry = null;
			MyParms myParms = (MyParms) parms;

			/*
			 * ADKS-16 to make sure service context is available when this process
			 * is called from back-end worker thread
			 */
			ServiceContext serviceContext = ServiceContextThreadLocal.getServiceContext();
			ServiceContext parmServiceContext = myParms.getServiceContext();
			if (serviceContext == null && parmServiceContext != null) {
				ServiceContextThreadLocal.pushServiceContext(parmServiceContext);
			}

			Boolean enableJavascriptWriter = Boolean.FALSE;
			try {
				enableJavascriptWriter = ConfigurationPropertyLocalServiceUtil.getBoolean(myParms.getCompanyId(),
						myParms.getGroupId(), RenderConstants.ENABLE_JAVASCRIPT_WRITER);
			} catch (Exception e) {
				// do nothing
			}

			if (Boolean.TRUE.equals(enableJavascriptWriter)) {
				JavascriptWriter.startTransientStorage(myParms.getSessionID());
				try {
					JournalArticleDisplay articleDisplay = 
							EPJournalDisplayUtil.getArticleDisplay(
									myParms.getGroupId(), 
									myParms.getArticleId(), 
									myParms.getTemplateId(), 
									myParms.getViewMode(), 
									myParms.getLanguageId(), 
									myParms.getPage(),
									myParms.getXmlRequest(), 
									myParms.getDocument(),
									myParms.getThemeDisplay());
					
					entry = new Entry(
							articleDisplay,
							JavascriptWriter.getTransientLinks(myParms.getSessionID()),
							JavascriptWriter.getTransientScripts(myParms.getSessionID()),
							JavascriptWriter.getTransientRefresh(myParms.getSessionID()));
				} catch (Throwable e) {
					if (e.getCause() != null) {
						e = e.getCause();
					}
					
					if (_log.isWarnEnabled()) {
						_log.warn("Unable to get display for " + parms.getDescription(), e);
					}
					else {
						_log.error("Unable to get display for " + parms.getDescription()+ ". " + e);
					}
				} finally {
					JavascriptWriter.stopTransientStorage(myParms.getSessionID());
				}
			} else {
				try {
					JournalArticleDisplay articleDisplay = 
							EPJournalDisplayUtil.getArticleDisplay(
									myParms.getGroupId(), 
									myParms.getArticleId(), 
									myParms.getTemplateId(), 
									myParms.getViewMode(), 
									myParms.getLanguageId(), 
									myParms.getPage(),
									myParms.getXmlRequest(), 
									myParms.getDocument(),
									myParms.getThemeDisplay());
					
					entry = new Entry(articleDisplay, null, null, 0);
				} catch (Throwable e) {
					if (e.getCause() != null) {
						e = e.getCause();
				}
					
					if (_log.isWarnEnabled()) {
						_log.warn("Unable to get display for " + parms.getDescription(), e);
					}
					else {
						_log.error("Unable to get display for " + parms.getDescription()+ ". " + e);
					}
				}
			}
			
			return entry;
		}
	}
	
	private static class MyWorkerFactory implements CacheManager.WorkerFactory {
		public CacheManager.Worker getWorker() {
			return new MyWorker();
		}
	}
	
	private final static String DISABLE_JOURNAL_CONTENT_CACHE = "disable_journal_content_cache";
	private final static String CLEAR_ALL_CACHE_ENABLED_KEY = "journal_article_clear_all_cache_enabled";
	private final static String CACHE_ENABLED_KEY = "journal_article_cache_enabled";
	private final static String CACHE_TTL_KEY = "journal_article_cache_ttl";
	
	private final static int NUM_WORKERS = 1;
	private final static Integer DEFAULT_TTL_MINS = 5;

	private final static String CACHE_LEVEL_PAGE = "page"; 
//	private final static String CACHE_LEVEL_GENERAL = "general"; 
	private final static String CACHE_LEVEL_REQUEST_PARAMETER = "RequestParameters";
	private final static String CACHE_LEVEL_PAGE_WITH_REQUEST_PARAMETER = "PageWithRequestParameters";
//	private final static String NAVIGATION_SUPNAV = "NAVIGATION_SUPNAV";
//	private static WorkerThread[] workers = null;
//	private static BlockingQueue<Parms> requestQueue = new ArrayBlockingQueue<Parms>(1000);
//	private static boolean queueEnabled = false;
//	private static Thread shutdownHook = null;

	protected static Pattern lifecycleRenderPhasePattern = Pattern.compile(
		"<lifecycle>\\s*RENDER_PHASE\\s*</lifecycle>");
	
	private static Log _log = LogFactoryUtil.getLog(JournalContentImpl.class);

	protected static final String CACHE_NAME = JournalContent.class.getSimpleName();
	protected static CacheManager<Entry, MyParms> defaultCacheManager = null;
	protected static Map<Long, CacheManager<Entry, MyParms>> cacheManagerMap = new HashMap<Long, CacheManager<Entry, MyParms>>();

	protected static PortalCache defaultPortalCache = null;
	protected static Map<Long, PortalCache> portalCacheMap = new HashMap<Long, PortalCache>();

	protected void initSiteCaches() {
		try {
			Map<String, Object> cacheProperties = new HashMap<String, Object>();
			cacheProperties.put(ExternalCacheUtil.CACHE_TARGETED_KEY, ExternalCacheUtil.CACHE_TARGETED_MODULE);
			
			List<Company> companies = CompanyLocalServiceUtil.getCompanies();
			
			for (Company company : companies) {
				List<Organization> orgs = OrganizationLocalServiceUtil.getOrganizations(company.getCompanyId(), OrganizationConstants.ANY_PARENT_ORGANIZATION_ID, QueryUtil.ALL_POS, QueryUtil.ALL_POS);
				
				List<Group> groups = GroupLocalServiceUtil.getOrganizationsGroups(orgs);
				
				// create cache for each group
				for (Group group : groups) {
					// don't create cache if Organization Group doesn't have staging group
					if (!group.hasStagingGroup()) {
						continue;
					}
					
					String cacheName = CACHE_NAME+group.getFriendlyURL();
					
					Integer defaultTTL = null;
					
					try {
						defaultTTL = ConfigurationPropertyLocalServiceUtil.getInteger(company.getCompanyId(), group.getGroupId(), CACHE_TTL_KEY);
					} catch (Exception e) {}
					
					if (defaultTTL == null)
						defaultTTL = DEFAULT_TTL_MINS;
					
					CacheManager cacheManager = 
							new CacheManager<Entry, MyParms>(
									cacheName, 
									defaultTTL, 
									NUM_WORKERS, 
									new MyWorkerFactory());

					cacheManagerMap.put(group.getGroupId(), cacheManager);
					
					String cacheNameForMasterKey = CACHE_NAME+"/MASTERKEY"+group.getFriendlyURL();
					Map<String, Object> cachePropertiesForMasterKey = new HashMap<String, Object>();
					cacheProperties.put(ExternalCacheUtil.CACHE_TARGETED_KEY, ExternalCacheUtil.CACHE_TARGETED_MASTER_KEY);
					
					PortalCache portalCache = EPSwitchableCachePoolLocalServiceUtil.getCache(cacheNameForMasterKey, cachePropertiesForMasterKey);
					portalCacheMap.put(group.getGroupId(), portalCache);

					// map staging group to the same CacheManager
					if (group.getStagingGroup() != null) {
						cacheManagerMap.put(group.getStagingGroup().getGroupId(), cacheManager);
						portalCacheMap.put(group.getStagingGroup().getGroupId(), portalCache);
					}
				}
			}
			
			// default cache manager
			// where to set the default TTL?
			{
				int defaultTTL = DEFAULT_TTL_MINS;
				
				defaultCacheManager =
						new CacheManager<Entry, MyParms>(
								CACHE_NAME, 
								defaultTTL, 
								NUM_WORKERS, 
								new MyWorkerFactory());
				
				defaultPortalCache = EPSwitchableCachePoolLocalServiceUtil.getCache(CACHE_NAME, cacheProperties);				
			}
		}
		catch (Exception ex) {
			_log.error(ex);
		}
	}
	
	private CacheManager<Entry, MyParms> getCacheManager(long groupId) {
		CacheManager<Entry, MyParms> cacheManager = cacheManagerMap.get(groupId);
		
		if (cacheManager != null) {
			return cacheManager;
		}
		else {
			return defaultCacheManager;
		}
	}

	private PortalCache getPortalCache(long groupId) {
		PortalCache portalCache = portalCacheMap.get(groupId);
		
		if (portalCache != null) {
			return portalCache;
		}
		else {
			return defaultPortalCache;
		}
	}
}