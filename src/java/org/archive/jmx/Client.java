/*
 * Client
 *
 * $Id$
 *
 * Created on Nov 12, 2004
 *
 * Copyright (C) 2004 Internet Archive.
 *
 * This file is part of the Heritrix web crawler (crawler.archive.org).
 *
 * Heritrix is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or
 * any later version.
 *
 * Heritrix is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser Public License
 * along with Heritrix; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package org.archive.jmx;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.FieldPosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Set;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServerConnection;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;


/**
 * A Simple Command-Line JMX Client.
 * Connects to the JDK 1.5.0 JMX Agent which gets started if following
 * properties are set on the command line:
 * '-Dcom.sun.management.jmxremote.port=PORT#'.
 * See <a href="http://java.sun.com/j2se/1.5.0/docs/guide/management/agent.html">Monitoring
 * and Management Using JMX</a>. Because this client
 * doesn't yet do security, start the remote MBean with SSL
 * and authentication disabled: e.g. Also pass the following on
 * command line: <code>-Dcom.sun.management.jmxremote.authenticate=false
 * -Dcom.sun.management.jmxremote.ssl=false</code>.
 * @author stack
 */
public class Client {
    /**
     * Logging instance.
     */
    private static final Logger logger =
        Logger.getLogger(Client.class.getName());
    
    /**
     * Usage string.
     */
    private static final String USAGE = "Usage: java -jar" +
        " cmdline-jmxclient.jar HOST:PORT [BEAN] [COMMAND]\n" +
        "Options:\n" +
        " HOST:PORT Hostname and port to connect to. E.g. localhost:8081." +
        " Lists\n" +
        "           registered beans if only argument.\n" +
        " BEANNAME  Optional target bean name. If present we list" +
        " available operations\n" +
        "           and attributes.\n" +
        " COMMAND   Optional operation to run or attribute to fetch. If" +
        " none supplied,\n" +
        "           all operations and attributes are listed. Attributes" +
        " begin with a\n" +
        "           capital letter: e.g. 'Status' or 'Started'." +
        " Operations do not.\n" +
        "           Operations can take arguments.\n" +
        "Requirements:\n" +
        " JDK1.5.0.  Remote side must also be running jdk1.5.0 and be\n" +
        " started with the following system properties set:\n" +
        "     -Dcom.sun.management.jmxremote.remote=PORT\n" +
        "     -Dcom.sun.management.jmxremote.authenticate=false\n" +
        "     -Dcom.sun.management.jmxremote.ssl=false\n" +
        "Client Use Examples:\n" +
        " % java -jar cmdline-jmxclient-X.X.jar localhost:8081 \\\n" +
        "         org.archive.crawler:name=Heritrix,type=Service\n" +
        " % java -jar cmdline-jmxclient-X.X.jar localhost:8081 \\\n" +
        "         org.archive.crawler:name=Heritrix,type=Service \\\n" +
        "         schedule=http://www.archive.org\n" +
        " % java -jar cmdline-jmxclient-X.X.jar localhost:8081 \\\n" +
        "         java.util.logging:type=Logging \\\n" +
        "         setLoggingLevel=org.archive.crawler.Heritrix,FINE";
     
    /**
     * URL to use connecting to remote machine.
     */
    private String hostport = null;
    
    /**
     * Target bean name or snippet of bean name.
     */
    private String beanName = null;
    
    /**
     * Command or attribute to ask the other machine about.
     */
    private String command = null;
    
    /**
     * Pattern that matches a command name followed by
     * an optional equals and optional comma-delimited list
     * of arguments.
     */
    Pattern commandArgsPattern =
        Pattern.compile("^([^=]+)(?:(?:\\=)(.+))?$");
    
    
	public static void main(String[] args) throws Exception {
        Client client = new Client(args);
        // Set the logger to use our all-on-one-line formatter.
        Logger l = Logger.getLogger("");
        Handler [] hs = l.getHandlers();
        for (int i = 0; i < hs.length; i++) {
            Handler h = hs[0];
            if (h instanceof ConsoleHandler) {
                h.setFormatter(client.new OneLineSimpleLogger());
            }
        }
        client.execute();
	}
    
    protected static void usage() {
        usage(0, null);
    }
    
    protected static void usage(int exitCode, String message) {
        if (message != null && message.length() > 0) {
            System.out.println(message);
        }
        System.out.println(USAGE);
        System.exit(exitCode);
    }

    /**
     * Shutdown constructor.
     */
    private Client() {
        super();
    }
    
    /**
     * @param args Command line args.
     * @throws Exception
     */
    public Client(String [] args)
    throws Exception {
        if (args.length == 0) {
            usage();
        }
        this.hostport = args[0];
        if (args.length > 1) {
            this.beanName = args[1];
        }
        if (args.length > 2) {
            this.command = args[2];
        }
    }
    
    protected void execute()
    throws Exception {
        String hostname = this.hostport;
        int index = this.hostport.indexOf(':');
        if (index > 0) {
            hostname = hostname.substring(0, index);
        }
        JMXServiceURL rmiurl = 
            new JMXServiceURL("service:jmx:rmi://" + this.hostport +
                "/jndi/rmi://" + this.hostport + "/jmxrmi"); 
        JMXConnector jmxc = JMXConnectorFactory.connect(rmiurl, null);
        
        try {
            MBeanServerConnection mbsc = jmxc.getMBeanServerConnection();
            ObjectName objName =
                (this.beanName == null || this.beanName.length() <= 0)?
                    null: new ObjectName(this.beanName);
            Set beans = mbsc.queryMBeans(objName, null);
            if (beans.size() == 1) {
                ObjectInstance instance =
                    (ObjectInstance)beans.iterator().next();
                doBean(mbsc, instance);
            } else {
                for (Iterator i = beans.iterator(); i.hasNext();) {
                    Object obj = i.next();
                    if (obj instanceof ObjectName) {
                        System.out.println(((ObjectName)obj).
                            getCanonicalName());
                    } else if (obj instanceof ObjectInstance) {
                        System.out.println(((ObjectInstance)obj).
                            getObjectName());
                    }
                }
            }
        } finally {
            jmxc.close();
        }
    }
    
    protected void doBean(MBeanServerConnection mbsc,
            ObjectInstance instance)
    throws IntrospectionException, AttributeNotFoundException,
            InstanceNotFoundException, MBeanException,
            ReflectionException, IOException {
        if (this.command == null || this.command.length() <= 0) {
            listOptions(mbsc, instance);
            return;
        }
        
        Object result = null;
        if (Character.isUpperCase(command.charAt(0))) {
            result = mbsc.getAttribute(instance.getObjectName(),
                this.command);
        } else {
            // Operations may take arguments.   For now, do strings
            // only.  Assume strings do not contain ',' or '='.
            Object [] objs = null;
            Matcher m = this.commandArgsPattern.matcher(this.command);
            if (m != null && m.matches()) {
               String cmd = m.group(1);
               String args = m.group(2);
               if (args != null && args.length() > 0) {
                   objs = args.split(",");
               }
               String [] strs = null;
               if (objs != null && objs.length > 0) {
                   strs = new String[objs.length];
                   for (int i = 0; i < objs.length; i++) {
                       strs[i] = "java.lang.String";
                   }
               }
               result = mbsc.invoke(instance.getObjectName(), cmd,
                    objs, strs);
            } else {
                result = "Failed parse";
            }
        }
        logger.info(this.command + ": " + result);
    }

    protected void listOptions(MBeanServerConnection mbsc,
            ObjectInstance instance)
    throws InstanceNotFoundException, IntrospectionException,
            ReflectionException, IOException {
        MBeanInfo info = mbsc.getMBeanInfo(instance.getObjectName());
        MBeanAttributeInfo [] attributes  = info.getAttributes();
        if (attributes.length > 0) {
            System.out.println("Attributes:");
            for (int i = 0; i < attributes.length; i++) {
                System.out.println(' ' + attributes[i].getName() +
                    ": " + attributes[i].getDescription());
            }
        }
        MBeanOperationInfo [] operations = info.getOperations();
        if (operations.length > 0) {
            System.out.println("Operations:");
            for (int i = 0; i < operations.length; i++) {
                System.out.println(' ' + operations[i].getName() +
                    ": " + operations[i].getDescription());
            }
        }
    }
    
    /**
     * Logger that writes entry on one line with less verbose date.
     * Modelled on the OneLineSimpleLogger from Heritrix.
     * 
     * @author stack
     * @version $Revision$, $Date$
     */
    private class OneLineSimpleLogger extends SimpleFormatter {
        /**
         * Date instance.
         * 
         * Keep around instance of date.
         */
        private Date date = new Date();
        
        /**
         * Field position instance.
         * 
         * Keep around this instance.
         */
        private FieldPosition position = new FieldPosition(0);
        
        /**
         * MessageFormatter for date.
         */
        private SimpleDateFormat formatter =
            new SimpleDateFormat("MM/dd/yyyy HH:mm:ss Z");
        
        /**
         * Persistent buffer in which we conjure the log.
         */
        private StringBuffer buffer = new StringBuffer();
        

        public OneLineSimpleLogger() {
            super();
        }
        
        public synchronized String format(LogRecord record) {
            this.buffer.setLength(0);
            this.date.setTime(record.getMillis());
            this.position.setBeginIndex(0);
            this.formatter.format(this.date, buffer, this.position);
            buffer.append(' ');
            if (record.getSourceClassName() != null) {
                buffer.append(record.getSourceClassName());
            } else {
                buffer.append(record.getLoggerName());
            }
            buffer.append(' ');
            buffer.append(formatMessage(record));
            buffer.append(System.getProperty("line.separator"));
            if (record.getThrown() != null) {
                try {
                    StringWriter writer = new StringWriter();
                    PrintWriter printer = new PrintWriter(writer);
                    record.getThrown().printStackTrace(printer);
                    writer.close();
                    buffer.append(writer.toString());
                } catch (Exception e) {
                    buffer.append("Failed to get stack trace: " +
                        e.getMessage());
                }
            }
            return buffer.toString();
        }
    }
}
