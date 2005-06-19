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
import java.lang.reflect.InvocationTargetException;
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
import java.util.logging.Level;
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
import javax.management.MBeanParameterInfo;
import javax.management.MBeanServerConnection;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;


/**
 * A Simple Command-Line JMX Client.
 * Tested against the JDK 1.5.0 JMX Agent.
 * See <a href="http://java.sun.com/j2se/1.5.0/docs/guide/management/agent.html">Monitoring
 * and Management Using JMX</a>.
 * <p>Can supply credentials and do primitive string representation of tabular
 * and composite openmbeans.
 * @author stack
 */
public class Client {
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
        "           Operations can take arguments by adding an '=' " +
        "followed by\n" +
        "           comma-delimited params. Pass multiple " +
        "attributes/operations to run\n" +
        "           more than one per invocation.\n" +
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
     * Pattern that matches a command name followed by
     * an optional equals and optional comma-delimited list
     * of arguments.
     */
    private static final Pattern commandArgsPattern =
        Pattern.compile("^([^=]+)(?:(?:\\=)(.+))?$");
    
	public static void main(String[] args) throws Exception {
        Client client = new Client();
        // Set the logger to use our all-on-one-line formatter.
        Logger l = Logger.getLogger("");
        Handler [] hs = l.getHandlers();
        for (int i = 0; i < hs.length; i++) {
            Handler h = hs[0];
            if (h instanceof ConsoleHandler) {
                h.setFormatter(client.new OneLineSimpleLogger());
            }
        }
        client.execute(args);
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
     * Constructor.
     */
    public Client() {
        super();
    }
    
    /**
     * @param userpass String of login and password separated by colon
     * (e.g. controlRole:letmein).
     * @return Format credentials as map for RMI.
     */
    protected Map formatCredentials(String userpass) {
        Map env = null;
        if (userpass == null || userpass.equals("-")) {
            return env;
        }
        int index = userpass.indexOf(':');
        if (index <= 0) {
            throw new RuntimeException("Unable to parse: " +userpass);
        }
        String[] creds = new String[] {userpass.substring(0, index),
            userpass.substring(index + 1) };
        env = new HashMap(1);
        env.put(JMXConnector.CREDENTIALS, creds);
        return env;
    }
    
    protected void execute(String [] args)
    throws Exception {
        // Process command-line.
        if (args.length == 0 || args.length == 1) {
            usage();
        }
        String userpass = args[0];
        String hostport = args[1];
        String beanName = null;
        String [] command = null;
        if (args.length > 2) {
            beanName = args[2];
        }
        if (args.length > 3) {
            command = new String [args.length - 3];
            for (int i = 3; i < args.length; i++) {
                command[i - 3] = args[i];
            }
        }
        
        // Make up the jmx rmi URL and get a connector.
        String hostname = hostport;
        int index = hostport.indexOf(':');
        if (index > 0) {
            hostname = hostname.substring(0, index);
        }
        JMXServiceURL rmiurl = new JMXServiceURL("service:jmx:rmi://" +
            hostport + "/jndi/rmi://" + hostport + "/jmxrmi");
        JMXConnector jmxc =
            JMXConnectorFactory.connect(rmiurl,formatCredentials(userpass));
        
        // Query for instance of passed bean.
        try {
            MBeanServerConnection mbsc = jmxc.getMBeanServerConnection();
            ObjectName objName = (beanName == null || beanName.length() <= 0)?
                null: new ObjectName(beanName);
            Set beans = mbsc.queryMBeans(objName, null);
            if (beans.size() == 0) {
                // Complain if passed a nonexistent bean name.
                logger.severe(objName.getCanonicalName() +
                    " is not a registered bean");
            } else if (beans.size() == 1) {
                // If only one instance of asked-for bean.
                ObjectInstance instance =
                    (ObjectInstance)beans.iterator().next();
                doBean(mbsc, instance, command);
            } else {
                // This is case of multiple beans. Print name of each.
                for (Iterator i = beans.iterator(); i.hasNext();) {
                    Object obj = i.next();
                    if (obj instanceof ObjectName) {
                        System.out.println(((ObjectName)obj).getCanonicalName());
                    } else if (obj instanceof ObjectInstance) {
                        System.out.println(((ObjectInstance)obj).getObjectName().
                            getCanonicalName());
                    } else {
                        logger.severe("Unexpected object type: " + obj);
                    }
                }
            }
        } finally {
            jmxc.close();
        }
    }
    
    /**
     * Get attribute or run operation against passed bean
     * <code>instance</code>.
     * @param mbsc Server connection.
     * @param instance Bean instance we're to get attributes from or
     * run operation against.
     * @param command Command to run (May be null).
     * @throws Exception
     */
    protected void doBean(MBeanServerConnection mbsc, ObjectInstance instance,
        String [] command)
    throws Exception {
        // If no command, then print out list of attributes and operations.
        if (command == null || command.length <= 0) {
            listOptions(mbsc, instance);
            return;
        }
        
        // Maybe multiple attributes/operations listed on one command line.
        for (int i = 0; i < command.length; i++) {
            doSubCommand(mbsc, instance, command[i]);
        }
    }
    
    protected void doSubCommand(MBeanServerConnection mbsc,
            ObjectInstance instance, String subCommand)
    throws AttributeNotFoundException, InstanceNotFoundException,
        IntrospectionException, MBeanException, ReflectionException,
        IOException, NoSuchMethodException, InstantiationException,
        IllegalAccessException, InvocationTargetException,
        ClassNotFoundException {
        
        // Test if attribute or operation.
        Object result = (Character.isUpperCase(subCommand.charAt(0)))?
            mbsc.getAttribute(instance.getObjectName(), subCommand):
            doBeanOperation(mbsc, instance, subCommand);
        
        // Look at the result.  Is it of composite or tabular type?
        // If so, convert to a String representation.
        if (result instanceof CompositeData) {
            result = recurseCompositeData(new StringBuffer("\n"), "", "",
                (CompositeData)result);
        } else if (result instanceof TabularData) {
            result = recurseTabularData(new StringBuffer("\n"), "", "",
                 (TabularData)result);
        } else if (result instanceof String []) {
            String [] strs = (String [])result;
            StringBuffer buffer = new StringBuffer("\n");
            for (int i = 0; i < strs.length; i++) {
                buffer.append(strs[i]);
                buffer.append("\n");
            }
            result = buffer;
        }
        // Only log if a result.
        if (result != null && logger.isLoggable(Level.INFO)) {
            logger.info(subCommand + ": " + result);
        }
    }
    
    protected StringBuffer recurseTabularData(StringBuffer buffer,
            String indent, String name, TabularData data) {
        addNameToBuffer(buffer, indent, name);
        java.util.Collection c = data.values();
        for (Iterator i = c.iterator(); i.hasNext();) {
            Object obj = i.next();
            if (obj instanceof CompositeData) {
                recurseCompositeData(buffer, indent + " ", "",
                    (CompositeData)obj);
            } else if (obj instanceof TabularData) {
                recurseTabularData(buffer, indent, "",
                    (TabularData)obj);
            } else {
                buffer.append(obj);
            }
        }
        return buffer;
    }
    
    protected StringBuffer recurseCompositeData(StringBuffer buffer,
            String indent, String name, CompositeData data) {
        indent = addNameToBuffer(buffer, indent, name);
        for (Iterator i = data.getCompositeType().keySet().iterator();
                i.hasNext();) {
            String key = (String)i.next();
            Object o = data.get(key);
            if (o instanceof CompositeData) {
                recurseCompositeData(buffer, indent + " ", key,
                    (CompositeData)o);
            } else if (o instanceof TabularData) {
                recurseTabularData(buffer, indent, key, (TabularData)o);
            } else {
                buffer.append(indent);
                buffer.append(key);
                buffer.append(": ");
                buffer.append(o);
                buffer.append("\n");
            }
        }
        return buffer;
    }
    
    protected String addNameToBuffer(StringBuffer buffer, String indent,
            String name) {
        if (name == null || name.length() == 0) {
            return indent;
        }
        buffer.append(indent);
        buffer.append(name);
        buffer.append(":\n");
        // Move all that comes under this 'name' over by one space.
        return indent + " ";
    }

    protected Object doBeanOperation(MBeanServerConnection mbsc,
            ObjectInstance instance, String command)
    throws IntrospectionException, InstanceNotFoundException,
            MBeanException, ReflectionException, IOException,
            NoSuchMethodException, InstantiationException,
            IllegalAccessException, InvocationTargetException,
            ClassNotFoundException {
        // Operations may take arguments. Expected format is
        // 'operationName=arg0,arg1,arg2...' We are assuming no spaces nor
        // comma's in argument values.
        Matcher m = Client.commandArgsPattern.matcher(command);
        if (m == null || !m.matches()) {
            return "Failed command parse";
        }
        
        Object result = null;

        // Split the command arguments on the comma.
        Object [] objs = new Object [0];
        String cmd = m.group(1);
        String args = m.group(2);
        if (args != null && args.length() > 0) {
            objs = args.split(",");
        }
        
        // Get first method of name 'cmd'. Assumption is no method
        // overrides.  Then, look at the method and use its signature
        // to make sure client sends over parameters of the correct type.
        MBeanOperationInfo [] operations =
            mbsc.getMBeanInfo(instance.getObjectName()).getOperations();
        MBeanOperationInfo op = null;
        for (int i = 0; i < operations.length; i++) {
            if (operations[i].getName().equals(cmd)) {
                op = operations[i];
                break;
            }
        }
        if (op == null) {
            result = "Operation " + cmd + " not found.";
        } else {
            MBeanParameterInfo [] paraminfos = op.getSignature();
            int paraminfosLength = (paraminfos == null)? 0: paraminfos.length;
            int objsLength = (objs == null)? 0: objs.length;
            if (paraminfosLength != objsLength) {
                result = "Passed param count does not match signature count";
            } else {
                String [] signature = new String[paraminfosLength];
                Object [] params = (paraminfosLength == 0)? null
                        : new Object[paraminfosLength];
                for (int i = 0; i < paraminfosLength; i++) {
                    MBeanParameterInfo paraminfo = paraminfos[i];
                    java.lang.reflect.Constructor c = Class.forName(
                            paraminfo.getType()).getConstructor(
                            new Class[] {String.class});
                    params[i] = c.newInstance(new Object[] {objs[i]});
                }
                result = mbsc.invoke(instance.getObjectName(), cmd, params,
                        signature);
            }
        }
        return result;
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
                    ": " + attributes[i].getDescription() +
                    " (type=" + attributes[i].getType() +
                    ")");
            }
        }
        MBeanOperationInfo [] operations = info.getOperations();
        if (operations.length > 0) {
            System.out.println("Operations:");
            for (int i = 0; i < operations.length; i++) {
                MBeanParameterInfo [] params = operations[i].getSignature();
                StringBuffer paramsStrBuffer = new StringBuffer();
                if (params != null) {
                    for (int j = 0; j < params.length; j++) {
                        paramsStrBuffer.append("\n   name=");
                        paramsStrBuffer.append(params[j].getName());
                        paramsStrBuffer.append(" type=");
                        paramsStrBuffer.append(params[j].getType());
                        paramsStrBuffer.append(" ");
                        paramsStrBuffer.append(params[j].getDescription());
                    }
                }
                System.out.println(' ' + operations[i].getName() +              
                    ": " + operations[i].getDescription() +
                    "\n  Parameters " + params.length +
                    ", return type=" + operations[i].getReturnType() +
                    paramsStrBuffer.toString());
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
            this.formatter.format(this.date, this.buffer, this.position);
            this.buffer.append(' ');
            if (record.getSourceClassName() != null) {
                this.buffer.append(record.getSourceClassName());
            } else {
                this.buffer.append(record.getLoggerName());
            }
            this.buffer.append(' ');
            this.buffer.append(formatMessage(record));
            this.buffer.append(System.getProperty("line.separator"));
            if (record.getThrown() != null) {
                try {
                    StringWriter writer = new StringWriter();
                    PrintWriter printer = new PrintWriter(writer);
                    record.getThrown().printStackTrace(printer);
                    writer.close();
                    this.buffer.append(writer.toString());
                } catch (Exception e) {
                    this.buffer.append("Failed to get stack trace: " +
                        e.getMessage());
                }
            }
            return this.buffer.toString();
        }
    }
}
