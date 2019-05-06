package com.bethecoder.ascii_table;

import com.bethecoder.ascii_table.impl.*;
import com.bethecoder.ascii_table.spec.*;

public class ASCIITable implements IASCIITable {

    private static ASCIITable instance;
    private IASCIITable asciiTable;

    static {
        ASCIITable.instance = null;
    }

    private ASCIITable() {
        this.asciiTable = new SimpleASCIITableImpl();
    }

    public static synchronized ASCIITable getInstance() {
        if (ASCIITable.instance == null) {
            ASCIITable.instance = new ASCIITable();
        }
        return ASCIITable.instance;
    }

    @Override
    public String getTable(final String[] header, final String[][] data) {
        return this.asciiTable.getTable(header, data);
    }

    @Override
    public String getTable(final String[] header, final String[][] data, final int dataAlign) {
        return this.asciiTable.getTable(header, data, dataAlign);
    }

    @Override
    public String getTable(final String[] header, final int headerAlign, final String[][] data, final int dataAlign) {
        return this.asciiTable.getTable(header, headerAlign, data, dataAlign);
    }

    @Override
    public void printTable(final String[] header, final String[][] data) {
        this.asciiTable.printTable(header, data);
    }

    @Override
    public void printTable(final String[] header, final String[][] data, final int dataAlign) {
        this.asciiTable.printTable(header, data, dataAlign);
    }

    @Override
    public void printTable(final String[] header, final int headerAlign, final String[][] data, final int dataAlign) {
        this.asciiTable.printTable(header, headerAlign, data, dataAlign);
    }

    @Override
    public String getTable(final ASCIITableHeader[] headerObjs, final String[][] data) {
        return this.asciiTable.getTable(headerObjs, data);
    }

    @Override
    public void printTable(final ASCIITableHeader[] headerObjs, final String[][] data) {
        this.asciiTable.printTable(headerObjs, data);
    }

    @Override
    public String getTable(final IASCIITableAware asciiTableAware) {
        return this.asciiTable.getTable(asciiTableAware);
    }

    @Override
    public void printTable(final IASCIITableAware asciiTableAware) {
        this.asciiTable.printTable(asciiTableAware);
    }
}