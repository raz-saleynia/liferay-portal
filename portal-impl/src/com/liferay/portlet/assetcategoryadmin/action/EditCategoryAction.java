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

package com.liferay.portlet.assetcategoryadmin.action;

import com.endplay.portlet.asset.EPCategoryEnum;
import com.liferay.portal.kernel.json.JSONFactoryUtil;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.util.*;
import com.liferay.portal.service.ServiceContext;
import com.liferay.portal.service.ServiceContextFactory;
import com.liferay.portal.struts.PortletAction;
import com.liferay.portlet.asset.model.AssetCategory;
import com.liferay.portlet.asset.service.AssetCategoryServiceUtil;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;

import javax.portlet.*;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * @author Brian Wing Shun Chan
 * @author Julio Camarero
 */
public class EditCategoryAction extends PortletAction {

	@Override
	public void processAction(
			ActionMapping mapping, ActionForm form, PortletConfig portletConfig,
			ActionRequest actionRequest, ActionResponse actionResponse)
		throws Exception {

		JSONObject jsonObject = JSONFactoryUtil.createJSONObject();

		String cmd = ParamUtil.getString(actionRequest, Constants.CMD);

		try {
			if (cmd.equals(Constants.ADD) || cmd.equals(Constants.UPDATE)) {
				jsonObject = updateCategory(actionRequest);
			}
			else if (cmd.equals(Constants.MOVE)) {
				jsonObject = moveCategory(actionRequest);
			}
		}
		catch (Exception e) {
			jsonObject.putException(e);
		}

		writeJSON(actionRequest, actionResponse, jsonObject);
	}

	@Override
	public ActionForward render(
			ActionMapping mapping, ActionForm form, PortletConfig portletConfig,
			RenderRequest renderRequest, RenderResponse renderResponse)
		throws Exception {

		ActionUtil.getCategory(renderRequest);
		ActionUtil.getVocabularies(renderRequest);

		return mapping.findForward(
			getForward(
				renderRequest, "portlet.asset_category_admin.edit_category"));
	}

	protected String[] getCategoryProperties(ActionRequest actionRequest) {
		// Look for our custom properties
		String[] customProperties = getCustomProperties(actionRequest);
		int[] categoryPropertiesIndexes = StringUtil.split(
			ParamUtil.getString(actionRequest, "categoryPropertiesIndexes"), 0);

		String[] categoryProperties =
			new String[categoryPropertiesIndexes.length + customProperties.length];

		for (int i = 0; i < categoryPropertiesIndexes.length; i++) {
			int categoryPropertiesIndex = categoryPropertiesIndexes[i];

			String key = ParamUtil.getString(
				actionRequest, "key" + categoryPropertiesIndex);

			if (Validator.isNull(key)) {
				continue;
			}

			String value = ParamUtil.getString(
				actionRequest, "value" + categoryPropertiesIndex);

			categoryProperties[i] = key + StringPool.COLON + value;
		}
		for (int i = 0; i < customProperties.length; i++)
			categoryProperties[categoryPropertiesIndexes.length + i] = customProperties[i];

		return categoryProperties;
	}
	
	protected String[] getCustomProperties(ActionRequest actionRequest) {
		Map<String, String> props = new HashMap<String, String>();
		String globalCategoryId = ParamUtil.getString(actionRequest, EPCategoryEnum.GLOBAL_CATEGORY_ID.getName(), null);
		String staticCategoryId = ParamUtil.getString(actionRequest, EPCategoryEnum.STATIC_CATEGORY_ID.getName(), null);
		boolean deprecated = ParamUtil.getBoolean(actionRequest,  EPCategoryEnum.CATEGORY_DEPRECATED.getName(), false);
		
		if (globalCategoryId != null)
			props.put(EPCategoryEnum.GLOBAL_CATEGORY_ID.getName(), globalCategoryId);
		if (staticCategoryId != null)
			props.put(EPCategoryEnum.STATIC_CATEGORY_ID.getName(), staticCategoryId);
		String deprecatedValue = deprecated ? EPCategoryEnum.CATEGORY_DEPRECATED.getName() : "false";
		props.put(EPCategoryEnum.CATEGORY_DEPRECATED.getName(), deprecatedValue);
		
		String[] values = new String[props.size()];
		int index = 0;
		for (String key : props.keySet()) {
			values[index++] = key + StringPool.COLON + props.get(key);
		}
		return values;
	}

	protected JSONObject moveCategory(ActionRequest actionRequest)
		throws Exception {

		long categoryId = ParamUtil.getLong(actionRequest, "categoryId");

		long parentCategoryId = ParamUtil.getLong(
			actionRequest, "parentCategoryId");
		long vocabularyId = ParamUtil.getLong(actionRequest, "vocabularyId");

		ServiceContext serviceContext = ServiceContextFactory.getInstance(
			AssetCategory.class.getName(), actionRequest);

		AssetCategory category = AssetCategoryServiceUtil.moveCategory(
			categoryId, parentCategoryId, vocabularyId, serviceContext);

		JSONObject jsonObject = JSONFactoryUtil.createJSONObject();

		jsonObject.put("categoryId", category.getCategoryId());

		return jsonObject;
	}

	protected JSONObject updateCategory(ActionRequest actionRequest)
		throws Exception {

		long categoryId = ParamUtil.getLong(actionRequest, "categoryId");

		long parentCategoryId = ParamUtil.getLong(
			actionRequest, "parentCategoryId");
		Map<Locale, String> titleMap = LocalizationUtil.getLocalizationMap(
			actionRequest, "title");
		Map<Locale, String> descriptionMap =
			LocalizationUtil.getLocalizationMap(actionRequest, "description");
		long vocabularyId = ParamUtil.getLong(actionRequest, "vocabularyId");
		String[] categoryProperties = getCategoryProperties(actionRequest);

		ServiceContext serviceContext = ServiceContextFactory.getInstance(
			AssetCategory.class.getName(), actionRequest);

		AssetCategory category = null;

		if (categoryId <= 0) {

			// Add category

			category = AssetCategoryServiceUtil.addCategory(
				parentCategoryId, titleMap, descriptionMap, vocabularyId,
				categoryProperties, serviceContext);
		}
		else {

			// Update category

			category = AssetCategoryServiceUtil.updateCategory(
				categoryId, parentCategoryId, titleMap, descriptionMap,
				vocabularyId, categoryProperties, serviceContext);
		}

		JSONObject jsonObject = JSONFactoryUtil.createJSONObject();

		jsonObject.put("categoryId", category.getCategoryId());

		return jsonObject;
	}

}