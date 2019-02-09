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

package com.liferay.portal.image;

import com.endplay.portlet.documentlibrary.store.StoreConstants;
import com.endplay.portlet.documentlibrary.store.tools.ImageToolUtil;
import com.endplay.portlet.documentlibrary.util.DocumentLibraryDataUtil;
import com.liferay.portal.NoSuchImageException;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.util.FileUtil;
import com.liferay.portal.kernel.util.PropsKeys;
import com.liferay.portal.kernel.util.PropsUtil;
import com.liferay.portal.kernel.util.StringPool;
import com.liferay.portal.model.Company;
import com.liferay.portal.model.Group;
import com.liferay.portal.model.Image;
import com.liferay.portal.security.auth.CompanyThreadLocal;
import com.liferay.portal.service.CompanyLocalServiceUtil;
import com.liferay.portal.service.GroupLocalServiceUtil;
import com.liferay.portlet.documentlibrary.NoSuchFileException;
import com.liferay.portlet.documentlibrary.store.DLStoreUtil;

import java.io.IOException;
import java.io.InputStream;

/**
 * @author Jorge Ferrer
 */
public class DLHook extends BaseHook {

	public void deleteImage(Image image)
		throws PortalException, SystemException {

		String fileName = getFileName(image.getImageId(), image.getType());

		try {
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
				
				String mimeType = ImageToolUtil.detect(fileName);
				try {
					fileName = DocumentLibraryDataUtil.getCdnSubDirectoryPath(companyId,globalGroup.getGroupId(),mimeType) + StoreConstants.PATH_SEPARATOR +  StoreConstants.DEFAULT_IMAGE_FOLDER + StoreConstants.PATH_SEPARATOR +  fileName;
				} catch (Exception e) {
					throw new SystemException(e);
				}
				DLStoreUtil.deleteFile(companyId, globalGroup.getGroupId(), fileName);
			}
			else
			{
				DLStoreUtil.deleteFile(_COMPANY_ID, _REPOSITORY_ID, fileName);	
			}
			
		}
		catch (NoSuchFileException nsfe) {
			throw new NoSuchImageException(nsfe);
		}
	}

	public byte[] getImageAsBytes(Image image)
		throws PortalException, SystemException {

		String fileName = getFileName(image.getImageId(), image.getType());

		InputStream is = DLStoreUtil.getFileAsStream(
			_COMPANY_ID, _REPOSITORY_ID, fileName);

		byte[] bytes = null;

		try {
			bytes = FileUtil.getBytes(is);
		}
		catch (IOException ioe) {
			throw new SystemException(ioe);
		}

		return bytes;
	}

	public InputStream getImageAsStream(Image image)
		throws PortalException, SystemException {

		String fileName = getFileName(image.getImageId(), image.getType());
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
			
			String mimeType = ImageToolUtil.detect(fileName);

			try {
				fileName = DocumentLibraryDataUtil.getCdnSubDirectoryPath(companyId,globalGroup.getGroupId(),mimeType) + StoreConstants.PATH_SEPARATOR +  StoreConstants.DEFAULT_IMAGE_FOLDER + StoreConstants.PATH_SEPARATOR +  fileName;
			} catch (Exception e) {
				throw new SystemException(e);
			}

			return DLStoreUtil.getFileAsStream(
					companyId, globalGroup.getGroupId(), fileName);
		}
		else
		{
			return DLStoreUtil.getFileAsStream(
					_COMPANY_ID, _REPOSITORY_ID, fileName);
		}
	}

	public void updateImage(Image image, String type, byte[] bytes)
		throws PortalException, SystemException {

		String fileName = getFileName(image.getImageId(), image.getType());
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
			
			String mimeType = ImageToolUtil.detect(fileName);

			try {
				fileName = DocumentLibraryDataUtil.getCdnSubDirectoryPath(companyId,globalGroup.getGroupId(),mimeType) + StoreConstants.PATH_SEPARATOR +  StoreConstants.DEFAULT_IMAGE_FOLDER + StoreConstants.PATH_SEPARATOR +  fileName;
			} catch (Exception e) {
				throw new SystemException(e);
			}
			if (DLStoreUtil.hasFile(companyId, globalGroup.getGroupId(), fileName)) {
				DLStoreUtil.deleteFile(companyId, globalGroup.getGroupId(), fileName);
			}

			DLStoreUtil.addFile(companyId, globalGroup.getGroupId(), fileName, true, bytes);
		}
		else
		{
			if (DLStoreUtil.hasFile(_COMPANY_ID, _REPOSITORY_ID, fileName)) {
				DLStoreUtil.deleteFile(_COMPANY_ID, _REPOSITORY_ID, fileName);
			}

			DLStoreUtil.addFile(_COMPANY_ID, _REPOSITORY_ID, fileName, true, bytes);
			
		}
	}

	protected String getFileName(long imageId, String type) {
		return imageId + StringPool.PERIOD + type;
	}

	private static final long _COMPANY_ID = 0;

	private static final long _REPOSITORY_ID = 0;

}