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

package com.liferay.portlet.journalcontent.action;

import com.liferay.portal.kernel.portlet.DefaultConfigurationAction;
import com.liferay.portal.kernel.servlet.SessionErrors;
import com.liferay.portal.kernel.servlet.SessionMessages;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.ParamUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.model.Layout;
import com.liferay.portal.theme.ThemeDisplay;
import com.liferay.portal.util.WebKeys;
import com.liferay.portlet.journal.model.JournalArticle;
import com.liferay.portlet.journal.service.JournalArticleLocalServiceUtil;
import com.liferay.portlet.journal.service.JournalContentSearchLocalServiceUtil;

import javax.portlet.ActionRequest;
import javax.portlet.ActionResponse;
import javax.portlet.PortletConfig;

/**
 * @author Brian Wing Shun Chan
 */
public class ConfigurationActionImpl extends DefaultConfigurationAction {

	@Override
	public void processAction(
			PortletConfig portletConfig, ActionRequest actionRequest,
			ActionResponse actionResponse)
		throws Exception {

		String[] extensions = actionRequest.getParameterValues("extensions");

		setPreference(actionRequest, "extensions", extensions);

		super.processAction(portletConfig, actionRequest, actionResponse);

		if (SessionErrors.isEmpty(actionRequest)) {
			
			updateContentSearch(actionRequest, portletConfig);
		}
	}

	protected void updateContentSearch(ActionRequest actionRequest, PortletConfig portletConfig)
		throws Exception {

		ThemeDisplay themeDisplay = (ThemeDisplay)actionRequest.getAttribute(
			WebKeys.THEME_DISPLAY);

		String articleId = getParameter(
			actionRequest, "articleId").toUpperCase();

		String portletResource = ParamUtil.getString(
			actionRequest, "portletResource");

		Layout layout = themeDisplay.getLayout();

		JournalContentSearchLocalServiceUtil.updateContentSearch(
			layout.getGroupId(), layout.isPrivateLayout(), layout.getLayoutId(),
			portletResource, articleId, true);
		
		if (Validator.isNotNull(articleId)) {
			try {
				//EMDA-4516
				long currentPortletGroupId = GetterUtil.getLong(getParameter(actionRequest, "groupId"));
				JournalArticle article = JournalArticleLocalServiceUtil.getArticle(currentPortletGroupId, articleId);
				
				String structureId = article.getStructureId();
				
				if (Validator.isNotNull(structureId) &&  
					(structureId.equals("ADVERTISEMENT_MODULE") || structureId.equals("MARKETPLACE_AD")
							|| "HTML_IFRAME".equals(structureId))) {
					
					SessionMessages.add(
						actionRequest, portletConfig.getPortletName() + SessionMessages.KEY_SUFFIX_PORTLET_NOT_AJAXABLE, true);
				}
			}
			catch (Exception e) {}
		}
	}

}