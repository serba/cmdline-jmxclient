/*
 * HeritrixClient
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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.FieldPosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServerConnection;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;


/**
 * A Simple Command-Line JMX Client for Heritrix.
 * Used to control Heritrix remotely.
 * Connects to the JDK 1.5.0 JMX Agent (Heritrix registers itself
 * with this Agent if its been started).
 * See Heritrix#registerHeritrixMBean(Heritrix) to see
 * how to start the remote JMX Agent and to learn more about how
 * this JMX client/server interaction works.  Because this client
 * doesn't yet do security, start the remote MBean with SSL
 * and authentication disabled: e.g. Pass the following on
 * command line: <code>-Dcom.sun.management.jmxremote.authenticate=false
 * -Dcom.sun.management.jmxremote.ssl=false</code>.
 * <p>TODO: Make it so this same client can act as client to the JVM
 * logging, memory, and thread beans; as a cmdline jconsole.</p>
 * @author stack
 */
public class HeritrixClient {
    /**
     * Logging instance.
     */
    private static final Logger logger =
        Logger.getLogger(HeritrixClient.class.getName());
    
    /**
     * Name used to register remote Heritrix JMX Bean.
     * Below string must match the one in Heritrix.
     */
    private static final String REMOTE_NAME =
        "org.archive.crawler:name=Heritrix,type=Service";
    
    /**
     * Usage string.
     */
    private static final String USAGE = "Usage: java -jar" +
        " cmdline-jmxclient.jar HOST:PORT [COMMAND]\n" +
        "Options:\n" +
        " HOST:PORT Hostname and port to connect to. E.g. localhost:8081\n" +
        " COMMAND   Optional operation to run or attribute to fetch.  If" +
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
        " % java -jar cmdline-jmxclient-X.X.jar localhost:8081\n" +
        " % java -jar cmdline-jmxclient-X.X.jar localhost:8081 Status\n" +
        " % java -jar cmdline-jmxclient-X.X.jar localhost:8081 schedule=http://arc.org";
    
    /**
     * URL to use connecting to remote machine.
     */
    private String hostport = null;
    
    /**
     * Command or attribute to ask the other machine about.
     */
    private String command = null;
    
    
	public static void main(String[] args) throws Exception {
        HeritrixClient client = new HeritrixClient();
        // Set the logger to use our all-on-one-line formatter.
        Logger l = Logger.getLogger("");
        Handler [] hs = l.getHandlers();
        for (int i = 0; i < hs.length; i++) {
            Handler h = hs[0];
            if (h instanceof ConsoleHandler) {
                h.setFormatter(client.new OneLineSimpleLogger());
            }
        }
        // Process cmdline args.  Procesing results gets added to client
        // instance.
        client.init(args);
        client.doCommand();
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

    protected void init(String [] args)
    throws Exception {
        if (args.length != 2 && args.length != 1) {
            usage();
        }
        setHostport(args[0]);
        if (args.length > 1) {
            setCommand(args[1]);
        }
    }
    
    protected void doCommand()
    throws Exception {
        String hostname = getHostport();
        int index = hostport.indexOf(':');
        if (index > 0) {
            hostname = hostname.substring(0, index);
        }
        JMXServiceURL rmiurl = 
            new JMXServiceURL("service:jmx:rmi://" + getHostport() +
                "/jndi/rmi://" + getHostport() + "/jmxrmi"); 
        JMXConnector jmxc = JMXConnectorFactory.connect(rmiurl, null);
        
        try {
            MBeanServerConnection mbsc = jmxc.getMBeanServerConnection();
            ObjectInstance instance = mbsc.
                getObjectInstance(new ObjectName(REMOTE_NAME));
            if (command != null && command.length() > 0) {
                Object result = null;
                if (Character.isUpperCase(command.charAt(0))) {
                    result = mbsc.getAttribute(instance.getObjectName(),
                        command);
                } else {
                    // Operations may take an argument.   For now, takes one only
                    // and assumed a String.
                    Object [] objs = null;
                    String [] strs = null;
                    index = command.indexOf('=');
                    if (index > 0) {
                        String c = command.substring(0, index);
                        Object [] o = {command.substring(index + 1)};
                        objs = o;
                        String [] s = {"java.lang.String"};
                        strs = s;
                        command = c;
                    }
                    result = mbsc.invoke(instance.getObjectName(), command, objs, strs);
                }
                logger.info(command + ": " + result);
            } else {
                // No command. List out all methods and attributes.
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
        } finally {
            jmxc.close();
        }
    }
    
    /**
     * @return Returns the command.
     */
    public String getCommand() {
        return this.command;
    }
    
    /**
     * @param command The command to set.
     */
    public void setCommand(String command) {
        this.command = command;}
    
    /**
     * @return Returns the hostport.
     */
    public String getHostport() {
        return this.hostport;
    }
    
    /**
     * @param h The hostport to set.
     */
    public void setHostport(String h) {
        this.hostport = h;
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
