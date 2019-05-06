package com.bethecoder.ascii_table.impl;

import com.bethecoder.ascii_table.spec.*;
import com.bethecoder.ascii_table.*;
import java.sql.*;
import java.util.*;
import java.math.*;
import java.text.*;

public class JDBCASCIITableAware implements IASCIITableAware {
    private List<ASCIITableHeader> headers;
    private List<List<Object>> data;

    public JDBCASCIITableAware(final Connection connection, final String sql) {
        this.headers = null;
        this.data = null;
        try {
            final Statement stmt = connection.createStatement();
            final ResultSet resultSet = stmt.executeQuery(sql);
            this.init(resultSet);
        }
        catch (SQLException e) {
            throw new RuntimeException("Unable to get table data : " + e);
        }
    }

    public JDBCASCIITableAware(final ResultSet resultSet) {
        this.headers = null;
        this.data = null;
        try {
            this.init(resultSet);
        }
        catch (SQLException e) {
            throw new RuntimeException("Unable to get table data : " + e);
        }
    }

    private void init(final ResultSet resultSet) throws SQLException {
        final int colCount = resultSet.getMetaData().getColumnCount();
        this.headers = new ArrayList<ASCIITableHeader>(colCount);
        for (int i = 0; i < colCount; ++i) {
            this.headers.add(new ASCIITableHeader(resultSet.getMetaData().getColumnLabel(i + 1).toUpperCase()));
        }
        this.data = new ArrayList<List<Object>>();
        List<Object> rowData = null;
        while (resultSet.next()) {
            rowData = new ArrayList<Object>();
            for (int j = 0; j < colCount; ++j) {
                rowData.add(resultSet.getObject(j + 1));
            }
            this.data.add(rowData);
        }
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
        }
        catch (Exception ex) {
            return null;
        }
    }
}