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

package com.liferay.portlet.usersadmin.action;

import com.endplay.portlet.excel.helper.*;
import com.liferay.portal.kernel.bean.BeanPropertiesUtil;
import com.liferay.portal.kernel.dao.orm.QueryUtil;
import com.liferay.portal.kernel.servlet.ServletResponseUtil;
import com.liferay.portal.kernel.servlet.SessionErrors;
import com.liferay.portal.kernel.util.*;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.portal.model.User;
import com.liferay.portal.security.permission.ActionKeys;
import com.liferay.portal.security.permission.PermissionChecker;
import com.liferay.portal.service.UserLocalServiceUtil;
import com.liferay.portal.service.permission.PortalPermissionUtil;
import com.liferay.portal.struts.ActionConstants;
import com.liferay.portal.struts.PortletAction;
import com.liferay.portal.theme.ThemeDisplay;
import com.liferay.portal.util.PortalUtil;
import com.liferay.portal.util.PortletKeys;
import com.liferay.portal.util.PropsValues;
import com.liferay.portal.util.WebKeys;
import com.liferay.portlet.ActionResponseImpl;
import com.liferay.portlet.expando.model.ExpandoBridge;
import com.liferay.portlet.usersadmin.search.UserSearch;
import com.liferay.portlet.usersadmin.search.UserSearchTerms;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionMapping;

import javax.portlet.ActionRequest;
import javax.portlet.ActionResponse;
import javax.portlet.PortletConfig;
import javax.portlet.PortletURL;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * @author Brian Wing Shun Chan
 * @author Mika Koivisto
 */
public class ExportUsersAction extends PortletAction {

	@Override
	public void processAction(
			ActionMapping mapping, ActionForm form, PortletConfig portletConfig,
			ActionRequest actionRequest, ActionResponse actionResponse)
		throws Exception {

		try {

			String fileName = getFileName();
			byte[] bytes = getUsersExcel(actionRequest, actionResponse);

			HttpServletRequest request = PortalUtil.getHttpServletRequest(
				actionRequest);
			HttpServletResponse response = PortalUtil.getHttpServletResponse(
				actionResponse);

			ServletResponseUtil.sendFile(
				request, response, fileName, bytes, ContentTypes.APPLICATION_TEXT);

			setForward(actionRequest, ActionConstants.COMMON_NULL);
		}
		catch (Exception e) {
			SessionErrors.add(actionRequest, e.getClass().getName());

			setForward(actionRequest, "portlet.users_admin.error");
		}
	}

	protected String getUserCSV(User user) {
		StringBundler sb = new StringBundler(
			PropsValues.USERS_EXPORT_CSV_FIELDS.length * 2);

		for (int i = 0; i < PropsValues.USERS_EXPORT_CSV_FIELDS.length; i++) {
			String field = PropsValues.USERS_EXPORT_CSV_FIELDS[i];

			if (field.equals("fullName")) {
				sb.append(CSVUtil.encode(user.getFullName()));
			}
			else if (field.startsWith("expando:")) {
				String attributeName = field.substring(8);

				ExpandoBridge expandoBridge = user.getExpandoBridge();

				sb.append(
					CSVUtil.encode(expandoBridge.getAttribute(attributeName)));
			}
			else {
				sb.append(
					CSVUtil.encode(BeanPropertiesUtil.getString(user, field)));
			}

			if ((i + 1) < PropsValues.USERS_EXPORT_CSV_FIELDS.length) {
				sb.append(StringPool.COMMA);
			}
		}

		sb.append(StringPool.NEW_LINE);

		return sb.toString();
	}

	protected List<User> getUsers(
			ActionRequest actionRequest, ActionResponse actionResponse,
			ThemeDisplay themeDisplay)
		throws Exception {

		PortletURL portletURL =
			((ActionResponseImpl)actionResponse).createRenderURL(
				PortletKeys.USERS_ADMIN);

		UserSearch userSearch = new UserSearch(actionRequest, portletURL);

		UserSearchTerms searchTerms =
			(UserSearchTerms)userSearch.getSearchTerms();

		searchTerms.setStatus(WorkflowConstants.STATUS_APPROVED);

		LinkedHashMap<String, Object> params =
			new LinkedHashMap<String, Object>();

		long organizationId = searchTerms.getOrganizationId();

		if (organizationId > 0) {
			params.put("usersOrgs", new Long(organizationId));
		}

		long roleId = searchTerms.getRoleId();

		if (roleId > 0) {
			params.put("usersRoles", new Long(roleId));
		}

		long userGroupId = searchTerms.getUserGroupId();

		if (userGroupId > 0) {
			params.put("usersUserGroups", new Long(userGroupId));
		}

		if (searchTerms.isAdvancedSearch()) {
			return UserLocalServiceUtil.search(
				themeDisplay.getCompanyId(), searchTerms.getFirstName(),
				searchTerms.getMiddleName(), searchTerms.getLastName(),
				searchTerms.getScreenName(), searchTerms.getEmailAddress(),
				searchTerms.getStatus(), params, searchTerms.isAndOperator(),
				QueryUtil.ALL_POS, QueryUtil.ALL_POS, (OrderByComparator)null);
		}
		else {
			return UserLocalServiceUtil.search(
				themeDisplay.getCompanyId(), searchTerms.getKeywords(),
				searchTerms.getStatus(), params, QueryUtil.ALL_POS,
				QueryUtil.ALL_POS, (OrderByComparator)null);
		}
	}


    private byte[] getUsersExcel(
            ActionRequest actionRequest, ActionResponse actionResponse)
        throws Exception {

        ThemeDisplay themeDisplay = (ThemeDisplay)actionRequest.getAttribute(
            WebKeys.THEME_DISPLAY);

        PermissionChecker permissionChecker =
            themeDisplay.getPermissionChecker();

        if (!PortalPermissionUtil.contains(
                permissionChecker, ActionKeys.EXPORT_USER)) {

            return new byte[0];
        }

        String exportProgressId = ParamUtil.getString(
            actionRequest, "exportProgressId");

        ProgressTracker progressTracker = new ProgressTracker(
            actionRequest, exportProgressId);

        progressTracker.start();

        List<User> users = getUsers(
            actionRequest, actionResponse, themeDisplay);

        int percentage = 10;
        int total = users.size();

        progressTracker.updateProgress(percentage);

        if (total == 0) {
            return new byte[0];
        }

//        byte[] excelBytes = ExportUsersHelper.getExcelByteArray(users);
        byte[] excelBytes = getExcelByteArrayOutputStream(users).toByteArray();
        
        progressTracker.finish();

        return excelBytes;
    }
    
    private ByteArrayOutputStream getExcelByteArrayOutputStream(List<User> users) throws Exception{
        IExportHelper exportUserHelper = new ExportUserHelper();
        IExportHelper exportAddressHelper = new ExportAddressHelper();
        IExportHelper exportPhoneHelper = new ExportPhoneHelper();
        IExportHelper emailAddressHelper = new ExportEmailAddressHelper();
        IExportHelper deliveryHelper = new ExportAnnouncementsDeliveryHelper();
        IExportHelper websiteHelper = new ExportUserWebsiteHelper();
        
        
        HSSFWorkbook workBook = ExcelHelper.createWorkBook();
        HSSFSheet userSheet = exportUserHelper.createSheet(workBook);
        HSSFSheet addressSheet = exportAddressHelper.createSheet(workBook);
        HSSFSheet phoneSheet = exportPhoneHelper.createSheet(workBook);
        HSSFSheet additionalEmailSheet = emailAddressHelper.createSheet(workBook);
        HSSFSheet deliverySheet = deliveryHelper.createSheet(workBook);
        HSSFSheet websiteSheet = websiteHelper.createSheet(workBook);
        
        ExcelHelper.populateData(userSheet, exportUserHelper.convertDataToExcelFormat(users));
        ExcelHelper.populateData(addressSheet, exportAddressHelper.convertDataToExcelFormat(users));
        ExcelHelper.populateData(phoneSheet, exportPhoneHelper.convertDataToExcelFormat(users));
        ExcelHelper.populateData(additionalEmailSheet, emailAddressHelper.convertDataToExcelFormat(users));
        ExcelHelper.populateData(deliverySheet, deliveryHelper.convertDataToExcelFormat(users));
        ExcelHelper.populateData(websiteSheet, websiteHelper.convertDataToExcelFormat(users));
        
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            workBook.write(bos);
            bos.close();
        } catch (Exception e) {
            throw e;
        }
        
        return bos;
    }

    private String getFileName() {
        SimpleDateFormat dateformat=new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
        String formatedDate = dateformat.format(new Date());
        return "Users_" + formatedDate + ".xls";
    }
}