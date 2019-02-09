package com.liferay.portal.cache.cluster;

import com.endplay.portal.kernel.util.FinderCacheClearUtil;
import com.liferay.portal.kernel.cache.cluster.PortalCacheClusterEvent;
import com.liferay.portal.kernel.cache.cluster.PortalCacheClusterEventType;
import com.liferay.portal.kernel.cache.cluster.PortalCacheClusterLinkUtil;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.StringPool;
import net.sf.ehcache.CacheException;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import net.sf.ehcache.distribution.CacheReplicator;

import java.util.Properties;

/**
 * Created by Jeff.Ma on 5/15/2017.
 */
public class EhcachePortalCacheClusterReplicator implements CacheReplicator {

    public EhcachePortalCacheClusterReplicator(Properties properties) {
        if (properties != null) {
            _replicatePuts = GetterUtil.getBoolean(
                    properties.getProperty(_REPLICATE_PUTS));
            _replicatePutsViaCopy = GetterUtil.getBoolean(
                    properties.getProperty(_REPLICATE_PUTS_VIA_COPY));
            _replicateRemovals = GetterUtil.getBoolean(
                    properties.getProperty(_REPLICATE_REMOVALS), true);
            _replicateUpdates = GetterUtil.getBoolean(
                    properties.getProperty(_REPLICATE_UPDATES), true);
            _replicateUpdatesViaCopy = GetterUtil.getBoolean(
                    properties.getProperty(_REPLICATE_UPDATES_VIA_COPY));
        }
    }

    public boolean alive() {
        return true;
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        return super.clone();
    }

    public void dispose() {
    }

    public boolean isReplicateUpdatesViaCopy() {
        return false;
    }

    public boolean notAlive() {
        return false;
    }

    public void notifyElementEvicted(Ehcache ehcache, Element element) {
    }

    public void notifyElementExpired(Ehcache ehcache, Element element) {
    }

    public void notifyElementPut(Ehcache ehcache, Element element)
            throws CacheException {

        if (!_replicatePuts) {
            return;
        }

        PortalCacheClusterEvent portalCacheClusterEvent =
                new PortalCacheClusterEvent(
                        ehcache.getName(), element.getKey(),
                        PortalCacheClusterEventType.PUT);

        if (_replicatePutsViaCopy) {
            portalCacheClusterEvent.setElementValue(element.getValue());
        }

        PortalCacheClusterLinkUtil.sendEvent(portalCacheClusterEvent);
    }

    public void notifyElementRemoved(Ehcache ehcache, Element element)
            throws CacheException {

        if (!_replicateRemovals) {
            return;
        }

        PortalCacheClusterEvent portalCacheClusterEvent =
                new PortalCacheClusterEvent(
                        ehcache.getName(), element.getKey(),
                        PortalCacheClusterEventType.REMOVE);

        PortalCacheClusterLinkUtil.sendEvent(portalCacheClusterEvent);
    }

    public void notifyElementUpdated(Ehcache ehcache, Element element)
            throws CacheException {

        if (!_replicateUpdates) {
            return;
        }

        PortalCacheClusterEvent portalCacheClusterEvent =
                new PortalCacheClusterEvent(
                        ehcache.getName(), element.getKey(),
                        PortalCacheClusterEventType.UPDATE);

        if (_replicateUpdatesViaCopy) {
            portalCacheClusterEvent.setElementValue(element.getValue());
        }

        PortalCacheClusterLinkUtil.sendEvent(portalCacheClusterEvent);
    }

    public void notifyRemoveAll(Ehcache ehcache) {
        if (!_replicateRemovals) {
            return;
        }
        if (FinderCacheClearUtil.isIgnoreCacheClear()) {
            return;
        }

        PortalCacheClusterEvent portalCacheClusterEvent =
                new PortalCacheClusterEvent(
                        ehcache.getName(), StringPool.BLANK,
                        PortalCacheClusterEventType.REMOVE_ALL);

        PortalCacheClusterLinkUtil.sendEvent(portalCacheClusterEvent);

        if(_replication_log.isDebugEnabled()) {
            _replication_log.debug(String.format("Cache RemoveAll event sent for [%s]", ehcache.getName()));
        }
    }

    private static final String _REPLICATE_PUTS = "replicatePuts";

    private static final String _REPLICATE_PUTS_VIA_COPY =
            "replicatePutsViaCopy";

    private static final String _REPLICATE_REMOVALS = "replicateRemovals";

    private static final String _REPLICATE_UPDATES = "replicateUpdates";

    private static final String _REPLICATE_UPDATES_VIA_COPY =
            "replicateUpdatesViaCopy";

    private boolean _replicatePuts;
    private boolean _replicatePutsViaCopy;
    private boolean _replicateRemovals = true;
    private boolean _replicateUpdates = true;
    private boolean _replicateUpdatesViaCopy;

    private static Log _replication_log = LogFactoryUtil.getLog("ep.replication.log");

    private static Log _log = LogFactoryUtil.getLog(EhcachePortalCacheClusterReplicator.class);

}
