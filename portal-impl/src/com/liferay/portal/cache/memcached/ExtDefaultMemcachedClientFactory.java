package com.liferay.portal.cache.memcached;


import com.endplay.portal.util.ExternalCacheUtil;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.*;
import net.spy.memcached.*;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class ExtDefaultMemcachedClientFactory implements MemcachedClientFactory {

	private static final String _MEMCACHED_INET_SOCKET_ADDRESS = "memcached.inet.socket.address.and.port";
	private static final String _DEFAULT_MEMCACHED_HOST_NAME = "localhost";
    private static final int _DEFAULT_MEMCACHED_PORT = 11211;

    private static final Log _log = LogFactoryUtil.getLog(ExtDefaultMemcachedClientFactory.class);
    private ConnectionFactory _connectionFactory;
    private static List<InetSocketAddress> _inetSocketAddresses = new ArrayList<InetSocketAddress>();
    private static MemcachedClientIF memcachedClient;
    
    public void clear() {
    }

    public void close() {
    }

    public MemcachedClientIF getMemcachedClient() throws Exception {
        
        if (memcachedClient == null) {   	
            if (_inetSocketAddresses.size() == 0) {
            	// TEGNA-2081 Prevent multiple async mutations of this array
            	synchronized(this) {
            		if (_inetSocketAddresses.size() == 0) {
                        String addressesConfig = PropsUtil.get(_MEMCACHED_INET_SOCKET_ADDRESS);
                        if (Validator.isNotNull(addressesConfig)) {
                            String[] addressArray = StringUtil.split(addressesConfig.trim(), StringPool.COMMA);
                            fetchInetSocketAddresses(Arrays.asList(addressArray));
                        } else {
                            _log.error("Fail to get memcached.inet.socket.address.and.port!!! Try to use default host and port. Please config it in the portal properties.");
                            fetchInetSocketAddresses(Arrays.asList(_DEFAULT_MEMCACHED_HOST_NAME + StringPool.COLON + _DEFAULT_MEMCACHED_PORT));
                        }
            		}
            	}
            }
            if (_connectionFactory == null) {
            	_connectionFactory = new KetamaConnectionFactory();
            }
            memcachedClient = new MemcachedClient(_connectionFactory, _inetSocketAddresses);
    		memcachedClient.addObserver(new ConnectionObserver() {
    			@Override
    			public void connectionEstablished(SocketAddress sa, int reconnectCount) {
    				ExternalCacheUtil.setExternalCacheActive(true);
    				_log.debug("Connection Established " + sa.toString() + " reconnect count: " + reconnectCount);
    			}
    			@Override
    			public void connectionLost(SocketAddress sa) {
    				Collection<SocketAddress> servers = memcachedClient.getAvailableServers();
    				if (servers.isEmpty()) {
    					ExternalCacheUtil.setExternalCacheActive(false);
    				}
    				_log.debug("Connection Lost " + sa.toString());
    			}
    		});
    		Collection<SocketAddress> servers = memcachedClient.getAvailableServers();
    		if (servers.isEmpty()) {
    			ExternalCacheUtil.setExternalCacheActive(false);
    		} else {
    			ExternalCacheUtil.setExternalCacheActive(true);
    		}
        }
        
        return memcachedClient;
    }
    
    public int getNumActive() {
        throw new UnsupportedOperationException();
    }

    public int getNumIdle() {
        throw new UnsupportedOperationException();
    }

    public void invalidateMemcachedClient(MemcachedClientIF memcachedClient) {
        throw new UnsupportedOperationException();
    }

    public void returnMemcachedObject(MemcachedClientIF memcachedClient) {
        throw new UnsupportedOperationException();
    }

    public void setAddresses(List<String> addresses) {
        fetchInetSocketAddresses(addresses);
    }

    public void setConnectionFactory(ConnectionFactory connectionFactory) {
        _connectionFactory = connectionFactory;
    }


    private void fetchInetSocketAddresses (List<String> addresses) {
        for (String address : addresses) {
            String[] hostAndPort = StringUtil.split(address.trim(), StringPool.COLON);

            String hostName = hostAndPort[0].trim();

            int port = _DEFAULT_MEMCACHED_PORT;

            if (hostAndPort.length == 2) {
                port = GetterUtil.getInteger(hostAndPort[1].trim());
            }

            InetSocketAddress inetSocketAddress = new InetSocketAddress(
                hostName, port);

            _inetSocketAddresses.add(inetSocketAddress);
        }
    }
}
