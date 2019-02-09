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

package com.liferay.portal.model.impl;

import com.endplay.portlet.documentlibrary.store.StoreConstants;
import com.endplay.portlet.documentlibrary.store.tools.ImageToolUtil;
import com.endplay.portlet.documentlibrary.util.DocumentLibraryDataUtil;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.*;
import com.liferay.portal.model.Company;
import com.liferay.portal.model.Group;
import com.liferay.portal.security.auth.CompanyThreadLocal;
import com.liferay.portal.service.CompanyLocalServiceUtil;
import com.liferay.portal.service.GroupLocalServiceUtil;
import com.liferay.portlet.documentlibrary.model.DLFileEntry;
import com.liferay.portlet.documentlibrary.service.DLFileEntryLocalServiceUtil;
import com.liferay.portlet.documentlibrary.store.DLStoreUtil;

import java.io.InputStream;

/**
 * @author Brian Wing Shun Chan
 */
public class ImageImpl extends ImageBaseImpl {

	public ImageImpl() {
	}

	public byte[] getTextObj() {
		if (_textObj != null) {
			return _textObj;
		}

		long imageId = getImageId();

		try {
			DLFileEntry dlFileEntry =
				DLFileEntryLocalServiceUtil.fetchFileEntryByAnyImageId(imageId);

			InputStream is = null;

			if ((dlFileEntry != null) &&
				(dlFileEntry.getLargeImageId() == imageId)) {

				is = DLStoreUtil.getFileAsStream(
					dlFileEntry.getCompanyId(),
					dlFileEntry.getDataRepositoryId(), dlFileEntry.getName());
			}
			else {
				if (DocumentLibraryDataUtil.isRemoteStoreEnabled())
				{
					 Long companyId = CompanyThreadLocal.getCompanyId();
					 if(companyId == null || companyId == 0){
					 	String companyIdStr = PropsUtil.get(PropsKeys.COMPANY_DEFAULT_WEB_ID);
					 	Company company = CompanyLocalServiceUtil.getCompanyByWebId(companyIdStr);
					 	if(company != null){
					 		companyId = company.getCompanyId();
					 	}
					 }
					Group globalGroup = GroupLocalServiceUtil.getCompanyGroup(companyId);
					
					String mimeType = ImageToolUtil.detect(getFileName());
					
					String fileName = null;
					try {
						fileName = DocumentLibraryDataUtil.getCdnSubDirectoryPath(companyId,globalGroup.getGroupId(),mimeType) + StoreConstants.PATH_SEPARATOR +  StoreConstants.DEFAULT_IMAGE_FOLDER + StoreConstants.PATH_SEPARATOR +  getFileName();
					} catch (Exception e) {
						throw new SystemException(e);
					}
					
					is = DLStoreUtil.getFileAsStream(
							companyId, globalGroup.getGroupId(), fileName);
				}
				else
				{
					is = DLStoreUtil.getFileAsStream(
							_DEFAULT_COMPANY_ID, _DEFAULT_REPOSITORY_ID, getFileName());
				}
			}

			byte[] bytes = FileUtil.getBytes(is);

			_textObj = bytes;
		}
		catch (Exception e) {
			_log.error("Error reading image " + imageId, e);
		}

		return _textObj;
	}

	public void setTextObj(byte[] textObj) {
		_textObj = textObj;

		super.setText(Base64.objectToString(textObj));
	}

	protected String getFileName() {
		return getImageId() + StringPool.PERIOD + getType();
	}

	private static final long _DEFAULT_COMPANY_ID = 0;

	private static final long _DEFAULT_REPOSITORY_ID = 0;

	private static Log _log = LogFactoryUtil.getLog(ImageImpl.class);

	private byte[] _textObj;

}