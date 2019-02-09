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

package com.liferay.portal.events;

import com.endplay.portlet.epdata.media.configuration.ConfigurationPropertyUtil;
import com.liferay.portal.kernel.events.Action;
import com.liferay.portal.kernel.events.ActionException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.servlet.BrowserSnifferUtil;
import com.liferay.portal.model.ColorScheme;
import com.liferay.portal.model.Group;
import com.liferay.portal.model.Layout;
import com.liferay.portal.model.Theme;
import com.liferay.portal.model.impl.ColorSchemeImpl;
import com.liferay.portal.model.impl.ThemeImpl;
import com.liferay.portal.service.GroupLocalServiceUtil;
import com.liferay.portal.service.ThemeLocalServiceUtil;
import com.liferay.portal.theme.ThemeDisplay;
import com.liferay.portal.util.PortalUtil;
import com.liferay.portal.util.WebKeys;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @author Edward Han
 */
public class ThemeServicePreAction extends Action {
	
	private final String WAP_THEME_CHECK_DISABLED = "wap.theme.check.disabled";

	@Override
	public void run(HttpServletRequest request, HttpServletResponse response)
		throws ActionException {

		try {
			servicePre(request, response);
		}
		catch (Exception e) {
			throw new ActionException(e);
		}
	}

	protected void servicePre(
			HttpServletRequest request, HttpServletResponse response)
		throws Exception {

		ThemeDisplay themeDisplay = (ThemeDisplay)request.getAttribute(
			WebKeys.THEME_DISPLAY);

		Theme theme = themeDisplay.getTheme();
		ColorScheme colorScheme = themeDisplay.getColorScheme();

		if (theme != null) {
			if (_log.isInfoEnabled()) {
				_log.info("Theme is already set");
			}

			return;
		}

		Layout layout = themeDisplay.getLayout();

		//Never render with wap theme by default
		boolean wapTheme = false;
		
		//check wap theme if the feature is enabled
		if(!isWapThemeCheckDisabled(request)){
			wapTheme = BrowserSnifferUtil.isWap(request);
		}

		if (layout != null) {
			if (wapTheme) {
				theme = layout.getWapTheme();
				colorScheme = layout.getWapColorScheme();
			}
			else {
				theme = layout.getTheme();
				colorScheme = layout.getColorScheme();
			}
		}
		else {
			String themeId = null;
			String colorSchemeId = null;

			if (wapTheme) {
				themeId = ThemeImpl.getDefaultWapThemeId(
					themeDisplay.getCompanyId());
				colorSchemeId = ColorSchemeImpl.getDefaultWapColorSchemeId();
			}
			else {
				themeId = ThemeImpl.getDefaultRegularThemeId(
					themeDisplay.getCompanyId());
				colorSchemeId =
					ColorSchemeImpl.getDefaultRegularColorSchemeId();
			}

			theme = ThemeLocalServiceUtil.getTheme(
				themeDisplay.getCompanyId(), themeId, wapTheme);
			colorScheme = ThemeLocalServiceUtil.getColorScheme(
				themeDisplay.getCompanyId(), theme.getThemeId(), colorSchemeId,
				wapTheme);
		}

		request.setAttribute(WebKeys.THEME, theme);
		request.setAttribute(WebKeys.COLOR_SCHEME, colorScheme);

		themeDisplay.setLookAndFeel(theme, colorScheme);
	}
	
	private boolean isWapThemeCheckDisabled(HttpServletRequest request)
	{
		Boolean isWapThemeCheckDisabled = null;
		
		try {
			long groupId = PortalUtil.getScopeGroupId(request);
			Group group = GroupLocalServiceUtil.getGroup(groupId);
			isWapThemeCheckDisabled = ConfigurationPropertyUtil.getInstance().getBoolean(group.getCompanyId(), groupId, WAP_THEME_CHECK_DISABLED);
		} catch (Exception e) {
			_log.error("Failed to get global config:" + WAP_THEME_CHECK_DISABLED + ": " + e);
		}
		
		if (isWapThemeCheckDisabled == null) {
			_log.warn("The configuration value " + WAP_THEME_CHECK_DISABLED + " has not been set -- defaulting to FALSE");
			return false;
		}
		return isWapThemeCheckDisabled.booleanValue();
	}

	private static Log _log = LogFactoryUtil.getLog(
		ThemeServicePreAction.class);

}