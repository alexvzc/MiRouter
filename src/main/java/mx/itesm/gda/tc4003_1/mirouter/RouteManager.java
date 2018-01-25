/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package mx.itesm.gda.tc4003_1.mirouter;

import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.xml.bind.JAXBException;
import mx.itesm.gda.tc4003_1.mirouter.binding.Route;
import mx.itesm.gda.tc4003_1.mirouter.binding.Routes;
import org.slf4j.Logger;
import static org.slf4j.LoggerFactory.getLogger;

/**
 *
 * @author alexv
 */
public class RouteManager implements Runnable {

    private static final Logger LOGGER = getLogger(RouteManager.class);

    private final String nodeName;

    private SortedMap<Integer, Route> linkMap;

    private SortedMap<String, Route> routeMap;

    private Lock routeLock;

    private Condition routesChanged;

    private LinkManager linkManager;

    private Thread broadcastThread;

    private boolean keepRunning;

    public static Route copyRoute(Route route) {
        Route new_route = new Route();
        new_route.setDestination(route.getDestination());
        new_route.setLink(route.getLink());
        new_route.setCost(route.getCost());
        return new_route;
    }

    public static void displayRoutes(Routes routes, Writer out) {
        PrintWriter my_out = out instanceof PrintWriter
                ? (PrintWriter)out : new PrintWriter(out);

        my_out.printf("Node %s\n", routes.getNodeName());
        displayRoutes(routes.getRoute(), out);

    }

    public static void displayRoutes(Collection<Route> routes, Writer out) {
        PrintWriter my_out = out instanceof PrintWriter
                ? (PrintWriter)out : new PrintWriter(out);

        my_out.println("Destination\tLink\tCost");
        my_out.println("-----------\t----\t----");
        for(Route entry : routes) {
            my_out.printf("%11s\t%4d\t%4d\n", entry.getDestination(), entry.
                    getLink(), entry.getCost());
        }

    }

    public void displayRoutes(Writer out) {
        PrintWriter my_out = out instanceof PrintWriter
                ? (PrintWriter)out : new PrintWriter(out);

        my_out.printf("Node %s\n", getNodeName());
        displayRoutes(routeMap.values(), out);
    }

    public RouteManager(Routes local_routes, LinkManager link_mgr) {
        nodeName = local_routes.getNodeName();
        linkManager = link_mgr;

        linkMap = new TreeMap<Integer, Route>();
        routeMap = new TreeMap<String, Route>();

        for(Route entry : local_routes.getRoute()) {
            Route new_entry = copyRoute(entry);
            new_entry.setCost(1);

            linkMap.put(new_entry.getLink(), new_entry);

            new_entry = copyRoute(new_entry);
            routeMap.put(new_entry.getDestination(), new_entry);
        }

        Route self = new Route();
        self.setDestination(nodeName);
        self.setCost(0);
        self.setLink(0);
        linkMap.put(self.getLink(), self);

        self = copyRoute(self);
        routeMap.put(self.getDestination(), self);

        keepRunning = true;
        routeLock = new ReentrantLock();
        routesChanged = routeLock.newCondition();

        broadcastThread = new Thread(this);
        broadcastThread.setDaemon(true);
        broadcastThread.start();
    }

    @Override
    public void run() {
        LOGGER.info("Broadcast thread starting");
        RouteXMLUtil xmlUtil;
        try {
            xmlUtil = new RouteXMLUtil();
        } catch(JAXBException jaxbe) {
            LOGGER.error("Unable to create JAXB context", jaxbe);
            throw new RuntimeException(jaxbe);
        }

        Condition waitCondition = routeLock.newCondition();

        while(keepRunning) {
            Routes routes;

            routeLock.lock();
            try {
                try {
                    waitCondition.await(10, TimeUnit.SECONDS);
                } catch(InterruptedException ie) {
                    Thread.interrupted();
                }

                LOGGER.info("Generating and broadcasting table");
                routes = new Routes();
                routes.setNodeName(nodeName);
                routes.getRoute().addAll(getRouteMap());
            } finally {
                routeLock.unlock();
            }

            try {
                CharSequence data = xmlUtil.generateRouteStr(routes);
                linkManager.broadcast(data);
            } catch(JAXBException jaxbe) {
                LOGGER.error("Unable to create JAXB context", jaxbe);
            }

        }
    }

    public void processNewEntries(List<Route> new_entries, int link) {
        routeLock.lock();
        try {
            boolean changed = false;

            for(Route entry : new_entries) {
                String remote_dest = entry.getDestination();
                int remote_cost = entry.getCost() + 1;

                if(routeMap.containsKey(remote_dest)
                        && remote_cost >= routeMap.get(remote_dest).getCost()
                        && routeMap.get(remote_dest).getLink() != link) {
                    continue;
                }

                Route new_entry = new Route();
                new_entry.setDestination(remote_dest);
                new_entry.setCost(remote_cost);
                new_entry.setLink(link);

                routeMap.put(new_entry.getDestination(), new_entry);
                changed = true;
            }

            if(changed) {
                routesChanged.signalAll();
            }

        } finally {
            routeLock.unlock();
        }
    }

    public List<Route> getRouteMap() {
        routeLock.lock();
        try {
            List<Route> list = new ArrayList<Route>();

            for(Route entry : routeMap.values()) {
                list.add(copyRoute(entry));
            }

            return list;

        } finally {
            routeLock.unlock();
        }
    }

    public List<Route> getChangedRouteMap(int time, TimeUnit unit)
            throws InterruptedException {
        routeLock.lockInterruptibly();
        try {
            if(!routesChanged.await(time, unit)) {
                return null;
            }
            List<Route> list = new ArrayList<Route>();

            for(Route entry : routeMap.values()) {
                list.add(copyRoute(entry));
            }

            return list;

        } finally {
            routeLock.unlock();
        }

    }

    public Route routePacket(String destination) {
        routeLock.lock();
        try {
            Route route = routeMap.get(destination);
            return route == null ? null : copyRoute(route);

        } finally {
            routeLock.unlock();
        }
    }

    public int processRemoteTable(Routes routes) {
        int link = routeMap.get(routes.getNodeName()).getLink();
        processNewEntries(routes.getRoute(), link);
        displayRoutes(new PrintWriter(System.out, true));
        return link;

    }

    /**
     * @return the nodeName
     */
    public String getNodeName() {
        return nodeName;
    }

    /**
     * @return the linkMap
     */
    public SortedMap<Integer, Route> getLinkMap() {
        return linkMap;
    }

}
