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
import com.endplay.portal.lar.SiteReplicationExportUtil;
import com.endplay.portal.lar.SiteReplicationUtil;
import com.endplay.portlet.journal.service.ConfigurationPropertyLocalServiceUtil;
import com.liferay.portal.NoSuchLayoutException;
import com.liferay.portal.kernel.language.LanguageUtil;
import com.liferay.portal.kernel.lar.ImportExportThreadLocal;
import com.liferay.portal.kernel.lar.PortletDataContext;
import com.liferay.portal.kernel.lar.PortletDataHandler;
import com.liferay.portal.kernel.lar.PortletDataHandlerKeys;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.servlet.ServletContextPool;
import com.liferay.portal.kernel.staging.LayoutStagingUtil;
import com.liferay.portal.kernel.util.*;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.portal.kernel.xml.Document;
import com.liferay.portal.kernel.xml.Element;
import com.liferay.portal.kernel.xml.SAXReaderUtil;
import com.liferay.portal.kernel.zip.ZipWriter;
import com.liferay.portal.kernel.zip.ZipWriterFactoryUtil;
import com.liferay.portal.model.*;
import com.liferay.portal.model.impl.LayoutImpl;
import com.liferay.portal.service.*;
import com.liferay.portal.service.permission.PortletPermissionUtil;
import com.liferay.portal.service.persistence.LayoutRevisionUtil;
import com.liferay.portal.theme.ThemeLoader;
import com.liferay.portal.theme.ThemeLoaderFactory;
import com.liferay.portal.util.PortletKeys;
import com.liferay.portal.util.PropsValues;
import com.liferay.portlet.PortletPreferencesFactoryUtil;
import com.liferay.portlet.asset.NoSuchCategoryException;
import com.liferay.portlet.asset.model.AssetCategory;
import com.liferay.portlet.asset.model.AssetVocabulary;
import com.liferay.portlet.asset.service.AssetCategoryLocalServiceUtil;
import com.liferay.portlet.asset.service.AssetVocabularyLocalServiceUtil;
import com.liferay.portlet.asset.service.persistence.AssetCategoryUtil;
import com.liferay.portlet.journal.NoSuchArticleException;
import com.liferay.portlet.journal.lar.JournalPortletDataHandlerImpl;
import com.liferay.portlet.journal.model.JournalArticle;
import com.liferay.portlet.journal.service.JournalArticleLocalServiceUtil;
import com.liferay.util.ContentUtil;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang.time.StopWatch;

import javax.servlet.ServletContext;
import java.io.File;
import java.util.*;

/**
 * @author Brian Wing Shun Chan
 * @author Joel Kozikowski
 * @author Charles May
 * @author Raymond Augé
 * @author Jorge Ferrer
 * @author Bruno Farache
 * @author Karthik Sudarshan
 * @author Zsigmond Rab
 * @author Douglas Wong
 * @author Mate Thurzo
 */
public class LayoutExporter {

	public static final String SAME_GROUP_FRIENDLY_URL =
			"/[$SAME_GROUP_FRIENDLY_URL$]";

	public static List<Portlet> getAlwaysExportablePortlets(long companyId)
			throws Exception {

		List<Portlet> portlets = PortletLocalServiceUtil.getPortlets(companyId);

		Iterator<Portlet> itr = portlets.iterator();

		while (itr.hasNext()) {
			Portlet portlet = itr.next();

			if (!portlet.isActive()) {
				itr.remove();

				continue;
			}

			PortletDataHandler portletDataHandler =
					portlet.getPortletDataHandlerInstance();

			if ((portletDataHandler == null) ||
					!portletDataHandler.isAlwaysExportable()) {

				itr.remove();
			}
		}

		return portlets;
	}

	public static void updateLastPublishDate(
			LayoutSet layoutSet, long lastPublishDate)
			throws Exception {

		UnicodeProperties settingsProperties =
				layoutSet.getSettingsProperties();

		if (lastPublishDate <= 0) {
			settingsProperties.remove("last-publish-date");
		}
		else {
			settingsProperties.setProperty(
					"last-publish-date", String.valueOf(lastPublishDate));
		}

		LayoutSetLocalServiceUtil.updateSettings(
				layoutSet.getGroupId(), layoutSet.isPrivateLayout(),
				settingsProperties.toString());
	}

	public byte[] exportLayouts(
			long groupId, boolean privateLayout, long[] layoutIds,
			Map<String, String[]> parameterMap, Date startDate, Date endDate)
			throws Exception {

		File file = exportLayoutsAsFile(
				groupId, privateLayout, layoutIds, parameterMap, startDate,
				endDate);

		try {
			return FileUtil.getBytes(file);
		}
		finally {
			file.delete();
		}
	}

	public File exportLayoutsAsFile(
			long groupId, boolean privateLayout, long[] layoutIds,
			Map<String, String[]> parameterMap, Date startDate, Date endDate)
			throws Exception {

		try {
			ImportExportThreadLocal.setLayoutExportInProcess(true);

			return doExportLayoutsAsFile(
					groupId, privateLayout, layoutIds, parameterMap, startDate,
					endDate);
		}
		finally {
			ImportExportThreadLocal.setLayoutExportInProcess(false);
		}
	}

	protected File doExportLayoutsAsFile(
			long groupId, boolean privateLayout, long[] layoutIds,
			Map<String, String[]> parameterMapIn, Date startDate, Date endDate)
			throws Exception {

		//Make sure the parameterMap modifiable
		Map<String, String[]> parameterMap = new HashMap<String, String[]>(parameterMapIn);
		boolean exportCategories = MapUtil.getBoolean(
				parameterMap, PortletDataHandlerKeys.CATEGORIES);
		boolean exportIgnoreLastPublishDate = MapUtil.getBoolean(
				parameterMap, PortletDataHandlerKeys.IGNORE_LAST_PUBLISH_DATE);
		boolean exportPermissions = MapUtil.getBoolean(
				parameterMap, PortletDataHandlerKeys.PERMISSIONS);
		boolean exportUserPermissions = MapUtil.getBoolean(
				parameterMap, PortletDataHandlerKeys.USER_PERMISSIONS);
		boolean exportPortletArchivedSetups = MapUtil.getBoolean(
				parameterMap, PortletDataHandlerKeys.PORTLET_ARCHIVED_SETUPS);
		boolean exportPortletUserPreferences = MapUtil.getBoolean(
				parameterMap, PortletDataHandlerKeys.PORTLET_USER_PREFERENCES);
		boolean exportTheme = MapUtil.getBoolean(
				parameterMap, PortletDataHandlerKeys.THEME);
		boolean exportThemeSettings = MapUtil.getBoolean(
				parameterMap, PortletDataHandlerKeys.THEME_REFERENCE);
		boolean exportLogo = MapUtil.getBoolean(
				parameterMap, PortletDataHandlerKeys.LOGO);
		boolean exportLayoutSetSettings = MapUtil.getBoolean(
				parameterMap, PortletDataHandlerKeys.LAYOUT_SET_SETTINGS);
		//boolean publishToRemote = MapUtil.getBoolean(
		//	parameterMap, PortletDataHandlerKeys.PUBLISH_TO_REMOTE);
		boolean updateLastPublishDate = MapUtil.getBoolean(
				parameterMap, PortletDataHandlerKeys.UPDATE_LAST_PUBLISH_DATE);

		if (_log.isDebugEnabled()) {
			_log.debug("Export categories " + exportCategories);
			_log.debug("Export permissions " + exportPermissions);
			_log.debug("Export user permissions " + exportUserPermissions);
			_log.debug(
					"Export portlet archived setups " +
							exportPortletArchivedSetups);
			_log.debug(
					"Export portlet user preferences " +
							exportPortletUserPreferences);
			_log.debug("Export theme " + exportTheme);
		}

		LayoutSet layoutSet = LayoutSetLocalServiceUtil.getLayoutSet(
				groupId, privateLayout);

		long companyId = layoutSet.getCompanyId();
		long defaultUserId = UserLocalServiceUtil.getDefaultUserId(companyId);

		ServiceContext serviceContext =
				ServiceContextThreadLocal.getServiceContext();

		if (serviceContext == null) {
			serviceContext = new ServiceContext();

			serviceContext.setCompanyId(companyId);
			serviceContext.setSignedIn(false);
			serviceContext.setUserId(defaultUserId);

			ServiceContextThreadLocal.pushServiceContext(serviceContext);
		}

		serviceContext.setAttribute("exporting", Boolean.TRUE);

		long layoutSetBranchId = MapUtil.getLong(
				parameterMap, "layoutSetBranchId");

		serviceContext.setAttribute("layoutSetBranchId", layoutSetBranchId);

		long lastPublishDate = System.currentTimeMillis();

		if (endDate != null) {
			lastPublishDate = endDate.getTime();
		}

		if (exportIgnoreLastPublishDate) {
			endDate = null;
			startDate = null;
		}

		// ***************************************
		// EXT Override Start: Charles Im
		// ***************************************

		boolean exportTags = MapUtil.getBoolean(
				parameterMap, ExtPortletDataHandlerKeys.ASSET_TAGS);

		if (_log.isDebugEnabled()) {
			_log.debug("Export tags " + exportTags);
		}

		// ***************************************
		// EXT Override End
		// ***************************************

		StopWatch stopWatch = null;

		if (_log.isInfoEnabled()) {
			stopWatch = new StopWatch();

			stopWatch.start();
		}

		LayoutCache layoutCache = new LayoutCache();

		ZipWriter zipWriter = ZipWriterFactoryUtil.getZipWriter();

		PortletDataContext portletDataContext = new PortletDataContextImpl(
				companyId, groupId, parameterMap, new HashSet<String>(), startDate,
				endDate, zipWriter);

		portletDataContext.setPortetDataContextListener(
				new PortletDataContextListenerImpl(portletDataContext));

		Document document = SAXReaderUtil.createDocument();

		Element rootElement = document.addElement("root");

		Element headerElement = rootElement.addElement("header");

		headerElement.addAttribute(
				"available-locales",
				StringUtil.merge(LanguageUtil.getAvailableLocales()));
		headerElement.addAttribute(
				"build-number", String.valueOf(ReleaseInfo.getBuildNumber()));
		headerElement.addAttribute("export-date", Time.getRFC822());

		if (portletDataContext.hasDateRange()) {
			headerElement.addAttribute(
					"start-date",
					String.valueOf(portletDataContext.getStartDate()));
			headerElement.addAttribute(
					"end-date", String.valueOf(portletDataContext.getEndDate()));
		}

		Group companyGroup = GroupLocalServiceUtil.getCompanyGroup(companyId);

		headerElement.addAttribute(
				"company-group-id", String.valueOf(companyGroup.getGroupId()));

		headerElement.addAttribute("group-id", String.valueOf(groupId));
		headerElement.addAttribute(
				"private-layout", String.valueOf(privateLayout));

		Group group = layoutSet.getGroup();

		String type = "layout-set";

		if (group.isLayoutPrototype()) {
			type = "layout-prototype";

			LayoutPrototype layoutPrototype =
					LayoutPrototypeLocalServiceUtil.getLayoutPrototype(
							group.getClassPK());

			headerElement.addAttribute("type-uuid", layoutPrototype.getUuid());
		}
		else if (group.isLayoutSetPrototype()) {
			type ="layout-set-prototype";

			LayoutSetPrototype layoutSetPrototype =
					LayoutSetPrototypeLocalServiceUtil.getLayoutSetPrototype(
							group.getClassPK());

			headerElement.addAttribute(
					"type-uuid", layoutSetPrototype.getUuid());
		}

		headerElement.addAttribute("type", type);

		if (exportTheme || exportThemeSettings) {
			headerElement.addAttribute("theme-id", layoutSet.getThemeId());
			headerElement.addAttribute(
					"color-scheme-id", layoutSet.getColorSchemeId());
		}

		if (exportLogo) {
			Image image = ImageLocalServiceUtil.getImage(layoutSet.getLogoId());

			if ((image != null) && (image.getTextObj() != null)) {
				String logoPath = getLayoutSetLogoPath(portletDataContext);

				headerElement.addAttribute("logo-path", logoPath);

				portletDataContext.addZipEntry(logoPath, image.getTextObj());
			}
		}

		if (exportLayoutSetSettings) {
			Element settingsElement = headerElement.addElement("settings");

			settingsElement.addCDATA(layoutSet.getSettings());
		}

		Element cssElement = headerElement.addElement("css");

		cssElement.addCDATA(layoutSet.getCss());

		Portlet layoutConfigurationPortlet =
				PortletLocalServiceUtil.getPortletById(
						portletDataContext.getCompanyId(),
						PortletKeys.LAYOUT_CONFIGURATION);

		Map<String, Object[]> portletIds =
				new LinkedHashMap<String, Object[]>();

		List<Layout> layouts = null;

		if ((layoutIds == null) || (layoutIds.length == 0)) {
			layouts = LayoutLocalServiceUtil.getLayouts(groupId, privateLayout);
		}
		else {
			layouts = LayoutLocalServiceUtil.getLayouts(
					groupId, privateLayout, layoutIds);
		}

		List<Portlet> portlets = getAlwaysExportablePortlets(companyId);

		long plid = LayoutConstants.DEFAULT_PLID;

		if (!layouts.isEmpty()) {
			Layout firstLayout = layouts.get(0);

			plid = firstLayout.getPlid();
		}

		if (group.isStagingGroup()) {
			group = group.getLiveGroup();
		}

		for (Portlet portlet : portlets) {
			String portletId = portlet.getRootPortletId();

			if (!group.isStagedPortlet(portletId)) {
				continue;
			}

			String key = PortletPermissionUtil.getPrimaryKey(0, portletId);

			if (portletIds.get(key) == null) {
				portletIds.put(
						key,
						new Object[] {
								portletId, plid, groupId, StringPool.BLANK,
								StringPool.BLANK
						});
			}
		}

		Element layoutsElement = rootElement.addElement("layouts");

		String layoutSetPrototypeUuid = layoutSet.getLayoutSetPrototypeUuid();

		if (Validator.isNotNull(layoutSetPrototypeUuid)) {
			LayoutSetPrototype layoutSetPrototype =
					LayoutSetPrototypeLocalServiceUtil.getLayoutSetPrototypeByUuid(
							layoutSetPrototypeUuid);

			layoutsElement.addAttribute(
					"layout-set-prototype-uuid", layoutSetPrototypeUuid);

			layoutsElement.addAttribute(
					"layout-set-prototype-name",
					layoutSetPrototype.getName(LocaleUtil.getDefault()));
		}

		for (Layout layout : layouts) {
			exportLayout(
					portletDataContext, layoutConfigurationPortlet, layoutCache,
					portlets, portletIds, exportPermissions, exportUserPermissions,
					layout, layoutsElement);
		}

		if (PropsValues.PERMISSIONS_USER_CHECK_ALGORITHM < 5) {
			Element rolesElement = rootElement.addElement("roles");

			if (exportPermissions) {
				_permissionExporter.exportLayoutRoles(
						layoutCache, companyId, groupId, rolesElement);
			}
		}

		long previousScopeGroupId = portletDataContext.getScopeGroupId();

		Element portletsElement = rootElement.addElement("portlets");

		//******************************************
		// EXT Override Start: Off shore
		//******************************************
		boolean isSiteReplication = MapUtil.getBoolean(
				portletDataContext.getParameterMap(), ExtPortletDataHandlerKeys.IS_SITE_REPLICATION);
		//******************************************
		// EXT Override End
		//******************************************

		for (Map.Entry<String, Object[]> portletIdsEntry :
				portletIds.entrySet()) {

			Object[] portletObjects = portletIdsEntry.getValue();

			String portletId = null;
			plid = LayoutConstants.DEFAULT_PLID;
			long scopeGroupId = 0;
			String scopeType = StringPool.BLANK;
			String scopeLayoutUuid = null;

			if (portletObjects.length == 4) {
				portletId = (String)portletIdsEntry.getValue()[0];
				plid = (Long)portletIdsEntry.getValue()[1];
				scopeGroupId = (Long)portletIdsEntry.getValue()[2];
				scopeLayoutUuid = (String)portletIdsEntry.getValue()[3];
			}
			else {
				portletId = (String)portletIdsEntry.getValue()[0];
				plid = (Long)portletIdsEntry.getValue()[1];
				scopeGroupId = (Long)portletIdsEntry.getValue()[2];
				scopeType = (String)portletIdsEntry.getValue()[3];
				scopeLayoutUuid = (String)portletIdsEntry.getValue()[4];
			}

			Layout layout = LayoutLocalServiceUtil.fetchLayout(plid);

			if (layout == null) {
				if (!group.isCompany() &&
						(plid <= LayoutConstants.DEFAULT_PLID)) {

					continue;
				}

				if (_log.isWarnEnabled()) {
					_log.warn(
							"Assuming global scope because no layout was found");
				}

				layout = new LayoutImpl();

				layout.setGroupId(groupId);
				layout.setCompanyId(companyId);
			}

			portletDataContext.setPlid(plid);
			portletDataContext.setOldPlid(plid);
			portletDataContext.setScopeGroupId(scopeGroupId);
			portletDataContext.setScopeType(scopeType);
			portletDataContext.setScopeLayoutUuid(scopeLayoutUuid);

			boolean[] exportPortletControls = getExportPortletControls(
					companyId, portletId, portletDataContext, parameterMap, type);

			//******************************************
			// EXT Override Start: Off shore
			//******************************************
			// Add parameter to say we're exporting portlets of page templates
			if (isSiteReplication && portletObjects.length == 6
					&& (portletObjects[5] instanceof Boolean) && ((Boolean) portletObjects[5])) {
				portletDataContext.getParameterMap().put(ExtPortletDataHandlerKeys.IS_EXPORTING_PAGE_TEMPLATE,
						new String[] { Boolean.TRUE.toString() });
			}
			//******************************************
			// EXT Override End
			//******************************************

			_portletExporter.exportPortlet(
					portletDataContext, layoutCache, portletId, layout,
					portletsElement, defaultUserId, exportPermissions,
					exportPortletArchivedSetups, exportPortletControls[0],
					exportPortletControls[1], exportPortletUserPreferences,
					exportUserPermissions);

			//******************************************
			// EXT Override Start: Off shore
			//******************************************
			// reset that parameter
			portletDataContext.getParameterMap().put(ExtPortletDataHandlerKeys.IS_EXPORTING_PAGE_TEMPLATE,
					new String[] { Boolean.FALSE.toString() });
			//******************************************
			// EXT Override End
			//******************************************
		}

		portletDataContext.setScopeGroupId(previousScopeGroupId);

		exportAssetCategories(portletDataContext);

		_portletExporter.exportAssetLinks(portletDataContext);
		// ***************************************
		// EXT Override Start: Charles Im
		// ***************************************
		if (exportTags) {
			_portletExporter.exportAssetTags(portletDataContext);
		}
		// ***************************************
		// EXT Override End
		// ***************************************
		_portletExporter.exportComments(portletDataContext);
		_portletExporter.exportExpandoTables(portletDataContext);
		_portletExporter.exportLocks(portletDataContext);

		if (exportPermissions) {
			_permissionExporter.exportPortletDataPermissions(
					portletDataContext);
		}

		_portletExporter.exportRatingsEntries(portletDataContext, rootElement);

		if (exportTheme && !portletDataContext.isPerformDirectBinaryImport()) {
			exportTheme(layoutSet, zipWriter);
		}

		if (_log.isInfoEnabled()) {
			if (stopWatch != null) {
				_log.info(
						"Exporting layouts takes " + stopWatch.getTime() + " ms");
			}
			else {
				_log.info("Exporting layouts is finished");
			}
		}

		portletDataContext.addZipEntry(
				"/manifest.xml", document.formattedString());

		try {
			return zipWriter.getFile();
		}
		finally {
			if (updateLastPublishDate) {
				updateLastPublishDate(layoutSet, lastPublishDate);
			}
		}
	}

	protected void exportAssetCategories(PortletDataContext portletDataContext)
			throws Exception {

		Document document = SAXReaderUtil.createDocument();

		Element rootElement = document.addElement("categories-hierarchy");

		Element assetVocabulariesElement = rootElement.addElement(
				"vocabularies");

		// ***************************************
		// EXT Override Start: Charles Im
		// ***************************************

		boolean exportCategories = MapUtil.getBoolean(
				portletDataContext.getParameterMap(), PortletDataHandlerKeys.CATEGORIES);

		// if scoped article, then only export vocabularies that are associated with article
		String[] vocabularyIds = portletDataContext.getParameterMap().get(ExtPortletDataHandlerKeys.SCOPED_VOCABULARIES);

		List<AssetVocabulary> assetVocabularies = null;
		if (exportCategories) {
			assetVocabularies =
					AssetVocabularyLocalServiceUtil.getGroupVocabularies(
							portletDataContext.getGroupId());
		} else if ((vocabularyIds != null) && (vocabularyIds.length > 0)) {
			long[] vocabIds = new long[vocabularyIds.length];
			for (int i = 0; i < vocabularyIds.length; i++) {
				if (NumberUtils.isDigits(vocabularyIds[i]))
					vocabIds[i] = Long.parseLong(vocabularyIds[i]);
			}
			assetVocabularies = AssetVocabularyLocalServiceUtil.getVocabularies(vocabIds);
		}
		if (assetVocabularies != null) {
			for (AssetVocabulary assetVocabulary : assetVocabularies) {
				_portletExporter.exportAssetVocabulary(
						portletDataContext, assetVocabulariesElement, assetVocabulary);
			}
		}

		Element categoriesElement = rootElement.addElement("categories");

		// if scoped article, then only export categories that are associated with article
		String[] categoryIds = portletDataContext.getParameterMap().get(ExtPortletDataHandlerKeys.SCOPED_CATEGORIES);

		List<AssetCategory> assetCategories = null;
		if (exportCategories) {
			assetCategories = AssetCategoryUtil.findByGroupId(portletDataContext.getGroupId());
		} else if ((categoryIds != null) && (categoryIds.length > 0)) {
			for (int i = 0; i < categoryIds.length; i++) {
				if (NumberUtils.isDigits(categoryIds[i])) {
					long assetCategoryId = Long.parseLong(categoryIds[i]);

					try {
						AssetCategory category = AssetCategoryLocalServiceUtil.getAssetCategory(assetCategoryId);
						if (assetCategories == null)
							assetCategories = new ArrayList<AssetCategory>();

						assetCategories.add(category);
					} catch (NoSuchCategoryException nsc) {
						// do nothing
					}
				}
			}
		}
		if (assetCategories != null) {
			for (AssetCategory assetCategory : assetCategories) {
				_portletExporter.exportAssetCategory(
						portletDataContext, assetVocabulariesElement, categoriesElement,
						assetCategory);
			}
		}
		// ***************************************
		// EXT Override End
		// ***************************************

		_portletExporter.exportAssetCategories(portletDataContext, rootElement);

		portletDataContext.addZipEntry(
				portletDataContext.getRootPath() + "/categories-hierarchy.xml",
				document.formattedString());
	}

	protected void exportJournalArticle(
			PortletDataContext portletDataContext, Layout layout,
			Element layoutElement)
			throws Exception {

		UnicodeProperties typeSettingsProperties =
				layout.getTypeSettingsProperties();

		String articleId = typeSettingsProperties.getProperty(
				"article-id", StringPool.BLANK);

		long articleGroupId = layout.getGroupId();

		if (Validator.isNull(articleId)) {
			if (_log.isWarnEnabled()) {
				_log.warn(
						"No article id found in typeSettings of layout " +
								layout.getPlid());
			}
		}

		JournalArticle article = null;

		try {
			article = JournalArticleLocalServiceUtil.getLatestArticle(
					articleGroupId, articleId, WorkflowConstants.STATUS_APPROVED);
		}
		catch (NoSuchArticleException nsae) {
			if (_log.isWarnEnabled()) {
				_log.warn(
						"No approved article found with group id " +
								articleGroupId + " and article id " + articleId);
			}
		}

		if (article == null) {
			return;
		}

		// Check skip content while site replication
		boolean isSiteReplication = MapUtil.getBoolean(
				portletDataContext.getParameterMap(), ExtPortletDataHandlerKeys.IS_SITE_REPLICATION);
		boolean skipContent = false;
		if (isSiteReplication) {
			skipContent = false;
			String skipStructureIds = ConfigurationPropertyLocalServiceUtil.getString(article.getCompanyId(), article.getGroupId(),
					ExtPortletDataHandlerKeys.SKIP_CONTENT_STRUCTURES);
			if (StringUtils.isNotEmpty(skipStructureIds)) {
				String ids[] = skipStructureIds.split(";");
				for (String id : ids) {
					if (article.getStructureId().equals(id)) {
						skipContent = true;
						break;
					}
				}
			}
		}
		if (skipContent) {
			return;
		}
		String path = JournalPortletDataHandlerImpl.getArticlePath(
				portletDataContext, article);

		Element articleElement = layoutElement.addElement("article");

		articleElement.addAttribute("path", path);

		Element dlFileEntryTypesElement = layoutElement.addElement(
				"dl-file-entry-types");
		Element dlFoldersElement = layoutElement.addElement("dl-folders");
		Element dlFilesElement = layoutElement.addElement("dl-file-entries");
		Element dlFileRanksElement = layoutElement.addElement("dl-file-ranks");
		Element dlRepositoriesElement = layoutElement.addElement(
				"dl-repositories");
		Element dlRepositoryEntriesElement = layoutElement.addElement(
				"dl-repository-entries");

		JournalPortletDataHandlerImpl.exportArticle(
				portletDataContext, layoutElement, layoutElement, layoutElement,
				dlFileEntryTypesElement, dlFoldersElement, dlFilesElement,
				dlFileRanksElement, dlRepositoriesElement,
				dlRepositoryEntriesElement, article, false);
	}

	protected void exportLayout(
			PortletDataContext portletDataContext,
			Portlet layoutConfigurationPortlet, LayoutCache layoutCache,
			List<Portlet> portlets, Map<String, Object[]> portletIds,
			boolean exportPermissions, boolean exportUserPermissions,
			Layout layout, Element layoutsElement)
			throws Exception {

		//******************************************
		// EXT Override Start: Off shore
		//******************************************
		boolean isSiteReplication = MapUtil.getBoolean(
				portletDataContext.getParameterMap(), ExtPortletDataHandlerKeys.IS_SITE_REPLICATION);
		//******************************************
		// EXT Override End
		//******************************************


		String path = portletDataContext.getLayoutPath(
				layout.getLayoutId()) + "/layout.xml";

		if (!portletDataContext.isPathNotProcessed(path)) {
			return;
		}

		LayoutRevision layoutRevision = null;

		ServiceContext serviceContext =
				ServiceContextThreadLocal.getServiceContext();

		boolean exportLAR = ParamUtil.getBoolean(serviceContext, "exportLAR");

		if (!exportLAR && LayoutStagingUtil.isBranchingLayout(layout) &&
				!layout.isTypeURL()) {

			long layoutSetBranchId = ParamUtil.getLong(
					serviceContext, "layoutSetBranchId");

			if (layoutSetBranchId <= 0) {
				return;
			}

			layoutRevision = LayoutRevisionUtil.fetchByL_H_P(
					layoutSetBranchId, true, layout.getPlid());

			if (layoutRevision == null) {
				return;
			}

			LayoutStagingHandler layoutStagingHandler =
					LayoutStagingUtil.getLayoutStagingHandler(layout);

			layoutStagingHandler.setLayoutRevision(layoutRevision);
		}

		Element layoutElement = layoutsElement.addElement("layout");

		if (layoutRevision != null) {
			layoutElement.addAttribute(
					"layout-revision-id",
					String.valueOf(layoutRevision.getLayoutRevisionId()));
			layoutElement.addAttribute(
					"layout-branch-id",
					String.valueOf(layoutRevision.getLayoutBranchId()));
			layoutElement.addAttribute(
					"layout-branch-name",
					String.valueOf(layoutRevision.getLayoutBranch().getName()));
		}

		layoutElement.addAttribute("layout-uuid", layout.getUuid());
		layoutElement.addAttribute(
				"layout-id", String.valueOf(layout.getLayoutId()));

		long parentLayoutId = layout.getParentLayoutId();

		if (parentLayoutId != LayoutConstants.DEFAULT_PARENT_LAYOUT_ID) {
			Layout parentLayout = LayoutLocalServiceUtil.getLayout(
					layout.getGroupId(), layout.isPrivateLayout(), parentLayoutId);

			if (parentLayout != null) {
				layoutElement.addAttribute(
						"parent-layout-uuid", parentLayout.getUuid());
			}
		}

		String layoutPrototypeUuid = layout.getLayoutPrototypeUuid();

		if (Validator.isNotNull(layoutPrototypeUuid)) {
			LayoutPrototype layoutPrototype =
					LayoutPrototypeLocalServiceUtil.getLayoutPrototypeByUuid(
							layoutPrototypeUuid);

			layoutElement.addAttribute(
					"layout-prototype-uuid", layoutPrototypeUuid);
			layoutElement.addAttribute(
					"layout-prototype-name",
					layoutPrototype.getName(LocaleUtil.getDefault()));
		}

		boolean deleteLayout = MapUtil.getBoolean(
				portletDataContext.getParameterMap(), "delete_" + layout.getPlid());

		if (deleteLayout) {
			layoutElement.addAttribute("delete", String.valueOf(true));

			return;
		}

		portletDataContext.setPlid(layout.getPlid());

		if (layout.isIconImage()) {
			Image image = ImageLocalServiceUtil.getImage(
					layout.getIconImageId());

			if (image != null) {
				String iconPath = getLayoutIconPath(
						portletDataContext, layout, image);

				layoutElement.addElement("icon-image-path").addText(iconPath);

				portletDataContext.addZipEntry(iconPath, image.getTextObj());
			}
		}

		_portletExporter.exportPortletData(
				portletDataContext, layoutConfigurationPortlet, layout, null,
				layoutElement);

		// Layout permissions

		if (exportPermissions) {
			_permissionExporter.exportLayoutPermissions(
					portletDataContext, layoutCache,
					portletDataContext.getCompanyId(),
					portletDataContext.getScopeGroupId(), layout, layoutElement,
					exportUserPermissions);
		}

		if (layout.isTypeArticle()) {
			exportJournalArticle(portletDataContext, layout, layoutElement);
		}
		else if (layout.isTypeLinkToLayout()) {
			UnicodeProperties typeSettingsProperties =
					layout.getTypeSettingsProperties();

			long linkToLayoutId = GetterUtil.getLong(
					typeSettingsProperties.getProperty(
							"linkToLayoutId", StringPool.BLANK));

			if (linkToLayoutId > 0) {
				try {
					Layout linkedToLayout = LayoutLocalServiceUtil.getLayout(
							portletDataContext.getScopeGroupId(),
							layout.isPrivateLayout(), linkToLayoutId);

					exportLayout(
							portletDataContext, layoutConfigurationPortlet,
							layoutCache, portlets, portletIds, exportPermissions,
							exportUserPermissions, linkedToLayout, layoutsElement);
				}
				catch (NoSuchLayoutException nsle) {
				}
			}
		}
		else if (layout.isTypePortlet()) {
			for (Portlet portlet : portlets) {
				if (portlet.isScopeable() && layout.hasScopeGroup()) {
					String key = PortletPermissionUtil.getPrimaryKey(
							layout.getPlid(), portlet.getPortletId());

					portletIds.put(
							key,
							new Object[] {
									portlet.getPortletId(), layout.getPlid(),
									layout.getScopeGroup().getGroupId(),
									StringPool.BLANK, layout.getUuid()
							});
				}
			}

			LayoutTypePortlet layoutTypePortlet =
					(LayoutTypePortlet)layout.getLayoutType();

			for (String portletId : layoutTypePortlet.getPortletIds()) {
				javax.portlet.PortletPreferences jxPreferences =
						PortletPreferencesFactoryUtil.getLayoutPortletSetup(
								layout, portletId);

				String scopeType = GetterUtil.getString(
						jxPreferences.getValue("lfrScopeType", null));
				String scopeLayoutUuid = GetterUtil.getString(
						jxPreferences.getValue("lfrScopeLayoutUuid", null));

				long scopeGroupId = portletDataContext.getScopeGroupId();

				if (Validator.isNotNull(scopeType)) {
					Group scopeGroup = null;

					if (scopeType.equals("company")) {
						scopeGroup = GroupLocalServiceUtil.getCompanyGroup(
								layout.getCompanyId());
					}
					else if (scopeType.equals("layout")) {
						Layout scopeLayout = null;

						scopeLayout =
								LayoutLocalServiceUtil.fetchLayoutByUuidAndGroupId(
										scopeLayoutUuid,
										portletDataContext.getGroupId(),
										portletDataContext.isPrivateLayout());

						if (scopeLayout == null) {
							continue;
						}

						scopeGroup = scopeLayout.getScopeGroup();
					}
					else {
						throw new IllegalArgumentException(
								"Scope type " + scopeType + " is invalid");
					}

					if (scopeGroup != null) {
						scopeGroupId = scopeGroup.getGroupId();
					}
				}

				String key = PortletPermissionUtil.getPrimaryKey(
						layout.getPlid(), portletId);

				portletIds.put(
						key,
						new Object[] {
								portletId, layout.getPlid(), scopeGroupId, scopeType,
								scopeLayoutUuid
						}
				);
			}
		}

		fixTypeSettings(layout);

		//******************************************
		// EXT Override Start: Off shore
		//******************************************
		// export layout prototype
		if (isSiteReplication) {
			if (StringUtils.isNotEmpty(layout.getLayoutPrototypeUuid())) {
				LayoutPrototype proto = LayoutPrototypeLocalServiceUtil.getLayoutPrototypeByUuid(layout.getLayoutPrototypeUuid());

				Map<String, String> protoLayoutImportedPathMap =
						(Map<String, String>) portletDataContext.getNewPrimaryKeysMap(
								SiteReplicationUtil.KEY_PROTO_LAYOUT_EXPORTED_PATH);
				String protoLayoutPath = protoLayoutImportedPathMap.get(proto.getUuid());
				if (protoLayoutPath != null) {
					// have been exported successfully
					layoutElement.addAttribute(SiteReplicationUtil.ATTR_PROTO_LAYOUT_PATH, protoLayoutPath);
				} else {
					long previousScopeGroupId = portletDataContext.getScopeGroupId();

					try {
						exportProtoLayout(
								portletDataContext, layoutConfigurationPortlet,
								portlets, portletIds, proto, layoutsElement, layoutElement);
					} catch (Exception e) {
						_log.error("Error while exporting page template layout", e);
					} finally {
						portletDataContext.setScopeGroupId(previousScopeGroupId);
					}
				}
			}
		}
		//******************************************
		// EXT Override End
		//******************************************

		layoutElement.addAttribute("path", path);

		portletDataContext.addClassedModel(
				layoutElement, path, layout, "layoutsadmin");

		portletDataContext.addExpando(layoutElement, path, layout);

		portletDataContext.addZipEntry(path, layout);

		//******************************************
		// EXT Override Start: Off shore
		//******************************************
		// add a extend information of layout to track
		if (isSiteReplication) {
			String pathExtend = SiteReplicationExportUtil.getLayoutExtendPath(path);
			boolean isLayoutWithSiteSpecificCategory =
					SiteReplicationExportUtil.isLayoutWithSiteSpecificCategory(portletDataContext, layout.getPlid());

			Document document = SAXReaderUtil.createDocument();
			Element rootElement = document.addElement(SiteReplicationUtil.LAYOUT_EXTEND_ELEMENT_NAME);

			Element isLayoutWithSiteSpecificCategoryElement =
					rootElement.addElement(SiteReplicationUtil.IS_LAYOUT_WITH_SITE_SPECIFIC_CATEGORY_ELEMENT_NAME);
			isLayoutWithSiteSpecificCategoryElement.addAttribute(SiteReplicationUtil.ATTR_VALUE,
					Boolean.toString(isLayoutWithSiteSpecificCategory));
			portletDataContext.addZipEntry(pathExtend, document.formattedString());
		}
		//******************************************
		// EXT Override end
		//******************************************
	}

	//******************************************
	// EXT Override Start: Off shore
	//******************************************

	/**
	 * Export layout prototype related layout(with portlets/web contents)
	 *
	 * @param portletDataContext
	 * @param layoutConfigurationPortlet
	 * @param portlets
	 * @param portletIds
	 * @param proto
	 * @param layoutsElement
	 * @param relatedLayout
	 * @throws Exception
	 */
	protected void exportProtoLayout(PortletDataContext portletDataContext, Portlet layoutConfigurationPortlet,
									 List<Portlet> portlets, Map<String, Object[]> portletIds, LayoutPrototype proto,
									 Element layoutsElement, Element relatedLayout)
			throws Exception {

		Layout layout = proto.getLayout();

		long previousScopeGroupId = portletDataContext.getScopeGroupId();

		if (layout.getGroupId() != previousScopeGroupId) {
			portletDataContext.setScopeGroupId(layout.getGroupId());
		}

		String path = portletDataContext.getLayoutPath(layout.getLayoutId()) + "/layout.xml";

		if (!portletDataContext.isPathNotProcessed(path)) {
			portletDataContext.setScopeGroupId(previousScopeGroupId);
			return;
		}

		try {
			// get layout prototype suffix
			long companyId = layout.getCompanyId();
			long sourceGroupId = portletDataContext.getSourceGroupId();
			String[] pageTemplates;
			String templateName = proto.getName(Locale.getDefault());
			String pageTemplateStr = ConfigurationPropertyLocalServiceUtil.getString(companyId, sourceGroupId,
					ExtPortletDataHandlerKeys.PAGE_TEMPLATES);

			if (StringUtils.isNotEmpty(pageTemplateStr)) {
				pageTemplates = pageTemplateStr.split(";");
			} else {
				String storyTemplateNamePart = "Story Display Page";
				String galleryTemplateNamePart = "Gallery Display Page";
				pageTemplates = new String[] { storyTemplateNamePart, galleryTemplateNamePart };
			}
			int matchedIndex = -1;
			for (int i = 0; i < pageTemplates.length; i++) {
				String pageTemplateName = pageTemplates[i];
				if (StringUtils.isNotEmpty(pageTemplateName) && templateName.indexOf(pageTemplateName) >= 0) {
					matchedIndex = i;
					break;
				}
			}
			if (matchedIndex == -1) {
				StringBuilder message = new StringBuilder();
				message.append("Cannot match page template name {").append(templateName)
						.append("} with these suffixes: {");
				for (int i = 0; i < pageTemplates.length; i++) {
					if (i == 0) {
						message.append(pageTemplates[i]);
					} else {
						message.append(StringPool.COMMA + pageTemplates[i]);
					}
				}
				message.append("} during exporting layout whose uuid is {");
				message.append(layout.getUuid());
				message.append("} while prosessing site replication!");
				throw new Exception(message.toString());
			}
			String pageTemplateSuffix = pageTemplates[matchedIndex];

			// import protolayout
			Element layoutElement = layoutsElement.addElement("protolayout");

			layoutElement.addAttribute("layout-uuid", layout.getUuid());
			layoutElement.addAttribute("layout-id", String.valueOf(layout.getLayoutId()));

			portletDataContext.setPlid(layout.getPlid());

			_portletExporter.exportPortletData(portletDataContext, layoutConfigurationPortlet, layout, null,
					layoutElement);

			if (layout.isTypePortlet()) {
				for (Portlet portlet : portlets) {
					if (portlet.isScopeable() && layout.hasScopeGroup()) {
						String key = PortletPermissionUtil.getPrimaryKey(layout.getPlid(), portlet.getPortletId());

						portletIds.put(key, new Object[] { portlet.getPortletId(), layout.getPlid(),
								layout.getScopeGroup().getGroupId(), StringPool.BLANK, layout.getUuid() });
					}
				}

				LayoutTypePortlet layoutTypePortlet = (LayoutTypePortlet) layout.getLayoutType();

				for (String portletId : layoutTypePortlet.getPortletIds()) {
					javax.portlet.PortletPreferences jxPreferences = PortletPreferencesFactoryUtil
							.getLayoutPortletSetup(layout, portletId);

					String scopeType = GetterUtil.getString(jxPreferences.getValue("lfrScopeType", null));
					String scopeLayoutUuid = GetterUtil.getString(jxPreferences.getValue("lfrScopeLayoutUuid", null));

					long scopeGroupId = portletDataContext.getScopeGroupId();

					if (Validator.isNotNull(scopeType)) {
						Group scopeGroup = null;

						if (scopeType.equals("company")) {
							scopeGroup = GroupLocalServiceUtil.getCompanyGroup(layout.getCompanyId());
						} else if (scopeType.equals("layout")) {
							Layout scopeLayout = null;

							scopeLayout = LayoutLocalServiceUtil.fetchLayoutByUuidAndGroupId(scopeLayoutUuid,
									portletDataContext.getGroupId(), portletDataContext.isPrivateLayout());

							if (scopeLayout == null) {
								continue;
							}

							scopeGroup = scopeLayout.getScopeGroup();
						} else {
							throw new IllegalArgumentException("Scope type " + scopeType + " is invalid");
						}

						if (scopeGroup != null) {
							scopeGroupId = scopeGroup.getGroupId();
						}
					}

					String key = PortletPermissionUtil.getPrimaryKey(layout.getPlid(), portletId);

					portletIds.put(key, new Object[] { portletId, layout.getPlid(), scopeGroupId, scopeType,
							scopeLayoutUuid, Boolean.TRUE });
				}
			}

			fixTypeSettings(layout);

			layoutElement.addAttribute("path", path);

			portletDataContext.addClassedModel(layoutElement, path, layout, "layoutsadmin");

			portletDataContext.addExpando(layoutElement, path, layout);

			portletDataContext.addZipEntry(path, layout);

			String pathExtend = SiteReplicationUtil.getLayoutExtendPath(path);
			boolean isLayoutWithSiteSpecificCategory = false;

			Document document = SAXReaderUtil.createDocument();
			Element rootElement = document.addElement(SiteReplicationUtil.LAYOUT_EXTEND_ELEMENT_NAME);

			Element isLayoutWithSiteSpecificCategoryElement = rootElement
					.addElement(SiteReplicationUtil.IS_LAYOUT_WITH_SITE_SPECIFIC_CATEGORY_ELEMENT_NAME);
			isLayoutWithSiteSpecificCategoryElement.addAttribute(SiteReplicationUtil.ATTR_VALUE,
					Boolean.toString(isLayoutWithSiteSpecificCategory));

			// add prototype name pattern i.e. "Story Display Page" to attribute
			Element pageTemplateSuffixElement = rootElement
					.addElement(SiteReplicationUtil.PAGE_TEMPLATE_SUFFIX_ELEMENT_NAME);
			pageTemplateSuffixElement.addAttribute(SiteReplicationUtil.ATTR_VALUE, pageTemplateSuffix);

			portletDataContext.addZipEntry(pathExtend, document.formattedString());

			relatedLayout.addAttribute(SiteReplicationUtil.ATTR_PROTO_LAYOUT_PATH, path);

			Map<String, String> protoLayoutImportedPathMap =
					(Map<String, String>) portletDataContext.getNewPrimaryKeysMap(
							SiteReplicationUtil.KEY_PROTO_LAYOUT_EXPORTED_PATH);
			protoLayoutImportedPathMap.put(proto.getUuid(), path);

		} finally {
			portletDataContext.setScopeGroupId(previousScopeGroupId);
		}
	}
	//******************************************
	// EXT Override end
	//******************************************



	protected void exportTheme(LayoutSet layoutSet, ZipWriter zipWriter)
			throws Exception {

		Theme theme = layoutSet.getTheme();

		String lookAndFeelXML = ContentUtil.get(
				"com/liferay/portal/dependencies/liferay-look-and-feel.xml.tmpl");

		lookAndFeelXML = StringUtil.replace(
				lookAndFeelXML,
				new String[] {
						"[$TEMPLATE_EXTENSION$]", "[$VIRTUAL_PATH$]"
				},
				new String[] {
						theme.getTemplateExtension(), theme.getVirtualPath()
				}
		);

		String servletContextName = theme.getServletContextName();

		ServletContext servletContext = ServletContextPool.get(
				servletContextName);

		if (servletContext == null) {
			if (_log.isWarnEnabled()) {
				_log.warn(
						"Servlet context not found for theme " +
								theme.getThemeId());
			}

			return;
		}

		File themeZip = new File(zipWriter.getPath() + "/theme.zip");

		ZipWriter themeZipWriter = ZipWriterFactoryUtil.getZipWriter(themeZip);

		themeZipWriter.addEntry("liferay-look-and-feel.xml", lookAndFeelXML);

		File cssPath = null;
		File imagesPath = null;
		File javaScriptPath = null;
		File templatesPath = null;

		if (!theme.isLoadFromServletContext()) {
			ThemeLoader themeLoader = ThemeLoaderFactory.getThemeLoader(
					servletContextName);

			if (themeLoader == null) {
				_log.error(
						servletContextName + " does not map to a theme loader");
			}
			else {
				String realPath =
						themeLoader.getFileStorage().getPath() + StringPool.SLASH +
								theme.getName();

				cssPath = new File(realPath + "/css");
				imagesPath = new File(realPath + "/images");
				javaScriptPath = new File(realPath + "/javascript");
				templatesPath = new File(realPath + "/templates");
			}
		}
		else {
			cssPath = new File(servletContext.getRealPath(theme.getCssPath()));
			imagesPath = new File(
					servletContext.getRealPath(theme.getImagesPath()));
			javaScriptPath = new File(
					servletContext.getRealPath(theme.getJavaScriptPath()));
			templatesPath = new File(
					servletContext.getRealPath(theme.getTemplatesPath()));
		}

		exportThemeFiles("css", cssPath, themeZipWriter);
		exportThemeFiles("images", imagesPath, themeZipWriter);
		exportThemeFiles("javascript", javaScriptPath, themeZipWriter);
		exportThemeFiles("templates", templatesPath, themeZipWriter);
	}

	protected void exportThemeFiles(String path, File dir, ZipWriter zipWriter)
			throws Exception {

		if ((dir == null) || !dir.exists()) {
			return;
		}

		File[] files = dir.listFiles();

		for (File file : files) {
			if (file.isDirectory()) {
				exportThemeFiles(
						path + StringPool.SLASH + file.getName(), file, zipWriter);
			}
			else {
				zipWriter.addEntry(
						path + StringPool.SLASH + file.getName(),
						FileUtil.getBytes(file));
			}
		}
	}

	protected void fixTypeSettings(Layout layout) throws Exception {
		if (!layout.isTypeURL()) {
			return;
		}

		UnicodeProperties typeSettings = layout.getTypeSettingsProperties();

		String url = GetterUtil.getString(typeSettings.getProperty("url"));

		String friendlyURLPrivateGroupPath =
				PropsValues.LAYOUT_FRIENDLY_URL_PRIVATE_GROUP_SERVLET_MAPPING;
		String friendlyURLPrivateUserPath =
				PropsValues.LAYOUT_FRIENDLY_URL_PRIVATE_USER_SERVLET_MAPPING;
		String friendlyURLPublicPath =
				PropsValues.LAYOUT_FRIENDLY_URL_PUBLIC_SERVLET_MAPPING;

		if (!url.startsWith(friendlyURLPrivateGroupPath) &&
				!url.startsWith(friendlyURLPrivateUserPath) &&
				!url.startsWith(friendlyURLPublicPath)) {

			return;
		}

		int x = url.indexOf(CharPool.SLASH, 1);

		if (x == -1) {
			return;
		}

		int y = url.indexOf(CharPool.SLASH, x + 1);

		if (y == -1) {
			return;
		}

		String friendlyURL = url.substring(x, y);
		String groupFriendlyURL = layout.getGroup().getFriendlyURL();

		if (!friendlyURL.equals(groupFriendlyURL)) {
			return;
		}

		typeSettings.setProperty(
				"url",
				url.substring(0, x) + SAME_GROUP_FRIENDLY_URL + url.substring(y));
	}

	protected boolean[] getExportPortletControls(
			long companyId, String portletId,
			PortletDataContext portletDataContext,
			Map<String, String[]> parameterMap, String type)
			throws Exception {

		boolean exportPortletData = MapUtil.getBoolean(
				parameterMap, PortletDataHandlerKeys.PORTLET_DATA);
		boolean exportPortletDataAll = MapUtil.getBoolean(
				parameterMap, PortletDataHandlerKeys.PORTLET_DATA_ALL);
		boolean exportPortletSetup = MapUtil.getBoolean(
				parameterMap, PortletDataHandlerKeys.PORTLET_SETUP);
		boolean exportPortletSetupAll = MapUtil.getBoolean(
				parameterMap, PortletDataHandlerKeys.PORTLET_SETUP_ALL);

		if (_log.isDebugEnabled()) {
			_log.debug("Export portlet data " + exportPortletData);
			_log.debug("Export all portlet data " + exportPortletDataAll);
			_log.debug("Export portlet setup " + exportPortletSetup);
		}

		boolean exportCurPortletData = exportPortletData;
		boolean exportCurPortletSetup = exportPortletSetup;

		// If PORTLET_DATA_ALL is true, this means that staging has just been
		// activated and all data and setup must be exported. There is no
		// portlet export control to check in this case.

		if (exportPortletDataAll) {
			exportCurPortletData = true;
			exportCurPortletSetup = true;
		}
		else {
			Portlet portlet = PortletLocalServiceUtil.getPortletById(
					companyId, portletId);

			if (portlet != null) {
				String portletDataHandlerClass =
						portlet.getPortletDataHandlerClass();

				// Checking if the portlet has a data handler, if it doesn't,
				// the default values are the ones set in PORTLET_DATA and
				// PORTLET_SETUP. If it has a data handler, iterate over each
				// portlet export control.

				if (portletDataHandlerClass != null) {
					String rootPortletId = PortletConstants.getRootPortletId(
							portletId);

					// PORTLET_DATA and the PORTLET_DATA for this specific data
					// handler must be true

					exportCurPortletData =
							exportPortletData &&
									MapUtil.getBoolean(
											parameterMap,
											PortletDataHandlerKeys.PORTLET_DATA +
													StringPool.UNDERLINE + rootPortletId);

					// PORTLET_SETUP and the PORTLET_SETUP for this specific
					// data handler must be true

					exportCurPortletSetup =
							exportPortletSetup &&
									MapUtil.getBoolean(
											parameterMap,
											PortletDataHandlerKeys.PORTLET_SETUP +
													StringPool.UNDERLINE + rootPortletId);
				}
			}
		}

		if (exportPortletSetupAll ||
				(exportPortletSetup && type.equals("layout-prototype"))) {

			exportCurPortletSetup = true;
		}

		return new boolean[] {exportCurPortletData, exportCurPortletSetup};
	}

	protected String getLayoutIconPath(
			PortletDataContext portletDataContext, Layout layout, Image image) {

		StringBundler sb = new StringBundler(5);

		sb.append(portletDataContext.getLayoutPath(layout.getLayoutId()));
		sb.append("/icons/");
		sb.append(image.getImageId());
		sb.append(StringPool.PERIOD);
		sb.append(image.getType());

		return sb.toString();
	}

	protected String getLayoutSetLogoPath(
			PortletDataContext portletDataContext) {

		return portletDataContext.getRootPath().concat("/logo/");
	}

	protected String getLayoutSetPrototype(
			PortletDataContext portletDataContext, String layoutSetPrototypeUuid) {

		StringBundler sb = new StringBundler(3);

		sb.append(portletDataContext.getRootPath());
		sb.append("/layout-set-prototype/");
		sb.append(layoutSetPrototypeUuid);

		return sb.toString();
	}

	private static Log _log = LogFactoryUtil.getLog(LayoutExporter.class);

	private PermissionExporter _permissionExporter = new PermissionExporter();
	private PortletExporter _portletExporter = new PortletExporter();

}