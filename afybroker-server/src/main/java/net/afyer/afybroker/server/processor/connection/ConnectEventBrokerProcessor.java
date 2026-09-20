package net.afyer.afybroker.server.processor.connection;

import com.alipay.remoting.Connection;
import com.alipay.remoting.ConnectionEventProcessor;
import com.alipay.remoting.ConnectionEventType;
import com.alipay.remoting.InvokeCallback;
import com.alipay.remoting.exception.RemotingException;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.afyer.afybroker.core.BrokerClientType;
import net.afyer.afybroker.core.BrokerGlobalConfig;
import net.afyer.afybroker.core.BrokerServiceDescriptor;
import net.afyer.afybroker.core.MetadataKeys;
import net.afyer.afybroker.core.message.BrokerClientInfoMessage;
import net.afyer.afybroker.core.message.PlayerSessionInfo;
import net.afyer.afybroker.core.message.RequestBrokerClientInfoMessage;
import net.afyer.afybroker.core.message.RequestPlayerInfoMessage;
import net.afyer.afybroker.core.message.SyncServerMessage;
import net.afyer.afybroker.core.util.AbstractInvokeCallback;
import net.afyer.afybroker.server.BrokerServer;
import net.afyer.afybroker.server.aware.BrokerServerAware;
import net.afyer.afybroker.server.event.ClientConnectEvent;
import net.afyer.afybroker.server.event.ClientRegisterEvent;
import net.afyer.afybroker.server.processor.PlayerProxyConnectBrokerProcessor;
import net.afyer.afybroker.server.processor.PlayerProxyDisconnectBrokerProcessor;
import net.afyer.afybroker.server.processor.PlayerServerJoinBrokerProcessor;
import net.afyer.afybroker.server.event.PlayerServerJoinEvent;
import net.afyer.afybroker.server.proxy.BrokerClientItem;
import net.afyer.afybroker.server.proxy.BrokerClientManager;
import net.afyer.afybroker.server.proxy.BrokerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * @author Nipuru
 * @since 2022/7/30 11:42
 */
public class ConnectEventBrokerProcessor implements ConnectionEventProcessor, BrokerServerAware {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectEventBrokerProcessor.class);

    private BrokerServer brokerServer;
    final RequestBrokerClientInfoMessage requestBrokerClientInfoMessage = new RequestBrokerClientInfoMessage();
    final RequestPlayerInfoMessage requestPlayerInfoMessage = new RequestPlayerInfoMessage();
    final Map<PlayerSessionInfo, BrokerClientItem> playerBukkitMap = new HashMap<>();
    final Executor connectionThread = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder()
            .setNameFormat("Broker-connection-thread").build());

    @Override
    public void setBrokerServer(BrokerServer brokerServer) {
        this.brokerServer = brokerServer;
    }

    @Override
    public void onEvent(String remoteAddress, Connection connection) {
        brokerServer.getClientManager().beginConnection(remoteAddress, connection);
        brokerServer.getObservability().onConnection(ConnectionEventType.CONNECT);
        LOGGER.info("BrokerClient[{}] connected, sending request client info message", remoteAddress);

        ClientConnectEvent event = new ClientConnectEvent(remoteAddress, connection);
        brokerServer.getPluginManager().callEvent(event);

        InvokeCallback callback = registerBrokerClientCallback(remoteAddress, connection);
        int timeoutMillis = BrokerGlobalConfig.DEFAULT_TIMEOUT_MILLIS;
        try {
            brokerServer.getRpcServer().invokeWithCallback(connection, requestBrokerClientInfoMessage, callback, timeoutMillis);
        } catch (RemotingException e) {
            LOGGER.info("Request client info to BrokerClient:{} failed", remoteAddress);
            LOGGER.error(e.getMessage(), e);
        }
    }

    private InvokeCallback registerBrokerClientCallback(String remoteAddress, Connection connection) {
        return new AbstractInvokeCallback() {
            @Override
            public void onResponse(Object result) {
                BrokerClientManager clientManager = brokerServer.getClientManager();
                if (!clientManager.isConnecting(remoteAddress, connection)) {
                    return;
                }
                BrokerClientInfoMessage clientInfoMessage = cast(result);
                clientInfoMessage.setAddress(remoteAddress);

                BrokerClientItem client = new BrokerClientItem(clientInfoMessage,
                        brokerServer.getRpcServer(), brokerServer.getInterceptors(), connection);
                BrokerClientManager.RegistrationResult registration = clientManager.registerReplacingLogicalClient(client);
                if (!registration.isRegistered()) {
                    return;
                }
                for (BrokerClientItem replaced : registration.getReplacedClients()) {
                    CloseEventBrokerProcessor.cleanupClient(brokerServer, replaced.getAddress(), replaced);
                }

                ClientRegisterEvent event = new ClientRegisterEvent(clientInfoMessage, client);
                brokerServer.getPluginManager().callEvent(event);

                syncServer(client);
                registerPlayer(client);
                registerServices(client);

                LOGGER.info("BrokerClient:{} registration successful", remoteAddress);
            }

            @Override
            public void onException(Throwable e) {
                LOGGER.error("BrokerClient:{} registration failed", remoteAddress);
                LOGGER.error(e.getMessage(), e);
            }

            @Override
            public Executor getExecutor() {
                return connectionThread;
            }
        };
    }

    private void registerPlayer(BrokerClientItem client) {
        InvokeCallback callback = null;
        if (Objects.equals(client.getType(), BrokerClientType.PROXY)) {
            callback = registerPlayerBungeeCallback(client);
        } else if (Objects.equals(client.getType(), BrokerClientType.SERVER)) {
            callback = registerPlayerBukkitCallback(client);
        }
        if (callback == null) {
            return;
        }
        try {
            client.invokeWithCallback(requestPlayerInfoMessage, callback);
        } catch (RemotingException | InterruptedException e) {
            LOGGER.error("Request player server info to brokerClient:{} failed", client.getName());
            LOGGER.error(e.getMessage(), e);
        }
    }

    InvokeCallback registerPlayerBungeeCallback(BrokerClientItem bungeeClient) {
        return new AbstractInvokeCallback() {
            @Override
            public void onResponse(Object result) {
                List<PlayerSessionInfo> playerList = cast(result);
                Map<UUID, UUID> observedSessions = new HashMap<>();
                for (PlayerSessionInfo playerInfo : playerList) {
                    if (isSessionInfo(playerInfo)) {
                        observedSessions.put(playerInfo.getUniqueId(), playerInfo.getSessionId());
                    }
                }
                List<BrokerPlayer> removedPlayers = new ArrayList<>();
                List<BrokerPlayer> loginPlayers = new ArrayList<>();
                List<PlayerServerJoinEvent> joinEvents = new ArrayList<>();
                boolean current = brokerServer.getClientManager().runIfCurrent(bungeeClient, () -> {
                    for (BrokerPlayer existing : new ArrayList<>(brokerServer.getPlayerManager().getPlayers())) {
                        BrokerClientItem previousProxy = existing.getProxy();
                        if (previousProxy == bungeeClient
                                || !Objects.equals(previousProxy.getType(), BrokerClientType.PROXY)
                                || previousProxy.getName() == null
                                || !previousProxy.getName().equalsIgnoreCase(bungeeClient.getName())
                                || brokerServer.getClientManager().isCurrent(previousProxy)
                                || existing.getSessionId().equals(observedSessions.get(existing.getUniqueId()))) {
                            continue;
                        }
                        if (brokerServer.getPlayerManager().removePlayer(existing)) {
                            removedPlayers.add(existing);
                            playerBukkitMap.keySet().removeIf(previous ->
                                    previous.getUniqueId().equals(existing.getUniqueId())
                                            && previous.getSessionId().equals(existing.getSessionId()));
                        }
                    }

                    for (PlayerSessionInfo playerInfo : playerList) {
                        if (!isSessionInfo(playerInfo)) {
                            continue;
                        }
                        BrokerPlayer brokerPlayer = new BrokerPlayer(playerInfo.getUniqueId(), playerInfo.getName(),
                                playerInfo.getSessionId(), bungeeClient);
                        PlayerProxyConnectBrokerProcessor.SnapshotRegistration registration =
                                PlayerProxyConnectBrokerProcessor.registerSnapshotPlayer(brokerServer, brokerPlayer);
                        if (registration == null) {
                            continue;
                        }
                        playerBukkitMap.keySet().removeIf(previous ->
                                previous.getUniqueId().equals(playerInfo.getUniqueId()) && !previous.equals(playerInfo));
                        BrokerClientItem bukkitClient = playerBukkitMap.remove(playerInfo);
                        if (bukkitClient != null) {
                            PlayerServerJoinEvent joinEvent = PlayerServerJoinBrokerProcessor.updateBukkitJoin(
                                    brokerServer, registration.getPlayer(), bukkitClient);
                            if (joinEvent != null) {
                                joinEvents.add(joinEvent);
                            }
                        }
                        if (registration.isNewLogin()) {
                            loginPlayers.add(registration.getPlayer());
                        }
                    }
                });
                if (!current) {
                    return;
                }
                for (BrokerPlayer removed : removedPlayers) {
                    PlayerProxyDisconnectBrokerProcessor.publishPlayerLogout(brokerServer, removed);
                }
                for (BrokerPlayer player : loginPlayers) {
                    PlayerProxyConnectBrokerProcessor.publishSnapshotLogin(brokerServer, player);
                }
                for (PlayerServerJoinEvent event : joinEvents) {
                    brokerServer.getPluginManager().callEvent(event);
                }
            }

            @Override
            public void onException(Throwable e) {
                LOGGER.error("Request player info to bungee brokerClient:{} failed", bungeeClient.getName());
                LOGGER.error(e.getMessage(), e);
            }

            @Override
            public Executor getExecutor() {
                return connectionThread;
            }
        };
    }

    private static boolean isSessionInfo(PlayerSessionInfo playerInfo) {
        return playerInfo != null && playerInfo.getUniqueId() != null && playerInfo.getSessionId() != null;
    }

    InvokeCallback registerPlayerBukkitCallback(BrokerClientItem bukkitClient) {
        return new AbstractInvokeCallback() {
            @Override
            public void onResponse(Object result) {
                List<PlayerSessionInfo> playerList = cast(result);
                Set<PlayerSessionInfo> observed = new HashSet<>();
                List<PlayerServerJoinEvent> joinEvents = new ArrayList<>();
                playerList.forEach(playerInfo -> {
                    if (playerInfo == null || playerInfo.getUniqueId() == null || playerInfo.getSessionId() == null) {
                        return;
                    }
                    brokerServer.getClientManager().runIfCurrent(bukkitClient, () -> {
                        observed.add(playerInfo);
                        BrokerPlayer brokerPlayer = brokerServer.getPlayer(playerInfo.getUniqueId());
                        if (brokerPlayer == null) {
                            playerBukkitMap.put(playerInfo, bukkitClient);
                            return;
                        }
                        if (!brokerPlayer.getSessionId().equals(playerInfo.getSessionId())) {
                            return;
                        }
                        playerBukkitMap.keySet().removeIf(previous ->
                                previous.getUniqueId().equals(playerInfo.getUniqueId()) && !previous.equals(playerInfo));
                        PlayerServerJoinEvent event = PlayerServerJoinBrokerProcessor.updateBukkitJoin(
                                brokerServer, brokerPlayer, bukkitClient);
                        if (event != null) {
                            joinEvents.add(event);
                        }
                    });
                });
                brokerServer.getClientManager().runIfCurrent(bukkitClient, () ->
                        playerBukkitMap.entrySet().removeIf(entry ->
                                entry.getValue() == bukkitClient && !observed.contains(entry.getKey())));
                for (PlayerServerJoinEvent event : joinEvents) {
                    brokerServer.getPluginManager().callEvent(event);
                }
            }

            @Override
            public void onException(Throwable e) {
                LOGGER.error("Request player info to bukkit brokerClient:{} failed", bukkitClient.getName());
                LOGGER.error(e.getMessage(), e);
            }

            @Override
            public Executor getExecutor() {
                return connectionThread;
            }
        };
    }

    private void syncServer(BrokerClientItem client) {
        // 如果是 mc 服务器则同步至所有 proxy 服务器
        if (client.getType().equals(BrokerClientType.SERVER)) {
            Map<String, String> servers = new HashMap<>();
            servers.put(client.getName(), client.getMetadata(MetadataKeys.MC_SERVER_ADDRESS));
            SyncServerMessage message = new SyncServerMessage().setServers(servers);
            List<BrokerClientItem> proxyType = brokerServer.getClientManager().getByType(BrokerClientType.PROXY);
            for (BrokerClientItem proxy : proxyType) {
                try {
                    proxy.oneway(message);
                } catch (RemotingException | InterruptedException e) {
                    LOGGER.error(e.getMessage(), e);
                }
            }
        }
        // 如果是 proxy 服务器则发送当前连接的 mc 服务器
        if (client.getType().equals(BrokerClientType.PROXY)) {
            List<BrokerClientItem> serverType = brokerServer.getClientManager().getByType(BrokerClientType.SERVER);
            if (serverType.isEmpty()) {
                return;
            }
            Map<String, String> servers = new HashMap<>();
            for (BrokerClientItem server : serverType) {
                servers.put(server.getName(), server.getMetadata(MetadataKeys.MC_SERVER_ADDRESS));
            }
            SyncServerMessage message = new SyncServerMessage().setServers(servers);
            try {
                client.oneway(message);
            } catch (RemotingException | InterruptedException e) {
                LOGGER.error(e.getMessage(), e);
            }
        }
    }

    private void registerServices(BrokerClientItem client) {
        List<BrokerServiceDescriptor> services = client.getClientInfo().getServices();
        brokerServer.getServiceRegistry().registerClientServices(client, services);
        LOGGER.info("Registered {} services for client: {}", services.size(), client.getName());
    }
}
