package com.bethecoder.ascii_table.impl;

import com.bethecoder.ascii_table.ASCIITableHeader;
import com.bethecoder.ascii_table.spec.IASCIITableAware;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.*;

public class CollectionASCIITableAware<T> implements IASCIITableAware {

    private List<ASCIITableHeader> headers;
    private List<List<Object>> data;

    public CollectionASCIITableAware(final List<T> objList, final String... properties) {
        this(objList, Arrays.asList(properties), Arrays.asList(properties));
    }

    public CollectionASCIITableAware(final List<T> objList, final List<String> properties, final List<String> title) {
        this.headers = null;
        this.data = null;
        if (objList != null && !objList.isEmpty() && properties != null && !properties.isEmpty()) {
            String header = null;
            this.headers = new ArrayList<ASCIITableHeader>(properties.size());
            for (int i = 0; i < properties.size(); ++i) {
                header = ((i < title.size()) ? title.get(i) : properties.get(i));
                this.headers.add(new ASCIITableHeader(String.valueOf(header).toUpperCase()));
            }
            this.data = new ArrayList<List<Object>>();
            List<Object> rowData = null;
            final Class<?> dataClazz = objList.get(0).getClass();
            final Map<String, Method> propertyMethodMap = new HashMap<String, Method>();
            for (int j = 0; j < objList.size(); ++j) {
                rowData = new ArrayList<Object>();
                for (int k = 0; k < properties.size(); ++k) {
                    rowData.add(this.getProperty(propertyMethodMap, dataClazz, objList.get(j), properties.get(k)));
                }
                this.data.add(rowData);
            }
        }
    }

    private Object getProperty(final Map<String, Method> propertyMethodMap, final Class<?> dataClazz, final T obj, final String property) {
        Object cellValue = null;
        try {
            Method method = null;
            if (propertyMethodMap.containsKey(property)) {
                method = propertyMethodMap.get(property);
            } else {
                String methodName = "get" + this.capitalize(property);
                method = this.getMethod(dataClazz, methodName);
                if (method == null) {
                    methodName = "is" + this.capitalize(property);
                    method = this.getMethod(dataClazz, methodName);
                }
                if (method != null) {
                    propertyMethodMap.put(property, method);
                }
            }
            cellValue = method.invoke(obj, new Object[0]);
        } catch (Exception ex) {
        }
        return cellValue;
    }

    private Method getMethod(final Class<?> dataClazz, final String methodName) {
        Method method = null;
        try {
            method = dataClazz.getMethod(methodName, (Class<?>[]) new Class[0]);
        } catch (Exception ex) {
        }
        return method;
    }

    private String capitalize(final String property) {
        return (property.length() == 0) ? property : (String.valueOf(property.substring(0, 1).toUpperCase()) + property.substring(1).toLowerCase());
    }

    @Override
    public List<List<Object>> getData() {
        return this.data;
    }

    @Override
    public List<ASCIITableHeader> getHeaders() {
        return this.headers;
    }

    @Override
    public String formatData(final ASCIITableHeader header, final int row, final int col, final Object data) {
        try {
            final BigDecimal bd = new BigDecimal(data.toString());
            return NumberFormat.getInstance().format(bd);
        } catch (Exception ex) {
            return null;
        }
    }
}