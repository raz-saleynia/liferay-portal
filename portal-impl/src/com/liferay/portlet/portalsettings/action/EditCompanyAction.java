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

package com.liferay.portlet.portalsettings.action;

import com.endplay.portlet.journal.model.AdsTxtContent;
import com.endplay.portlet.journal.service.AdsTxtContentLocalServiceUtil;
import com.endplay.portlet.journal.service.persistence.AdsTxtContentPK;
import com.liferay.portal.kernel.servlet.SessionErrors;
import com.liferay.portal.kernel.util.*;
import com.liferay.portal.security.auth.PrincipalException;
import com.liferay.portal.service.CompanyServiceUtil;
import com.liferay.portal.struts.PortletAction;
import com.liferay.portlet.usersadmin.util.UsersAdminUtil;
import org.apache.commons.lang.StringUtils;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;

import javax.portlet.*;
import java.util.List;

/**
 * @author Brian Wing Shun Chan
 * @author Julio Camarero
 */
public class EditCompanyAction extends PortletAction {

	@Override
	public void processAction(
			ActionMapping mapping, ActionForm form, PortletConfig portletConfig,
			ActionRequest actionRequest, ActionResponse actionResponse)
		throws Exception {

		String cmd = ParamUtil.getString(actionRequest, Constants.CMD);

		try {
			if (cmd.equals(Constants.ADD) || cmd.equals(Constants.UPDATE)) {
				validateCAS(actionRequest);

				if (!SessionErrors.isEmpty(actionRequest)) {
					setForward(
						actionRequest, "portlet.portal_settings.edit_company");
				}
				else {
					updateCompany(actionRequest);
					updateDisplay(actionRequest);

					sendRedirect(actionRequest, actionResponse);
				}
			}
		}
		catch (Exception e) {
			if (e instanceof PrincipalException) {
				SessionErrors.add(actionRequest, e.getClass().getName());

				setForward(actionRequest, "portlet.portal_settings.error");
			}
			else if (e instanceof AddressCityException ||
					 e instanceof AccountNameException ||
					 e instanceof AddressStreetException ||
					 e instanceof AddressZipException ||
					 e instanceof CompanyMxException ||
					 e instanceof CompanyVirtualHostException ||
					 e instanceof CompanyWebIdException ||
					 e instanceof EmailAddressException ||
					 e instanceof LocaleException ||
					 e instanceof NoSuchCountryException ||
					 e instanceof NoSuchListTypeException ||
					 e instanceof NoSuchRegionException ||
					 e instanceof PhoneNumberException ||
					 e instanceof WebsiteURLException) {

				if (e instanceof NoSuchListTypeException) {
					NoSuchListTypeException nslte = (NoSuchListTypeException)e;

					SessionErrors.add(
						actionRequest,
						e.getClass().getName() + nslte.getType());
				}
				else {
					SessionErrors.add(actionRequest, e.getClass().getName(), e);
				}

				setForward(
					actionRequest, "portlet.portal_settings.edit_company");
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

		return mapping.findForward(
			getForward(renderRequest, "portlet.portal_settings.edit_company"));
	}

	protected void updateCompany(ActionRequest actionRequest) throws Exception {
		long companyId = PortalUtil.getCompanyId(actionRequest);

		String virtualHostname = ParamUtil.getString(
			actionRequest, "virtualHostname");
		String mx = ParamUtil.getString(actionRequest, "mx");
		String homeURL = ParamUtil.getString(actionRequest, "homeURL");
		String name = ParamUtil.getString(actionRequest, "name");
		String legalName = ParamUtil.getString(actionRequest, "legalName");
		String legalId = ParamUtil.getString(actionRequest, "legalId");
		String legalType = ParamUtil.getString(actionRequest, "legalType");
		String sicCode = ParamUtil.getString(actionRequest, "sicCode");
		String tickerSymbol = ParamUtil.getString(
			actionRequest, "tickerSymbol");
		String industry = ParamUtil.getString(actionRequest, "industry");
		String type = ParamUtil.getString(actionRequest, "type");
		String size = ParamUtil.getString(actionRequest, "size");
		String languageId = ParamUtil.getString(actionRequest, "languageId");
		String timeZoneId = ParamUtil.getString(actionRequest, "timeZoneId");
		List<Address> addresses = UsersAdminUtil.getAddresses(actionRequest);
		List<EmailAddress> emailAddresses = UsersAdminUtil.getEmailAddresses(
			actionRequest);
		List<Phone> phones = UsersAdminUtil.getPhones(actionRequest);
		List<Website> websites = UsersAdminUtil.getWebsites(actionRequest);

		//LAKPLAT-2265 START
		String portalDefaultAdsTxt = ParamUtil.getString(actionRequest, "portalDefaultAdsTxt");
		AdsTxtContent existingAdsTxtContent = AdsTxtContentLocalServiceUtil.fetchAdsTxtContent(0l, companyId);

		if (existingAdsTxtContent == null) {

			if (StringUtils.isNotBlank(portalDefaultAdsTxt)) {
				AdsTxtContent adsTxtContent = AdsTxtContentLocalServiceUtil.createAdsTxtContent(new AdsTxtContentPK(0l, companyId));
				adsTxtContent.setContent(portalDefaultAdsTxt);
				AdsTxtContentLocalServiceUtil.updateAdsTxtContent(adsTxtContent);
			}
		} else {
			existingAdsTxtContent.setContent(portalDefaultAdsTxt);
			AdsTxtContentLocalServiceUtil.updateAdsTxtContent(existingAdsTxtContent);
		}
		//LAKPLAT-2265 END

		UnicodeProperties properties = PropertiesParamUtil.getProperties(
			actionRequest, "settings--");

		CompanyServiceUtil.updateCompany(
			companyId, virtualHostname, mx, homeURL, name, legalName, legalId,
			legalType, sicCode, tickerSymbol, industry, type, size, languageId,
			timeZoneId, addresses, emailAddresses, phones, websites,
			properties);

		PortalUtil.resetCDNHosts();
	}

	protected void updateDisplay(ActionRequest actionRequest) throws Exception {
		Company company = PortalUtil.getCompany(actionRequest);

		String languageId = ParamUtil.getString(actionRequest, "languageId");
		String timeZoneId = ParamUtil.getString(actionRequest, "timeZoneId");

		CompanyServiceUtil.updateDisplay(
			company.getCompanyId(), languageId, timeZoneId);

		boolean siteLogo = ParamUtil.getBoolean(
			actionRequest,
			"settings--" + PropsKeys.COMPANY_SECURITY_SITE_LOGO + "--");

		CompanyServiceUtil.updateSecurity(
			company.getCompanyId(), company.getAuthType(),
			company.isAutoLogin(), company.isSendPassword(),
			company.isStrangers(), company.isStrangersWithMx(),
			company.isStrangersVerify(), siteLogo);

		boolean deleteLogo = ParamUtil.getBoolean(actionRequest, "deleteLogo");

		if (deleteLogo) {
			CompanyServiceUtil.deleteLogo(company.getCompanyId());
		}
	}

	protected void validateCAS(ActionRequest actionRequest) throws Exception {
		boolean casEnabled = ParamUtil.getBoolean(
			actionRequest, "settings--" + PropsKeys.CAS_AUTH_ENABLED + "--");

		if (!casEnabled) {
			return;
		}

		String casLoginURL = ParamUtil.getString(
			actionRequest, "settings--" + PropsKeys.CAS_LOGIN_URL + "--");
		String casLogoutURL = ParamUtil.getString(
			actionRequest, "settings--" + PropsKeys.CAS_LOGOUT_URL + "--");
		String casServerName = ParamUtil.getString(
			actionRequest, "settings--" + PropsKeys.CAS_SERVER_NAME + "--");
		String casServerURL = ParamUtil.getString(
			actionRequest, "settings--" + PropsKeys.CAS_SERVER_URL + "--");
		String casServiceURL = ParamUtil.getString(
			actionRequest, "settings--" + PropsKeys.CAS_SERVICE_URL + "--");
		String casNoSuchUserRedirectURL = ParamUtil.getString(
			actionRequest, "settings--" +
			PropsKeys.CAS_NO_SUCH_USER_REDIRECT_URL + "--");

		if (!Validator.isUrl(casLoginURL)) {
			SessionErrors.add(actionRequest, "casLoginURLInvalid");
		}

		if (!Validator.isUrl(casLogoutURL)) {
			SessionErrors.add(actionRequest, "casLogoutURLInvalid");
		}

		if (Validator.isNull(casServerName)) {
			SessionErrors.add(actionRequest, "casServerNameInvalid");
		}

		if (Validator.isNotNull(casServerURL) &&
			Validator.isNotNull(casServiceURL)) {

			SessionErrors.add(
				actionRequest, "casServerURLAndServiceURLConflict");
		}
		else if (Validator.isNull(casServerURL) &&
				 Validator.isNull(casServiceURL)) {

			SessionErrors.add(actionRequest, "casServerURLAndServiceURLNotSet");
		}
		else {
			if (Validator.isNotNull(casServerURL) &&
				!Validator.isUrl(casServerURL)) {

				SessionErrors.add(actionRequest, "casServerURLInvalid");
			}

			if (Validator.isNotNull(casServiceURL) &&
				!Validator.isUrl(casServiceURL)) {

				SessionErrors.add(actionRequest, "casServiceURLInvalid");
			}
		}

		if (Validator.isNotNull(casNoSuchUserRedirectURL) &&
			!Validator.isUrl(casNoSuchUserRedirectURL)) {

			SessionErrors.add(actionRequest, "casNoSuchUserURLInvalid");
		}
	}

}