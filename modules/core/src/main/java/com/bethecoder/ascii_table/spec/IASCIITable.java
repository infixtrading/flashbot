package com.bethecoder.ascii_table.spec;

import com.bethecoder.ascii_table.*;

public interface IASCIITable {

    public static final int ALIGN_LEFT = -1;
    public static final int ALIGN_CENTER = 0;
    public static final int ALIGN_RIGHT = 1;
    public static final int DEFAULT_HEADER_ALIGN = 0;
    public static final int DEFAULT_DATA_ALIGN = 1;

    void printTable(String[] p0, String[][] p1);

    void printTable(String[] p0, String[][] p1, int p2);

    void printTable(String[] p0, int p1, String[][] p2, int p3);

    void printTable(ASCIITableHeader[] p0, String[][] p1);

    void printTable(IASCIITableAware p0);

    String getTable(String[] p0, String[][] p1);

    String getTable(String[] p0, String[][] p1, int p2);

    String getTable(String[] p0, int p1, String[][] p2, int p3);

    String getTable(ASCIITableHeader[] p0, String[][] p1);

    String getTable(IASCIITableAware p0);
}