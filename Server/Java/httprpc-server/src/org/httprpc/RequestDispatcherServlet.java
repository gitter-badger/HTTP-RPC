/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.httprpc;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.Principal;
import java.util.AbstractList;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.TreeMap;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.httprpc.beans.BeanAdapter;

/**
 * Servlet that dispatches HTTP-RPC web service requests.
 */
public class RequestDispatcherServlet extends HttpServlet {
    private static final long serialVersionUID = 0;

    /**
     * Method descriptor.
     */
    public static final class MethodDescriptor {
        private Method method;
        private ResourceBundle resourceBundle;

        private MethodDescriptor(Method method, ResourceBundle resourceBundle) {
            this.method = method;
            this.resourceBundle = resourceBundle;
        }

        /**
         * Returns the method's name.
         *
         * @return
         * The name of the method.
         */
        public String getName() {
            return method.getName();
        }

        /**
         * Returns the method's description.
         *
         * @return
         * A localized description of the method.
         */
        public String getDescription() {
            String description = null;

            if (resourceBundle != null) {
                try {
                    description = resourceBundle.getString(method.getName());
                } catch (MissingResourceException exception) {
                    // No-op
                }
            }

            return description;
        }

        /**
         * Returns the method's parameters.
         *
         * @return
         * A list of the method's parameters.
         */
        public ParameterDescriptorList getParameters() {
            return new ParameterDescriptorList(method.getParameters(), resourceBundle);
        }

        /**
         * Returns the method's return type.
         *
         * @return
         * The type of the value returned by the method, or <tt>null</tt> if
         * the method does not return a value.
         */
        public String getReturns() {
            return getDescriptorType(method.getReturnType());
        }
    }

    /**
     * Parameter descriptor list.
     */
    public static final class ParameterDescriptorList extends AbstractList<ParameterDescriptor> {
        private Parameter[] parameters;
        private ResourceBundle resourceBundle;

        private ParameterDescriptorList(Parameter[] parameters, ResourceBundle resourceBundle) {
            this.parameters = parameters;
            this.resourceBundle = resourceBundle;
        }

        @Override
        public ParameterDescriptor get(int index) {
            return new ParameterDescriptor(parameters[index], resourceBundle);
        }

        @Override
        public int size() {
            return parameters.length;
        }
    }

    /**
     * Parameter descriptor.
     */
    public static final class ParameterDescriptor {
        private Parameter parameter;
        private ResourceBundle resourceBundle;

        private ParameterDescriptor(Parameter parameter, ResourceBundle resourceBundle) {
            this.parameter = parameter;
            this.resourceBundle = resourceBundle;
        }

        /**
         * Returns the parameter's name.
         *
         * @return
         * The name of the parameter.
         */
        public String getName() {
            return parameter.getName();
        }

        /**
         * Returns the parameters's description.
         *
         * @return
         * A localized description of the parameters.
         */
        public String getDescription() {
            String description = null;

            if (resourceBundle != null) {
                Executable method = parameter.getDeclaringExecutable();

                try {
                    description = resourceBundle.getString(method.getName() + "_" + parameter.getName());
                } catch (MissingResourceException exception) {
                    // No-op
                }
            }

            return description;
        }

        /**
         * Returns the parameter's type.
         *
         * @return
         * The type of the parameter.
         */
        public String getType() {
            return getDescriptorType(parameter.getType());
        }
    }

    // User role set
    private static class UserRoleSet extends AbstractSet<String> {
        private HttpServletRequest request;

        public UserRoleSet(HttpServletRequest request) {
            this.request = request;
        }

        @Override
        public boolean contains(Object object) {
            return request.isUserInRole(object.toString());
        }

        @Override
        public int size() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<String> iterator() {
            throw new UnsupportedOperationException();
        }
    }

    private Class<?> serviceType = null;

    private TreeMap<String, Method> methodMap = new TreeMap<>();

    private static final String STRING_TYPE = "string";
    private static final String NUMBER_TYPE = "number";
    private static final String BOOLEAN_TYPE = "boolean";

    private static final String ARRAY_TYPE = "array";
    private static final String OBJECT_TYPE = "object";

    private static final String UNSUPPORTED_TYPE = "?";

    private static final String JSON_MIME_TYPE = "application/json; charset=UTF-8";

    @Override
    public void init() throws ServletException {
        String serviceClassName = getServletConfig().getInitParameter("serviceClassName");

        try {
            serviceType = Class.forName(serviceClassName);
        } catch (ClassNotFoundException exception) {
            throw new ServletException(exception);
        }

        if (!WebService.class.isAssignableFrom(serviceType)) {
            throw new ServletException("Invalid service type.");
        }

        Method[] methods = serviceType.getMethods();

        for (int i = 0; i < methods.length; i++) {
            Method method = methods[i];

            if (serviceType.isAssignableFrom(method.getDeclaringClass())) {
                methodMap.put(method.getName(), method);
            }
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        doPost(request, response);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        String pathInfo = request.getPathInfo();

        if (pathInfo == null) {
            // Write method descriptor list
            ResourceBundle resourceBundle = null;

            try {
                resourceBundle = ResourceBundle.getBundle(serviceType.getName(), request.getLocale());
            } catch (MissingResourceException exception) {
                // No-op
            }

            LinkedList<MethodDescriptor> methodDescriptorList = new LinkedList<>();

            for (Method method : methodMap.values()) {
                methodDescriptorList.add(new MethodDescriptor(method, resourceBundle));
            }

            writeValue(response.getWriter(), BeanAdapter.adapt(methodDescriptorList), 0);
        } else {
            // Look up service method
            Method method = methodMap.get(pathInfo.substring(1));

            if (method == null) {
                throw new ServletException("Method not found.");
            }

            // Construct arguments
            Parameter[] parameters = method.getParameters();

            Object[] arguments = new Object[parameters.length];

            for (int i = 0; i < parameters.length; i++) {
                Parameter parameter = parameters[i];

                String name = parameter.getName();
                Class<?> type = parameter.getType();

                Object argument;
                if (type == List.class) {
                    String[] values = request.getParameterValues(name);

                    List<Object> list;
                    if (values != null) {
                        ParameterizedType parameterizedType = (ParameterizedType)parameter.getParameterizedType();
                        Type elementType = parameterizedType.getActualTypeArguments()[0];

                        int n = values.length;

                        list = new ArrayList<>(n);

                        for (int j = 0; j < n; j++) {
                            list.add(coerce(values[j], elementType));
                        }
                    } else {
                        list = Collections.EMPTY_LIST;
                    }

                    argument = list;
                } else {
                    argument = coerce(request.getParameter(name), type);
                }

                arguments[i] = argument;
            }

            // Execute method
            WebService service;
            try {
                service = (WebService)serviceType.newInstance();
            } catch (IllegalAccessException | InstantiationException exception) {
                throw new ServletException(exception);
            }

            service.setLocale(request.getLocale());

            Principal userPrincipal = request.getUserPrincipal();

            if (userPrincipal != null) {
                service.setUserName(userPrincipal.getName());
                service.setUserRoles(new UserRoleSet(request));
            }

            Object result;
            try {
                result = method.invoke(service, arguments);
            } catch (IllegalAccessException | InvocationTargetException exception) {
                throw new ServletException(exception);
            }

            // Write response
            Class<?> returnType = method.getReturnType();

            if (returnType != Void.TYPE && returnType != Void.class) {
                response.setContentType(JSON_MIME_TYPE);

                writeValue(response.getWriter(), result, 0);
            }
        }
    }

    private static String getDescriptorType(Class<?> type) {
        String descriptorType;
        if (type == String.class) {
            descriptorType = STRING_TYPE;
        } else if (type == Byte.TYPE
            || type == Short.TYPE
            || type == Integer.TYPE
            || type == Float.TYPE
            || type == Double.TYPE
            || Number.class.isAssignableFrom(type)) {
            descriptorType = NUMBER_TYPE;
        } else if (type == Boolean.TYPE || type == Boolean.class) {
            descriptorType = BOOLEAN_TYPE;
        } else if (List.class.isAssignableFrom(type)) {
            descriptorType = ARRAY_TYPE;
        } else if (Map.class.isAssignableFrom(type)) {
            descriptorType = OBJECT_TYPE;
        } else if (type == Void.TYPE || type == Void.class) {
            descriptorType = null;
        } else {
            descriptorType = UNSUPPORTED_TYPE;
        }

        return descriptorType;
    }

    private static Object coerce(String value, Type type) throws ServletException {
        Object argument;
        if (value == null || type == String.class) {
            argument = value;
        } else if (type == Byte.TYPE || type == Byte.class) {
            argument = Byte.parseByte(value);
        } else if (type == Short.TYPE || type == Short.class) {
            argument = Short.parseShort(value);
        } else if (type == Integer.TYPE || type == Integer.class) {
            argument = Integer.parseInt(value);
        } else if (type == Long.TYPE || type == Long.class) {
            argument = Long.parseLong(value);
        } else if (type == Float.TYPE || type == Float.class) {
            argument = Float.parseFloat(value);
        } else if (type == Double.TYPE || type == Double.class) {
            argument = Double.parseDouble(value);
        } else if (type == BigInteger.class) {
            argument = new BigInteger(value);
        } else if (type == BigDecimal.class) {
            argument = new BigDecimal(value);
        } else if (type == Boolean.TYPE || type == Boolean.class) {
            argument = Boolean.parseBoolean(value);
        } else {
            throw new ServletException("Invalid parameter type.");
        }

        return argument;
    }

    private static void writeValue(Writer writer, Object value, int depth) throws IOException {
        if (value == null) {
            writer.append(null);
        } else if (value instanceof String) {
            String string = (String)value;

            writer.append("\"");

            for (int i = 0, n = string.length(); i < n; i++) {
                char c = string.charAt(i);

                switch (c) {
                    case '"':
                    case '\\':
                    case '/': {
                        writer.append("\\" + c);
                        break;
                    }

                    case '\b': {
                        writer.append("\\b");
                        break;
                    }

                    case '\f': {
                        writer.append("\\f");
                        break;
                    }

                    case '\n': {
                        writer.append("\\n");
                        break;
                    }

                    case '\r': {
                        writer.append("\\r");
                        break;
                    }

                    case '\t': {
                        writer.append("\\t");
                        break;
                    }

                    default: {
                        writer.append(c);
                    }
                }
            }

            writer.append("\"");
        } else if (value instanceof Number || value instanceof Boolean) {
            writer.append(String.valueOf(value));
        } else {
            try {
                if (value instanceof List<?>) {
                    List<?> list = (List<?>)value;

                    writer.append("[");

                    depth++;

                    int i = 0;

                    for (Object element : list) {
                        if (i > 0) {
                            writer.append(",");
                        }

                        writer.append("\n");

                        indent(writer, depth);

                        writeValue(writer, element, depth);

                        i++;
                    }

                    depth--;

                    writer.append("\n");

                    indent(writer, depth);

                    writer.append("]");
                } else if (value instanceof Map<?, ?>) {
                    Map<?, ?> map = (Map<?, ?>)value;

                    writer.append("{");

                    depth++;

                    int i = 0;

                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (i > 0) {
                            writer.append(",");
                        }

                        writer.append("\n");

                        Object key = entry.getKey();

                        if (!(key instanceof String)) {
                            throw new IOException("Invalid key type.");
                        }

                        indent(writer, depth);

                        writer.append("\"" + key + "\": ");

                        writeValue(writer, entry.getValue(), depth);

                        i++;
                    }

                    depth--;

                    writer.append("\n");

                    indent(writer, depth);

                    writer.append("}");
                } else {
                    throw new IOException("Invalid value type.");
                }
            } finally {
                if (value instanceof AutoCloseable) {
                    try {
                        ((AutoCloseable)value).close();
                    } catch (Exception exception) {
                        throw new IOException(exception);
                    }
                }
            }
        }
    }

    private static void indent(Writer writer, int depth) throws IOException {
        for (int i = 0; i < depth; i++) {
            writer.append("  ");
        }
    }
}