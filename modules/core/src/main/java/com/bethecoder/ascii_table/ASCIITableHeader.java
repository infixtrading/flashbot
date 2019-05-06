package com.bethecoder.ascii_table;

public class ASCIITableHeader {

    private String headerName;
    private int headerAlign;
    private int dataAlign;

    public ASCIITableHeader(final String headerName) {
        this.headerAlign = 0;
        this.dataAlign = 1;
        this.headerName = headerName;
    }

    public ASCIITableHeader(final String headerName, final int dataAlign) {
        this.headerAlign = 0;
        this.dataAlign = 1;
        this.headerName = headerName;
        this.dataAlign = dataAlign;
    }

    public ASCIITableHeader(final String headerName, final int dataAlign, final int headerAlign) {
        this.headerAlign = 0;
        this.dataAlign = 1;
        this.headerName = headerName;
        this.dataAlign = dataAlign;
        this.headerAlign = headerAlign;
    }

    public String getHeaderName() {
        return this.headerName;
    }

    public void setHeaderName(final String headerName) {
        this.headerName = headerName;
    }

    public int getHeaderAlign() {
        return this.headerAlign;
    }

    public void setHeaderAlign(final int headerAlign) {
        this.headerAlign = headerAlign;
    }

    public int getDataAlign() {
        return this.dataAlign;
    }

    public void setDataAlign(final int dataAlign) {
        this.dataAlign = dataAlign;
    }
}