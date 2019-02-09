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

package com.liferay.portlet.dynamicdatalists.action;

import com.endplay.portlet.epdata.media.configuration.ConfigurationPropertyUtil;
import com.liferay.portal.kernel.servlet.SessionErrors;
import com.liferay.portal.kernel.servlet.SessionMessages;
import com.liferay.portal.kernel.upload.UploadPortletRequest;
import com.liferay.portal.kernel.util.Constants;
import com.liferay.portal.kernel.util.ParamUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.security.auth.PrincipalException;
import com.liferay.portal.service.ServiceContext;
import com.liferay.portal.service.ServiceContextFactory;
import com.liferay.portal.struts.PortletAction;
import com.liferay.portal.theme.ThemeDisplay;
import com.liferay.portal.util.PortalUtil;
import com.liferay.portal.util.WebKeys;
import com.liferay.portlet.documentlibrary.FileSizeException;
import com.liferay.portlet.dynamicdatalists.NoSuchRecordException;
import com.liferay.portlet.dynamicdatalists.model.DDLRecord;
import com.liferay.portlet.dynamicdatalists.service.DDLRecordLocalServiceUtil;
import com.liferay.portlet.dynamicdatalists.util.DDLUtil;
import com.liferay.portlet.dynamicdatamapping.StorageFieldRequiredException;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;

import javax.portlet.*;

/**
 * @author Marcellus Tavares
 * @author Eduardo Lundgren
 */
public class EditRecordAction extends PortletAction {

	@Override
	public void processAction(
			ActionMapping mapping, ActionForm form, PortletConfig portletConfig,
			ActionRequest actionRequest, ActionResponse actionResponse)
		throws Exception {

		String cmd = ParamUtil.getString(actionRequest, Constants.CMD);
		String fromPortlet = ParamUtil.getString(actionRequest, "fromPortlet");

		try {
			if (cmd.equals(Constants.ADD) || cmd.equals(Constants.UPDATE)) {
				updateRecord(actionRequest);
				ConfigurationPropertyUtil.getInstance().clearCache();
			}
			else if (cmd.equals(Constants.DELETE)) {
				deleteRecord(actionRequest);
				ConfigurationPropertyUtil.getInstance().clearCache();
			}
			else if (cmd.equals(Constants.REVERT)) {
				revertRecordVersion(actionRequest);
				ConfigurationPropertyUtil.getInstance().clearCache();
			}

			if (Validator.isNotNull(cmd)) {
				sendRedirect(actionRequest, actionResponse);
			}

			// EXT added for clearing the session message if redirect to configuration property portlet
			// see LAKPLAT-750
			if (Validator.isNotNull(fromPortlet)) {
				SessionMessages.clear(actionRequest);
			}
		}
		catch (Exception e) {
			if (e instanceof NoSuchRecordException ||
				e instanceof PrincipalException) {

				SessionErrors.add(actionRequest, e.getClass().getName());

				setForward(actionRequest, "portlet.dynamic_data_lists.error");
			}
			else if (e instanceof FileSizeException ||
					 e instanceof StorageFieldRequiredException) {

				SessionErrors.add(actionRequest, e.getClass().getName());
			}
			else {
				throw e;
			}
		}
	}

	@Override
	public ActionForward render(
			ActionMapping mapping, ActionForm form, PortletConfig portletConfig,
			RenderRequest renderRequest, RenderResponse renderResponse)
		throws Exception {

		try {
			ActionUtil.getRecord(renderRequest);
		}
		catch (Exception e) {
			if (e instanceof NoSuchRecordException ||
				e instanceof PrincipalException) {

				SessionErrors.add(renderRequest, e.getClass().getName());

				return mapping.findForward("portlet.dynamic_data_lists.error");
			}
			else {
				throw e;
			}
		}

		return mapping.findForward(
			getForward(
				renderRequest, "portlet.dynamic_data_lists.edit_record"));
	}

	protected void deleteRecord(ActionRequest actionRequest)
		throws Exception {

		long recordId = ParamUtil.getLong(actionRequest, "recordId");

		DDLRecordLocalServiceUtil.deleteRecord(recordId);
	}

	protected void revertRecordVersion(ActionRequest actionRequest)
		throws Exception {

		ThemeDisplay themeDisplay = (ThemeDisplay)actionRequest.getAttribute(
			WebKeys.THEME_DISPLAY);

		long recordId = ParamUtil.getLong(actionRequest, "recordId");

		String version = ParamUtil.getString(actionRequest, "version");

		ServiceContext serviceContext = ServiceContextFactory.getInstance(
			DDLRecord.class.getName(), actionRequest);

		DDLRecordLocalServiceUtil.revertRecordVersion(
			themeDisplay.getUserId(), recordId, version, serviceContext);
	}

	protected DDLRecord updateRecord(ActionRequest actionRequest)
		throws Exception {

		UploadPortletRequest uploadPortletRequest =
			PortalUtil.getUploadPortletRequest(actionRequest);

		ServiceContext serviceContext = ServiceContextFactory.getInstance(
			DDLRecord.class.getName(), uploadPortletRequest);

		long recordId = ParamUtil.getLong(serviceContext, "recordId");

		long recordSetId = ParamUtil.getLong(serviceContext, "recordSetId");

		return DDLUtil.updateRecord(
			recordId, recordSetId, false, serviceContext);
	}

}