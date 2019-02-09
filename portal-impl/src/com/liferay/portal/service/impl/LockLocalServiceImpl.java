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

package com.liferay.portal.service.impl;

import com.liferay.portal.DuplicateLockException;
import com.liferay.portal.ExpiredLockException;
import com.liferay.portal.NoSuchLockException;
import com.liferay.portal.kernel.dao.orm.LockMode;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.transaction.Propagation;
import com.liferay.portal.kernel.transaction.Transactional;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.model.Lock;
import com.liferay.portal.model.User;
import com.liferay.portal.service.base.LockLocalServiceBaseImpl;

import java.util.Date;
import java.util.List;

/**
 * @author Brian Wing Shun Chan
 * @author Shuyang Zhou
 */
public class LockLocalServiceImpl extends LockLocalServiceBaseImpl {

	public void clear() throws SystemException {
		if (_log.isDebugEnabled()) {
			_log.debug(String.format("%s | clear()", Thread.currentThread().getName()));
		}
		
		lockPersistence.removeByLtExpirationDate(new Date());
	}

	public Lock getLock(String className, long key)
		throws PortalException, SystemException {

		return getLock(className, String.valueOf(key));
	}

	public Lock getLock(String className, String key)
		throws PortalException, SystemException {

		Lock lock = lockPersistence.findByC_K(className, key);
		
		if (_log.isDebugEnabled()) {
			_log.debug(String.format("%s | getLock() className=%s key=%s, lock=%s", Thread.currentThread().getName(), className, key, lock));
		}

		if (lock.isExpired()) {
			if (_log.isDebugEnabled()) {
				_log.debug(String.format("%s | getLock() remove expired className=%s key=%s, lock=%s", Thread.currentThread().getName(), className, key, lock));
			}
			
			lockPersistence.remove(lock);

			throw new ExpiredLockException();
		}

		return lock;
	}

	public Lock getLockByUuid(String uuid)
		throws PortalException, SystemException {

		List<Lock> locks = lockPersistence.findByUuid(uuid);

		if (locks.isEmpty()) {
			if (_log.isDebugEnabled()) {
				_log.debug(String.format("%s | getLockByUuid() not found uuid=%s",	 Thread.currentThread().getName(), uuid));
			}

			throw new NoSuchLockException();
		}
		
		if (_log.isDebugEnabled()) {
			_log.debug(String.format("%s | getLockByUuid() uuid=%s, lock=%s", Thread.currentThread().getName(), uuid, locks.get(0)));
		}

		return locks.get(0);
	}

	public boolean hasLock(long userId, String className, long key)
		throws SystemException {

		return hasLock(userId, className, String.valueOf(key));
	}

	public boolean hasLock(long userId, String className, String key)
		throws SystemException {

		Lock lock = fetchLock(className, key);

		if (_log.isDebugEnabled()) {
			_log.debug(String.format("%s | hasLock() userId=%d className=%s key=%s, result=%b lock=%s", Thread.currentThread().getName(), userId, className, key, (lock != null) && (lock.getUserId() == userId), lock));
		}

		if ((lock != null) && (lock.getUserId() == userId)) {
			return true;
		}
		else {
			return false;
		}
	}

	public boolean isLocked(String className, long key)
		throws SystemException {

		return isLocked(className, String.valueOf(key));
	}

	public boolean isLocked(String className, String key)
		throws SystemException {

		Lock lock = fetchLock(className, key);

		if (lock == null) {
			return false;
		}
		else {
			return true;
		}
	}

	public Lock lock(
			long userId, String className, long key, String owner,
			boolean inheritable, long expirationTime)
		throws PortalException, SystemException {

		return lock(
			userId, className, String.valueOf(key), owner, inheritable,
			expirationTime);
	}

	public Lock lock(
			long userId, String className, String key, String owner,
			boolean inheritable, long expirationTime)
		throws PortalException, SystemException {

		Date now = new Date();

		// don't check the cache
		Lock lock = lockPersistence.fetchByC_K(className, key, false);
		
		if (_log.isDebugEnabled()) {
			_log.debug(String.format("%s | lock(6) className=%s key=%s, lock=%s", Thread.currentThread().getName(), className, key, lock));
		}

		if (lock != null) {
			if (lock.isExpired()) {
				if (_log.isDebugEnabled()) {
					_log.debug(String.format("%s | lock(6) lockId=%d expired", Thread.currentThread().getName(), lock.getLockId()));
				}
				
				lockPersistence.remove(lock);

				lock = null;
			}
			else if (lock.getUserId() != userId) {
				if (_log.isDebugEnabled()) {
					_log.debug(String.format("%s | lock(6) lockId=%d userId %d != %d", Thread.currentThread().getName(), lock.getLockId(), lock.getUserId(), userId));
				}
				
				throw new DuplicateLockException(lock);
			}
		}

		if (lock == null) {
			User user = userPersistence.findByPrimaryKey(userId);

			long lockId = counterLocalService.increment();

			if (_log.isDebugEnabled()) {
				_log.debug(String.format("%s | lock(6) create lockId=%d userId=%d", Thread.currentThread().getName(), lockId, userId));
			}
			
			lock = lockPersistence.create(lockId);

			lock.setCompanyId(user.getCompanyId());
			lock.setUserId(user.getUserId());
			lock.setUserName(user.getFullName());
			lock.setClassName(className);
			lock.setKey(key);
			lock.setOwner(owner);
			lock.setInheritable(inheritable);
		}

		lock.setCreateDate(now);

		if (expirationTime == 0) {
			lock.setExpirationDate(null);
			
			if (_log.isDebugEnabled()) {
				_log.debug(String.format("%s | lock(6) lockId=%d clear expiration", Thread.currentThread().getName(), lock.getLockId()));
			}
		}
		else {
			lock.setExpirationDate(new Date(now.getTime() + expirationTime));
			
			if (_log.isDebugEnabled()) {
				_log.debug(String.format("%s | lock(6) lockId=%d set expiration=%s", Thread.currentThread().getName(), lock.getLockId(), lock.getExpirationDate()));
			}
		}

		lockPersistence.update(lock, false);
		
		if (_log.isDebugEnabled()) {
			_log.debug(String.format("%s | lock(6) lockId=%d updated", Thread.currentThread().getName(), lock.getLockId()));
		}

		return lock;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Lock lock(
			String className, String key, String owner,
			boolean retrieveFromCache)
		throws SystemException {

		return lock(className, key, null, owner, retrieveFromCache);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Lock lock(
			String className, String key, String expectedOwner,
			String updatedOwner, boolean retrieveFromCache)
		throws SystemException {

		Lock lock = lockFinder.fetchByC_K(className, key, LockMode.UPGRADE);
		
		if (_log.isDebugEnabled()) {
			_log.debug(String.format("%s | lock(5) for className=%s key=%s expectedOwner=%s updatedOwner=%s, lock=%s", Thread.currentThread().getName(), className, key, expectedOwner, updatedOwner, lock));
		}

		if (lock == null) {
			long lockId = counterLocalService.increment();

			if (_log.isDebugEnabled()) {
				_log.debug(String.format("%s | lock(5) create lockId=%d", Thread.currentThread().getName(), lockId));
			}

			lock = lockPersistence.create(lockId);

			lock.setCreateDate(new Date());
			lock.setClassName(className);
			lock.setKey(key);
			lock.setOwner(updatedOwner);

			lockPersistence.update(lock, false);

			lock.setNew(true);
		}
		else if (Validator.equals(lock.getOwner(), expectedOwner)) {
			if (_log.isDebugEnabled()) {
				_log.debug(String.format("%s | lock(5) update lockId=%d", Thread.currentThread().getName(), lock.getLockId()));
			}
			
			lock.setCreateDate(new Date());
			lock.setClassName(className);
			lock.setKey(key);
			lock.setOwner(updatedOwner);

			lockPersistence.update(lock, false);

			lock.setNew(true);
		}
		else {
			if (_log.isDebugEnabled()) {
				_log.debug(String.format("%s | lock(5) lockId=%d owner/expectedOwner %s != %s", Thread.currentThread().getName(), lock.getLockId(), lock.getOwner(), expectedOwner));
			}
		}

		return lock;
	}

	public Lock refresh(String uuid, long expirationTime)
		throws PortalException, SystemException {

		Date now = new Date();

		List<Lock> locks = lockPersistence.findByUuid(uuid);

		if (locks.isEmpty()) {
			if (_log.isDebugEnabled()) {
				_log.debug(String.format("%s | lock(2) not found uuid=%s d", Thread.currentThread().getName(), uuid));
			}
			
			throw new NoSuchLockException();
		}

		Lock lock = locks.get(0);

		lock.setCreateDate(now);

		if (expirationTime == 0) {
			lock.setExpirationDate(null);
		}
		else {
			lock.setExpirationDate(new Date(now.getTime() + expirationTime));
		}

		if (_log.isDebugEnabled()) {
			_log.debug(String.format("%s | lock(2) lockId=%d refresh uuid=%s expirationTime=%d", Thread.currentThread().getName(), lock.getLockId(), uuid, expirationTime));
		}

		lockPersistence.update(lock, false);

		return lock;
	}

	public void unlock(String className, long key) throws SystemException {
		unlock(className, String.valueOf(key));
	}

	public void unlock(String className, String key) throws SystemException {
		try {
			if (_log.isDebugEnabled()) {
				_log.debug(String.format("%s | unlock(2) className=%s key=%s", Thread.currentThread().getName(), className, key));
			}
			
			lockPersistence.removeByC_K(className, key);
		}
		catch (NoSuchLockException nsle) {
			if (_log.isDebugEnabled()) {
				_log.debug(String.format("%s | unlock(2) className=%s key=%s failed %s", Thread.currentThread().getName(), className, key, nsle.toString()));
			}
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void unlock(
			String className, String key, String owner,
			boolean retrieveFromCache)
		throws SystemException {

		Lock lock = lockFinder.fetchByC_K(className, key, LockMode.UPGRADE);
		
		if (_log.isDebugEnabled()) {
			_log.debug(String.format("%s | unlock(4) className=%s key=%s owner=%s, lock=%s", Thread.currentThread().getName(), className, key, owner, lock));
		}

		if (lock == null) {
			return;
		}

		if (Validator.equals(lock.getOwner(), owner)) {
			if (_log.isDebugEnabled()) {
				_log.debug(String.format("%s | unlock() lockId=%d remove for owner=%s", Thread.currentThread().getName(), lock.getLockId(), owner));
			}
			
			deleteLock(lock);
		}
	}

	protected Lock fetchLock(String className, String key)
		throws SystemException {

		Lock lock = lockPersistence.fetchByC_K(className, key, false);
		
		if (_log.isDebugEnabled()) {
			_log.debug(String.format("%s | fetchLock() className=%s key=%s, lock=%s", Thread.currentThread().getName(), className, key, lock));
		}

		if (lock != null) {
			if (lock.isExpired()) {
				if (_log.isDebugEnabled()) {
					_log.debug(String.format("%s | fetchLock() lockId=%d expired, remove", Thread.currentThread().getName(), lock.getLockId()));
				}
				
				lockPersistence.remove(lock);

				lock = null;
			}
		}

		return lock;
	}

	private static Log _log = LogFactoryUtil.getLog(LockLocalServiceImpl.class);
}
