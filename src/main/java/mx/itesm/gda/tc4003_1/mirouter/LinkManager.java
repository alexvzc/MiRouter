/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package mx.itesm.gda.tc4003_1.mirouter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import mx.itesm.gda.tc4003_1.mirouter.binding.Packet;
import mx.itesm.gda.tc4003_1.mirouter.binding.Route;
import mx.itesm.gda.tc4003_1.mirouter.binding.Routes;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 *
 * @author alexv
 */
public class LinkManager implements Runnable {

    private static final Log LOGGER = LogFactory.getLog(LinkManager.class);

    private static final Pattern ADDR_PATTERN = Pattern.compile(
            "([A-Za-z0-9](?:[-.]?[A-Za-z0-9])*):([0-9]{1,5})");

    private int listeningPort;

    private ServerSocketChannel listeningChannel;

    private Set<ConnectionManager> clientConnections;

    private Map<Integer, ConnectionManager> managedConnections;

    private Map<Integer, ConnectionManager> perceivedLinkConnections;

    private Lock connectionListLock;

    private Thread managerThread;

    private boolean keepRunning;

    private RouteManager routeManager;

    public LinkManager(Routes routeFile) {
        listeningPort = routeFile.getNodePort();

        routeManager = new RouteManager(routeFile, this);
        managedConnections = new HashMap<Integer, ConnectionManager>();
        perceivedLinkConnections = new HashMap<Integer, ConnectionManager>();
        clientConnections = new HashSet<ConnectionManager>();

        for(Route entry : routeFile.getRoute()) {
            String dest_addr = entry.getNodeAddress();
            if(dest_addr == null) {
                continue;
            }

            Matcher m = ADDR_PATTERN.matcher(dest_addr);
            if(!m.matches()) {
                continue;
            }

            SocketAddress sock_addr = new InetSocketAddress(
                    m.group(1), Integer.parseInt(m.group(2)));

            managedConnections.put(entry.getLink(),
                    new ConnectionManager(sock_addr, this));
        }

        keepRunning = true;
        connectionListLock = new ReentrantLock();

        managerThread = new Thread(this);
        managerThread.start();
    }

    @Override
    public void run() {
        Selector my_selector;

        try {
            my_selector = Selector.open();
            listeningChannel = ServerSocketChannel.open();
            listeningChannel.configureBlocking(false);
            listeningChannel.socket().bind(new InetSocketAddress(listeningPort));
            listeningChannel.register(my_selector, SelectionKey.OP_ACCEPT);
            LOGGER.info("Listening on port " + listeningPort);

        } catch(IOException ioe) {
            LOGGER.error("Unable to start listening to port " + listeningPort,
                    ioe);
            throw new RuntimeException(ioe);
        }

        connectionListLock.lock();
        try {
            for(ConnectionManager conn : managedConnections.values()) {
                conn.setSelector(my_selector);
                conn.reconnect();
            }
        } finally {
            connectionListLock.unlock();
        }

        try {
            while(keepRunning) {
                int num_keys = my_selector.select(1000);

                if(num_keys > 0) {
                    Set<SelectionKey> keys = my_selector.selectedKeys();
                    for(SelectionKey key : keys) {
                        if(listeningChannel == key.channel()) {
                            if(key.isAcceptable()) {
                                connectionListLock.lock();
                                try {
                                    clientConnections.add(
                                            new ConnectionManager(
                                            listeningChannel.accept(),
                                            my_selector,
                                            this));
                                } finally {
                                    connectionListLock.unlock();
                                }
                            }
                        } else {
                            ConnectionManager c_m =
                                    (ConnectionManager)key.attachment();

                            c_m.selected(key);
                            if(!c_m.isConnected()) {
                                connectionListLock.lock();
                                try {
                                    clientConnections.remove(c_m);
                                } finally {
                                    connectionListLock.unlock();
                                }
                            }

                        }
                    }

                    keys.clear();
                }

                for(ConnectionManager c_m : managedConnections.values()) {
                    if(!c_m.isConnected()) {
                        c_m.reconnect();
                    }
                }

            }

        } catch(IOException ioe) {
            LOGGER.error("Unhandled IOE", ioe);
        }
    }

    public void processData(CharSequence cs, ConnectionManager cm) {
        LOGGER.info("Received remote data: " + cs);
        try {
            RouteXMLUtil xmlUtil = new RouteXMLUtil();
            JAXBElement<?> elem = xmlUtil.parseDataStr(cs);

            if(elem.getDeclaredType() == Routes.class) {
                Routes routes = (Routes)elem.getValue();
                int link = routeManager.processRemoteTable(routes);
                perceivedLinkConnections.put(link, cm);

            } else if(elem.getDeclaredType() == Packet.class) {
                Packet packet = (Packet)elem.getValue();
                try {
                    int dest_link = routeManager.routePacket(
                            packet.getDestination()).getLink();

                    if(dest_link == 0) {
                        LOGGER.info("Successully received packet: " + cs);
                    } else {
                        packet.getVisited().add(routeManager.getNodeName());
                        ConnectionManager dest_cm =
                                perceivedLinkConnections.get(dest_link);
                        dest_cm.send(xmlUtil.generatePacket(packet));
                    }
                    
                } catch(Exception e) {
                    LOGGER.error("Unable to deliver packet", e);
                }
            }

        } catch(JAXBException jaxbe) {
            LOGGER.error("Unable to parse data");
        }

    }

    public void broadcast(CharSequence cs) {
        Set<ConnectionManager> all_connections =
                new HashSet<ConnectionManager>();

        connectionListLock.lock();
        try {
            all_connections.addAll(managedConnections.values());
            all_connections.addAll(clientConnections);
        } finally {
            connectionListLock.unlock();
        }

        for(ConnectionManager c_m : all_connections) {
            if(c_m.isConnected()) {
                try {
                    c_m.send(cs);
                } catch(IOException ioe) {
                    LOGGER.error("Error sending msg to "
                            + c_m.getSocketAddress(), ioe);
                }
            }
        }


    }

    /**
     * @return the routeManager
     */
    public RouteManager getRouteManager() {
        return routeManager;
    }

}
