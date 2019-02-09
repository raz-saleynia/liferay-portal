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

package com.liferay.portlet.documentlibrary.service.impl;

import com.endplay.activityfeed.base.ActivityFeedEntryVO;
import com.endplay.activityfeed.base.ActivityFeedExtraDataBaseVO;
import com.endplay.activityfeed.media.MediaActivityEventTypeEnum;
import com.endplay.activityfeed.service.ActivityFeedLocalServiceUtil;
import com.endplay.portlet.custom.util.CustomFieldDataUtil;
import com.endplay.portlet.documentlibrary.DocumentLibraryDataConstants;
import com.endplay.portlet.documentlibrary.store.CDNStoreUtil;
import com.endplay.portlet.documentlibrary.store.StoreConstants;
import com.endplay.portlet.documentlibrary.util.DocumentLibraryDataUtil;
import com.endplay.portlet.epdata.media.assets.VideoBean;
import com.endplay.portlet.epdata.media.configuration.ConfigurationPropertyUtil;
import com.liferay.portal.ExpiredLockException;
import com.liferay.portal.InvalidLockException;
import com.liferay.portal.NoSuchLockException;
import com.liferay.portal.NoSuchModelException;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.image.ImageBag;
import com.liferay.portal.kernel.image.ImageToolUtil;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.search.*;
import com.liferay.portal.kernel.util.*;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.portal.kernel.workflow.WorkflowHandlerRegistryUtil;
import com.liferay.portal.model.*;
import com.liferay.portal.repository.liferayrepository.model.LiferayFileEntry;
import com.liferay.portal.repository.liferayrepository.model.LiferayFileVersion;
import com.liferay.portal.security.auth.PrincipalThreadLocal;
import com.liferay.portal.service.ServiceContext;
import com.liferay.portal.util.PrefsPropsUtil;
import com.liferay.portal.util.PropsValues;
import com.liferay.portlet.asset.model.AssetEntry;
import com.liferay.portlet.asset.model.AssetLink;
import com.liferay.portlet.asset.model.AssetLinkConstants;
import com.liferay.portlet.documentlibrary.model.*;
import com.liferay.portlet.documentlibrary.model.impl.DLFileEntryImpl;
import com.liferay.portlet.documentlibrary.service.base.DLFileEntryLocalServiceBaseImpl;
import com.liferay.portlet.documentlibrary.store.DLStoreUtil;
import com.liferay.portlet.documentlibrary.util.DLAppUtil;
import com.liferay.portlet.documentlibrary.util.DLUtil;
import com.liferay.portlet.documentlibrary.util.comparator.RepositoryModelModifiedDateComparator;
import com.liferay.portlet.dynamicdatamapping.model.DDMStructure;
import com.liferay.portlet.dynamicdatamapping.storage.Fields;
import com.liferay.portlet.dynamicdatamapping.storage.StorageEngineUtil;
import com.liferay.portlet.expando.model.ExpandoBridge;
import com.liferay.portlet.expando.model.ExpandoColumnConstants;
import com.liferay.portlet.expando.util.ExpandoBridgeFactoryUtil;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;

import java.awt.image.RenderedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.*;

/**
 * The document library file entry local service.
 *
 * <p>
 * Due to legacy code, the names of some file entry properties are not
 * intuitive. Each file entry has both a name and title. The <code>name</code>
 * is a unique identifier for a given file and is generally numeric, whereas the
 * <code>title</code> is the actual name specified by the user (such as
 * &quot;Budget.xls&quot;).
 * </p>
 *
 * @author Brian Wing Shun Chan
 * @author Harry Mark
 * @author Alexander Chow
 */
public class DLFileEntryLocalServiceImpl
		extends DLFileEntryLocalServiceBaseImpl {
	
	@Override
	public DLFileEntry addFileEntry(
			long userId, long groupId, long repositoryId, long folderId,
			String sourceFileName, String mimeType, String title,
			String description, String changeLog, long fileEntryTypeId,
			Map<String, Fields> fieldsMap, File file, InputStream is, long size,
			ServiceContext serviceContext)
			throws PortalException, SystemException {
		
		if (Validator.isNull(title)) {
			if (size == 0) {
				throw new FileNameException();
			}
			else {
				title = sourceFileName;
			}
		}
		
		// File entry
		
		User user = userPersistence.findByPrimaryKey(userId);
		folderId = dlFolderLocalService.getFolderId(
				user.getCompanyId(), folderId);
		String name = String.valueOf(
				counterLocalService.increment(DLFileEntry.class.getName()));
		String extension = DLAppUtil.getExtension(title, sourceFileName);
		fileEntryTypeId = getFileEntryTypeId(
				DLUtil.getGroupIds(groupId), folderId, fileEntryTypeId);
		Date now = new Date();
		
		validateFile(
				groupId, folderId, 0, title, extension, sourceFileName, file, is);
		
		long fileEntryId = counterLocalService.increment();
		
		DLFileEntry dlFileEntry = dlFileEntryPersistence.create(fileEntryId);
		
		dlFileEntry.setUuid(serviceContext.getUuid());
		dlFileEntry.setGroupId(groupId);
		dlFileEntry.setCompanyId(user.getCompanyId());
		dlFileEntry.setUserId(user.getUserId());
		dlFileEntry.setUserName(user.getFullName());
		dlFileEntry.setVersionUserId(user.getUserId());
		dlFileEntry.setVersionUserName(user.getFullName());
		dlFileEntry.setCreateDate(serviceContext.getCreateDate(now));
		dlFileEntry.setModifiedDate(serviceContext.getModifiedDate(now));
		dlFileEntry.setRepositoryId(repositoryId);
		dlFileEntry.setFolderId(folderId);
		dlFileEntry.setName(name);
		dlFileEntry.setExtension(extension);
		dlFileEntry.setMimeType(mimeType);
		dlFileEntry.setTitle(title);
		dlFileEntry.setDescription(description);
		dlFileEntry.setFileEntryTypeId(fileEntryTypeId);
		dlFileEntry.setVersion(DLFileEntryConstants.VERSION_DEFAULT);
		dlFileEntry.setSize(size);
		dlFileEntry.setReadCount(DLFileEntryConstants.DEFAULT_READ_COUNT);
		
		dlFileEntryPersistence.update(dlFileEntry, false);
		/*ADDED*/
		String path = null;
		String fileName = null;
		boolean skipStore = false;
		if (DocumentLibraryDataUtil.isRemoteStoreEnabled())
		{
			String zone;
			String subDir = null;
			skipStore = serviceContext.getAttribute(DocumentLibraryDataConstants.URL) != null;
			try
			{
				zone = CustomFieldDataUtil.getGroupTimeZoneValueByGroupIdWithDefault(dlFileEntry.getGroupId());
				if (zone == null)
				{
					zone = PropsUtil.get(StoreConstants.DL_TIMEZONE);
				}
				// change the filename if the media is external.
				Object isEncodeVideo = serviceContext.getAttribute(StoreConstants.IS_ENCODED_VIDEO);
				if (isEncodeVideo != null && Boolean.TRUE.equals((Boolean) isEncodeVideo)) {
					subDir = DocumentLibraryDataUtil.getCdnSubDirectoryPath(dlFileEntry.getCompanyId(),
							dlFileEntry.getGroupId(), mimeType, true);
				} else {
					subDir = DocumentLibraryDataUtil.getCdnSubDirectoryPath(dlFileEntry.getCompanyId(),
							dlFileEntry.getGroupId(), mimeType);
				}
				
			} catch (Exception e) {
				throw new SystemException(e);
			}
			if (skipStore)
			{
				String url = (String)serviceContext.getAttribute(DocumentLibraryDataConstants.URL);
				Boolean isFromMovide = serviceContext.getAttribute("isFromMovide") != null
						? (Boolean)serviceContext.getAttribute("isFromMovide") : false;
				
				if(isFromMovide) {
					// set the path value as "movideid"
					path = url;
					fileName = sourceFileName;
				} else {
					path = url.substring(0,url.lastIndexOf(DocumentLibraryDataConstants.PATH_SEPARATOR));
					fileName = url.substring(url.lastIndexOf(DocumentLibraryDataConstants.PATH_SEPARATOR)+1);
				}
			}
			else
			{
				path = subDir + StoreConstants.PATH_SEPARATOR +  CDNStoreUtil.getDateDirectoryNameForMedia(serviceContext.getCreateDate(now), TimeZone.getTimeZone(zone));
			}
			serviceContext.getExpandoBridgeAttributes().put(DocumentLibraryDataConstants.CUSTOM_FIELD_PATH,path);
		}
		/*END ADDED*/
		
		// File version
		DLFileVersion dlFileVersion = addFileVersion(
				user, dlFileEntry, serviceContext.getModifiedDate(now), extension,
				mimeType, title, description, null, StringPool.BLANK,
				fileEntryTypeId, fieldsMap, DLFileEntryConstants.VERSION_DEFAULT,
				size, WorkflowConstants.STATUS_DRAFT, serviceContext);
		
		dlFileEntry.setFileVersion(dlFileVersion);
		
		/*ADDED*/
		if (DocumentLibraryDataUtil.isRemoteStoreEnabled())
		{
			if (skipStore)
			{
				serviceContext.getExpandoBridgeAttributes().put(DocumentLibraryDataConstants.CUSTOM_FILENAME,fileName);
			}
			else
			{
				//encode the sourceFileName using UTF-8 except the space character will not encode.
				String customFileName = HttpUtil.encodeURL(CDNStoreUtil.getRemoteFileName(sourceFileName,dlFileVersion.getVersion(),name), true);
				serviceContext.getExpandoBridgeAttributes().put(DocumentLibraryDataConstants.CUSTOM_FILENAME,
						customFileName);
			}
			dlFileVersion.setExpandoBridgeAttributes(serviceContext);
			dlFileVersionPersistence.update(dlFileVersion, false);
		}
		/*END ADDED*/
		
		// Folder
		
		if (folderId != DLFolderConstants.DEFAULT_PARENT_FOLDER_ID) {
			DLFolder dlFolder = dlFolderPersistence.findByPrimaryKey(
					dlFileEntry.getFolderId());
			
			dlFolder.setLastPostDate(dlFileEntry.getModifiedDate());
			
			dlFolderPersistence.update(dlFolder, false);
		}
		
		// change the filename if the media is external.
		Object isExternal = serviceContext.getAttribute(StoreConstants.IS_EXTERNAL);
		if (isExternal != null && (Boolean) isExternal) {
			sourceFileName = StoreConstants.ANVATO_IMAGE_IDENTIFICATION + sourceFileName;
		}
		
		// File
		if (file != null) {
			/*ADDED*/
			if (DocumentLibraryDataUtil.isRemoteStoreEnabled() && !skipStore)
			{
				DLStoreUtil.addFile(
						user.getCompanyId(), dlFileEntry.getDataRepositoryId(), CDNStoreUtil.getRemoteFilePath(sourceFileName,dlFileVersion.getVersion(),name,path),
						false, file);
			/*END ADDED*/
			}
			else
			{
				DLStoreUtil.addFile(
						user.getCompanyId(), dlFileEntry.getDataRepositoryId(), name,
						false, file);
			}
			
		}
		else {
			/*ADDED*/
			if (DocumentLibraryDataUtil.isRemoteStoreEnabled() && !skipStore && serviceContext.getAttribute(VideoBean.EXT_VIDEO_ID) == null)
			{
				DLStoreUtil.addFile(
						user.getCompanyId(), dlFileEntry.getDataRepositoryId(), CDNStoreUtil.getRemoteFilePath(sourceFileName,dlFileVersion.getVersion(),name,path),
						false, is);
			/*END ADDED*/
			}
			else {
				DLStoreUtil.addFile(
						user.getCompanyId(), dlFileEntry.getDataRepositoryId(), name,
						false, is);
			}
		}
		
		return dlFileEntry;
	}
	
	@Override
	public DLFileVersion cancelCheckOut(long userId, long fileEntryId)
			throws PortalException, SystemException {
		
		if (!isFileEntryCheckedOut(fileEntryId)) {
			return null;
		}
		
		DLFileEntry dlFileEntry = dlFileEntryPersistence.findByPrimaryKey(
				fileEntryId);
		
		DLFileVersion dlFileVersion =
				dlFileVersionLocalService.getLatestFileVersion(fileEntryId, false);
		
		removeFileVersion(dlFileEntry, dlFileVersion);
		
		if (dlFileEntry.getFolderId() !=
				DLFolderConstants.DEFAULT_PARENT_FOLDER_ID) {
			
			DLFolder dlFolder = dlFolderPersistence.findByPrimaryKey(
					dlFileEntry.getFolderId());
			
			dlFolder.setLastPostDate(new Date());
			
			dlFolderPersistence.update(dlFolder, false);
		}
		
		return dlFileVersion;
	}
	
	@Override
	public void checkInFileEntry(
			long userId, long fileEntryId, boolean majorVersion,
			String changeLog, ServiceContext serviceContext)
			throws PortalException, SystemException {
		
		if (!isFileEntryCheckedOut(fileEntryId)) {
			return;
		}
		
		User user = userPersistence.findByPrimaryKey(userId);
		
		DLFileEntry dlFileEntry = dlFileEntryPersistence.findByPrimaryKey(
				fileEntryId);
		
		DLFileVersion lastDLFileVersion =
				dlFileVersionLocalService.getFileVersion(
						dlFileEntry.getFileEntryId(), dlFileEntry.getVersion());
		DLFileVersion latestDLFileVersion =
				dlFileVersionLocalService.getLatestFileVersion(fileEntryId, false);
		/*ADDED*/
		String filePath = null;
		if (DocumentLibraryDataUtil.isRemoteStoreEnabled())
		{
			filePath = (String)latestDLFileVersion.getExpandoBridge().getAttribute(DocumentLibraryDataConstants.CUSTOM_FIELD_PATH) +  StoreConstants.PATH_SEPARATOR + (String)latestDLFileVersion.getExpandoBridge().getAttribute(DocumentLibraryDataConstants.CUSTOM_FILENAME);
		}
		// if migrate image, sps/lin use 640x480 size image as source image, foxsport not.
		Boolean useResizeImageAsSource = ConfigurationPropertyUtil.getInstance().getBoolean(dlFileEntry.getCompanyId(), 0, StoreConstants.MIGRATE_IMAGE_USE_RESIZE_AS_SOURCE);
		if (isMigrateEntry(dlFileEntry) && useResizeImageAsSource != null && useResizeImageAsSource.booleanValue() == Boolean.TRUE) {
			GroupThreadLocal.setGroupId(dlFileEntry.getGroupId());
			filePath = resetMigrateEntryFilePath(filePath);
			GroupThreadLocal.setGroupId(0L);
		}
		/*END ADDED*/
		if (isKeepFileVersionLabel(
				dlFileEntry, lastDLFileVersion, latestDLFileVersion,
				serviceContext.getWorkflowAction())) {
			
			if (lastDLFileVersion.getSize() != latestDLFileVersion.getSize()) {
				
				// File version
				
				lastDLFileVersion.setExtension(
						latestDLFileVersion.getExtension());
				lastDLFileVersion.setMimeType(
						latestDLFileVersion.getMimeType());
				lastDLFileVersion.setSize(latestDLFileVersion.getSize());
				
				dlFileVersionPersistence.update(lastDLFileVersion, false);
				
				// File
				
				try {
					DLStoreUtil.deleteFile(
							user.getCompanyId(), dlFileEntry.getDataRepositoryId(),
							dlFileEntry.getName(), lastDLFileVersion.getVersion());
				}
				catch (NoSuchModelException nsme) {
				}
				
				DLStoreUtil.copyFileVersion(
						user.getCompanyId(), dlFileEntry.getDataRepositoryId(),
						dlFileEntry.getName(),
						DLFileEntryConstants.PRIVATE_WORKING_COPY_VERSION,
						lastDLFileVersion.getVersion());
			}
			
			// Latest file version
			
			removeFileVersion(dlFileEntry, latestDLFileVersion);
			
			latestDLFileVersion = lastDLFileVersion;
		}
		else {
			
			// File version
			
			String version = getNextVersion(
					dlFileEntry, majorVersion, serviceContext.getWorkflowAction());
			
			latestDLFileVersion.setVersion(version);
			latestDLFileVersion.setChangeLog(changeLog);
			
			dlFileVersionPersistence.update(latestDLFileVersion, false);
			
			// File
			/*ADDED*/
			if (DocumentLibraryDataUtil.isRemoteStoreEnabled())
			{
				DLStoreUtil.updateFileVersion(
						user.getCompanyId(), dlFileEntry.getDataRepositoryId(),
						filePath,
						DLFileEntryConstants.PRIVATE_WORKING_COPY_VERSION, version);
				String customFileName = (String)latestDLFileVersion.getExpandoBridge().getAttribute(DocumentLibraryDataConstants.CUSTOM_FILENAME);
				customFileName = customFileName.replace(DLFileEntryConstants.PRIVATE_WORKING_COPY_VERSION, version);
				serviceContext.getExpandoBridgeAttributes().put(DocumentLibraryDataConstants.CUSTOM_FILENAME,customFileName);
				latestDLFileVersion.setExpandoBridgeAttributes(serviceContext);
				dlFileVersionPersistence.update(latestDLFileVersion, false);
			}
		/*END ADDED*/
			else {
				DLStoreUtil.updateFileVersion(
						user.getCompanyId(), dlFileEntry.getDataRepositoryId(),
						dlFileEntry.getName(),
						DLFileEntryConstants.PRIVATE_WORKING_COPY_VERSION, version);
			}
		}
		
		// Folder
		
		if (dlFileEntry.getFolderId() !=
				DLFolderConstants.DEFAULT_PARENT_FOLDER_ID) {
			
			DLFolder dlFolder = dlFolderPersistence.findByPrimaryKey(
					dlFileEntry.getFolderId());
			
			dlFolder.setLastPostDate(dlFileEntry.getModifiedDate());
			
			dlFolderPersistence.update(dlFolder, false);
		}
		
		// Workflow
		
		if (serviceContext.getWorkflowAction() ==
				WorkflowConstants.ACTION_PUBLISH) {
			
			startWorkflowInstance(
					userId, serviceContext, latestDLFileVersion,
					DLSyncConstants.EVENT_UPDATE);
		}
		
		lockLocalService.unlock(DLFileEntry.class.getName(), fileEntryId);
	}
	
	@Override
	public void checkInFileEntry(long userId, long fileEntryId, String lockUuid)
			throws PortalException, SystemException {
		
		if (Validator.isNotNull(lockUuid)) {
			try {
				Lock lock = lockLocalService.getLock(
						DLFileEntry.class.getName(), fileEntryId);
				
				if (!lock.getUuid().equals(lockUuid)) {
					throw new InvalidLockException("UUIDs do not match");
				}
			}
			catch (PortalException pe) {
				if ((pe instanceof ExpiredLockException) ||
						(pe instanceof NoSuchLockException)) {
				}
				else {
					throw pe;
				}
			}
		}
		
		checkInFileEntry(
				userId, fileEntryId, false, StringPool.BLANK, new ServiceContext());
	}
	
	/**
	 * @deprecated {@link #checkOutFileEntry(long, long, ServiceContext)}
	 */
	@Override
	public DLFileEntry checkOutFileEntry(long userId, long fileEntryId)
			throws PortalException, SystemException {
		
		return checkOutFileEntry(userId, fileEntryId, new ServiceContext());
	}
	
	@Override
	public DLFileEntry checkOutFileEntry(
			long userId, long fileEntryId, ServiceContext serviceContext)
			throws PortalException, SystemException {
		
		return checkOutFileEntry(
				userId, fileEntryId, StringPool.BLANK,
				DLFileEntryImpl.LOCK_EXPIRATION_TIME, serviceContext);
	}
	
	/**
	 * @deprecated {@link #checkOutFileEntry(long, long, String, long,
	 *             ServiceContext)}
	 */
	@Override
	public DLFileEntry checkOutFileEntry(
			long userId, long fileEntryId, String owner, long expirationTime)
			throws PortalException, SystemException {
		
		return checkOutFileEntry(
				userId, fileEntryId, owner, expirationTime, new ServiceContext());
	}
	
	@Override
	public DLFileEntry checkOutFileEntry(
			long userId, long fileEntryId, String owner, long expirationTime,
			ServiceContext serviceContext)
			throws PortalException, SystemException {
		
		DLFileEntry dlFileEntry = dlFileEntryPersistence.findByPrimaryKey(
				fileEntryId);
		
		boolean hasLock = hasFileEntryLock(userId, fileEntryId);
		
		if (!hasLock) {
			if ((expirationTime <= 0) ||
					(expirationTime > DLFileEntryImpl.LOCK_EXPIRATION_TIME)) {
				
				expirationTime = DLFileEntryImpl.LOCK_EXPIRATION_TIME;
			}
			
			lockLocalService.lock(
					userId, DLFileEntry.class.getName(), fileEntryId, owner, false,
					expirationTime);
		}
		
		User user = userPersistence.findByPrimaryKey(userId);
		
		serviceContext.setCompanyId(user.getCompanyId());
		
		DLFileVersion dlFileVersion =
				dlFileVersionLocalService.getLatestFileVersion(fileEntryId, false);
		
		long dlFileVersionId = dlFileVersion.getFileVersionId();
		
		ExpandoBridge expandoBridge = ExpandoBridgeFactoryUtil.getExpandoBridge(
				serviceContext.getCompanyId(), DLFileEntry.class.getName(),
				dlFileVersionId);
		
		serviceContext.setExpandoBridgeAttributes(
				expandoBridge.getAttributes());
		
		serviceContext.setUserId(userId);
		
		dlFileEntryPersistence.update(dlFileEntry, false);
		
		String version = dlFileVersion.getVersion();
		/*ADDED*/
		String filePath = null;
		/*END ADDED*/
		if (!version.equals(
				DLFileEntryConstants.PRIVATE_WORKING_COPY_VERSION)) {
			
			long existingDLFileVersionId = ParamUtil.getLong(
					serviceContext, "existingDLFileVersionId");
			
			if (existingDLFileVersionId > 0) {
				DLFileVersion existingDLFileVersion =
						dlFileVersionPersistence.findByPrimaryKey(
								existingDLFileVersionId);
				
				dlFileVersion = updateFileVersion(
						user, existingDLFileVersion, null,
						existingDLFileVersion.getExtension(),
						existingDLFileVersion.getMimeType(),
						existingDLFileVersion.getTitle(),
						existingDLFileVersion.getDescription(),
						existingDLFileVersion.getChangeLog(),
						existingDLFileVersion.getExtraSettings(),
						existingDLFileVersion.getFileEntryTypeId(), null,
						DLFileEntryConstants.PRIVATE_WORKING_COPY_VERSION,
						existingDLFileVersion.getSize(),
						WorkflowConstants.STATUS_DRAFT, new Date(), serviceContext);
			}
			else {
				/*ADDED*/
				if (DocumentLibraryDataUtil.isRemoteStoreEnabled())
				{
					filePath = (String)dlFileVersion.getExpandoBridge().getAttribute(DocumentLibraryDataConstants.CUSTOM_FIELD_PATH);
					serviceContext.getExpandoBridgeAttributes().put(DocumentLibraryDataConstants.CUSTOM_FIELD_PATH,filePath);
					
					String customFileName = (String)dlFileVersion.getExpandoBridge().getAttribute(DocumentLibraryDataConstants.CUSTOM_FILENAME);
					customFileName = customFileName.replace(StoreConstants.UNDERSCORE+version+StoreConstants.DOT,StoreConstants.UNDERSCORE+DLFileEntryConstants.PRIVATE_WORKING_COPY_VERSION+StoreConstants.DOT);
					serviceContext.getExpandoBridgeAttributes().put(DocumentLibraryDataConstants.CUSTOM_FILENAME,customFileName);
					
				}
				/*END ADDED*/
				
				dlFileVersion = addFileVersion(
						user, dlFileEntry, new Date(), dlFileVersion.getExtension(),
						dlFileVersion.getMimeType(), dlFileVersion.getTitle(),
						dlFileVersion.getDescription(),
						dlFileVersion.getChangeLog(),
						dlFileVersion.getExtraSettings(),
						dlFileVersion.getFileEntryTypeId(), null,
						DLFileEntryConstants.PRIVATE_WORKING_COPY_VERSION,
						dlFileVersion.getSize(), WorkflowConstants.STATUS_DRAFT,
						serviceContext);
			}
			if (DocumentLibraryDataUtil.isRemoteStoreEnabled())
			{
				filePath = (String)dlFileVersion.getExpandoBridge().getAttribute(DocumentLibraryDataConstants.CUSTOM_FIELD_PATH) +  StoreConstants.PATH_SEPARATOR + (String)dlFileVersion.getExpandoBridge().getAttribute(DocumentLibraryDataConstants.CUSTOM_FILENAME);
			}
			// if migrate image, sps/lin use 640x480 size image as source image, foxsport not.
			Boolean useResizeImageAsSource = ConfigurationPropertyUtil.getInstance().getBoolean(dlFileEntry.getCompanyId(), 0, StoreConstants.MIGRATE_IMAGE_USE_RESIZE_AS_SOURCE);
			if (isMigrateEntry(dlFileEntry) && useResizeImageAsSource != null && useResizeImageAsSource.booleanValue() == Boolean.TRUE) {
				GroupThreadLocal.setGroupId(dlFileEntry.getGroupId());
				filePath = resetMigrateEntryFilePath(filePath);
				GroupThreadLocal.setGroupId(0L);
			}
			try {
				if (DocumentLibraryDataUtil.isRemoteStoreEnabled())
				{
					DLStoreUtil.deleteFile(
							dlFileEntry.getCompanyId(),
							dlFileEntry.getDataRepositoryId(), filePath,
							DLFileEntryConstants.PRIVATE_WORKING_COPY_VERSION);
				}
				else {
					DLStoreUtil.deleteFile(
							dlFileEntry.getCompanyId(),
							dlFileEntry.getDataRepositoryId(), dlFileEntry.getName(),
							DLFileEntryConstants.PRIVATE_WORKING_COPY_VERSION);
				}
			}
			catch (NoSuchModelException nsme) {
			}
			if (DocumentLibraryDataUtil.isRemoteStoreEnabled())
			{
				DLStoreUtil.copyFileVersion(
						user.getCompanyId(), dlFileEntry.getDataRepositoryId(),
						filePath, version,
						DLFileEntryConstants.PRIVATE_WORKING_COPY_VERSION);
			}
			else {
				DLStoreUtil.copyFileVersion(
						user.getCompanyId(), dlFileEntry.getDataRepositoryId(),
						dlFileEntry.getName(), version,
						DLFileEntryConstants.PRIVATE_WORKING_COPY_VERSION);
			}
			copyFileEntryMetadata(
					dlFileEntry.getCompanyId(), dlFileVersion.getFileEntryTypeId(),
					fileEntryId, dlFileVersionId, dlFileVersion.getFileVersionId(),
					serviceContext);
		}
		
		if (dlFileEntry.getFolderId() !=
				DLFolderConstants.DEFAULT_PARENT_FOLDER_ID) {
			
			DLFolder dlFolder = dlFolderPersistence.findByPrimaryKey(
					dlFileEntry.getFolderId());
			
			dlFolder.setLastPostDate(dlFileVersion.getModifiedDate());
			
			dlFolderPersistence.update(dlFolder, false);
		}
		
		return dlFileEntry;
	}
	
	@Override
	public void convertExtraSettings(String[] keys)
			throws PortalException, SystemException {
		
		int count = dlFileEntryFinder.countByExtraSettings();
		
		int pages = count / Indexer.DEFAULT_INTERVAL;
		
		for (int i = 0; i <= pages; i++) {
			int start = (i * Indexer.DEFAULT_INTERVAL);
			int end = start + Indexer.DEFAULT_INTERVAL;
			
			List<DLFileEntry> dlFileEntries =
					dlFileEntryFinder.findByExtraSettings(start, end);
			
			for (DLFileEntry dlFileEntry : dlFileEntries) {
				convertExtraSettings(dlFileEntry, keys);
			}
		}
	}
	
	@Override
	public void copyFileEntryMetadata(
			long companyId, long fileEntryTypeId, long fileEntryId,
			long fromFileVersionId, long toFileVersionId,
			ServiceContext serviceContext)
			throws PortalException, SystemException {
		
		Map<String, Fields> fieldsMap = new HashMap<String, Fields>();
		
		List<DDMStructure> ddmStructures = null;
		
		if (fileEntryTypeId > 0) {
			DLFileEntryType dlFileEntryType =
					dlFileEntryTypeLocalService.getFileEntryType(fileEntryTypeId);
			
			ddmStructures = dlFileEntryType.getDDMStructures();
			
			for (DDMStructure ddmStructure : ddmStructures) {
				try {
					DLFileEntryMetadata dlFileEntryMetadata =
							dlFileEntryMetadataLocalService.getFileEntryMetadata(
									ddmStructure.getStructureId(), fromFileVersionId);
					
					Fields fields = StorageEngineUtil.getFields(
							dlFileEntryMetadata.getDDMStorageId());
					
					fieldsMap.put(ddmStructure.getStructureKey(), fields);
				}
				catch (NoSuchFileEntryMetadataException nsfeme) {
				}
			}
			
			dlFileEntryMetadataLocalService.updateFileEntryMetadata(
					companyId, ddmStructures, fileEntryTypeId, fileEntryId,
					toFileVersionId, fieldsMap, serviceContext);
		}
		
		long classNameId = PortalUtil.getClassNameId(DLFileEntry.class);
		
		ddmStructures = ddmStructureLocalService.getClassStructures(
				companyId, classNameId);
		
		for (DDMStructure ddmStructure : ddmStructures) {
			try {
				DLFileEntryMetadata fileEntryMetadata =
						dlFileEntryMetadataLocalService.getFileEntryMetadata(
								ddmStructure.getStructureId(), fromFileVersionId);
				
				Fields fields = StorageEngineUtil.getFields(
						fileEntryMetadata.getDDMStorageId());
				
				fieldsMap.put(ddmStructure.getStructureKey(), fields);
			}
			catch (NoSuchFileEntryMetadataException nsfeme) {
			}
		}
		
		dlFileEntryMetadataLocalService.updateFileEntryMetadata(
				companyId, ddmStructures, fileEntryTypeId, fileEntryId,
				toFileVersionId, fieldsMap, serviceContext);
	}
	
	@Override
	public void deleteFileEntries(long groupId, long folderId)
			throws PortalException, SystemException {
		
		int count = dlFileEntryPersistence.countByG_F(groupId, folderId);
		
		int pages = count / _DELETE_INTERVAL;
		
		for (int i = 0; i <= pages; i++) {
			int start = (i * _DELETE_INTERVAL);
			int end = start + _DELETE_INTERVAL;
			
			List<DLFileEntry> dlFileEntries = dlFileEntryPersistence.findByG_F(
					groupId, folderId, start, end);
			
			for (DLFileEntry dlFileEntry : dlFileEntries) {
				dlAppHelperLocalService.deleteFileEntry(
						new LiferayFileEntry(dlFileEntry));
				
				deleteFileEntry(dlFileEntry);
			}
		}
	}
	
	@Override
	public void deleteFileEntry(long fileEntryId)
			throws PortalException, SystemException {
		
		DLFileEntry dlFileEntry = getFileEntry(fileEntryId);
		
		deleteFileEntry(dlFileEntry);
	}
	
	@Override
	public void deleteFileEntry(long userId, long fileEntryId)
			throws PortalException, SystemException {
		
		if (!hasFileEntryLock(userId, fileEntryId)) {
			lockFileEntry(userId, fileEntryId);
		}
		
		try {
			DLFileEntry dlFileEntry = getFileEntry(fileEntryId);
			ActivityFeedEntryVO feedEntryVO = new ActivityFeedEntryVO();
			feedEntryVO.setUserId(userId);
			feedEntryVO.setGroupId(dlFileEntry.getGroupId());
			feedEntryVO.setModelId(fileEntryId);
			feedEntryVO.setExtraData(new ActivityFeedExtraDataBaseVO(dlFileEntry.getTitle()));
			feedEntryVO.setEventTypeId(MediaActivityEventTypeEnum.DELETE_IMAGE.getEventTypeId());
			
			deleteFileEntry(fileEntryId);
			
			ActivityFeedLocalServiceUtil.addImageActivity(feedEntryVO);
		}
		finally {
			unlockFileEntry(fileEntryId);
		}
	}
	
	@Indexable(type = IndexableType.DELETE)
	@Override
	public void deleteFileVersion(long userId, long fileEntryId, String version)
			throws PortalException, SystemException {
		
		if (Validator.isNull(version) ||
				version.equals(DLFileEntryConstants.PRIVATE_WORKING_COPY_VERSION)) {
			
			throw new InvalidFileVersionException();
		}
		
		if (!hasFileEntryLock(userId, fileEntryId)) {
			lockFileEntry(userId, fileEntryId);
		}
		
		try {
			DLFileVersion dlFileVersion = dlFileVersionPersistence.findByF_V(
					fileEntryId, version);
			
			if (!dlFileVersion.isApproved()) {
				throw new InvalidFileVersionException(
						"Cannot delete an unapproved file version");
			}
			else {
				int count = dlFileVersionPersistence.countByF_S(
						fileEntryId, WorkflowConstants.STATUS_APPROVED);
				
				if (count <= 1) {
					throw new InvalidFileVersionException(
							"Cannot delete the only approved file version");
				}
			}
			
			dlFileVersionPersistence.remove(dlFileVersion);
			
			expandoValueLocalService.deleteValues(
					DLFileVersion.class.getName(),
					dlFileVersion.getFileVersionId());
			
			DLFileEntry dlFileEntry = dlFileEntryPersistence.findByPrimaryKey(
					fileEntryId);
			
			if (version.equals(dlFileEntry.getVersion())) {
				try {
					DLFileVersion dlLatestFileVersion =
							dlFileVersionLocalService.getLatestFileVersion(
									dlFileEntry.getFileEntryId(), true);
					
					dlFileEntry.setVersionUserId(
							dlLatestFileVersion.getUserId());
					dlFileEntry.setVersionUserName(
							dlLatestFileVersion.getUserName());
					dlFileEntry.setModifiedDate(
							dlLatestFileVersion.getCreateDate());
					dlFileEntry.setExtension(
							dlLatestFileVersion.getExtension());
					dlFileEntry.setMimeType(dlLatestFileVersion.getMimeType());
					dlFileEntry.setTitle(dlLatestFileVersion.getTitle());
					dlFileEntry.setDescription(
							dlLatestFileVersion.getDescription());
					dlFileEntry.setExtraSettings(
							dlLatestFileVersion.getExtraSettings());
					dlFileEntry.setFileEntryTypeId(
							dlLatestFileVersion.getFileEntryTypeId());
					dlFileEntry.setVersion(dlLatestFileVersion.getVersion());
					dlFileEntry.setSize(dlLatestFileVersion.getSize());
					
					dlFileEntryPersistence.update(dlFileEntry, false);
				}
				catch (NoSuchFileVersionException nsfve) {
				}
			}
			
			try {
				DLStoreUtil.deleteFile(
						dlFileEntry.getCompanyId(),
						dlFileEntry.getDataRepositoryId(), dlFileEntry.getName(),
						version);
			}
			catch (NoSuchModelException nsme) {
			}
		}
		finally {
			unlockFileEntry(fileEntryId);
		}
	}
	
	@Override
	public DLFileEntry fetchFileEntryByAnyImageId(long imageId)
			throws SystemException {
		
		return dlFileEntryFinder.fetchByAnyImageId(imageId);
	}
	
	@Override
	public DLFileEntry fetchFileEntryByName(
			long groupId, long folderId, String name)
			throws SystemException {
		
		return dlFileEntryPersistence.fetchByG_F_N(groupId, folderId, name);
	}
	
	@Override
	public List<DLFileEntry> getExtraSettingsFileEntries(int start, int end)
			throws SystemException {
		
		return dlFileEntryFinder.findByExtraSettings(start, end);
	}
	
	@Override
	public File getFile(
			long userId, long fileEntryId, String version,
			boolean incrementCounter)
			throws PortalException, SystemException {
		
		DLFileEntry dlFileEntry = dlFileEntryPersistence.findByPrimaryKey(
				fileEntryId);
		
		if (PropsValues.DL_FILE_ENTRY_READ_COUNT_ENABLED && incrementCounter) {
			dlFileEntry.setReadCount(dlFileEntry.getReadCount() + 1);
			
			dlFileEntryPersistence.update(dlFileEntry, false);
		}
		
		dlAppHelperLocalService.getFileAsStream(
				userId, new LiferayFileEntry(dlFileEntry), incrementCounter);
		
		return DLStoreUtil.getFile(
				dlFileEntry.getCompanyId(), dlFileEntry.getDataRepositoryId(),
				dlFileEntry.getName(), version);
	}
	
	@Override
	public InputStream getFileAsStream(
			long userId, long fileEntryId, String version)
			throws PortalException, SystemException {
		
		return getFileAsStream(userId, fileEntryId, version, true);
	}
	
	@Override
	public InputStream getFileAsStream(
			long userId, long fileEntryId, String version,
			boolean incrementCounter)
			throws PortalException, SystemException {
		
		DLFileEntry dlFileEntry = dlFileEntryPersistence.findByPrimaryKey(
				fileEntryId);
		
		if (PropsValues.DL_FILE_ENTRY_READ_COUNT_ENABLED && incrementCounter) {
			dlFileEntry.setReadCount(dlFileEntry.getReadCount() + 1);
			
			dlFileEntryPersistence.update(dlFileEntry, false);
		}
		
		dlAppHelperLocalService.getFileAsStream(
				userId, new LiferayFileEntry(dlFileEntry), incrementCounter);
		/*ADDED*/
		if (DocumentLibraryDataUtil.isRemoteStoreEnabled())
		{
			DLFileVersion dlFileVersion = dlFileVersionLocalService.getFileVersion(fileEntryId, version);
			String filePath = (String)dlFileVersion.getExpandoBridge().getAttribute(DocumentLibraryDataConstants.CUSTOM_FIELD_PATH) +  StoreConstants.PATH_SEPARATOR + (String)dlFileVersion.getExpandoBridge().getAttribute(DocumentLibraryDataConstants.CUSTOM_FILENAME);
			return DLStoreUtil.getFileAsStream(
					dlFileEntry.getCompanyId(), dlFileEntry.getDataRepositoryId(),
					filePath, version);
		}
		/*END ADDED*/
		else {
			return DLStoreUtil.getFileAsStream(
					dlFileEntry.getCompanyId(), dlFileEntry.getDataRepositoryId(),
					dlFileEntry.getName(), version);
		}
	}
	
	@Override
	public List<DLFileEntry> getFileEntries(int start, int end)
			throws SystemException {
		
		return dlFileEntryPersistence.findAll(start, end);
	}
	
	@Override
	public List<DLFileEntry> getFileEntries(
			long groupId, long folderId, int start, int end,
			OrderByComparator obc)
			throws SystemException {
		
		return dlFileEntryPersistence.findByG_F(
				groupId, folderId, start, end, obc);
	}
	
	@Override
	public List<DLFileEntry> getFileEntries(long folderId, String name)
			throws SystemException {
		
		return dlFileEntryPersistence.findByF_N(folderId, name);
	}
	
	@Override
	public int getFileEntriesCount() throws SystemException {
		return dlFileEntryPersistence.countAll();
	}
	
	@Override
	public int getFileEntriesCount(long groupId, long folderId)
			throws SystemException {
		
		return dlFileEntryPersistence.countByG_F(groupId, folderId);
	}
	
	@Override
	public DLFileEntry getFileEntry(long fileEntryId)
			throws PortalException, SystemException {
		
		DLFileEntry dlFileEntry = dlFileEntryPersistence.findByPrimaryKey(
				fileEntryId);
		
		setFileVersion(dlFileEntry);
		
		return dlFileEntry;
	}
	
	@Override
	public DLFileEntry getFileEntry(long groupId, long folderId, String title)
			throws PortalException, SystemException {
		
		DLFileEntry dlFileEntry = dlFileEntryPersistence.fetchByG_F_T(
				groupId, folderId, title);
		
		if (dlFileEntry != null) {
			setFileVersion(dlFileEntry);
			
			return dlFileEntry;
		}
		
		List<DLFileVersion> dlFileVersions =
				dlFileVersionPersistence.findByG_F_T_V(
						groupId, folderId, title,
						DLFileEntryConstants.PRIVATE_WORKING_COPY_VERSION);
		
		long userId = PrincipalThreadLocal.getUserId();
		
		for (DLFileVersion dlFileVersion : dlFileVersions) {
			if (hasFileEntryLock(userId, dlFileVersion.getFileEntryId())) {
				return dlFileVersion.getFileEntry();
			}
		}
		
		StringBundler sb = new StringBundler(8);
		
		sb.append("No DLFileEntry exists with the key {");
		sb.append("groupId=");
		sb.append(groupId);
		sb.append(", folderId=");
		sb.append(folderId);
		sb.append(", title=");
		sb.append(title);
		sb.append(StringPool.CLOSE_CURLY_BRACE);
		
		throw new NoSuchFileEntryException(sb.toString());
	}
	
	@Override
	public DLFileEntry getFileEntryByName(
			long groupId, long folderId, String name)
			throws PortalException, SystemException {
		
		DLFileEntry dlFileEntry = dlFileEntryPersistence.findByG_F_N(
				groupId, folderId, name);
		
		setFileVersion(dlFileEntry);
		
		return dlFileEntry;
	}
	
	@Override
	public DLFileEntry getFileEntryByUuidAndGroupId(String uuid, long groupId)
			throws PortalException, SystemException {
		
		DLFileEntry dlFileEntry = dlFileEntryPersistence.findByUUID_G(
				uuid, groupId);
		
		setFileVersion(dlFileEntry);
		
		return dlFileEntry;
	}
	
	@Override
	public List<DLFileEntry> getGroupFileEntries(
			long groupId, int start, int end)
			throws SystemException {
		
		return getGroupFileEntries(
				groupId, start, end, new RepositoryModelModifiedDateComparator());
	}
	
	@Override
	public List<DLFileEntry> getGroupFileEntries(
			long groupId, int start, int end, OrderByComparator obc)
			throws SystemException {
		
		return dlFileEntryPersistence.findByGroupId(groupId, start, end, obc);
	}
	
	@Override
	public List<DLFileEntry> getGroupFileEntries(
			long groupId, long userId, int start, int end)
			throws SystemException {
		
		return getGroupFileEntries(
				groupId, userId, start, end,
				new RepositoryModelModifiedDateComparator());
	}
	
	@Override
	public List<DLFileEntry> getGroupFileEntries(
			long groupId, long userId, int start, int end,
			OrderByComparator obc)
			throws SystemException {
		
		if (userId <= 0) {
			return dlFileEntryPersistence.findByGroupId(
					groupId, start, end, obc);
		}
		else {
			return dlFileEntryPersistence.findByG_U(
					groupId, userId, start, end, obc);
		}
	}
	
	@Override
	public int getGroupFileEntriesCount(long groupId) throws SystemException {
		return dlFileEntryPersistence.countByGroupId(groupId);
	}
	
	@Override
	public int getGroupFileEntriesCount(long groupId, long userId)
			throws SystemException {
		
		if (userId <= 0) {
			return dlFileEntryPersistence.countByGroupId(groupId);
		}
		else {
			return dlFileEntryPersistence.countByG_U(groupId, userId);
		}
	}
	
	@Override
	public List<DLFileEntry> getMisversionedFileEntries()
			throws SystemException {
		
		return dlFileEntryFinder.findByMisversioned();
	}
	
	@Override
	public List<DLFileEntry> getNoAssetFileEntries() throws SystemException {
		return dlFileEntryFinder.findByNoAssets();
	}
	
	@Override
	public List<DLFileEntry> getOrphanedFileEntries() throws SystemException {
		return dlFileEntryFinder.findByOrphanedFileEntries();
	}
	
	@Override
	public boolean hasExtraSettings() throws SystemException {
		if (dlFileEntryFinder.countByExtraSettings() > 0) {
			return true;
		}
		else {
			return false;
		}
	}
	
	@Override
	public boolean hasFileEntryLock(long userId, long fileEntryId)
			throws PortalException, SystemException {
		
		DLFileEntry dlFileEntry = getFileEntry(fileEntryId);
		
		long folderId = dlFileEntry.getFolderId();
		
		boolean hasLock = lockLocalService.hasLock(
				userId, DLFileEntry.class.getName(), fileEntryId);
		
		if (!hasLock &&
				(folderId != DLFolderConstants.DEFAULT_PARENT_FOLDER_ID)) {
			
			hasLock = dlFolderService.hasInheritableLock(folderId);
		}
		
		return hasLock;
	}
	
	@Override
	public boolean isFileEntryCheckedOut(long fileEntryId)
			throws PortalException, SystemException {
		
		DLFileVersion dlFileVersion =
				dlFileVersionLocalService.getLatestFileVersion(fileEntryId, false);
		
		String version = dlFileVersion.getVersion();
		
		if (version.equals(DLFileEntryConstants.PRIVATE_WORKING_COPY_VERSION)) {
			return true;
		}
		else {
			return false;
		}
	}
	
	@Override
	public DLFileEntry moveFileEntry(
			long userId, long fileEntryId, long newFolderId,
			ServiceContext serviceContext)
			throws PortalException, SystemException {
		
		if (!hasFileEntryLock(userId, fileEntryId)) {
			lockFileEntry(userId, fileEntryId);
		}
		
		try {
			DLFileEntry dlFileEntry = moveFileEntryImpl(
					userId, fileEntryId, newFolderId, serviceContext);
			
			dlAppHelperLocalService.moveFileEntry(
					new LiferayFileEntry(dlFileEntry));
			
			return dlFileEntryTypeLocalService.updateFileEntryFileEntryType(
					dlFileEntry, serviceContext);
		}
		finally {
			if (!isFileEntryCheckedOut(fileEntryId)) {
				unlockFileEntry(fileEntryId);
			}
		}
	}
	
	@Override
	public void revertFileEntry(
			long userId, long fileEntryId, String version,
			ServiceContext serviceContext)
			throws PortalException, SystemException {
		
		if (Validator.isNull(version) ||
				version.equals(DLFileEntryConstants.PRIVATE_WORKING_COPY_VERSION)) {
			
			throw new InvalidFileVersionException();
		}
		
		DLFileVersion dlFileVersion = dlFileVersionLocalService.getFileVersion(
				fileEntryId, version);
		
		if (!dlFileVersion.isApproved()) {
			throw new InvalidFileVersionException(
					"Cannot revert from an unapproved file version");
		}
		
		DLFileVersion latestDLFileVersion =
				dlFileVersionLocalService.getLatestFileVersion(fileEntryId, false);
		
		if (version.equals(latestDLFileVersion.getVersion())) {
			throw new InvalidFileVersionException(
					"Cannot revert from the latest file version");
		}
		
		String sourceFileName = dlFileVersion.getTitle();
		String extension = dlFileVersion.getExtension();
		String mimeType = dlFileVersion.getMimeType();
		String title = dlFileVersion.getTitle();
		String description = dlFileVersion.getDescription();
		String changeLog = "Reverted to " + version;
		boolean majorVersion = true;
		String extraSettings = dlFileVersion.getExtraSettings();
		long fileEntryTypeId = dlFileVersion.getFileEntryTypeId();
		Map<String, Fields> fieldsMap = null;
		InputStream is = getFileAsStream(userId, fileEntryId, version);
		long size = dlFileVersion.getSize();
		
		DLFileEntry dlFileEntry = updateFileEntry(
				userId, fileEntryId, sourceFileName, extension, mimeType, title,
				description, changeLog, majorVersion, extraSettings,
				fileEntryTypeId, fieldsMap, null, is, size, serviceContext);
		
		DLFileVersion newDlFileVersion =
				dlFileVersionLocalService.getFileVersion(
						fileEntryId, dlFileEntry.getVersion());
		
		copyFileEntryMetadata(
				dlFileVersion.getCompanyId(), dlFileVersion.getFileEntryTypeId(),
				fileEntryId, newDlFileVersion.getFileVersionId(),
				dlFileVersion.getFileVersionId(), serviceContext);
	}
	
	@Override
	public DLFileEntry updateFileEntry(
			long userId, long fileEntryId, String sourceFileName,
			String mimeType, String title, String description, String changeLog,
			boolean majorVersion, long fileEntryTypeId,
			Map<String, Fields> fieldsMap, File file, InputStream is, long size,
			ServiceContext serviceContext)
			throws PortalException, SystemException {
		
		DLFileEntry dlFileEntry = dlFileEntryPersistence.findByPrimaryKey(
				fileEntryId);
		
		String extension = DLAppUtil.getExtension(title, sourceFileName);
		
		String extraSettings = StringPool.BLANK;
		
		if (fileEntryTypeId == -1) {
			fileEntryTypeId = dlFileEntry.getFileEntryTypeId();
		}
		
		fileEntryTypeId = getFileEntryTypeId(
				DLUtil.getGroupIds(dlFileEntry.getGroupId()),
				dlFileEntry.getFolderId(), fileEntryTypeId);
		
		if (serviceContext.getAttribute(StoreConstants.REPLACE_IMAGE) != null
				&& (Boolean)serviceContext.getAttribute(StoreConstants.REPLACE_IMAGE)) {
			return updateFileEntryAndSource(
					userId, fileEntryId, sourceFileName, extension, mimeType, title,
					description, changeLog, majorVersion, extraSettings,
					fileEntryTypeId, fieldsMap, file, is, size, serviceContext);
		} else {
			return updateFileEntry(
					userId, fileEntryId, sourceFileName, extension, mimeType, title,
					description, changeLog, majorVersion, extraSettings,
					fileEntryTypeId, fieldsMap, file, is, size, serviceContext);
		}
	}
	
	protected DLFileEntry updateFileEntryAndSource(
			long userId, long fileEntryId, String sourceFileName, String extension,
			String mimeType, String title, String description, String changeLog,
			boolean majorVersion, String extraSettings, long fileEntryTypeId,
			Map<String, Fields> fieldsMap, File file, InputStream is, long size,
			ServiceContext serviceContext)
			throws PortalException, SystemException {
		
		User user = userPersistence.findByPrimaryKey(userId);
		DLFileEntry dlFileEntry = dlFileEntryPersistence.findByPrimaryKey(
				fileEntryId);
		/**  MODIFIED **/
		boolean checkedOut = true;
		boolean skipCheckOutCheckIn = BooleanUtils.toBoolean((Boolean)serviceContext.getAttribute(StoreConstants.SKIP_CHECKOUT_CHECKIN));
		System.out.println("Updating File Entry:"+ fileEntryId);
		
		if (skipCheckOutCheckIn == false)
		{
			checkedOut = dlFileEntry.isCheckedOut();
		}
		/** END MODIFIED **/
		
		DLFileVersion dlFileVersion =
				dlFileVersionLocalService.getLatestFileVersion(
						fileEntryId, !checkedOut);
		
		boolean autoCheckIn = !checkedOut && dlFileVersion.isApproved();
		
		if (autoCheckIn) {
			dlFileEntry = checkOutFileEntry(
					userId, fileEntryId, serviceContext);
		}
		else if (!checkedOut) {
			lockFileEntry(userId, fileEntryId);
		}
		
		if (!hasFileEntryLock(userId, fileEntryId)) {
			lockFileEntry(userId, fileEntryId);
		}
		
		if (checkedOut || autoCheckIn) {
			dlFileVersion = dlFileVersionLocalService.getLatestFileVersion(
					fileEntryId, false);
		}
		
		try {
			if (Validator.isNull(extension)) {
				extension = dlFileEntry.getExtension();
			}
			
			if (Validator.isNull(mimeType)) {
				mimeType = dlFileEntry.getMimeType();
			}
			
			if (Validator.isNull(title)) {
				title = sourceFileName;
				
				if (Validator.isNull(title)) {
					title = dlFileEntry.getTitle();
				}
			}
			
			Date now = new Date();
			
			validateFile(
					dlFileEntry.getGroupId(), dlFileEntry.getFolderId(),
					dlFileEntry.getFileEntryId(), title, extension, sourceFileName,
					file, is);
			
			String name = dlFileEntry.getName();
			String path = null;
			String fileName = null;
			/*ADDED*/
			if (DocumentLibraryDataUtil.isRemoteStoreEnabled())
			{
				String zone = null;
				try
				{
					if (StringUtils.isNotEmpty(sourceFileName))
					{
						zone = CustomFieldDataUtil.getGroupTimeZoneValueByGroupIdWithDefault(dlFileEntry.getGroupId());
						if (zone == null)
						{
							zone = PropsUtil.get(StoreConstants.DL_TIMEZONE);
						}
						path = DocumentLibraryDataUtil.getCdnSubDirectoryPath(dlFileEntry.getCompanyId(), dlFileEntry.getGroupId(),dlFileEntry.getMimeType()) + StoreConstants.PATH_SEPARATOR + CDNStoreUtil.getDateDirectoryNameForMedia(TimeZone.getTimeZone(zone));
						
						// Each time we upload an image to replace the original one, DLFileEntry version will increase every time, 
						// but the newly uploaded image file always have default version 1.0, 
						// We should use default version to generate image file name instead of dlfileentry version.
						fileName = HttpUtil.encodeURL(CDNStoreUtil.getRemoteFileName(sourceFileName, DLFileEntryConstants.VERSION_DEFAULT, name), true);
						serviceContext.getExpandoBridgeAttributes().put(DocumentLibraryDataConstants.CUSTOM_FIELD_PATH, path);
						serviceContext.getExpandoBridgeAttributes().put(DocumentLibraryDataConstants.CUSTOM_FILENAME, fileName);
					}
				} catch (Exception e) {
					throw new SystemException(e);
				}
			}
			/*END ADDED*/
			
			// File version
			
			String version = dlFileVersion.getVersion();
			
			if (size == 0) {
				size = dlFileVersion.getSize();
			}

			/* ADDED */
			if (skipCheckOutCheckIn)
			{
				//skipping checkout explicitly
				//For EndPlay story workflow every edit media action creates new version
				//without using checkout and checkin actions.
				
				if (DocumentLibraryDataUtil.isRemoteStoreEnabled())
				{
					dlFileVersion = dlFileVersionLocalService.getLatestFileVersion(fileEntryId, false);
					// Movideo custom filename override
					Boolean isFromMovide = serviceContext.getAttribute("isFromMovide") != null ? (Boolean)serviceContext.getAttribute("isFromMovide") : false;
					if (isFromMovide) {
						fileName = sourceFileName;
					}
					
					if(dlFileVersion.getStatus() == WorkflowConstants.STATUS_APPROVED) {
						version = getNextVersion(
								dlFileEntry, majorVersion, serviceContext.getWorkflowAction());
						dlFileVersion = addFileVersion(
								user, dlFileEntry, serviceContext.getModifiedDate(now), extension,
								dlFileVersion.getMimeType(), title,
								description,
								dlFileVersion.getChangeLog(),
								dlFileVersion.getExtraSettings(),
								dlFileVersion.getFileEntryTypeId(), fieldsMap,
								version,
								dlFileVersion.getSize(), WorkflowConstants.STATUS_DRAFT,
								serviceContext);
						System.out.println("New File version added:"+ dlFileVersion.getFileVersionId());
						dlFileEntry.setVersion(dlFileVersion.getVersion());
						//dlFileEntryPersistence.update(dlFileEntry, false);
						
					} else {
						dlFileVersion = updateFileVersion(
								user, dlFileVersion, sourceFileName, extension, mimeType, title,
								description, changeLog, extraSettings, fileEntryTypeId,
								fieldsMap, version, size, dlFileVersion.getStatus(),
								serviceContext.getModifiedDate(now), serviceContext);
					}
					// Synchronize dlFileEntry modifiedDate same as dlFileVersion
					dlFileEntry.setModifiedDate(dlFileVersion.getModifiedDate());
					dlFileEntryPersistence.update(dlFileEntry, false);
					
					if (skipCheckOutCheckIn)
					{
						checkedOut = false;
					}
					
				}
				else
				{
					updateFileVersion(
							user, dlFileVersion, sourceFileName, extension, mimeType, title,
							description, changeLog, extraSettings, fileEntryTypeId,
							fieldsMap, version, size, dlFileVersion.getStatus(),
							serviceContext.getModifiedDate(now), serviceContext);
				}
			}
			else
			{
				updateFileVersion(
						user, dlFileVersion, sourceFileName, extension, mimeType, title,
						description, changeLog, extraSettings, fileEntryTypeId,
						fieldsMap, version, size, dlFileVersion.getStatus(),
						serviceContext.getModifiedDate(now), serviceContext);
			}
			
			// App helper
			
			dlAppHelperLocalService.updateAsset(
					userId, new LiferayFileEntry(dlFileEntry),
					new LiferayFileVersion(dlFileVersion),
					serviceContext.getAssetCategoryIds(),
					serviceContext.getAssetTagNames(),
					serviceContext.getAssetLinkEntryIds());
			
			// File
			
			if ((file != null) || (is != null)) {
				
				if (file != null) {
					DLStoreUtil.addFile(
							user.getCompanyId(), dlFileEntry.getDataRepositoryId(), CDNStoreUtil.getRemoteFilePath(sourceFileName,DLFileEntryConstants.VERSION_DEFAULT,name,path),
							false, file);
				}
				else {

					/*ADDED*/
					if (DocumentLibraryDataUtil.isRemoteStoreEnabled())
					{
						DLStoreUtil.addFile(
								user.getCompanyId(), dlFileEntry.getDataRepositoryId(), CDNStoreUtil.getRemoteFilePath(sourceFileName,DLFileEntryConstants.VERSION_DEFAULT,name,path),
								false, is);
					}
					/*END ADDED*/
					else
					{
						DLStoreUtil.addFile(
								user.getCompanyId(), dlFileEntry.getDataRepositoryId(), CDNStoreUtil.getRemoteFilePath(sourceFileName,DLFileEntryConstants.VERSION_DEFAULT,name,path),
								false, is);
					}
				}
			}
			
			if (autoCheckIn) {
				checkInFileEntry(
						userId, fileEntryId, majorVersion, changeLog,
						serviceContext);
			}
			else if (!checkedOut &&
					(serviceContext.getWorkflowAction() ==
							WorkflowConstants.ACTION_PUBLISH)) {
				
				String syncEvent = DLSyncConstants.EVENT_UPDATE;
				
				if (dlFileVersion.getVersion().equals(
						DLFileEntryConstants.VERSION_DEFAULT)) {
					
					syncEvent = DLSyncConstants.EVENT_ADD;
				}
				
				startWorkflowInstance(
						userId, serviceContext, dlFileVersion, syncEvent);
			}
		}
		catch (PortalException pe) {
			if (autoCheckIn) {
				cancelCheckOut(userId, fileEntryId);
			}
			
			throw pe;
		}
		catch (SystemException se) {
			if (autoCheckIn) {
				cancelCheckOut(userId, fileEntryId);
			}
			
			throw se;
		}
		finally {
			if (!autoCheckIn && !checkedOut) {
				unlockFileEntry(fileEntryId);
			}
			//Remove replace image flag
			serviceContext.getAttributes().remove(StoreConstants.REPLACE_IMAGE);
		}
		return dlFileEntryPersistence.findByPrimaryKey(fileEntryId);
	}
	
	@Override
	public void updateSmallImage(long smallImageId, long largeImageId)
			throws PortalException, SystemException {
		
		try {
			RenderedImage renderedImage = null;
			
			Image largeImage = imageLocalService.getImage(largeImageId);
			
			byte[] bytes = largeImage.getTextObj();
			String contentType = largeImage.getType();
			
			if (bytes != null) {
				ImageBag imageBag = ImageToolUtil.read(bytes);
				
				renderedImage = imageBag.getRenderedImage();
				
				//validate(bytes);
			}
			
			if (renderedImage != null) {
				int height = PrefsPropsUtil.getInteger(
						PropsKeys.DL_FILE_ENTRY_THUMBNAIL_MAX_HEIGHT);
				int width = PrefsPropsUtil.getInteger(
						PropsKeys.DL_FILE_ENTRY_THUMBNAIL_MAX_WIDTH);
				
				RenderedImage thumbnailRenderedImage = ImageToolUtil.scale(
						renderedImage, height, width);
				
				imageLocalService.updateImage(
						smallImageId,
						ImageToolUtil.getBytes(
								thumbnailRenderedImage, contentType));
			}
		}
		catch (IOException ioe) {
			throw new ImageSizeException(ioe);
		}
	}
	
	@Override
	public DLFileEntry updateStatus(
			long userId, long fileVersionId, int status,
			Map<String, Serializable> workflowContext,
			ServiceContext serviceContext)
			throws PortalException, SystemException {
		
		// File version
		
		User user = userPersistence.findByPrimaryKey(userId);
		
		DLFileVersion dlFileVersion = dlFileVersionPersistence.findByPrimaryKey(
				fileVersionId);
		
		dlFileVersion.setStatus(status);
		dlFileVersion.setStatusByUserId(user.getUserId());
		dlFileVersion.setStatusByUserName(user.getFullName());
		dlFileVersion.setStatusDate(new Date());
		
		dlFileVersionPersistence.update(dlFileVersion, false);
		
		// File entry
		
		DLFileEntry dlFileEntry = dlFileEntryPersistence.findByPrimaryKey(
				dlFileVersion.getFileEntryId());
		
		if (status == WorkflowConstants.STATUS_APPROVED) {
			if (DLUtil.compareVersions(
					dlFileEntry.getVersion(),
					dlFileVersion.getVersion()) <= 0) {
				
				dlFileEntry.setExtension(dlFileVersion.getExtension());
				dlFileEntry.setMimeType(dlFileVersion.getMimeType());
				dlFileEntry.setTitle(dlFileVersion.getTitle());
				dlFileEntry.setDescription(dlFileVersion.getDescription());
				dlFileEntry.setExtraSettings(dlFileVersion.getExtraSettings());
				dlFileEntry.setFileEntryTypeId(
						dlFileVersion.getFileEntryTypeId());
				dlFileEntry.setVersion(dlFileVersion.getVersion());
				dlFileEntry.setVersionUserId(dlFileVersion.getUserId());
				dlFileEntry.setVersionUserName(dlFileVersion.getUserName());
				/* Ext Start */
				// Synchronize dlFileEntry modifiedDate same as dlFileVersion
				//dlFileEntry.setModifiedDate(dlFileVersion.getCreateDate());
				dlFileEntry.setModifiedDate(dlFileVersion.getModifiedDate());
				/* Ext End */
				dlFileEntry.setSize(dlFileVersion.getSize());
				
				dlFileEntryPersistence.update(dlFileEntry, false);
			}
		}
		else {
			
			// File entry
			
			if (dlFileEntry.getVersion().equals(dlFileVersion.getVersion())) {
				String newVersion = DLFileEntryConstants.VERSION_DEFAULT;
				
				List<DLFileVersion> approvedFileVersions =
						dlFileVersionPersistence.findByF_S(
								dlFileEntry.getFileEntryId(),
								WorkflowConstants.STATUS_APPROVED);
				
				if (!approvedFileVersions.isEmpty()) {
					newVersion = approvedFileVersions.get(0).getVersion();
				}
				
				dlFileEntry.setVersion(newVersion);
				
				dlFileEntryPersistence.update(dlFileEntry, false);
			}
			
			// Indexer
			
			if (dlFileVersion.getVersion().equals(
					DLFileEntryConstants.VERSION_DEFAULT)) {
				
				Indexer indexer = IndexerRegistryUtil.nullSafeGetIndexer(
						DLFileEntry.class);
				
				indexer.delete(dlFileEntry);
			}
		}
		
		// App helper
		
		dlAppHelperLocalService.updateStatus(
				userId, new LiferayFileEntry(dlFileEntry),
				new LiferayFileVersion(dlFileVersion), status, workflowContext);
		
		// Indexer
		
		if ((status == WorkflowConstants.STATUS_APPROVED) &&
				((serviceContext == null) || serviceContext.isIndexingEnabled())) {
			
			reindex(dlFileEntry);
		}
		
		return dlFileEntry;
	}
	
	@Override
	public boolean verifyFileEntryCheckOut(long fileEntryId, String lockUuid)
			throws PortalException, SystemException {
		
		if (verifyFileEntryLock(fileEntryId, lockUuid) &&
				isFileEntryCheckedOut(fileEntryId)) {
			
			return true;
		}
		else {
			return false;
		}
	}
	
	@Override
	public boolean verifyFileEntryLock(long fileEntryId, String lockUuid)
			throws PortalException, SystemException {
		
		boolean lockVerified = false;
		
		try {
			Lock lock = lockLocalService.getLock(
					DLFileEntry.class.getName(), fileEntryId);
			
			if (lock.getUuid().equals(lockUuid)) {
				lockVerified = true;
			}
		}
		catch (PortalException pe) {
			if ((pe instanceof ExpiredLockException) ||
					(pe instanceof NoSuchLockException)) {
				
				DLFileEntry dlFileEntry = dlFileEntryLocalService.getFileEntry(
						fileEntryId);
				
				lockVerified = dlFolderService.verifyInheritableLock(
						dlFileEntry.getFolderId(), lockUuid);
			}
			else {
				throw pe;
			}
		}
		
		return lockVerified;
	}
	
	protected DLFileVersion addFileVersion(
			User user, DLFileEntry dlFileEntry, Date modifiedDate,
			String extension, String mimeType, String title, String description,
			String changeLog, String extraSettings, long fileEntryTypeId,
			Map<String, Fields> fieldsMap, String version, long size,
			int status, ServiceContext serviceContext)
			throws PortalException, SystemException {
		
		long fileVersionId = counterLocalService.increment();
		
		DLFileVersion dlFileVersion = dlFileVersionPersistence.create(
				fileVersionId);
		
		String uuid = ParamUtil.getString(
				serviceContext, "fileVersionUuid", serviceContext.getUuid());
		
		dlFileVersion.setUuid(uuid);
		
		dlFileVersion.setGroupId(dlFileEntry.getGroupId());
		dlFileVersion.setCompanyId(dlFileEntry.getCompanyId());
		
		long versionUserId = dlFileEntry.getVersionUserId();
		
		if (versionUserId <= 0) {
			versionUserId = dlFileEntry.getUserId();
		}
		
		dlFileVersion.setUserId(versionUserId);
		
		String versionUserName = GetterUtil.getString(
				dlFileEntry.getVersionUserName(), dlFileEntry.getUserName());
		
		dlFileVersion.setUserName(versionUserName);
		
		dlFileVersion.setCreateDate(modifiedDate);
		dlFileVersion.setModifiedDate(modifiedDate);
		dlFileVersion.setRepositoryId(dlFileEntry.getRepositoryId());
		dlFileVersion.setFolderId(dlFileEntry.getFolderId());
		dlFileVersion.setFileEntryId(dlFileEntry.getFileEntryId());
		dlFileVersion.setExtension(extension);
		dlFileVersion.setMimeType(mimeType);
		dlFileVersion.setTitle(title);
		dlFileVersion.setDescription(description);
		dlFileVersion.setChangeLog(changeLog);
		dlFileVersion.setExtraSettings(extraSettings);
		dlFileVersion.setFileEntryTypeId(fileEntryTypeId);
		dlFileVersion.setVersion(version);
		dlFileVersion.setSize(size);
		dlFileVersion.setStatus(status);
		dlFileVersion.setStatusByUserId(user.getUserId());
		dlFileVersion.setStatusByUserName(user.getFullName());
		dlFileVersion.setStatusDate(dlFileEntry.getModifiedDate());
		dlFileVersion.setExpandoBridgeAttributes(serviceContext);
		
		dlFileVersionPersistence.update(dlFileVersion, false);
		
		if ((fileEntryTypeId > 0) && (fieldsMap != null)) {
			dlFileEntryMetadataLocalService.updateFileEntryMetadata(
					fileEntryTypeId, dlFileEntry.getFileEntryId(), fileVersionId,
					fieldsMap, serviceContext);
		}
		
		return dlFileVersion;
	}
	
	protected void convertExtraSettings(
			DLFileEntry dlFileEntry, DLFileVersion dlFileVersion, String[] keys)
			throws PortalException, SystemException {
		
		UnicodeProperties extraSettingsProperties =
				dlFileVersion.getExtraSettingsProperties();
		
		ExpandoBridge expandoBridge = dlFileVersion.getExpandoBridge();
		
		convertExtraSettings(extraSettingsProperties, expandoBridge, keys);
		
		dlFileVersion.setExtraSettingsProperties(extraSettingsProperties);
		
		dlFileVersionPersistence.update(dlFileVersion, false);
		
		int status = dlFileVersion.getStatus();
		
		if ((status == WorkflowConstants.STATUS_APPROVED) &&
				(DLUtil.compareVersions(
						dlFileEntry.getVersion(), dlFileVersion.getVersion()) <= 0)) {
			
			reindex(dlFileEntry);
		}
	}
	
	protected void convertExtraSettings(DLFileEntry dlFileEntry, String[] keys)
			throws PortalException, SystemException {
		
		UnicodeProperties extraSettingsProperties =
				dlFileEntry.getExtraSettingsProperties();
		
		ExpandoBridge expandoBridge = dlFileEntry.getExpandoBridge();
		
		convertExtraSettings(extraSettingsProperties, expandoBridge, keys);
		
		dlFileEntry.setExtraSettingsProperties(extraSettingsProperties);
		
		dlFileEntryPersistence.update(dlFileEntry, false);
		
		List<DLFileVersion> dlFileVersions =
				dlFileVersionLocalService.getFileVersions(
						dlFileEntry.getFileEntryId(), WorkflowConstants.STATUS_ANY);
		
		for (DLFileVersion dlFileVersion : dlFileVersions) {
			convertExtraSettings(dlFileEntry, dlFileVersion, keys);
		}
	}
	
	protected void convertExtraSettings(
			UnicodeProperties extraSettingsProperties, ExpandoBridge expandoBridge,
			String[] keys) {
		
		for (String key : keys) {
			String value = extraSettingsProperties.remove(key);
			
			if (Validator.isNull(value)) {
				continue;
			}
			
			int type = expandoBridge.getAttributeType(key);
			
			Serializable serializable = ExpandoColumnConstants.getSerializable(
					type, value);
			
			expandoBridge.setAttribute(key, serializable);
		}
	}
	
	protected void deleteFileEntry(DLFileEntry dlFileEntry)
			throws PortalException, SystemException {
		
		// File entry
		
		dlFileEntryPersistence.remove(dlFileEntry);
		
		// Resources
		
		resourceLocalService.deleteResource(
				dlFileEntry.getCompanyId(), DLFileEntry.class.getName(),
				ResourceConstants.SCOPE_INDIVIDUAL, dlFileEntry.getFileEntryId());
		
		// WebDAVProps
		
		webDAVPropsLocalService.deleteWebDAVProps(
				DLFileEntry.class.getName(), dlFileEntry.getFileEntryId());
		
		// File entry metadata
		
		dlFileEntryMetadataLocalService.deleteFileEntryMetadata(
				dlFileEntry.getFileEntryId());
		
		// File versions
		
		List<DLFileVersion> dlFileVersions =
				dlFileVersionPersistence.findByFileEntryId(
						dlFileEntry.getFileEntryId());
		
		for (DLFileVersion dlFileVersion : dlFileVersions) {
			dlFileVersionPersistence.remove(dlFileVersion);
			
			expandoValueLocalService.deleteValues(
					DLFileVersion.class.getName(),
					dlFileVersion.getFileVersionId());
			
			workflowInstanceLinkLocalService.deleteWorkflowInstanceLinks(
					dlFileEntry.getCompanyId(), dlFileEntry.getGroupId(),
					DLFileEntry.class.getName(), dlFileVersion.getFileVersionId());
		}
		
		// Expando
		
		expandoValueLocalService.deleteValues(
				DLFileEntry.class.getName(), dlFileEntry.getFileEntryId());
		
		// Lock
		
		lockLocalService.unlock(
				DLFileEntry.class.getName(), dlFileEntry.getFileEntryId());
		
		// File
		
		try {
			if (DocumentLibraryDataUtil.isRemoteStoreEnabled())
			{
				for (DLFileVersion dlFileVersion : dlFileVersions) {
					String filePath = dlFileVersion.getExpandoBridge().getAttributes().get(DocumentLibraryDataConstants.CUSTOM_FIELD_PATH) + StoreConstants.PATH_SEPARATOR + dlFileVersion.getExpandoBridge().getAttributes().get(DocumentLibraryDataConstants.CUSTOM_FILENAME);
					DLStoreUtil.deleteFile(
							dlFileEntry.getCompanyId(), dlFileEntry.getDataRepositoryId(),
							filePath);
				}
			}
			else {
				DLStoreUtil.deleteFile(
						dlFileEntry.getCompanyId(), dlFileEntry.getDataRepositoryId(),
						dlFileEntry.getName());
			}
		}
		catch (Exception e) {
			if (_log.isWarnEnabled()) {
				_log.warn(e, e);
			}
		}
		
		// Index
		
		Indexer indexer = IndexerRegistryUtil.nullSafeGetIndexer(
				DLFileEntry.class);
		
		indexer.delete(dlFileEntry);
	}
	
	protected Long getFileEntryTypeId(
			long[] groupIds, long folderId, long fileEntryTypeId)
			throws PortalException, SystemException {
		
		if (fileEntryTypeId == -1) {
			fileEntryTypeId =
					dlFileEntryTypeLocalService.getDefaultFileEntryTypeId(folderId);
		}
		else {
			List<DLFileEntryType> dlFileEntryTypes =
					dlFileEntryTypeLocalService.getFolderFileEntryTypes(
							groupIds, folderId, true);
			
			boolean found = false;
			
			for (DLFileEntryType dlFileEntryType : dlFileEntryTypes) {
				if (dlFileEntryType.getFileEntryTypeId() == fileEntryTypeId) {
					found = true;
					
					break;
				}
			}
			
			if (!found) {
				throw new InvalidFileEntryTypeException(
						"Invalid file entry type " + fileEntryTypeId +
								" for folder " + folderId);
			}
		}
		
		return fileEntryTypeId;
	}
	
	protected String getNextVersion(
			DLFileEntry dlFileEntry, boolean majorVersion, int workflowAction)
			throws PortalException, SystemException {
		
		String version = dlFileEntry.getVersion();
		
		try {
			DLFileVersion dlFileVersion =
					dlFileVersionLocalService.getLatestFileVersion(
							dlFileEntry.getFileEntryId(), true);
			
			version = dlFileVersion.getVersion();
		}
		catch (NoSuchFileVersionException nsfve) {
		}
		
		if (workflowAction == WorkflowConstants.ACTION_SAVE_DRAFT) {
			majorVersion = false;
		}
		
		int[] versionParts = StringUtil.split(version, StringPool.PERIOD, 0);
		
		if (majorVersion) {
			versionParts[0]++;
			versionParts[1] = 0;
		}
		else {
			versionParts[1]++;
		}
		
		return versionParts[0] + StringPool.PERIOD + versionParts[1];
	}
	
	protected boolean isKeepFileVersionLabel(
			DLFileEntry dlFileEntry, DLFileVersion lastDLFileVersion,
			DLFileVersion latestDLFileVersion, int workflowAction)
			throws PortalException, SystemException {
		
		if (workflowAction == WorkflowConstants.ACTION_SAVE_DRAFT) {
			return false;
		}
		
		if (PropsValues.DL_FILE_ENTRY_VERSION_POLICY != 1) {
			return false;
		}
		
		if ((lastDLFileVersion.getFolderId() ==
				latestDLFileVersion.getFolderId()) &&
				Validator.equals(
						lastDLFileVersion.getTitle(), latestDLFileVersion.getTitle()) &&
				Validator.equals(
						lastDLFileVersion.getDescription(),
						latestDLFileVersion.getDescription()) &&
				(lastDLFileVersion.getFileEntryTypeId() ==
						latestDLFileVersion.getFileEntryTypeId())) {
			
			// Asset
			
			AssetEntry lastAssetEntry = assetEntryLocalService.getEntry(
					DLFileEntryConstants.getClassName(),
					dlFileEntry.getFileEntryId());
			AssetEntry latestAssetEntry = assetEntryLocalService.getEntry(
					DLFileEntryConstants.getClassName(),
					latestDLFileVersion.getFileVersionId());
			
			if (!Validator.equalsSorted(
					lastAssetEntry.getCategoryIds(),
					latestAssetEntry.getCategoryIds())) {
				
				return false;
			}
			
			if (!Validator.equalsSorted(
					lastAssetEntry.getTagNames(),
					latestAssetEntry.getTagNames())) {
				
				return false;
			}
			
			List<AssetLink> lastAssetLinks =
					assetLinkLocalService.getDirectLinks(
							lastAssetEntry.getEntryId(),
							AssetLinkConstants.TYPE_RELATED);
			List<AssetLink> latestAssetLinks =
					assetLinkLocalService.getDirectLinks(
							latestAssetEntry.getEntryId(),
							AssetLinkConstants.TYPE_RELATED);
			
			if (!Validator.equalsSorted(
					StringUtil.split(
							ListUtil.toString(
									lastAssetLinks, AssetLink.ENTRY_ID2_ACCESSOR), 0L),
					StringUtil.split(
							ListUtil.toString(
									latestAssetLinks, AssetLink.ENTRY_ID2_ACCESSOR),
							0L))) {
				
				return false;
			}
			
			// Expando
			
			ExpandoBridge lastExpandoBridge =
					lastDLFileVersion.getExpandoBridge();
			ExpandoBridge latestExpandoBridge =
					latestDLFileVersion.getExpandoBridge();
			
			if (!lastExpandoBridge.equals(latestExpandoBridge)) {
				return false;
			}
			
			// File entry type
			
			List<DLFileEntryMetadata> lastFileEntryMetadatas =
					dlFileEntryMetadataLocalService.
							getFileVersionFileEntryMetadatas(
									lastDLFileVersion.getFileVersionId());
			List<DLFileEntryMetadata> latestFileEntryMetadatas =
					dlFileEntryMetadataLocalService.
							getFileVersionFileEntryMetadatas(
									latestDLFileVersion.getFileVersionId());
			
			for (DLFileEntryMetadata lastFileVersionFileEntryMetadata :
					lastFileEntryMetadatas) {
				
				Fields lastFields = StorageEngineUtil.getFields(
						lastFileVersionFileEntryMetadata.getDDMStorageId());
				
				boolean found = false;
				
				for (DLFileEntryMetadata latestFileVersionFileEntryMetadata :
						latestFileEntryMetadatas) {
					
					Fields latestFields = StorageEngineUtil.getFields(
							latestFileVersionFileEntryMetadata.getDDMStorageId());
					
					if (lastFields.equals(latestFields)) {
						found = true;
						
						break;
					}
				}
				
				if (!found) {
					return false;
				}
			}
			
			// Size
			
			long lastSize = lastDLFileVersion.getSize();
			long latestSize = latestDLFileVersion.getSize();
			
			if ((lastSize == 0) && ((latestSize == 0) || (latestSize > 0))) {
				return true;
			}
			
			if (lastSize != latestSize) {
				return false;
			}
			
			// Checksum
			
			InputStream lastInputStream = null;
			InputStream latestInputStream = null;
			
			try {
				String lastChecksum = lastDLFileVersion.getChecksum();
				String latestChecksum = null;
				
				if (Validator.isNull(lastChecksum)) {
					lastInputStream = DLStoreUtil.getFileAsStream(
							dlFileEntry.getCompanyId(),
							dlFileEntry.getDataRepositoryId(),
							dlFileEntry.getName(), lastDLFileVersion.getVersion());
					
					lastChecksum = DigesterUtil.digestBase64(lastInputStream);
					
					lastDLFileVersion.setChecksum(lastChecksum);
					
					// LPS-28886
					
					//dlFileVersionPersistence.update(dlLastFileVersion, false);
				}
				
				latestInputStream = DLStoreUtil.getFileAsStream(
						dlFileEntry.getCompanyId(),
						dlFileEntry.getDataRepositoryId(), dlFileEntry.getName(),
						latestDLFileVersion.getVersion());
				
				latestChecksum = DigesterUtil.digestBase64(latestInputStream);
				
				if (lastChecksum.equals(latestChecksum)) {
					return true;
				}
				
				latestDLFileVersion.setChecksum(latestChecksum);
				
				// LPS-28886
				
				//dlFileVersionPersistence.update(dlLatestFileVersion, false);
			}
			catch (Exception e) {
				if (_log.isWarnEnabled()) {
					_log.warn(e, e);
				}
			}
			finally {
				StreamUtil.cleanUp(lastInputStream);
				StreamUtil.cleanUp(latestInputStream);
			}
		}
		
		return false;
	}
	
	protected Lock lockFileEntry(long userId, long fileEntryId)
			throws PortalException, SystemException {
		
		return lockFileEntry(
				userId, fileEntryId, null, DLFileEntryImpl.LOCK_EXPIRATION_TIME);
	}
	
	protected Lock lockFileEntry(
			long userId, long fileEntryId, String owner, long expirationTime)
			throws PortalException, SystemException {
		
		if (hasFileEntryLock(userId, fileEntryId)) {
			return lockLocalService.getLock(
					DLFileEntry.class.getName(), fileEntryId);
		}
		
		if ((expirationTime <= 0) ||
				(expirationTime > DLFileEntryImpl.LOCK_EXPIRATION_TIME)) {
			
			expirationTime = DLFileEntryImpl.LOCK_EXPIRATION_TIME;
		}
		
		return lockLocalService.lock(
				userId, DLFileEntry.class.getName(), fileEntryId, owner, false,
				expirationTime);
	}
	
	protected DLFileEntry moveFileEntryImpl(
			long userId, long fileEntryId, long newFolderId,
			ServiceContext serviceContext)
			throws PortalException, SystemException {
		
		// File entry
		
		User user = userPersistence.findByPrimaryKey(userId);
		DLFileEntry dlFileEntry = dlFileEntryPersistence.findByPrimaryKey(
				fileEntryId);
		
		long oldDataRepositoryId = dlFileEntry.getDataRepositoryId();
		
		validateFile(
				dlFileEntry.getGroupId(), newFolderId, dlFileEntry.getFileEntryId(),
				dlFileEntry.getTitle(), dlFileEntry.getExtension());
		
		if (DLStoreUtil.hasFile(
				user.getCompanyId(),
				DLFolderConstants.getDataRepositoryId(
						dlFileEntry.getGroupId(), newFolderId),
				dlFileEntry.getName(), StringPool.BLANK)) {
			
			throw new DuplicateFileException(dlFileEntry.getName());
		}
		
		dlFileEntry.setModifiedDate(serviceContext.getModifiedDate(null));
		dlFileEntry.setFolderId(newFolderId);
		
		dlFileEntryPersistence.update(dlFileEntry, false);
		
		// File version
		
		List<DLFileVersion> dlFileVersions =
				dlFileVersionPersistence.findByFileEntryId(fileEntryId);
		
		for (DLFileVersion dlFileVersion : dlFileVersions) {
			dlFileVersion.setFolderId(newFolderId);
			
			dlFileVersionPersistence.update(dlFileVersion, false);
		}
		
		// Folder
		
		if (newFolderId != DLFolderConstants.DEFAULT_PARENT_FOLDER_ID) {
			DLFolder dlFolder = dlFolderPersistence.findByPrimaryKey(
					newFolderId);
			
			dlFolder.setModifiedDate(serviceContext.getModifiedDate(null));
			
			dlFolderPersistence.update(dlFolder, false);
		}
		
		// File
		
		DLStoreUtil.updateFile(
				user.getCompanyId(), oldDataRepositoryId,
				dlFileEntry.getDataRepositoryId(), dlFileEntry.getName());
		
		// Index
		
		if ((serviceContext == null) || serviceContext.isIndexingEnabled()) {
			reindex(dlFileEntry);
		}
		
		return dlFileEntry;
	}
	
	protected void reindex(DLFileEntry dlFileEntry) throws SearchException {
		Indexer indexer = IndexerRegistryUtil.nullSafeGetIndexer(
				DLFileEntry.class);
		
		indexer.reindex(dlFileEntry);
	}
	
	protected void removeFileVersion(
			DLFileEntry dlFileEntry, DLFileVersion dlFileVersion)
			throws PortalException, SystemException {
		
		dlFileVersionPersistence.remove(dlFileVersion);
		
		expandoValueLocalService.deleteValues(
				DLFileVersion.class.getName(), dlFileVersion.getFileVersionId());
		
		dlFileEntryMetadataLocalService.deleteFileVersionFileEntryMetadata(
				dlFileVersion.getFileVersionId());
		String filePath = null;
		if (DocumentLibraryDataUtil.isRemoteStoreEnabled())
		{
			filePath = (String)dlFileVersion.getExpandoBridge().getAttribute(DocumentLibraryDataConstants.CUSTOM_FIELD_PATH) +  StoreConstants.PATH_SEPARATOR + (String)dlFileVersion.getExpandoBridge().getAttribute(DocumentLibraryDataConstants.CUSTOM_FILENAME);
			DLStoreUtil.deleteFile(
					dlFileEntry.getCompanyId(), dlFileEntry.getDataRepositoryId(),
					filePath,
					DLFileEntryConstants.PRIVATE_WORKING_COPY_VERSION);
		}
		else {
			try {
				DLStoreUtil.deleteFile(
						dlFileEntry.getCompanyId(), dlFileEntry.getDataRepositoryId(),
						dlFileEntry.getName(),
						DLFileEntryConstants.PRIVATE_WORKING_COPY_VERSION);
			} catch (NoSuchModelException nsme) {
			}
		}
		lockLocalService.unlock(
				DLFileEntry.class.getName(), dlFileEntry.getFileEntryId());
	}
	
	protected void setFileVersion(DLFileEntry dlFileEntry)
			throws PortalException, SystemException {
		
		try {
			DLFileVersion dlFileVersion =
					dlFileVersionLocalService.getFileVersion(
							dlFileEntry.getFileEntryId(), dlFileEntry.getVersion());
			
			dlFileEntry.setFileVersion(dlFileVersion);
		}
		catch (NoSuchFileVersionException nsfve) {
		}
	}
	
	protected void startWorkflowInstance(
			long userId, ServiceContext serviceContext,
			DLFileVersion dlFileVersion, String syncEventType)
			throws PortalException, SystemException {
		
		Map<String, Serializable> workflowContext =
				new HashMap<String, Serializable>();
		
		workflowContext.put("event", syncEventType);
		
		WorkflowHandlerRegistryUtil.startWorkflowInstance(
				dlFileVersion.getCompanyId(), dlFileVersion.getGroupId(), userId,
				DLFileEntry.class.getName(), dlFileVersion.getFileVersionId(),
				dlFileVersion, serviceContext, workflowContext);
	}
	
	protected void unlockFileEntry(long fileEntryId) throws SystemException {
		lockLocalService.unlock(DLFileEntry.class.getName(), fileEntryId);
	}
	
	protected void unlockFileEntry(long fileEntryId, String lockUuid)
			throws PortalException, SystemException {
		
		if (Validator.isNotNull(lockUuid)) {
			try {
				Lock lock = lockLocalService.getLock(
						DLFileEntry.class.getName(), fileEntryId);
				
				if (!lock.getUuid().equals(lockUuid)) {
					throw new InvalidLockException("UUIDs do not match");
				}
			}
			catch (PortalException pe) {
				if ((pe instanceof ExpiredLockException) ||
						(pe instanceof NoSuchLockException)) {
				}
				else {
					throw pe;
				}
			}
		}
		
		if (!isFileEntryCheckedOut(fileEntryId)) {
			lockLocalService.unlock(DLFileEntry.class.getName(), fileEntryId);
		}
	}
	
	protected DLFileEntry updateFileEntry(
			long userId, long fileEntryId, String sourceFileName,
			String extension, String mimeType, String title, String description,
			String changeLog, boolean majorVersion, String extraSettings,
			long fileEntryTypeId, Map<String, Fields> fieldsMap, File file,
			InputStream is, long size, ServiceContext serviceContext)
			throws PortalException, SystemException {
		
		User user = userPersistence.findByPrimaryKey(userId);
		DLFileEntry dlFileEntry = dlFileEntryPersistence.findByPrimaryKey(
				fileEntryId);
		/**  MODIFIED **/
		boolean checkedOut = true;
		boolean skipCheckOutCheckIn = BooleanUtils.toBoolean((Boolean)serviceContext.getAttribute(StoreConstants.SKIP_CHECKOUT_CHECKIN));
		System.out.println("Updating File Entry:"+ fileEntryId);
		
		if (skipCheckOutCheckIn == false)
		{
			checkedOut = dlFileEntry.isCheckedOut();
		}
		/** END MODIFIED **/
		
		DLFileVersion dlFileVersion =
				dlFileVersionLocalService.getLatestFileVersion(
						fileEntryId, !checkedOut);
		
		boolean autoCheckIn = !checkedOut && dlFileVersion.isApproved();
		
		if (autoCheckIn) {
			dlFileEntry = checkOutFileEntry(
					userId, fileEntryId, serviceContext);
		}
		else if (!checkedOut) {
			lockFileEntry(userId, fileEntryId);
		}
		
		if (!hasFileEntryLock(userId, fileEntryId)) {
			lockFileEntry(userId, fileEntryId);
		}
		
		if (checkedOut || autoCheckIn) {
			dlFileVersion = dlFileVersionLocalService.getLatestFileVersion(
					fileEntryId, false);
		}
		
		try {
			if (Validator.isNull(extension)) {
				extension = dlFileEntry.getExtension();
			}
			
			if (Validator.isNull(mimeType)) {
				mimeType = dlFileEntry.getMimeType();
			}
			
			if (Validator.isNull(title)) {
				title = sourceFileName;
				
				if (Validator.isNull(title)) {
					title = dlFileEntry.getTitle();
				}
			}
			
			Date now = new Date();
			
			validateFile(
					dlFileEntry.getGroupId(), dlFileEntry.getFolderId(),
					dlFileEntry.getFileEntryId(), title, extension, sourceFileName,
					file, is);
			
			/*ADDED*/
			if (DocumentLibraryDataUtil.isRemoteStoreEnabled())
			{
				String zone;
				String path = null;
				try
				{
					zone = CustomFieldDataUtil.getGroupTimeZoneValueByGroupIdWithDefault(dlFileEntry.getGroupId());
					if (zone == null)
					{
						zone = PropsUtil.get(StoreConstants.DL_TIMEZONE);
					}
					path = DocumentLibraryDataUtil.getCdnSubDirectoryPath(dlFileEntry.getCompanyId(), dlFileEntry.getGroupId(),dlFileEntry.getMimeType()) + StoreConstants.PATH_SEPARATOR + CDNStoreUtil.getDateDirectoryNameForMedia(TimeZone.getTimeZone(zone));
				} catch (Exception e) {
					throw new SystemException(e);
				}
				if (StringUtils.isNotEmpty(sourceFileName))
				{
					serviceContext.getExpandoBridgeAttributes().put(DocumentLibraryDataConstants.CUSTOM_FIELD_PATH,path);
					serviceContext.getExpandoBridgeAttributes().put(DocumentLibraryDataConstants.CUSTOM_FILENAME,CDNStoreUtil.getRemoteFileName(sourceFileName, DLFileEntryConstants.PRIVATE_WORKING_COPY_VERSION, dlFileEntry.getName()));
				}
			}
			/*END ADDED*/
			
			// File version
			
			String version = dlFileVersion.getVersion();
			
			if (size == 0) {
				size = dlFileVersion.getSize();
			}
			/* ADDED */
			if (skipCheckOutCheckIn)
			{
				//skipping checkout explicitly
				//For EndPlay story workflow every edit media action creates new version
				//without using checkout and checkin actions.
				
				if (DocumentLibraryDataUtil.isRemoteStoreEnabled())
				{
					dlFileVersion = dlFileVersionLocalService.getLatestFileVersion(fileEntryId, false);
					String filePath = (String)dlFileVersion.getExpandoBridge().getAttribute(DocumentLibraryDataConstants.CUSTOM_FIELD_PATH);
					String fileName = (String)dlFileVersion.getExpandoBridge().getAttribute(DocumentLibraryDataConstants.CUSTOM_FILENAME);
					if (serviceContext.getAttribute(StoreConstants.IMAGE_CUSTOM_FILENAME_OVERRIDED) != null) {
						fileName = HttpUtil.encodeURL((String)serviceContext.getAttribute(StoreConstants.IMAGE_CUSTOM_FILENAME_OVERRIDED), true);
					}
					// Movideo custom filename override
					Boolean isFromMovide = serviceContext.getAttribute("isFromMovide") != null ? (Boolean)serviceContext.getAttribute("isFromMovide") : false;
					if (isFromMovide) {
						fileName = sourceFileName;
					}
					
					serviceContext.getExpandoBridgeAttributes().put(DocumentLibraryDataConstants.CUSTOM_FIELD_PATH,filePath);
					serviceContext.getExpandoBridgeAttributes().put(DocumentLibraryDataConstants.CUSTOM_FILENAME,fileName);
					
					if(serviceContext.getWorkflowAction() == WorkflowConstants.STATUS_EXPIRED) {
						dlFileVersion.setStatus(serviceContext.getWorkflowAction());
					}
					
					if(dlFileVersion.getStatus() == WorkflowConstants.STATUS_APPROVED) {
						version = getNextVersion(
								dlFileEntry, majorVersion, serviceContext.getWorkflowAction());
						dlFileVersion = addFileVersion(
								user, dlFileEntry, serviceContext.getModifiedDate(now), extension,
								dlFileVersion.getMimeType(), title,
								description,
								dlFileVersion.getChangeLog(),
								dlFileVersion.getExtraSettings(),
								dlFileVersion.getFileEntryTypeId(), fieldsMap,
								version,
								dlFileVersion.getSize(), WorkflowConstants.STATUS_DRAFT,
								serviceContext);
						System.out.println("New File version added:"+ dlFileVersion.getFileVersionId());
						dlFileEntry.setVersion(dlFileVersion.getVersion());
						//dlFileEntryPersistence.update(dlFileEntry, false);
						
					} else {
						dlFileVersion.setStatus(serviceContext.getWorkflowAction());
						dlFileVersion = updateFileVersion(
								user, dlFileVersion, sourceFileName, extension, mimeType, title,
								description, changeLog, extraSettings, fileEntryTypeId,
								fieldsMap, version, size, dlFileVersion.getStatus(),
								serviceContext.getModifiedDate(now), serviceContext);
					}
					// Synchronize dlFileEntry modifiedDate same as dlFileVersion
					dlFileEntry.setModifiedDate(dlFileVersion.getModifiedDate());
					dlFileEntryPersistence.update(dlFileEntry, false);
					
					if (skipCheckOutCheckIn)
					{
						checkedOut = false;
					}
					
				}
				else
				{
					updateFileVersion(
							user, dlFileVersion, sourceFileName, extension, mimeType, title,
							description, changeLog, extraSettings, fileEntryTypeId,
							fieldsMap, version, size, dlFileVersion.getStatus(),
							serviceContext.getModifiedDate(now), serviceContext);
				}
			}
			else
			{
				updateFileVersion(
						user, dlFileVersion, sourceFileName, extension, mimeType, title,
						description, changeLog, extraSettings, fileEntryTypeId,
						fieldsMap, version, size, dlFileVersion.getStatus(),
						serviceContext.getModifiedDate(now), serviceContext);
			}
			
			// Folder
			
			if (dlFileEntry.getFolderId() !=
					DLFolderConstants.DEFAULT_PARENT_FOLDER_ID) {
				
				DLFolder dlFolder = dlFolderPersistence.findByPrimaryKey(
						dlFileEntry.getFolderId());
				
				dlFolder.setLastPostDate(serviceContext.getModifiedDate(now));
				
				dlFolderPersistence.update(dlFolder, false);
			}
			
			// App helper
			
			dlAppHelperLocalService.updateAsset(
					userId, new LiferayFileEntry(dlFileEntry),
					new LiferayFileVersion(dlFileVersion),
					serviceContext.getAssetCategoryIds(),
					serviceContext.getAssetTagNames(),
					serviceContext.getAssetLinkEntryIds());
			
			// File
			
			if ((file != null) || (is != null)) {
				try {
					if (!DocumentLibraryDataUtil.isRemoteStoreEnabled()) {
						DLStoreUtil.deleteFile(
								user.getCompanyId(), dlFileEntry.getDataRepositoryId(),
								dlFileEntry.getName(), version);
					}
				}
				catch (NoSuchModelException nsme) {
				}
				
				if (file != null) {
					DLStoreUtil.updateFile(
							user.getCompanyId(), dlFileEntry.getDataRepositoryId(),
							dlFileEntry.getName(), dlFileEntry.getExtension(),
							false, version, sourceFileName, file);
				}
				else {
					/*ADDED*/
					if (DocumentLibraryDataUtil.isRemoteStoreEnabled())
					{
						String filePath = serviceContext.getExpandoBridgeAttributes().get(DocumentLibraryDataConstants.CUSTOM_FIELD_PATH) + StoreConstants.PATH_SEPARATOR + serviceContext.getExpandoBridgeAttributes().get(DocumentLibraryDataConstants.CUSTOM_FILENAME);
						DLStoreUtil.updateFile(
								user.getCompanyId(), dlFileEntry.getDataRepositoryId(),
								filePath, dlFileEntry.getExtension(),
								false, version, sourceFileName, is);
					}
					/*END ADDED*/
					else
					{
						DLStoreUtil.updateFile(
								user.getCompanyId(), dlFileEntry.getDataRepositoryId(),
								dlFileEntry.getName(), dlFileEntry.getExtension(),
								false, version, sourceFileName, is);
					}
				}
			}
			
			if (autoCheckIn) {
				dlFileEntryService.checkInFileEntry(
						fileEntryId, majorVersion, changeLog, serviceContext);
			}
			else if (!checkedOut &&
					(serviceContext.getWorkflowAction() ==
							WorkflowConstants.ACTION_PUBLISH)) {
				
				String syncEvent = DLSyncConstants.EVENT_UPDATE;
				
				if (dlFileVersion.getVersion().equals(
						DLFileEntryConstants.VERSION_DEFAULT)) {
					
					syncEvent = DLSyncConstants.EVENT_ADD;
				}
				
				startWorkflowInstance(
						userId, serviceContext, dlFileVersion, syncEvent);
			}
		}
		catch (PortalException pe) {
			if (autoCheckIn) {
				dlFileEntryService.cancelCheckOut(fileEntryId);
			}
			
			throw pe;
		}
		catch (SystemException se) {
			if (autoCheckIn) {
				dlFileEntryService.cancelCheckOut(fileEntryId);
			}
			
			throw se;
		}
		finally {
			if (!autoCheckIn && !checkedOut) {
				unlockFileEntry(fileEntryId);
			}
		}
		
		return dlFileEntryPersistence.findByPrimaryKey(fileEntryId);
	}
	
	protected DLFileVersion updateFileVersion(
			User user, DLFileVersion dlFileVersion, String sourceFileName,
			String extension, String mimeType, String title, String description,
			String changeLog, String extraSettings, long fileEntryTypeId,
			Map<String, Fields> fieldsMap, String version, long size,
			int status, Date statusDate, ServiceContext serviceContext)
			throws PortalException, SystemException {
		
		dlFileVersion.setModifiedDate(statusDate);
		
		if (Validator.isNotNull(sourceFileName)) {
			dlFileVersion.setExtension(extension);
			dlFileVersion.setMimeType(mimeType);
		}
		
		dlFileVersion.setTitle(title);
		dlFileVersion.setDescription(description);
		dlFileVersion.setChangeLog(changeLog);
		dlFileVersion.setExtraSettings(extraSettings);
		dlFileVersion.setFileEntryTypeId(fileEntryTypeId);
		dlFileVersion.setVersion(version);
		dlFileVersion.setSize(size);
		dlFileVersion.setStatus(status);
		dlFileVersion.setStatusByUserId(user.getUserId());
		dlFileVersion.setStatusByUserName(user.getFullName());
		dlFileVersion.setStatusDate(statusDate);
		dlFileVersion.setExpandoBridgeAttributes(serviceContext);
		
		dlFileVersion = dlFileVersionPersistence.update(dlFileVersion, false);
		
		if ((fileEntryTypeId > 0) && (fieldsMap != null)) {
			dlFileEntryMetadataLocalService.updateFileEntryMetadata(
					fileEntryTypeId, dlFileVersion.getFileEntryId(),
					dlFileVersion.getFileVersionId(), fieldsMap, serviceContext);
		}
		
		return dlFileVersion;
	}
	
	protected void validateFile(
			long groupId, long folderId, long fileEntryId, String title,
			String extension)
			throws PortalException, SystemException {
		
		DLFolder dlFolder = dlFolderPersistence.fetchByG_P_N(
				groupId, folderId, title);
		
		if (dlFolder != null) {
			throw new DuplicateFolderNameException(title);
		}
		
		DLFileEntry dlFileEntry = dlFileEntryPersistence.fetchByG_F_T(
				groupId, folderId, title);
		
		if ((dlFileEntry != null) &&
				(dlFileEntry.getFileEntryId() != fileEntryId)) {
			
			throw new DuplicateFileException(title);
		}
		
		String periodAndExtension = StringPool.PERIOD + extension;
		
		if (!title.endsWith(periodAndExtension)) {
			title += periodAndExtension;
			
			dlFileEntry = dlFileEntryPersistence.fetchByG_F_T(
					groupId, folderId, title);
			
			if ((dlFileEntry != null) &&
					(dlFileEntry.getFileEntryId() != fileEntryId)) {
				
				throw new DuplicateFileException(title);
			}
		}
	}
	
	protected void validateFile(
			long groupId, long folderId, long fileEntryId, String title,
			String extension, String sourceFileName, File file, InputStream is)
			throws PortalException, SystemException {
		
		if (Validator.isNotNull(sourceFileName)) {
			if (file != null) {
				DLStoreUtil.validate(
						sourceFileName, extension, sourceFileName, true, file);
			}
			else {
				DLStoreUtil.validate(
						sourceFileName, extension, sourceFileName, true, is);
			}
		}
		
		validateFileExtension(extension);
		validateFileName(title);
		
		DLStoreUtil.validate(title, false);
		
		validateFile(groupId, folderId, fileEntryId, title, extension);
	}
	
	protected void validateFileExtension(String extension)
			throws PortalException {
		
		if (Validator.isNotNull(extension)) {
			int maxLength = ModelHintsUtil.getMaxLength(
					DLFileEntry.class.getName(), "extension");
			
			if (extension.length() > maxLength) {
				throw new FileExtensionException();
			}
		}
	}
	
	protected void validateFileName(String fileName) throws PortalException {
		if (fileName.contains(StringPool.SLASH)) {
			throw new FileNameException(fileName);
		}
	}
	
	protected boolean isMigrateEntry(DLFileEntry dlFileEntry) {
		boolean isMigrateEntry = false;
		Map<String, String> dataMap = DocumentLibraryDataUtil.getDLDataStringMap(dlFileEntry);
		if (dataMap != null && dataMap.size() > 0) {
			String migrateFlag = dataMap.get("migrate_flag");
			if (migrateFlag != null && "true".equals(migrateFlag)) {
				isMigrateEntry = true;
			}
		}
		return isMigrateEntry;
	}
	
	private String resetMigrateEntryFilePath(String filePath) {
		int chaAt = filePath.lastIndexOf(StringPool.PERIOD);
		String tmpFileName = filePath.substring(0, chaAt);
		String tmpFileExt = filePath.substring(chaAt);
		List<String> imageSizeMap = com.endplay.portlet.documentlibrary.store.tools.ImageToolUtil.getImageSizeMap();
		// get size 640x480 
		// see portal-ext.properties
		// dl.image.tool.sizes=19x14,33x25,60x45,82x61,93x70,107x80,140x105,200x150,320x240,400x300,640x480
		String maxSize = imageSizeMap.get(imageSizeMap.size() - 1);
		String tmpSize = StringPool.UNDERLINE + maxSize.replace("x", StringPool.UNDERLINE);
		return tmpFileName + tmpSize + tmpFileExt;
	}
	
	private static final int _DELETE_INTERVAL = 100;
	
	private static Log _log = LogFactoryUtil.getLog(
			DLFileEntryLocalServiceImpl.class);
	
}