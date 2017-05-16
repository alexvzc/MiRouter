
package mx.itesm.gda.tc4003_1.mirouter;

import java.io.IOException;
import java.io.PrintWriter;
import javax.xml.bind.JAXBException;
import mx.itesm.gda.tc4003_1.mirouter.binding.Packet;
import mx.itesm.gda.tc4003_1.mirouter.binding.Routes;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Hello world!
 *
 */
public class App {

    private static final Log LOGGER = LogFactory.getLog(App.class);

    public static void main(String[] args) {
        LOGGER.info("Starting up");
        if(args.length != 1) {
            System.out.println("Usage: java -jar MiRouter.jar [node-file.xml]");
            return;
        }

        LOGGER.info("Reading route tables");
        Routes routes;
        try {
            routes = new RouteXMLUtil().parseRoutes(args[0]);
        } catch(Exception e) {
            LOGGER.fatal("Cannot read route file: " + args[0], e);
            return;
        }

        LOGGER.info("Route table read");
        RouteManager.displayRoutes(routes, new PrintWriter(System.out, true));

        LinkManager link_manager = new LinkManager(routes);
        RouteManager route_manager = link_manager.getRouteManager();

        while(true) {
            try {
                int ch = System.in.read();
                String dest_node = Character.toString((char)ch).toUpperCase();

                if(route_manager.routePacket(dest_node) != null) {
                    LOGGER.info("Inyecting packet from " +
                            route_manager.getNodeName() + " to "+ dest_node);

                    Packet p = new Packet();
                    p.setFrom(route_manager.getNodeName());
                    p.setDestination(dest_node);

                    CharSequence cs = new RouteXMLUtil().generatePacket(p);
                    link_manager.processData(cs, null);
                }

            } catch(JAXBException jaxbe) {
                LOGGER.error("JAXBE console error", jaxbe);
            } catch(IOException ioe) {
                LOGGER.error("IO console error", ioe);
            }
        }
    }

}
