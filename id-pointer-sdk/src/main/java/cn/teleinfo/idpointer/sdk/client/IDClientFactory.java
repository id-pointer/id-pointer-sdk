package cn.teleinfo.idpointer.sdk.client;

import cn.teleinfo.idpointer.sdk.config.IDClientConfig;
import cn.teleinfo.idpointer.sdk.core.*;
import cn.teleinfo.idpointer.sdk.exception.IDException;
import cn.teleinfo.idpointer.sdk.transport.ChannelPoolMapManager;
import cn.teleinfo.idpointer.sdk.util.SiteUtils;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.security.PrivateKey;

/**
 * IDClientFactory
 */
@RequiredArgsConstructor
public class IDClientFactory {

    private final ChannelPoolMapManager channelPoolMapManager;
    private final IDClientConfig idClientConfig;

    private IDResolver idResolver;

    public IDClientConfig getIdClientConfig() {
        return idClientConfig;
    }

    public IDResolver getIdResolver() {
        if (idResolver == null) {
            synchronized (this) {
                if (idResolver == null) {
                    idResolver = new DefaultIdResolver(this);
                }
            }
        }
        return idResolver;
    }

    public IDClient newInstance(InetSocketAddress serverAddress, String adminUserId, int adminUserIndex, PrivateKey privateKey) throws HandleException, UnsupportedEncodingException, IDException {
        AuthenticationInfo authenticationInfo = new PublicKeyAuthenticationInfo(Util.encodeString(adminUserId), adminUserIndex, privateKey);
        return newInstance(serverAddress, authenticationInfo);
    }

    public IDClient newInstance(InetSocketAddress serverAddress, AuthenticationInfo authenticationInfo) throws HandleException, UnsupportedEncodingException, IDException {
        DefaultIdClient defaultIdClient = new DefaultIdClient(serverAddress, channelPoolMapManager);
        defaultIdClient.login(authenticationInfo);
        return defaultIdClient;
    }

    public IDClient newInstance(InetSocketAddress serverAddress) {
        return new DefaultIdClient(serverAddress, channelPoolMapManager);
    }

    public IDClient newInstance(String prefix) throws IDException {
        InetSocketAddress serverAddress = GlobalIdClientFactory.getPrefixTcpInetSocketAddress(prefix);
        return new DefaultIdClient(serverAddress, channelPoolMapManager);
    }

    public IDClient newInstance(String prefix, AuthenticationInfo authenticationInfo) throws IDException, HandleException, UnsupportedEncodingException {
        InetSocketAddress serverAddress = GlobalIdClientFactory.getPrefixTcpInetSocketAddress(prefix);
        IDClient idClient = new DefaultIdClient(serverAddress, channelPoolMapManager);
        idClient.login(authenticationInfo);
        return idClient;
    }

    public IDClient newInstance(String prefix, String adminUserId, int adminUserIndex, PrivateKey privateKey) throws IDException, HandleException, UnsupportedEncodingException {
        InetSocketAddress serverAddress = GlobalIdClientFactory.getPrefixTcpInetSocketAddress(prefix);
        IDClient idClient = new DefaultIdClient(serverAddress, channelPoolMapManager);
        AuthenticationInfo authenticationInfo = new PublicKeyAuthenticationInfo(Util.encodeString(adminUserId), adminUserIndex, privateKey);
        idClient.login(authenticationInfo);
        return idClient;
    }


}