/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package mx.itesm.gda.tc4003_1.mirouter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.Set;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;
import mx.itesm.gda.tc4003_1.mirouter.binding.ObjectFactory;
import mx.itesm.gda.tc4003_1.mirouter.binding.Packet;
import mx.itesm.gda.tc4003_1.mirouter.binding.Routes;

/**
 *
 * @author alexv
 */
public class RouteXMLUtil {

    private JAXBContext jaxbCtx;

    private static Set<ClassLoader> getClassLoaders() {
        Set<ClassLoader> ret = new HashSet<ClassLoader>();
        ret.add(Thread.currentThread().getContextClassLoader());
        ret.add(RouteXMLUtil.class.getClassLoader());
        ret.add(ClassLoader.getSystemClassLoader());
        return ret;
    }

    private static InputStream ultimateFind(String filename)
            throws FileNotFoundException {
        for(ClassLoader cl : getClassLoaders()) {
            InputStream in = cl.getResourceAsStream(filename);
            if(in != null) {
                return in;
            }
        }

        File file_in = new File(System.getProperty("user.dir"), filename);
        return new FileInputStream(file_in);

    }

    public RouteXMLUtil() throws JAXBException {
        jaxbCtx = JAXBContext.newInstance(
                "mx.itesm.gda.tc4003_1.mirouter.binding");

    }

    public Routes parseRoutes(String classpath_resource) throws JAXBException,
            IOException {
        Unmarshaller unmarshaller = jaxbCtx.createUnmarshaller();
        InputStream in = ultimateFind(classpath_resource);
        try {
            return unmarshaller.unmarshal(
                    new StreamSource(in), Routes.class).getValue();
        } finally {
            in.close();
        }
    }

    public JAXBElement<?> parseDataStr(CharSequence source)
            throws JAXBException {
        Unmarshaller unmarshaller = jaxbCtx.createUnmarshaller();
        return (JAXBElement<?>)unmarshaller.unmarshal(
                new StringReader(source.toString()));

    }

    public CharSequence generateRouteStr(Routes routes) throws JAXBException {
        Marshaller marshaller = jaxbCtx.createMarshaller();
        marshaller.setProperty("com.sun.xml.bind.xmlDeclaration", false);
        StringWriter sw = new StringWriter(4096);
        marshaller.marshal(new ObjectFactory().createRoutes(routes), sw);
        return sw.toString();
    }

    public CharSequence generatePacket(Packet packet) throws JAXBException {
        Marshaller marshaller = jaxbCtx.createMarshaller();
        marshaller.setProperty("com.sun.xml.bind.xmlDeclaration", false);
        StringWriter sw = new StringWriter(4096);
        marshaller.marshal(new ObjectFactory().createPacket(packet), sw);
        return sw.toString();
    }

}
