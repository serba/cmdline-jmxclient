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
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
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
 * Connects to the JDK 1.5.0 JMX Agent.
 * See <a href="http://java.sun.com/j2se/1.5.0/docs/guide/management/agent.html">Monitoring
 * and Management Using JMX</a>.
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
        " cmdline-jmxclient.jar USER:PASS HOST:PORT [BEAN] [COMMAND]\n" +
        "Options:\n" +
        " USER:PASS Username and password. Required. If none, pass '-'.\n" +
        "           E.g. 'controlRole:secret'\n" +
        " HOST:PORT Hostname and port to connect to. Required." +
        " E.g. localhost:8081.\n" +
        "           Lists registered beans if only USER:PASS and this" +
        " argument.\n" +
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
        " JDK1.5.0. If connecting to a SUN 1.5.0 JDK JMX Agent, remote side" +
        " must be\n" +
        " started with system properties such as the following:\n" +
        "     -Dcom.sun.management.jmxremote.port=PORT\n" +
        "     -Dcom.sun.management.jmxremote.authenticate=false\n" +
        "     -Dcom.sun.management.jmxremote.ssl=false\n" +
        " The above will start the remote server with no password. See\n" +
        " http://java.sun.com/j2se/1.5.0/docs/guide/management/agent.html" +
        " for more on\n" +
        " 'Monitoring and Management via JMX'.\n" +
        "Client Use Examples:\n" +
        " To list MBeans on a non-password protected remote agent:\n" +
        "     % java -jar cmdline-jmxclient-X.X.jar - localhost:8081 \\\n" +
        "         org.archive.crawler:name=Heritrix,type=Service\n" +
        " To list attributes and attributes of the Heritrix MBean:\n" +
        "     % java -jar cmdline-jmxclient-X.X.jar - localhost:8081 \\\n" +
        "         org.archive.crawler:name=Heritrix,type=Service \\\n" +
        "         schedule=http://www.archive.org\n" +
        " To set logging level to FINE on a password protected JVM:\n" +
        "     % java -jar cmdline-jmxclient-X.X.jar controlRole:secret" +
        " localhost:8081 \\\n" +
        "         java.util.logging:type=Logging \\\n" +
        "         setLoggingLevel=org.archive.crawler.Heritrix,FINE";
    
    /**
     * Username + password with colon separator.
     */
    private String userpass = null;
    
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
        if (args.length == 0 || args.length == 1) {
            usage();
        }
        this.userpass = args[0];
        this.hostport = args[1];
        if (args.length > 2) {
            this.beanName = args[2];
        }
        if (args.length > 3) {
            this.command = args[3];
        }
    }
    
    protected Map getCredentials() {
        Map env = null;
        if (this.userpass != null && !this.userpass.equals("-")) {
            int index = this.userpass.indexOf(':');
            if (index <= 0) {
                throw new RuntimeException("Unable to parse: " +
                    this.userpass);
            }
            String [] creds = new String [] {
                this.userpass.substring(0, index),
                this.userpass.substring(index + 1)};
            env = new HashMap(1);
            env.put(JMXConnector.CREDENTIALS, creds);
        }
        return env;
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
        JMXConnector jmxc =
            JMXConnectorFactory.connect(rmiurl, getCredentials());
        
        
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
