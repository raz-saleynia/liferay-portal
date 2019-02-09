/**
 * Copyright (c) 2000-2013 Liferay, Inc. All rights reserved.
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

package com.liferay.portal.lar;

import com.endplay.portal.kernel.lar.ExtPortletDataHandlerKeys;
import com.endplay.portal.lar.SiteReplicationImportUtil;
import com.endplay.portal.lar.SiteReplicationUtil;
import com.endplay.portlet.asset.EPCategoryEnum;
import com.endplay.portlet.journal.NoSuchGlobalAssetCategoryException;
import com.endplay.portlet.journal.service.JournalArticleCategoryLocalServiceUtil;
import com.liferay.portal.*;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.language.LanguageUtil;
import com.liferay.portal.kernel.lar.*;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.search.Indexer;
import com.liferay.portal.kernel.search.IndexerRegistryUtil;
import com.liferay.portal.kernel.util.*;
import com.liferay.portal.kernel.xml.*;
import com.liferay.portal.kernel.zip.ZipReader;
import com.liferay.portal.kernel.zip.ZipReaderFactoryUtil;
import com.liferay.portal.model.*;
import com.liferay.portal.security.permission.PermissionCacheUtil;
import com.liferay.portal.service.*;
import com.liferay.portal.service.persistence.PortletPreferencesUtil;
import com.liferay.portal.service.persistence.UserUtil;
import com.liferay.portal.servlet.filters.cache.CacheUtil;
import com.liferay.portal.util.PortalUtil;
import com.liferay.portal.util.PortletKeys;
import com.liferay.portal.util.PropsValues;
import com.liferay.portlet.PortletPreferencesFactoryUtil;
import com.liferay.portlet.PortletPreferencesImpl;
import com.liferay.portlet.asset.NoSuchCategoryException;
import com.liferay.portlet.asset.NoSuchEntryException;
import com.liferay.portlet.asset.NoSuchTagException;
import com.liferay.portlet.asset.model.*;
import com.liferay.portlet.asset.service.*;
import com.liferay.portlet.asset.service.persistence.AssetCategoryUtil;
import com.liferay.portlet.asset.service.persistence.AssetTagUtil;
import com.liferay.portlet.asset.service.persistence.AssetVocabularyUtil;
import com.liferay.portlet.assetpublisher.util.AssetPublisherUtil;
import com.liferay.portlet.documentlibrary.model.DLFileEntry;
import com.liferay.portlet.documentlibrary.model.DLFileEntryType;
import com.liferay.portlet.documentlibrary.service.persistence.DLFileEntryTypeUtil;
import com.liferay.portlet.expando.NoSuchTableException;
import com.liferay.portlet.expando.model.ExpandoColumn;
import com.liferay.portlet.expando.model.ExpandoTable;
import com.liferay.portlet.expando.service.ExpandoColumnLocalServiceUtil;
import com.liferay.portlet.expando.service.ExpandoTableLocalServiceUtil;
import com.liferay.portlet.expando.util.ExpandoConverterUtil;
import com.liferay.portlet.journal.model.JournalArticle;
import com.liferay.portlet.journal.model.JournalStructure;
import com.liferay.portlet.journal.service.persistence.JournalStructureUtil;
import com.liferay.portlet.journalcontent.util.JournalContentUtil;
import com.liferay.portlet.messageboards.model.MBMessage;
import com.liferay.portlet.ratings.model.RatingsEntry;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.StopWatch;

import java.io.File;
import java.io.Serializable;
import java.util.*;

/**
 * @author Brian Wing Shun Chan
 * @author Joel Kozikowski
 * @author Charles May
 * @author Raymond Augé
 * @author Jorge Ferrer
 * @author Bruno Farache
 * @author Zsigmond Rab
 * @author Douglas Wong
 * @author Mate Thurzo
 */
public class PortletImporter {

	public void importPortletInfo(
			long userId, long plid, long groupId, String portletId,
			Map<String, String[]> parameterMap, File file)
			throws Exception {

		try {
			ImportExportThreadLocal.setPortletImportInProcess(true);

			doImportPortletInfo(
					userId, plid, groupId, portletId, parameterMap, file);
		}
		finally {
			ImportExportThreadLocal.setPortletImportInProcess(false);

			CacheUtil.clearCache();
			JournalContentUtil.clearCache();
			PermissionCacheUtil.clearCache();
		}
	}

	protected void deletePortletData(
			PortletDataContext portletDataContext, String portletId, long plid)
			throws Exception {

		long ownerId = PortletKeys.PREFS_OWNER_ID_DEFAULT;
		int ownerType = PortletKeys.PREFS_OWNER_TYPE_LAYOUT;

		PortletPreferences portletPreferences =
				PortletPreferencesUtil.fetchByO_O_P_P(
						ownerId, ownerType, plid, portletId);

		if (portletPreferences == null) {
			portletPreferences =
					new com.liferay.portal.model.impl.PortletPreferencesImpl();
		}

		String xml = deletePortletData(
				portletDataContext, portletId, portletPreferences);

		if (xml != null) {
			PortletPreferencesLocalServiceUtil.updatePreferences(
					ownerId, ownerType, plid, portletId, xml);
		}
	}

	protected String deletePortletData(
			PortletDataContext portletDataContext, String portletId,
			PortletPreferences portletPreferences)
			throws Exception {

		Portlet portlet = PortletLocalServiceUtil.getPortletById(
				portletDataContext.getCompanyId(), portletId);

		if (portlet == null) {
			if (_log.isDebugEnabled()) {
				_log.debug(
						"Do not delete portlet data for " + portletId +
								" because the portlet does not exist");
			}

			return null;
		}

		PortletDataHandler portletDataHandler =
				portlet.getPortletDataHandlerInstance();

		if (portletDataHandler == null) {
			if (_log.isDebugEnabled()) {
				_log.debug(
						"Do not delete portlet data for " + portletId +
								" because the portlet does not have a " +
								"PortletDataHandler");
			}

			return null;
		}

		if (_log.isDebugEnabled()) {
			_log.debug("Deleting data for " + portletId);
		}

		PortletPreferencesImpl portletPreferencesImpl =
				(PortletPreferencesImpl)
						PortletPreferencesFactoryUtil.fromDefaultXML(
								portletPreferences.getPreferences());

		try {
			portletPreferencesImpl =
					(PortletPreferencesImpl)portletDataHandler.deleteData(
							portletDataContext, portletId, portletPreferencesImpl);
		}
		finally {
			portletDataContext.setGroupId(portletDataContext.getScopeGroupId());
		}

		if (portletPreferencesImpl == null) {
			return null;
		}

		return PortletPreferencesFactoryUtil.toXML(portletPreferencesImpl);
	}

	protected void doImportPortletInfo(
			long userId, long plid, long groupId, String portletId,
			Map<String, String[]> parameterMap, File file)
			throws Exception {

		boolean deletePortletData = MapUtil.getBoolean(
				parameterMap, PortletDataHandlerKeys.DELETE_PORTLET_DATA);
		boolean importPermissions = MapUtil.getBoolean(
				parameterMap, PortletDataHandlerKeys.PERMISSIONS);
		boolean importUserPermissions = MapUtil.getBoolean(
				parameterMap, PortletDataHandlerKeys.PERMISSIONS);
		boolean importPortletData = MapUtil.getBoolean(
				parameterMap, PortletDataHandlerKeys.PORTLET_DATA);
		boolean importPortletArchivedSetups = MapUtil.getBoolean(
				parameterMap, PortletDataHandlerKeys.PORTLET_ARCHIVED_SETUPS);
		boolean importPortletSetup = MapUtil.getBoolean(
				parameterMap, PortletDataHandlerKeys.PORTLET_SETUP);
		boolean importPortletUserPreferences = MapUtil.getBoolean(
				parameterMap, PortletDataHandlerKeys.PORTLET_USER_PREFERENCES);
		String userIdStrategyString = MapUtil.getString(
				parameterMap, PortletDataHandlerKeys.USER_ID_STRATEGY);

		// ***************************************
		// EXT Override Start: Charles Im
		// ***************************************

		boolean importTags = MapUtil.getBoolean(
				parameterMap, ExtPortletDataHandlerKeys.ASSET_TAGS);

		if (_log.isDebugEnabled()) {
			_log.debug("Import tags " + importTags);
		}

		// ***************************************
		// EXT Override End
		// ***************************************

		StopWatch stopWatch = null;

		if (_log.isInfoEnabled()) {
			stopWatch = new StopWatch();

			stopWatch.start();
		}

		Layout layout = LayoutLocalServiceUtil.getLayout(plid);

		User user = UserUtil.findByPrimaryKey(userId);

		UserIdStrategy userIdStrategy = getUserIdStrategy(
				user, userIdStrategyString);

		ZipReader zipReader = ZipReaderFactoryUtil.getZipReader(file);

		PortletDataContext portletDataContext = new PortletDataContextImpl(
				layout.getCompanyId(), groupId, parameterMap, new HashSet<String>(),
				userIdStrategy, zipReader);

		portletDataContext.setPortetDataContextListener(
				new PortletDataContextListenerImpl(portletDataContext));

		portletDataContext.setPlid(plid);
		portletDataContext.setPrivateLayout(layout.isPrivateLayout());

		// Manifest

		String xml = portletDataContext.getZipEntryAsString("/manifest.xml");

		Element rootElement = null;

		try {
			Document document = SAXReaderUtil.read(xml);

			rootElement = document.getRootElement();
		}
		catch (Exception e) {
			throw new LARFileException("Unable to read /manifest.xml");
		}

		// Build compatibility

		Element headerElement = rootElement.element("header");

		int buildNumber = ReleaseInfo.getBuildNumber();

		int importBuildNumber = GetterUtil.getInteger(
				headerElement.attributeValue("build-number"));

		if (buildNumber != importBuildNumber) {
			throw new LayoutImportException(
					"LAR build number " + importBuildNumber + " does not match " +
							"portal build number " + buildNumber);
		}

		// Type compatibility

		String type = headerElement.attributeValue("type");

		if (!type.equals("portlet")) {
			throw new LARTypeException(
					"Invalid type of LAR file (" + type + ")");
		}

		// Portlet compatibility

		String rootPortletId = headerElement.attributeValue("root-portlet-id");

		if (!PortletConstants.getRootPortletId(portletId).equals(
				rootPortletId)) {

			throw new PortletIdException("Invalid portlet id " + rootPortletId);
		}

		// Available locales

		Portlet portlet = PortletLocalServiceUtil.getPortletById(
				portletDataContext.getCompanyId(), portletId);

		PortletDataHandler portletDataHandler =
				portlet.getPortletDataHandlerInstance();

		if ((portletDataHandler != null) &&
				portletDataHandler.isDataLocalized()) {

			Locale[] sourceAvailableLocales = LocaleUtil.fromLanguageIds(
					StringUtil.split(
							headerElement.attributeValue("available-locales")));

			Locale[] targetAvailableLocales =
					LanguageUtil.getAvailableLocales();

			for (Locale sourceAvailableLocale : sourceAvailableLocales) {
				if (!ArrayUtil.contains(
						targetAvailableLocales, sourceAvailableLocale)) {

					LocaleException le = new LocaleException();

					le.setSourceAvailableLocales(sourceAvailableLocales);
					le.setTargetAvailableLocales(targetAvailableLocales);

					throw le;
				}
			}
		}

		// Company group id

		long sourceCompanyGroupId = GetterUtil.getLong(
				headerElement.attributeValue("company-group-id"));

		portletDataContext.setSourceCompanyGroupId(sourceCompanyGroupId);

		// Group id

		long sourceGroupId = GetterUtil.getLong(
				headerElement.attributeValue("group-id"));

		portletDataContext.setSourceGroupId(sourceGroupId);

		// Read asset categories, asset tags, comments, locks, and ratings
		// entries to make them available to the data handlers through the
		// context

		if (importPermissions) {
			_permissionImporter.readPortletDataPermissions(portletDataContext);
		}

		readAssetCategories(portletDataContext);
		// ***************************************
		// EXT Override Start: Charles Im
		// ***************************************
		if (importTags) {
			readAssetTags(portletDataContext);
		}
		// ***************************************
		// EXT Override End
		// ***************************************
		readComments(portletDataContext);
		readExpandoTables(portletDataContext);
		readLocks(portletDataContext);
		readRatingsEntries(portletDataContext);

		// Delete portlet data

		if (_log.isDebugEnabled()) {
			_log.debug("Deleting portlet data");
		}

		if (deletePortletData) {
			deletePortletData(portletDataContext, portletId, plid);
		}

		Element portletElement = null;

		try {
			portletElement = rootElement.element("portlet");

			Document portletDocument = SAXReaderUtil.read(
					portletDataContext.getZipEntryAsString(
							portletElement.attributeValue("path")));

			portletElement = portletDocument.getRootElement();
		}
		catch (DocumentException de) {
			throw new SystemException(de);
		}

		setPortletScope(portletDataContext, portletElement);

		Element portletDataElement = portletElement.element("portlet-data");

		boolean importData = importPortletData && (portletDataElement != null);

		try {

			// Portlet preferences

			importPortletPreferences(
					portletDataContext, layout.getCompanyId(), groupId, layout,
					portletId, portletElement, importPortletSetup,
					importPortletArchivedSetups, importPortletUserPreferences, true,
					importData);

			// Portlet data

			if (importData) {
				if (_log.isDebugEnabled()) {
					_log.debug("Importing portlet data");
				}

				importPortletData(
						portletDataContext, portletId, plid, portletDataElement);
			}
		}
		finally {
			resetPortletScope(portletDataContext, groupId);
		}

		// Portlet permissions

		if (importPermissions) {
			if (_log.isDebugEnabled()) {
				_log.debug("Importing portlet permissions");
			}

			LayoutCache layoutCache = new LayoutCache();

			_permissionImporter.importPortletPermissions(
					layoutCache, layout.getCompanyId(), groupId, userId, layout,
					portletElement, portletId, importUserPermissions);

			if ((userId > 0) &&
					((PropsValues.PERMISSIONS_USER_CHECK_ALGORITHM == 5) ||
							(PropsValues.PERMISSIONS_USER_CHECK_ALGORITHM == 6))) {

				Indexer indexer = IndexerRegistryUtil.nullSafeGetIndexer(
						User.class);

				indexer.reindex(userId);
			}
		}

		// Asset links

		if (_log.isDebugEnabled()) {
			_log.debug("Importing asset links");
		}

		readAssetLinks(portletDataContext);

		if (_log.isInfoEnabled()) {
			_log.info("Importing portlet takes " + stopWatch.getTime() + " ms");
		}

		zipReader.close();
	}

	//******************************************
	// Copied and modified from 6.1.20 -- start
	//******************************************
	/**
	 * @see {@link DLPortletDataHandlerImpl#getFileEntryTypeName(String, long,
	 *      String, int)}
	 * @see {@link DLPortletDataHandlerImpl#getFolderName(String, long, long,
	 *      String, int)}
	 */
	protected String getAssetCategoryName(
			String uuid, long groupId, long parentCategoryId, String name,
			int count)
			throws Exception {

		String searchName = name;
		if (count > 0)
			searchName = StringUtil.appendParentheticalSuffix(name, count);

		List<AssetCategory> assetCategories =
				AssetCategoryUtil.findByP_N(parentCategoryId, searchName);

		if ((assetCategories == null) || (assetCategories.size() < 1)) {
			return name;
		}

		// See if one of them is on our group
		for (AssetCategory category : assetCategories) {
			if ((category.getGroupId() == groupId) &&
					(Validator.isNotNull(uuid) && uuid.equals(category.getUuid()))) {
				return searchName;
			}
		}

//		if (count > 0) {
//			name = StringUtil.appendParentheticalSuffix(name, count);
//		}

		return getAssetCategoryName(
				uuid, groupId, parentCategoryId, name, ++count);
	}
	//******************************************
	// Copied and modified from 6.1.20 -- end
	//******************************************

	protected String getAssetCategoryNameForSiteReplication(long parentCategoryId, String name, long vocabularyId,
															int count)
			throws Exception {

		AssetCategory assetCategory = AssetCategoryUtil.fetchByP_N_V(parentCategoryId, name, vocabularyId);

		if (assetCategory == null) {
			return name;
		}

		if (count > 0) {
			name = StringUtil.appendParentheticalSuffix(name, count);
		}

		return getAssetCategoryNameForSiteReplication(
				parentCategoryId, name, vocabularyId, ++count);
	}

	protected String getAssetCategoryPath(
			PortletDataContext portletDataContext, long assetCategoryId) {

		StringBundler sb = new StringBundler(6);

		sb.append(portletDataContext.getSourceRootPath());
		sb.append("/categories/");
		sb.append(assetCategoryId);
		sb.append(".xml");

		return sb.toString();
	}

	protected Map<Locale, String> getAssetCategoryTitleMap(
			AssetCategory assetCategory, String name) {

		Map<Locale, String> titleMap = assetCategory.getTitleMap();

		if (titleMap == null) {
			titleMap = new HashMap<Locale, String>();
		}

		Locale locale = LocaleUtil.getDefault();

		titleMap.put(locale, name);

		return titleMap;
	}

	/**
	 * @see {@link DLPortletDataHandlerImpl#getFileEntryTypeName(String, long,
	 *      String, int)}
	 * @see {@link DLPortletDataHandlerImpl#getFolderName(String, long, long,
	 *      String, int)}
	 */
	protected String getAssetVocabularyName(
			String uuid, long groupId, String name, int count)
			throws Exception {

		AssetVocabulary assetVocabulary = AssetVocabularyUtil.fetchByG_N(
				groupId, name);

		if (assetVocabulary == null) {
			return name;
		}

		if (Validator.isNotNull(uuid) &&
				uuid.equals(assetVocabulary.getUuid())) {

			return name;
		}

		name = StringUtil.appendParentheticalSuffix(name, count);

		return getAssetVocabularyName(uuid, groupId, name, ++count);
	}

	protected Map<Locale, String> getAssetVocabularyTitleMap(
			AssetVocabulary assetVocabulary, String name) {

		Map<Locale, String> titleMap = assetVocabulary.getTitleMap();

		if (titleMap == null) {
			titleMap = new HashMap<Locale, String>();
		}

		Locale locale = LocaleUtil.getDefault();

		titleMap.put(locale, name);

		return titleMap;
	}

	protected UserIdStrategy getUserIdStrategy(
			User user, String userIdStrategy) {

		if (UserIdStrategy.ALWAYS_CURRENT_USER_ID.equals(userIdStrategy)) {
			return new AlwaysCurrentUserIdStrategy(user);
		}

		return new CurrentUserIdStrategy(user);
	}

	protected void importAssetCategory(
			PortletDataContext portletDataContext,
			Map<Long, Long> assetVocabularyPKs,
			Map<Long, Long> assetCategoryPKs,
			Map<String, String> assetCategoryUuids,
			Element assetCategoryElement, AssetCategory assetCategory)
			throws Exception {

		boolean isSiteReplication = MapUtil.getBoolean(
				portletDataContext.getParameterMap(), ExtPortletDataHandlerKeys.IS_SITE_REPLICATION);
		boolean isSkippingImportingCategory = MapUtil.getBoolean(
				portletDataContext.getParameterMap(), ExtPortletDataHandlerKeys.IS_SKIPPING_CATEGORY);

		long userId = portletDataContext.getUserId(assetCategory.getUserUuid());
		//******************************************
		// Copied from 6.1.20 -- start
		//******************************************
		long groupId = portletDataContext.getGroupId();
		//******************************************
		// Copied from 6.1.20 -- end
		//******************************************

		//******************************************
		// EXT Override Start: Off shore
		//******************************************
		// if is site replication, replace global category id for crossing env
		long actualGlobalCategoryId = 0;
		if (isSiteReplication) {
			boolean isGlobalCategory = GetterUtil.getBoolean(
					assetCategoryElement.attributeValue(SiteReplicationUtil.ATTR_IS_GLOBAL_CATEGORY));
			if (isGlobalCategory) {
				_log.error("Skip importing global category:" + assetCategory.toString());
				return;
			}
			long staticCategoryId = GetterUtil.getLong(
					assetCategoryElement.attributeValue(SiteReplicationUtil.ATTR_STATIC_CATEGORY_ID));
			if (staticCategoryId != 0) {
				AssetCategory globalCategory = null;
				try {
					globalCategory = SiteReplicationImportUtil.getGlobalCategoryWithStaticCategoryId(staticCategoryId,
							portletDataContext.getCompanyId());
				} catch (SystemException e) {
					_log.error("SystemException occurs while get global category with static categoryId: {" + staticCategoryId + "}", e);
				}
				if (globalCategory != null) {
					actualGlobalCategoryId = globalCategory.getCategoryId();
				}
			}
			if (!isSkippingImportingCategory) {
				if (actualGlobalCategoryId == 0) {
					String message = "No global categoryId found for category:" + assetCategory.toString();
					throw new NoSuchGlobalAssetCategoryException(message);
				}
			} else {
				/*
				 *  if skip importing categories
				 */
				if (actualGlobalCategoryId == 0) {
					String message = "No global categoryId found for category: {id:" + assetCategory.getCategoryId()
							+ ", name:" + assetCategory.getName() + "}, skip it.";
					_log.error(message);
				} else {
					AssetCategory localCategory = null;
					try {
						localCategory = JournalArticleCategoryLocalServiceUtil.getLocalCategoryWithGlobalCategoryId(
								actualGlobalCategoryId, groupId);
					} catch (SystemException e) {
						_log.error(e);
					}
					if (localCategory == null) {
						_log.error("No local category found for category: {id:" + assetCategory.getCategoryId()
								+ ", name:" + assetCategory.getName() + "} to replace, skip it.");
					} else {
						assetCategoryPKs.put(
								assetCategory.getCategoryId(),
								localCategory.getCategoryId());

						assetCategoryUuids.put(
								assetCategory.getUuid(), localCategory.getUuid());
					}
				}
				return;
			}
		}
		// ***************************************
		// EXT Override End
		// **************************************
		long assetVocabularyId = MapUtil.getLong(
				assetVocabularyPKs, assetCategory.getVocabularyId(),
				assetCategory.getVocabularyId());
		long parentAssetCategoryId = MapUtil.getLong(
				assetCategoryPKs, assetCategory.getParentCategoryId(),
				assetCategory.getParentCategoryId());

		if ((parentAssetCategoryId !=
				AssetCategoryConstants.DEFAULT_PARENT_CATEGORY_ID) &&
				(parentAssetCategoryId == assetCategory.getParentCategoryId())) {

			String path = getAssetCategoryPath(
					portletDataContext, parentAssetCategoryId);

			AssetCategory parentAssetCategory =
					(AssetCategory)portletDataContext.getZipEntryAsObject(path);

			Node parentCategoryNode =
					assetCategoryElement.getParent().selectSingleNode(
							"./category[@path='" + path + "']");

			if (parentCategoryNode != null) {
				importAssetCategory(
						portletDataContext, assetVocabularyPKs, assetCategoryPKs,
						assetCategoryUuids, (Element)parentCategoryNode,
						parentAssetCategory);

				parentAssetCategoryId = MapUtil.getLong(
						assetCategoryPKs, assetCategory.getParentCategoryId(),
						assetCategory.getParentCategoryId());
			}
		}

		ServiceContext serviceContext = new ServiceContext();

		serviceContext.setAddGroupPermissions(true);
		serviceContext.setAddGuestPermissions(true);
		serviceContext.setCreateDate(assetCategory.getCreateDate());
		serviceContext.setModifiedDate(assetCategory.getModifiedDate());
		serviceContext.setScopeGroupId(portletDataContext.getScopeGroupId());

		boolean global = GetterUtil.getBoolean(
				assetCategoryElement.attributeValue("global"));

		if (global) {
			Group companyGroup = GroupLocalServiceUtil.getCompanyGroup(
					portletDataContext.getCompanyId());

			groupId = companyGroup.getGroupId();
		}

		AssetCategory importedAssetCategory = null;

		try {
			if (parentAssetCategoryId !=
					AssetCategoryConstants.DEFAULT_PARENT_CATEGORY_ID) {

				AssetCategoryUtil.findByPrimaryKey(parentAssetCategoryId);
			}

			List<Element> propertyElements = assetCategoryElement.elements(
					"property");

			String[] properties = new String[propertyElements.size()];

			for (int i = 0; i < propertyElements.size(); i++) {
				Element propertyElement = propertyElements.get(i);

				String key = propertyElement.attributeValue("key");
				String value = propertyElement.attributeValue("value");

				//******************************************
				// EXT Override Start: Off shore
				//******************************************
				// if is site replication, replace global category id for crossing env
				if (isSiteReplication) {
					if (EPCategoryEnum.GLOBAL_CATEGORY_ID.getName().equals(key)) {
						value = String.valueOf(actualGlobalCategoryId);
					}
				}
				// ***************************************
				// EXT Override End
				// ***************************************


				properties[i] = key.concat(StringPool.COLON).concat(value);
			}

			//******************************************
			// Copied from 6.1.20 -- start
			//******************************************
			AssetCategory existingAssetCategory =
					AssetCategoryUtil.fetchByUUID_G(
							assetCategory.getUuid(), groupId);

			//******************************************
			// EXT Override Start: Off shore
			//******************************************
			// if is site replication, check existing asset category with name
			if (existingAssetCategory == null) {
				if (isSiteReplication) {
					existingAssetCategory = AssetCategoryUtil.fetchByP_N_V(parentAssetCategoryId, assetCategory.getName(), assetVocabularyId);
				}
			}

			//******************************************
			// EXT Override End
			//******************************************

			if (existingAssetCategory == null) {

				String name = assetCategory.getName();
				//******************************************
				// EXT Override Start: Off shore
				//******************************************
				// if is site replication, create category with new UUID
				if (isSiteReplication) {
					name = getAssetCategoryNameForSiteReplication(parentAssetCategoryId,
							name, assetVocabularyId, 0);
					serviceContext.setUuid(null);
				} else {
					//******************************************
					// Copied from 6.1.20 -- start
					//******************************************
					name = getAssetCategoryName(
							null, portletDataContext.getGroupId(),
							parentAssetCategoryId, assetCategory.getName(), 0);
					//******************************************
					// Copied from 6.1.20 -- end
					//******************************************

					serviceContext.setUuid(assetCategory.getUuid());
				}
				// ***************************************
				// EXT Override End
				// ***************************************

				// ***************************************
				// EXT Override Start: Off Shore
				// ***************************************
				try {
					importedAssetCategory =
							AssetCategoryLocalServiceUtil.addCategory(
									userId, parentAssetCategoryId,
									getAssetCategoryTitleMap(assetCategory, name),
									assetCategory.getDescriptionMap(), assetVocabularyId,
									properties, serviceContext);
				} catch (SystemException e) {
					throw e;
				} catch (Exception e) {
					String message = "Error occurs while importing category {name:" + assetCategory.getName()
							+ ", categoryId:" + assetCategory.getCategoryId() + "} : ";
					throw new PortalException(message, e);
				}
				// ***************************************
				// EXT Override End
				// ***************************************

				//******************************************
				// EXT Override Start: Off shore
				//******************************************
				// if is site replication, add newly created category to map.
				if (isSiteReplication) {
					Map<Long, Long> newlyCreatedCategoryMap =
							(Map<Long, Long>)portletDataContext.getNewPrimaryKeysMap(
									SiteReplicationUtil.KEY_ASSET_CATEGORY_NEWLY_CREATED);
					newlyCreatedCategoryMap.put(assetCategory.getCategoryId(), importedAssetCategory.getCategoryId());
				}
				//******************************************
				// EXT Override End: Off shore
				//******************************************
			}
			else {
				//******************************************
				// EXT Override Start: Off shore
				//******************************************
				// if is site replication, do not update category
				if (isSiteReplication) {
					importedAssetCategory = existingAssetCategory;
				} else {

					//******************************************
					// Copied from 6.1.20 -- start
					//******************************************
					String name = getAssetCategoryName(
							assetCategory.getUuid(), assetCategory.getGroupId(),
							parentAssetCategoryId, assetCategory.getName(), 0);
					//******************************************
					// Copied from 6.1.20 -- end
					//******************************************

					// ***************************************
					// EXT Override Start: Charles Im
					// ***************************************

					// check if scoped article
					boolean scopedArticle = MapUtil.getBoolean(
							portletDataContext.getParameterMap(), ExtPortletDataHandlerKeys.SCOPED_ARTICLE);

					// only do update if not a scoped article
					if (!scopedArticle) {
						importedAssetCategory =
								AssetCategoryLocalServiceUtil.updateCategory(
										userId, existingAssetCategory.getCategoryId(),
										parentAssetCategoryId,
										getAssetCategoryTitleMap(assetCategory, name),
										assetCategory.getDescriptionMap(), assetVocabularyId,
										properties, serviceContext);
					} else {
						importedAssetCategory = existingAssetCategory;
					}

					// ***************************************
					// EXT Override End
					// ***************************************
				}
				//******************************************
				// EXT Override End: Off shore
				//******************************************
			}

			assetCategoryPKs.put(
					assetCategory.getCategoryId(),
					importedAssetCategory.getCategoryId());

			assetCategoryUuids.put(
					assetCategory.getUuid(), importedAssetCategory.getUuid());

			portletDataContext.importPermissions(
					AssetCategory.class, assetCategory.getCategoryId(),
					importedAssetCategory.getCategoryId());
		}
		catch (NoSuchCategoryException nsce) {
			_log.error(
					"Could not find the parent category for category " +
							assetCategory.getCategoryId());
		}
	}

	protected void importAssetTag(
			PortletDataContext portletDataContext, Map<Long, Long> assetTagPKs,
			Element assetTagElement, AssetTag assetTag)
			throws PortalException, SystemException {

		long userId = portletDataContext.getUserId(assetTag.getUserUuid());

		ServiceContext serviceContext = new ServiceContext();

		serviceContext.setAddGroupPermissions(true);
		serviceContext.setAddGuestPermissions(true);
		serviceContext.setCreateDate(assetTag.getCreateDate());
		serviceContext.setModifiedDate(assetTag.getModifiedDate());
		serviceContext.setScopeGroupId(portletDataContext.getScopeGroupId());

		AssetTag importedAssetTag = null;

		List<Element> propertyElements = assetTagElement.elements("property");

		String[] properties = new String[propertyElements.size()];

		for (int i = 0; i < propertyElements.size(); i++) {
			Element propertyElement = propertyElements.get(i);

			String key = propertyElement.attributeValue("key");
			String value = propertyElement.attributeValue("value");

			properties[i] = key.concat(StringPool.COLON).concat(value);
		}

		AssetTag existingAssetTag = null;

		try {
			existingAssetTag = AssetTagUtil.findByG_N(
					portletDataContext.getScopeGroupId(), assetTag.getName());
		}
		catch (NoSuchTagException nste) {
			if (_log.isDebugEnabled()) {
				StringBundler sb = new StringBundler(5);

				sb.append("No AssetTag exists with the key {groupId=");
				sb.append(portletDataContext.getScopeGroupId());
				sb.append(", name=");
				sb.append(assetTag.getName());
				sb.append("}");

				_log.debug(sb.toString());
			}
		}

		try {
			if (existingAssetTag == null) {
				importedAssetTag = AssetTagLocalServiceUtil.addTag(
						userId, assetTag.getName(), properties, serviceContext);
			}
			else {
				// ***************************************
				// EXT Override Start: Charles Im
				// ***************************************

				// check if scoped article
				boolean scopedArticle = MapUtil.getBoolean(
						portletDataContext.getParameterMap(), ExtPortletDataHandlerKeys.SCOPED_ARTICLE);

				// only do update if not a scoped article
				if (!scopedArticle) {
					importedAssetTag = AssetTagLocalServiceUtil.updateTag(
							userId, existingAssetTag.getTagId(), assetTag.getName(),
							properties, serviceContext);
				} else {
					importedAssetTag = existingAssetTag;
				}

				// ***************************************
				// EXT Override End
				// ***************************************
			}

			assetTagPKs.put(assetTag.getTagId(), importedAssetTag.getTagId());

			portletDataContext.importPermissions(
					AssetTag.class, assetTag.getTagId(),
					importedAssetTag.getTagId());
		}
		catch (NoSuchTagException nste) {
			_log.error(
					"Could not find the parent category for category " +
							assetTag.getTagId());
		}
	}

	protected void importAssetVocabulary(
			PortletDataContext portletDataContext,
			Map<Long, Long> assetVocabularyPKs, Element assetVocabularyElement,
			AssetVocabulary assetVocabulary)
			throws Exception {

		long userId = portletDataContext.getUserId(
				assetVocabulary.getUserUuid());
		long groupId = portletDataContext.getScopeGroupId();

		ServiceContext serviceContext = new ServiceContext();

		serviceContext.setAddGroupPermissions(true);
		serviceContext.setAddGuestPermissions(true);
		serviceContext.setCreateDate(assetVocabulary.getCreateDate());
		serviceContext.setModifiedDate(assetVocabulary.getModifiedDate());
		serviceContext.setScopeGroupId(portletDataContext.getScopeGroupId());

		AssetVocabulary importedAssetVocabulary = null;

		AssetVocabulary existingAssetVocabulary =
				AssetVocabularyUtil.fetchByUUID_G(
						assetVocabulary.getUuid(), groupId);

		boolean globalVocabulary = false;

		if (existingAssetVocabulary == null) {
			Group companyGroup = GroupLocalServiceUtil.getCompanyGroup(
					portletDataContext.getCompanyId());

			existingAssetVocabulary = AssetVocabularyUtil.fetchByUUID_G(
					assetVocabulary.getUuid(), companyGroup.getGroupId());

			if (existingAssetVocabulary != null) {
				globalVocabulary = true;
			}
		}

		//******************************************
		// EXT Override Start: Off shore
		//******************************************
		// if is site replication, check existing asset vocabulary with name
		boolean isSiteReplication = MapUtil.getBoolean(
				portletDataContext.getParameterMap(), ExtPortletDataHandlerKeys.IS_SITE_REPLICATION);
		if (existingAssetVocabulary == null) {
			if (isSiteReplication) {
				existingAssetVocabulary = AssetVocabularyUtil.fetchByG_N(groupId, assetVocabulary.getName());
			}
		}

		// ***************************************
		// EXT Override End
		// ***************************************

		if (existingAssetVocabulary == null) {
			String name = getAssetVocabularyName(
					null, groupId, assetVocabulary.getName(), 2);

			serviceContext.setUuid(assetVocabulary.getUuid());
			//******************************************
			// EXT Override Start: Off shore
			//******************************************
			// if is site replication, create category with new UUID
			if (isSiteReplication) {
				serviceContext.setUuid(null);
			}
			// ***************************************
			// EXT Override End
			// ***************************************

			importedAssetVocabulary =
					AssetVocabularyLocalServiceUtil.addVocabulary(
							userId, StringPool.BLANK,
							getAssetVocabularyTitleMap(assetVocabulary, name),
							assetVocabulary.getDescriptionMap(),
							assetVocabulary.getSettings(), serviceContext);
		}
		else {
			String name = getAssetVocabularyName(
					assetVocabulary.getUuid(), groupId, assetVocabulary.getName(),
					2);

			// ***************************************
			// EXT Override Start: Off Shore
			// ***************************************
			// if is site replication, do not update vocabulary
			if (isSiteReplication) {
				importedAssetVocabulary = existingAssetVocabulary;
			} else {

				// ***************************************
				// EXT Override Start: Charles Im
				// ***************************************

				// check if scoped article
				boolean scopedArticle = MapUtil.getBoolean(
						portletDataContext.getParameterMap(), ExtPortletDataHandlerKeys.SCOPED_ARTICLE);

				// only do update if not a scoped article
				// and not a global vocabulary (EPCORE-696)
				if (!scopedArticle && !globalVocabulary) {
					importedAssetVocabulary =
							AssetVocabularyLocalServiceUtil.updateVocabulary(
									existingAssetVocabulary.getVocabularyId(), StringPool.BLANK,
									getAssetVocabularyTitleMap(assetVocabulary, name),
									assetVocabulary.getDescriptionMap(),
									assetVocabulary.getSettings(), serviceContext);
				} else {
					importedAssetVocabulary = existingAssetVocabulary;
				}

				// ***************************************
				// EXT Override End
				// ***************************************
			}
			// ***************************************
			// EXT Override End : Off shore
			// ***************************************
		}

		assetVocabularyPKs.put(
				assetVocabulary.getVocabularyId(),
				importedAssetVocabulary.getVocabularyId());

		portletDataContext.importPermissions(
				AssetVocabulary.class, assetVocabulary.getVocabularyId(),
				importedAssetVocabulary.getVocabularyId());
	}

	protected void importPortletData(
			PortletDataContext portletDataContext, String portletId, long plid,
			Element portletDataElement)
			throws Exception {

		long ownerId = PortletKeys.PREFS_OWNER_ID_DEFAULT;
		int ownerType = PortletKeys.PREFS_OWNER_TYPE_LAYOUT;

		PortletPreferences portletPreferences =
				PortletPreferencesUtil.fetchByO_O_P_P(
						ownerId, ownerType, plid, portletId);

		if (portletPreferences == null) {
			portletPreferences =
					new com.liferay.portal.model.impl.PortletPreferencesImpl();
		}

		String xml = importPortletData(
				portletDataContext, portletId, portletPreferences,
				portletDataElement);

		if (Validator.isNotNull(xml)) {
			PortletPreferencesLocalServiceUtil.updatePreferences(
					ownerId, ownerType, plid, portletId, xml);
		}
	}

	protected String importPortletData(
			PortletDataContext portletDataContext, String portletId,
			PortletPreferences portletPreferences, Element portletDataElement)
			throws Exception {

		Portlet portlet = PortletLocalServiceUtil.getPortletById(
				portletDataContext.getCompanyId(), portletId);

		if (portlet == null) {
			if (_log.isDebugEnabled()) {
				_log.debug(
						"Do not import portlet data for " + portletId +
								" because the portlet does not exist");
			}

			return null;
		}

		PortletDataHandler portletDataHandler =
				portlet.getPortletDataHandlerInstance();

		if (portletDataHandler == null) {
			if (_log.isDebugEnabled()) {
				_log.debug(
						"Do not import portlet data for " + portletId +
								" because the portlet does not have a " +
								"PortletDataHandler");
			}

			return null;
		}

		if (_log.isDebugEnabled()) {
			_log.debug("Importing data for " + portletId);
		}

		PortletPreferencesImpl portletPreferencesImpl = null;

		if (portletPreferences != null) {
			portletPreferencesImpl =
					(PortletPreferencesImpl)
							PortletPreferencesFactoryUtil.fromDefaultXML(
									portletPreferences.getPreferences());
		}

		String portletData = portletDataContext.getZipEntryAsString(
				portletDataElement.attributeValue("path"));

		try {
			portletPreferencesImpl =
					(PortletPreferencesImpl)portletDataHandler.importData(
							portletDataContext, portletId, portletPreferencesImpl,
							portletData);
		}
		catch (Exception e) {
			throw e;
		}

		if (portletPreferencesImpl == null) {
			return null;
		}

		return PortletPreferencesFactoryUtil.toXML(portletPreferencesImpl);
	}

	protected void importPortletPreferences(
			PortletDataContext portletDataContext, long companyId, long groupId,
			Layout layout, String portletId, Element parentElement,
			boolean importPortletSetup, boolean importPortletArchivedSetups,
			boolean importPortletUserPreferences, boolean preserveScopeLayoutId,
			boolean importPortletData)
			throws Exception {

		long defaultUserId = UserLocalServiceUtil.getDefaultUserId(companyId);
		long plid = 0;
		String scopeType = StringPool.BLANK;
		String scopeLayoutUuid = StringPool.BLANK;

		if (layout != null) {
			plid = layout.getPlid();

			if (preserveScopeLayoutId && (portletId != null)) {
				javax.portlet.PortletPreferences jxPreferences =
						PortletPreferencesFactoryUtil.getLayoutPortletSetup(
								layout, portletId);

				scopeType = GetterUtil.getString(
						jxPreferences.getValue("lfrScopeType", null));
				scopeLayoutUuid = GetterUtil.getString(
						jxPreferences.getValue("lfrScopeLayoutUuid", null));

				portletDataContext.setScopeType(scopeType);
				portletDataContext.setScopeLayoutUuid(scopeLayoutUuid);
			}
		}

		List<Element> portletPreferencesElements = parentElement.elements(
				"portlet-preferences");

		for (Element portletPreferencesElement : portletPreferencesElements) {
			String path = portletPreferencesElement.attributeValue("path");

			if (portletDataContext.isPathNotProcessed(path)) {
				String xml = null;

				Element element = null;

				try {
					xml = portletDataContext.getZipEntryAsString(path);

					Document preferencesDocument = SAXReaderUtil.read(xml);

					element = preferencesDocument.getRootElement();
				}
				catch (DocumentException de) {
					throw new SystemException(de);
				}

				long ownerId = GetterUtil.getLong(
						element.attributeValue("owner-id"));
				int ownerType = GetterUtil.getInteger(
						element.attributeValue("owner-type"));

				if (ownerType == PortletKeys.PREFS_OWNER_TYPE_COMPANY) {
					continue;
				}

				if (((ownerType == PortletKeys.PREFS_OWNER_TYPE_GROUP) ||
						(ownerType == PortletKeys.PREFS_OWNER_TYPE_LAYOUT)) &&
						!importPortletSetup) {

					continue;
				}

				if ((ownerType == PortletKeys.PREFS_OWNER_TYPE_ARCHIVED) &&
						!importPortletArchivedSetups) {

					continue;
				}

				if ((ownerType == PortletKeys.PREFS_OWNER_TYPE_USER) &&
						(ownerId != PortletKeys.PREFS_OWNER_ID_DEFAULT) &&
						!importPortletUserPreferences) {

					continue;
				}

				if (ownerType == PortletKeys.PREFS_OWNER_TYPE_GROUP) {
					plid = PortletKeys.PREFS_PLID_SHARED;
					ownerId = portletDataContext.getScopeGroupId();
				}

				boolean defaultUser = GetterUtil.getBoolean(
						element.attributeValue("default-user"));

				if (portletId == null) {
					portletId = element.attributeValue("portlet-id");
				}

				if (ownerType == PortletKeys.PREFS_OWNER_TYPE_ARCHIVED) {
					portletId = PortletConstants.getRootPortletId(portletId);

					String userUuid = element.attributeValue(
							"archive-user-uuid");
					String name = element.attributeValue("archive-name");

					long userId = portletDataContext.getUserId(userUuid);

					PortletItem portletItem =
							PortletItemLocalServiceUtil.updatePortletItem(
									userId, groupId, name, portletId,
									PortletPreferences.class.getName());

					plid = 0;
					ownerId = portletItem.getPortletItemId();
				}
				else if (ownerType == PortletKeys.PREFS_OWNER_TYPE_USER) {
					String userUuid = element.attributeValue("user-uuid");

					ownerId = portletDataContext.getUserId(userUuid);
				}

				if (defaultUser) {
					ownerId = defaultUserId;
				}

				String rootPotletId = PortletConstants.getRootPortletId(
						portletId);

				if (rootPotletId.equals(PortletKeys.ASSET_PUBLISHER)) {
					xml = updateAssetPublisherPortletPreferences(
							portletDataContext, companyId, ownerId, ownerType, plid,
							portletId, xml, layout);
				}
				else if (rootPotletId.equals(
						PortletKeys.TAGS_CATEGORIES_NAVIGATION)) {

					xml = updateAssetCategoriesNavigationPortletPreferences(
							portletDataContext, companyId, ownerId, ownerType, plid,
							portletId, xml);
				}

				updatePortletPreferences(
						portletDataContext, ownerId, ownerType, plid, portletId,
						xml, importPortletData);
			}
		}

		if (preserveScopeLayoutId && (layout != null)) {
			javax.portlet.PortletPreferences jxPreferences =
					PortletPreferencesFactoryUtil.getLayoutPortletSetup(
							layout, portletId);

			try {
				jxPreferences.setValue("lfrScopeType", scopeType);
				jxPreferences.setValue("lfrScopeLayoutUuid", scopeLayoutUuid);

				jxPreferences.store();
			}
			finally {
				portletDataContext.setScopeType(scopeType);
				portletDataContext.setScopeLayoutUuid(scopeLayoutUuid);
			}
		}
	}

	protected void readAssetCategories(PortletDataContext portletDataContext)
			throws Exception {

		String xml = portletDataContext.getZipEntryAsString(
				portletDataContext.getSourceRootPath() +
						"/categories-hierarchy.xml");

		if (xml == null) {
			return;
		}

		Document document = SAXReaderUtil.read(xml);

		Element rootElement = document.getRootElement();

		Element assetVocabulariesElement = rootElement.element("vocabularies");

		List<Element> assetVocabularyElements =
				assetVocabulariesElement.elements("vocabulary");

		Map<Long, Long> assetVocabularyPKs =
				(Map<Long, Long>)portletDataContext.getNewPrimaryKeysMap(
						AssetVocabulary.class);

		for (Element assetVocabularyElement : assetVocabularyElements) {
			String path = assetVocabularyElement.attributeValue("path");

			if (!portletDataContext.isPathNotProcessed(path)) {
				continue;
			}

			AssetVocabulary assetVocabulary =
					(AssetVocabulary)portletDataContext.getZipEntryAsObject(path);

			importAssetVocabulary(
					portletDataContext, assetVocabularyPKs, assetVocabularyElement,
					assetVocabulary);
		}

		Element assetCategoriesElement = rootElement.element("categories");

		List<Element> assetCategoryElements = assetCategoriesElement.elements(
				"category");

		Map<Long, Long> assetCategoryPKs =
				(Map<Long, Long>)portletDataContext.getNewPrimaryKeysMap(
						AssetCategory.class);

		Map<String, String> assetCategoryUuids =
				(Map<String, String>)portletDataContext.getNewPrimaryKeysMap(
						AssetCategory.class.getName() + "uuid");

		for (Element assetCategoryElement : assetCategoryElements) {
			String path = assetCategoryElement.attributeValue("path");

			if (!portletDataContext.isPathNotProcessed(path)) {
				continue;
			}

			AssetCategory assetCategory =
					(AssetCategory)portletDataContext.getZipEntryAsObject(path);

			//******************************************
			// EXT Override Start: Off shore
			//******************************************
			// if it's site replication, skip site specific category
			boolean isSiteReplication = MapUtil.getBoolean(
					portletDataContext.getParameterMap(), ExtPortletDataHandlerKeys.IS_SITE_REPLICATION);
			if (isSiteReplication) {
				Map<Long, AssetCategory> categoryShouldHaveImportedMap =
						(Map<Long, AssetCategory>) portletDataContext.getNewPrimaryKeysMap(
								SiteReplicationUtil.KEY_ASSET_CATEGORY_SHOULD_HAVE_IMPORTED);
				Map<Long, AssetCategory> categorySkippedMap =
						(Map<Long, AssetCategory>) portletDataContext.getNewPrimaryKeysMap(
								SiteReplicationUtil.KEY_ASSET_CATEGORY_SKIPPED);
				categoryShouldHaveImportedMap.put(assetCategory.getCategoryId(), assetCategory);
				boolean isSiteSpecificCategory = GetterUtil.getBoolean(
						assetCategoryElement.attributeValue(SiteReplicationUtil.ATTR_IS_SITE_SPECIFIC));
				if (isSiteSpecificCategory) {
					// add to skipped map
					categorySkippedMap.put(assetCategory.getCategoryId(), assetCategory);
					continue;
				}
			}
			//******************************************
			// EXT Override End
			//******************************************

			importAssetCategory(
					portletDataContext, assetVocabularyPKs, assetCategoryPKs,
					assetCategoryUuids, assetCategoryElement, assetCategory);
		}

		Element assetsElement = rootElement.element("assets");

		List<Element> assetElements = assetsElement.elements("asset");

		for (Element assetElement : assetElements) {
			String className = GetterUtil.getString(
					assetElement.attributeValue("class-name"));
			long classPK = GetterUtil.getLong(
					assetElement.attributeValue("class-pk"));
			String[] assetCategoryUuidArray = StringUtil.split(
					GetterUtil.getString(
							assetElement.attributeValue("category-uuids")));

			long[] assetCategoryIds = new long[0];

			for (String assetCategoryUuid : assetCategoryUuidArray) {
				assetCategoryUuid = MapUtil.getString(
						assetCategoryUuids, assetCategoryUuid, assetCategoryUuid);

				AssetCategory assetCategory = AssetCategoryUtil.fetchByUUID_G(
						assetCategoryUuid, portletDataContext.getScopeGroupId());

				if (assetCategory == null) {
					Group companyGroup = GroupLocalServiceUtil.getCompanyGroup(
							portletDataContext.getCompanyId());

					assetCategory = AssetCategoryUtil.fetchByUUID_G(
							assetCategoryUuid, companyGroup.getGroupId());
				}

				if (assetCategory != null) {
					assetCategoryIds = ArrayUtil.append(
							assetCategoryIds, assetCategory.getCategoryId());
				}
			}

			portletDataContext.addAssetCategories(
					className, classPK, assetCategoryIds);
		}
	}

	protected void readAssetLinks(PortletDataContext portletDataContext)
			throws Exception {

		String xml = portletDataContext.getZipEntryAsString(
				portletDataContext.getSourceRootPath() + "/links.xml");

		if (xml == null) {
			return;
		}

		Document document = SAXReaderUtil.read(xml);

		Element rootElement = document.getRootElement();

		List<Element> assetLinkElements = rootElement.elements("asset-link");

		for (Element assetLinkElement : assetLinkElements) {
			String sourceUuid = GetterUtil.getString(
					assetLinkElement.attributeValue("source-uuid"));
			String[] assetEntryUuidArray = StringUtil.split(
					GetterUtil.getString(
							assetLinkElement.attributeValue("target-uuids")));
			int assetLinkType = GetterUtil.getInteger(
					assetLinkElement.attributeValue("type"));
			String assetLinkWeightsString = GetterUtil.getString(
					assetLinkElement.attributeValue("weights"));

			Map<String, Integer> assetLinkWeights =
					new HashMap<String, Integer>();

			if (Validator.isNotNull(assetLinkWeightsString)) {
				String[] assetLinkWeightsArray = StringUtil.split(
						assetLinkWeightsString);

				for (int i = 0; i < assetEntryUuidArray.length; i++) {
					assetLinkWeights.put(
							assetEntryUuidArray[i],
							GetterUtil.getInteger(assetLinkWeightsArray[i]));
				}
			}

			List<Long> assetEntryIds = new ArrayList<Long>();
			Map<Long, AssetEntry> targrtAssetEntries =
					new HashMap<Long, AssetEntry>();

			for (String assetEntryUuid : assetEntryUuidArray) {
				if (StringUtils.contains(assetEntryUuid, StringPool.STAR)) {
					String[] uuidAndGroupId = StringUtils.split(assetEntryUuid, StringPool.STAR);
					try {
						AssetEntry assetEntry = AssetEntryLocalServiceUtil.getEntry(
								portletDataContext.getScopeGroupId(), uuidAndGroupId[0]);

						assetEntryIds.add(assetEntry.getEntryId());
					}
					catch (NoSuchEntryException nsee) {
						if (uuidAndGroupId.length > 1) {
							AssetEntry assetEntry = AssetEntryLocalServiceUtil.getEntry(
									Long.parseLong(uuidAndGroupId[1]), uuidAndGroupId[0]);

							assetEntryIds.add(assetEntry.getEntryId());
						}
					}
				} else {
					try {
						AssetEntry assetEntry = AssetEntryLocalServiceUtil.getEntry(
								portletDataContext.getScopeGroupId(), assetEntryUuid);

						assetEntryIds.add(assetEntry.getEntryId());
					}
					catch (NoSuchEntryException nsee) {
					}
				}
			}

			if (assetEntryIds.isEmpty()) {
				continue;
			}

			long[] assetEntryIdsArray = ArrayUtil.toArray(
					assetEntryIds.toArray(new Long[assetEntryIds.size()]));

			AssetEntry assetEntry = AssetEntryLocalServiceUtil.fetchEntry(
					portletDataContext.getScopeGroupId(), sourceUuid);

			if (assetEntry == null) {
				assetEntry = AssetEntryLocalServiceUtil.fetchEntry(
						portletDataContext.getCompanyGroupId(), sourceUuid);
			}

			if (assetEntry == null) {
				continue;
			}

			AssetLinkLocalServiceUtil.updateLinks(
					assetEntry.getUserId(), assetEntry.getEntryId(),
					assetEntryIdsArray, assetLinkType);

			if (Validator.isNull(assetLinkWeightsString)) {
				continue;
			}

			List<AssetLink> directAssetLinks =
					AssetLinkLocalServiceUtil.getDirectLinks(
							assetEntry.getEntryId(), assetLinkType);

			for (AssetLink directAssetLink : directAssetLinks) {
				AssetEntry targetAssetEntry = targrtAssetEntries.get(
						directAssetLink.getEntryId2());

				if (targetAssetEntry == null) {
					continue;
				}

				int assetLinkWeight = assetLinkWeights.get(
						targetAssetEntry.getClassUuid());

				directAssetLink.setWeight(assetLinkWeight);

				AssetLinkLocalServiceUtil.updateAssetLink(directAssetLink);
			}
		}
	}

	protected void readAssetTags(PortletDataContext portletDataContext)
			throws Exception {

		String xml = portletDataContext.getZipEntryAsString(
				portletDataContext.getSourceRootPath() + "/tags.xml");

		if (xml == null) {
			return;
		}

		Document document = SAXReaderUtil.read(xml);

		Element rootElement = document.getRootElement();

		List<Element> assetTagElements = rootElement.elements("tag");

		for (Element assetTagElement : assetTagElements) {
			String path = assetTagElement.attributeValue("path");

			if (!portletDataContext.isPathNotProcessed(path)) {
				continue;
			}

			AssetTag assetTag =
					(AssetTag)portletDataContext.getZipEntryAsObject(path);

			Map<Long, Long> assetTagPKs =
					(Map<Long, Long>)portletDataContext.getNewPrimaryKeysMap(
							AssetTag.class);

			importAssetTag(
					portletDataContext, assetTagPKs, assetTagElement, assetTag);
		}

		List<Element> assetElements = rootElement.elements("asset");

		for (Element assetElement : assetElements) {
			String className = GetterUtil.getString(
					assetElement.attributeValue("class-name"));
			long classPK = GetterUtil.getLong(
					assetElement.attributeValue("class-pk"));
			String assetTagNames = GetterUtil.getString(
					assetElement.attributeValue("tags"));

			portletDataContext.addAssetTags(
					className, classPK, StringUtil.split(assetTagNames));
		}
	}

	protected void readComments(PortletDataContext portletDataContext)
			throws Exception {

		String xml = portletDataContext.getZipEntryAsString(
				portletDataContext.getSourceRootPath() + "/comments.xml");

		if (xml == null) {
			return;
		}

		Document document = SAXReaderUtil.read(xml);

		Element rootElement = document.getRootElement();

		List<Element> assetElements = rootElement.elements("asset");

		for (Element assetElement : assetElements) {
			String path = assetElement.attributeValue("path");
			String className = assetElement.attributeValue("class-name");
			long classPK = GetterUtil.getLong(
					assetElement.attributeValue("class-pk"));

			List<String> zipFolderEntries =
					portletDataContext.getZipFolderEntries(path);

			List<MBMessage> mbMessages = new ArrayList<MBMessage>();

			for (String zipFolderEntry : zipFolderEntries) {
				MBMessage mbMessage =
						(MBMessage)portletDataContext.getZipEntryAsObject(
								zipFolderEntry);

				if (mbMessage != null) {
					mbMessages.add(mbMessage);
				}
			}

			portletDataContext.addComments(className, classPK, mbMessages);
		}
	}

	protected void readExpandoTables(PortletDataContext portletDataContext)
			throws Exception {

		String xml = portletDataContext.getZipEntryAsString(
				portletDataContext.getSourceRootPath() + "/expando-tables.xml");

		if (xml == null) {
			return;
		}

		Document document = SAXReaderUtil.read(xml);

		Element rootElement = document.getRootElement();

		List<Element> expandoTableElements = rootElement.elements(
				"expando-table");

		for (Element expandoTableElement : expandoTableElements) {
			String className = expandoTableElement.attributeValue("class-name");

			ExpandoTable expandoTable = null;

			try {
				expandoTable = ExpandoTableLocalServiceUtil.getDefaultTable(
						portletDataContext.getCompanyId(), className);
			}
			catch (NoSuchTableException nste) {
				expandoTable = ExpandoTableLocalServiceUtil.addDefaultTable(
						portletDataContext.getCompanyId(), className);
			}

			List<Element> expandoColumnElements = expandoTableElement.elements(
					"expando-column");

			for (Element expandoColumnElement : expandoColumnElements) {
				long columnId = GetterUtil.getLong(
						expandoColumnElement.attributeValue("column-id"));
				String name = expandoColumnElement.attributeValue("name");
				int type = GetterUtil.getInteger(
						expandoColumnElement.attributeValue("type"));
				String defaultData = expandoColumnElement.elementText(
						"default-data");
				String typeSettings = expandoColumnElement.elementText(
						"type-settings");

				Serializable defaultDataObject =
						ExpandoConverterUtil.getAttributeFromString(
								type, defaultData);

				ExpandoColumn expandoColumn =
						ExpandoColumnLocalServiceUtil.getColumn(
								expandoTable.getTableId(), name);

				if (expandoColumn != null) {
					ExpandoColumnLocalServiceUtil.updateColumn(
							expandoColumn.getColumnId(), name, type,
							defaultDataObject);
				}
				else {
					expandoColumn = ExpandoColumnLocalServiceUtil.addColumn(
							expandoTable.getTableId(), name, type,
							defaultDataObject);
				}

				ExpandoColumnLocalServiceUtil.updateTypeSettings(
						expandoColumn.getColumnId(), typeSettings);

				portletDataContext.importPermissions(
						ExpandoColumn.class, columnId, expandoColumn.getColumnId());
			}
		}
	}

	protected void readLocks(PortletDataContext portletDataContext)
			throws Exception {

		String xml = portletDataContext.getZipEntryAsString(
				portletDataContext.getSourceRootPath() + "/locks.xml");

		if (xml == null) {
			return;
		}

		Document document = SAXReaderUtil.read(xml);

		Element rootElement = document.getRootElement();

		List<Element> assetElements = rootElement.elements("asset");

		for (Element assetElement : assetElements) {
			String path = assetElement.attributeValue("path");
			String className = assetElement.attributeValue("class-name");
			String key = assetElement.attributeValue("key");

			Lock lock = (Lock)portletDataContext.getZipEntryAsObject(path);

			if (lock != null) {
				portletDataContext.addLocks(className, key, lock);
			}
		}
	}

	protected void readRatingsEntries(PortletDataContext portletDataContext)
			throws Exception {

		String xml = portletDataContext.getZipEntryAsString(
				portletDataContext.getSourceRootPath() + "/ratings.xml");

		if (xml == null) {
			return;
		}

		Document document = SAXReaderUtil.read(xml);

		Element rootElement = document.getRootElement();

		List<Element> assetElements = rootElement.elements("asset");

		for (Element assetElement : assetElements) {
			String path = assetElement.attributeValue("path");
			String className = assetElement.attributeValue("class-name");
			long classPK = GetterUtil.getLong(
					assetElement.attributeValue("class-pk"));

			List<String> zipFolderEntries =
					portletDataContext.getZipFolderEntries(path);

			List<RatingsEntry> ratingsEntries = new ArrayList<RatingsEntry>();

			for (String zipFolderEntry : zipFolderEntries) {
				RatingsEntry ratingsEntry =
						(RatingsEntry)portletDataContext.getZipEntryAsObject(
								zipFolderEntry);

				if (ratingsEntry != null) {
					ratingsEntries.add(ratingsEntry);
				}
			}

			portletDataContext.addRatingsEntries(
					className, classPK, ratingsEntries);
		}
	}

	protected void resetPortletScope(
			PortletDataContext portletDataContext, long groupId) {

		portletDataContext.setScopeGroupId(groupId);
		portletDataContext.setScopeLayoutUuid(StringPool.BLANK);
		portletDataContext.setScopeType(StringPool.BLANK);
	}

	protected void setPortletScope(
			PortletDataContext portletDataContext, Element portletElement) {

		// Portlet data scope

		String scopeLayoutUuid = GetterUtil.getString(
				portletElement.attributeValue("scope-layout-uuid"));
		String scopeLayoutType = GetterUtil.getString(
				portletElement.attributeValue("scope-layout-type"));

		portletDataContext.setScopeLayoutUuid(scopeLayoutUuid);
		portletDataContext.setScopeType(scopeLayoutType);

		// Layout scope

		try {
			Group scopeGroup = null;

			if (scopeLayoutType.equals("company")) {
				scopeGroup = GroupLocalServiceUtil.getCompanyGroup(
						portletDataContext.getCompanyId());
			}
			else if (Validator.isNotNull(scopeLayoutUuid)) {
				boolean privateLayout = GetterUtil.getBoolean(
						portletElement.attributeValue("private-layout"));

				Layout scopeLayout =
						LayoutLocalServiceUtil.getLayoutByUuidAndGroupId(
								scopeLayoutUuid, portletDataContext.getGroupId(),
								privateLayout);

				if (scopeLayout.hasScopeGroup()) {
					scopeGroup = scopeLayout.getScopeGroup();
				}
				else {
					String name = String.valueOf(scopeLayout.getPlid());

					scopeGroup = GroupLocalServiceUtil.addGroup(
							portletDataContext.getUserId(null),
							Layout.class.getName(), scopeLayout.getPlid(), name,
							null, 0, null, false, true, null);
				}

				Group group = scopeLayout.getGroup();

				if (group.isStaged() && !group.isStagedRemotely()) {
					try {
						Layout oldLayout =
								LayoutLocalServiceUtil.getLayoutByUuidAndGroupId(
										scopeLayoutUuid,
										portletDataContext.getSourceGroupId(),
										privateLayout);

						Group oldScopeGroup = oldLayout.getScopeGroup();

						oldScopeGroup.setLiveGroupId(scopeGroup.getGroupId());

						GroupLocalServiceUtil.updateGroup(oldScopeGroup, true);
					}
					catch (NoSuchLayoutException nsle) {
						if (_log.isWarnEnabled()) {
							_log.warn(nsle);
						}
					}
				}
			}

			if (scopeGroup != null) {
				portletDataContext.setScopeGroupId(scopeGroup.getGroupId());
			}
		}
		catch (PortalException pe) {
		}
		catch (Exception e) {
			_log.error(e, e);
		}
	}

	protected String updateAssetCategoriesNavigationPortletPreferences(
			PortletDataContext portletDataContext, long companyId, long ownerId,
			int ownerType, long plid, String portletId, String xml)
			throws Exception {

		Company company = CompanyLocalServiceUtil.getCompanyById(companyId);

		Group companyGroup = company.getGroup();

		javax.portlet.PortletPreferences jxPreferences =
				PortletPreferencesFactoryUtil.fromXML(
						companyId, ownerId, ownerType, plid, portletId, xml);

		Enumeration<String> enu = jxPreferences.getNames();

		while (enu.hasMoreElements()) {
			String name = enu.nextElement();

			if (name.equals("assetVocabularyIds")) {
				updatePreferencesClassPKs(
						portletDataContext, jxPreferences, name,
						AssetVocabulary.class, companyGroup.getGroupId());
			}
		}

		return PortletPreferencesFactoryUtil.toXML(jxPreferences);
	}

	protected void updateAssetPublisherClassNameIds(
			javax.portlet.PortletPreferences jxPreferences, String key)
			throws Exception {

		String[] oldValues = jxPreferences.getValues(key, null);

		if (oldValues == null) {
			return;
		}

		String[] newValues = new String[oldValues.length];

		int i = 0;

		for (String oldValue : oldValues) {
			if (key.equals("anyAssetType") &&
					(oldValue.equals("false") || oldValue.equals("true"))) {

				newValues[i++] = oldValue;

				continue;
			}

			try {
				long classNameId = PortalUtil.getClassNameId(oldValue);

				newValues[i++] = String.valueOf(classNameId);
			}
			catch (Exception e) {
				if (_log.isWarnEnabled()) {
					_log.warn(
							"Unable to find class name ID for class name " +
									oldValue);
				}
			}
		}

		jxPreferences.setValues(key, newValues);
	}

	protected String updateAssetPublisherPortletPreferences(
			PortletDataContext portletDataContext, long companyId, long ownerId,
			int ownerType, long plid, String portletId, String xml,
			Layout layout)
			throws Exception {

		Company company = CompanyLocalServiceUtil.getCompanyById(companyId);

		Group companyGroup = company.getGroup();

		javax.portlet.PortletPreferences jxPreferences =
				PortletPreferencesFactoryUtil.fromXML(
						companyId, ownerId, ownerType, plid, portletId, xml);

		String anyAssetTypeClassName = jxPreferences.getValue(
				"anyAssetType", StringPool.BLANK);

		Enumeration<String> enu = jxPreferences.getNames();

		while (enu.hasMoreElements()) {
			String name = enu.nextElement();

			String value = GetterUtil.getString(
					jxPreferences.getValue(name, null));

			if (name.equals("anyAssetType") || name.equals("classNameIds")) {
				updateAssetPublisherClassNameIds(jxPreferences, name);
			}
			else if (name.equals(
					"anyClassTypeDLFileEntryAssetRendererFactory") ||
					(name.equals("classTypeIds") &&
							anyAssetTypeClassName.equals(
									DLFileEntry.class.getName())) ||
					name.equals(
							"classTypeIdsDLFileEntryAssetRendererFactory")) {

				updatePreferencesClassPKs(
						portletDataContext, jxPreferences, name,
						DLFileEntryType.class, companyGroup.getGroupId());
			}
			else if (name.equals(
					"anyClassTypeJournalArticleAssetRendererFactory") ||
					(name.equals("classTypeIds") &&
							anyAssetTypeClassName.equals(
									JournalArticle.class.getName())) ||
					name.equals(
							"classTypeIdsJournalArticleAssetRendererFactory")) {

				updatePreferencesClassPKs(
						portletDataContext, jxPreferences, name,
						JournalStructure.class, companyGroup.getGroupId());
			}
			else if (name.equals("assetVocabularyId")) {
				updatePreferencesClassPKs(
						portletDataContext, jxPreferences, name,
						AssetVocabulary.class, companyGroup.getGroupId());
			}
			else if (name.equals("defaultScope") || name.equals("scopeIds")) {
				updateAssetPublisherScopeIds(
						jxPreferences, name, companyGroup.getGroupId(),
						layout.getPlid());
			}
			else if (name.startsWith("queryName") &&
					value.equalsIgnoreCase("assetCategories")) {

				String index = name.substring(9, name.length());

				updatePreferencesClassPKs(
						portletDataContext, jxPreferences, "queryValues" + index,
						AssetCategory.class, companyGroup.getGroupId());
			}
		}

		return PortletPreferencesFactoryUtil.toXML(jxPreferences);
	}

	protected void updateAssetPublisherScopeIds(
			javax.portlet.PortletPreferences jxPreferences, String key,
			long groupId, long plid)
			throws Exception {

		String[] oldValues = jxPreferences.getValues(key, null);

		if (oldValues == null) {
			return;
		}

		String groupScopeId =
				AssetPublisherUtil.SCOPE_ID_GROUP_PREFIX + groupId;

		String[] newValues = new String[oldValues.length];

		for (int i = 0; i < oldValues.length; i++) {
			String oldValue = oldValues[i];

			newValues[i] = StringUtil.replace(
					oldValue, "[$GROUP_SCOPE_ID$]", groupScopeId);
		}

		jxPreferences.setValues(key, newValues);
	}

	protected void updatePortletPreferences(
			PortletDataContext portletDataContext, long ownerId, int ownerType,
			long plid, String portletId, String xml, boolean importData)
			throws Exception {

		if (importData) {
			PortletPreferencesLocalServiceUtil.updatePreferences(
					ownerId, ownerType, plid, portletId, xml);
		}
		else {
			Portlet portlet = PortletLocalServiceUtil.getPortletById(
					portletDataContext.getCompanyId(), portletId);

			if (portlet == null) {
				if (_log.isDebugEnabled()) {
					_log.debug(
							"Do not update portlet preferences for " + portletId +
									" because the portlet does not exist");
				}

				return;
			}

			PortletDataHandler portletDataHandler =
					portlet.getPortletDataHandlerInstance();

			if (portletDataHandler == null) {
				PortletPreferencesLocalServiceUtil.updatePreferences(
						ownerId, ownerType, plid, portletId, xml);

				return;
			}

			// Portlet preferences to be updated only when importing data

			String[] dataPortletPreferences =
					portletDataHandler.getDataPortletPreferences();

			// Current portlet preferences

			javax.portlet.PortletPreferences portletPreferences =
					PortletPreferencesLocalServiceUtil.getPreferences(
							portletDataContext.getCompanyId(), ownerId, ownerType, plid,
							portletId);

			// New portlet preferences

			javax.portlet.PortletPreferences jxPreferences =
					PortletPreferencesFactoryUtil.fromXML(
							portletDataContext.getCompanyId(), ownerId, ownerType, plid,
							portletId, xml);

			Enumeration<String> enu = jxPreferences.getNames();

			while (enu.hasMoreElements()) {
				String name = enu.nextElement();

				String scopeLayoutUuid =
						portletDataContext.getScopeLayoutUuid();
				String scopeType = portletDataContext.getScopeType();

				if (!ArrayUtil.contains(dataPortletPreferences, name) ||
						(Validator.isNull(scopeLayoutUuid) &&
								scopeType.equals("company"))) {

					String[] values = jxPreferences.getValues(name, null);

					portletPreferences.setValues(name, values);
				}
			}

			PortletPreferencesLocalServiceUtil.updatePreferences(
					ownerId, ownerType, plid, portletId, portletPreferences);
		}
	}

	protected void updatePreferencesClassPKs(
			PortletDataContext portletDataContext,
			javax.portlet.PortletPreferences jxPreferences, String key,
			Class<?> clazz, long companyGroupId)
			throws Exception {

		String[] oldValues = jxPreferences.getValues(key, null);

		if (oldValues == null) {
			return;
		}

		Map<Long, Long> primaryKeysMap =
				(Map<Long, Long>)portletDataContext.getNewPrimaryKeysMap(clazz);

		String[] newValues = new String[oldValues.length];

		for (int i = 0; i < oldValues.length; i++) {
			String oldValue = oldValues[i];

			String newValue = oldValue;

			String[] uuids = StringUtil.split(oldValue);

			for (String uuid : uuids) {
				Long newPrimaryKey = null;

				if (Validator.isNumber(uuid)) {
					long oldPrimaryKey = GetterUtil.getLong(uuid);

					newPrimaryKey = MapUtil.getLong(
							primaryKeysMap, oldPrimaryKey, oldPrimaryKey);
				}
				else {
					String className = clazz.getName();

					if (className.equals(AssetCategory.class.getName())) {
						AssetCategory assetCategory =
								AssetCategoryUtil.fetchByUUID_G(
										uuid, portletDataContext.getScopeGroupId());

						if (assetCategory == null) {
							assetCategory = AssetCategoryUtil.fetchByUUID_G(
									uuid, companyGroupId);
						}

						if (assetCategory != null) {
							newPrimaryKey = assetCategory.getCategoryId();
						}
					}
					else if (className.equals(
							AssetVocabulary.class.getName())) {

						AssetVocabulary assetVocabulary =
								AssetVocabularyUtil.fetchByUUID_G(
										uuid, portletDataContext.getScopeGroupId());

						if (assetVocabulary == null) {
							assetVocabulary = AssetVocabularyUtil.fetchByUUID_G(
									uuid, companyGroupId);
						}

						if (assetVocabulary != null) {
							newPrimaryKey = assetVocabulary.getVocabularyId();
						}
					}
					else if (className.equals(
							DLFileEntryType.class.getName())) {

						DLFileEntryType dlFileEntryType =
								DLFileEntryTypeUtil.fetchByUUID_G(
										uuid, portletDataContext.getScopeGroupId());

						if (dlFileEntryType == null) {
							dlFileEntryType = DLFileEntryTypeUtil.fetchByUUID_G(
									uuid, companyGroupId);
						}

						if (dlFileEntryType != null) {
							newPrimaryKey =
									dlFileEntryType.getFileEntryTypeId();
						}
					}
					else if (className.equals(
							JournalStructure.class.getName())) {

						JournalStructure journalStructure =
								JournalStructureUtil.fetchByUUID_G(
										uuid, portletDataContext.getScopeGroupId());

						if (journalStructure == null) {
							journalStructure =
									JournalStructureUtil.fetchByUUID_G(
											uuid, companyGroupId);
						}

						if (journalStructure != null) {
							newPrimaryKey = journalStructure.getId();
						}
					}
				}

				if (Validator.isNull(newPrimaryKey)) {
					if (_log.isWarnEnabled()) {
						StringBundler sb = new StringBundler(8);

						sb.append("Unable to get primary key for ");
						sb.append(clazz);
						sb.append(" with UUID ");
						sb.append(uuid);
						sb.append(" in company group ");
						sb.append(companyGroupId);
						sb.append(" or in group ");
						sb.append(portletDataContext.getScopeGroupId());

						_log.warn(sb.toString());
					}
				}
				else {
					newValue = StringUtil.replace(
							newValue, uuid, newPrimaryKey.toString());
				}
			}

			newValues[i] = newValue;
		}

		jxPreferences.setValues(key, newValues);
	}

	private static Log _log = LogFactoryUtil.getLog(PortletImporter.class);

	private PermissionImporter _permissionImporter = new PermissionImporter();

}