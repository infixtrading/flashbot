package com.bethecoder.ascii_table.impl;

import com.bethecoder.ascii_table.*;
import com.bethecoder.ascii_table.spec.*;
import java.util.*;

public class SimpleASCIITableImpl implements IASCIITable {

    @Override
    public void printTable(final String[] header, final String[][] data) {
        this.printTable(header, 0, data, 1);
    }

    @Override
    public void printTable(final String[] header, final String[][] data, final int dataAlign) {
        this.printTable(header, 0, data, dataAlign);
    }

    @Override
    public void printTable(final String[] header, final int headerAlign, final String[][] data, final int dataAlign) {
        System.out.println(this.getTable(header, headerAlign, data, dataAlign));
    }

    @Override
    public String getTable(final String[] header, final String[][] data) {
        return this.getTable(header, 0, data, 1);
    }

    @Override
    public String getTable(final String[] header, final String[][] data, final int dataAlign) {
        return this.getTable(header, 0, data, dataAlign);
    }

    @Override
    public String getTable(final String[] header, final int headerAlign, final String[][] data, final int dataAlign) {
        ASCIITableHeader[] headerObjs = new ASCIITableHeader[0];
        if (header != null && header.length > 0) {
            headerObjs = new ASCIITableHeader[header.length];
            for (int i = 0; i < header.length; ++i) {
                headerObjs[i] = new ASCIITableHeader(header[i], dataAlign, headerAlign);
            }
        }
        return this.getTable(headerObjs, data);
    }

    @Override
    public void printTable(final ASCIITableHeader[] headerObjs, final String[][] data) {
        System.out.println(this.getTable(headerObjs, data));
    }

    @Override
    public String getTable(final IASCIITableAware asciiTableAware) {
        ASCIITableHeader[] headerObjs = new ASCIITableHeader[0];
        String[][] data = new String[0][0];
        List<Object> rowData = null;
        ASCIITableHeader colHeader = null;
        Vector<String> rowTransData = null;
        String[] rowContent = null;
        String cellData = null;
        if (asciiTableAware != null) {
            if (asciiTableAware.getHeaders() != null && !asciiTableAware.getHeaders().isEmpty()) {
                headerObjs = new ASCIITableHeader[asciiTableAware.getHeaders().size()];
                for (int i = 0; i < asciiTableAware.getHeaders().size(); ++i) {
                    headerObjs[i] = asciiTableAware.getHeaders().get(i);
                }
            }
            if (asciiTableAware.getData() != null && !asciiTableAware.getData().isEmpty()) {
                data = new String[asciiTableAware.getData().size()][];
                for (int i = 0; i < asciiTableAware.getData().size(); ++i) {
                    rowData = asciiTableAware.getData().get(i);
                    rowTransData = new Vector<String>(rowData.size());
                    for (int j = 0; j < rowData.size(); ++j) {
                        colHeader = ((j < headerObjs.length) ? headerObjs[j] : null);
                        cellData = asciiTableAware.formatData(colHeader, i, j, rowData.get(j));
                        if (cellData == null) {
                            cellData = String.valueOf(rowData.get(j));
                        }
                        rowTransData.add(cellData);
                    }
                    rowContent = new String[rowTransData.size()];
                    rowTransData.copyInto(rowContent);
                    data[i] = rowContent;
                }
            }
        }
        return this.getTable(headerObjs, data);
    }

    @Override
    public void printTable(final IASCIITableAware asciiTableAware) {
        System.out.println(this.getTable(asciiTableAware));
    }

    @Override
    public String getTable(final ASCIITableHeader[] headerObjs, final String[][] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Please provide valid data : " + data);
        }
        final StringBuilder tableBuf = new StringBuilder();
        final String[] header = this.getHeaders(headerObjs);
        final int colCount = this.getMaxColumns(header, data);
        final List<Integer> colMaxLenList = this.getMaxColLengths(colCount, header, data);
        if (header != null && header.length > 0) {
            tableBuf.append(this.getRowLineBuf(colCount, colMaxLenList, data));
            tableBuf.append(this.getRowDataBuf(colCount, colMaxLenList, header, headerObjs, true));
        }
        tableBuf.append(this.getRowLineBuf(colCount, colMaxLenList, data));
        String[] rowData = null;
        for (int i = 0; i < data.length; ++i) {
            rowData = new String[colCount];
            for (int j = 0; j < colCount; ++j) {
                if (j < data[i].length) {
                    rowData[j] = data[i][j];
                }
                else {
                    rowData[j] = "";
                }
            }
            tableBuf.append(this.getRowDataBuf(colCount, colMaxLenList, rowData, headerObjs, false));
        }
        tableBuf.append(this.getRowLineBuf(colCount, colMaxLenList, data));
        return tableBuf.toString();
    }

    private String getRowDataBuf(final int colCount, final List<Integer> colMaxLenList, final String[] row, final ASCIITableHeader[] headerObjs, final boolean isHeader) {
        final StringBuilder rowBuilder = new StringBuilder();
        String formattedData = null;
        for (int i = 0; i < colCount; ++i) {
            int align = isHeader ? 0 : 1;
            if (headerObjs != null && i < headerObjs.length) {
                if (isHeader) {
                    align = headerObjs[i].getHeaderAlign();
                }
                else {
                    align = headerObjs[i].getDataAlign();
                }
            }
            formattedData = ((i < row.length) ? row[i] : "");
            formattedData = "| " + this.getFormattedData(colMaxLenList.get(i), formattedData, align) + " ";
            if (i + 1 == colCount) {
                formattedData = String.valueOf(formattedData) + "|";
            }
            rowBuilder.append(formattedData);
        }
        return rowBuilder.append("\n").toString();
    }

    private String getFormattedData(final int maxLength, String data, final int align) {
        if (data.length() > maxLength) {
            return data;
        }
        boolean toggle = true;
        while (data.length() < maxLength) {
            if (align == -1) {
                data = String.valueOf(data) + " ";
            }
            else if (align == 1) {
                data = " " + data;
            }
            else {
                if (align != 0) {
                    continue;
                }
                if (toggle) {
                    data = " " + data;
                    toggle = false;
                }
                else {
                    data = String.valueOf(data) + " ";
                    toggle = true;
                }
            }
        }
        return data;
    }

    private String getRowLineBuf(final int colCount, final List<Integer> colMaxLenList, final String[][] data) {
        final StringBuilder rowBuilder = new StringBuilder();
        int colWidth = 0;
        for (int i = 0; i < colCount; ++i) {
            colWidth = colMaxLenList.get(i) + 3;
            for (int j = 0; j < colWidth; ++j) {
                if (j == 0) {
                    rowBuilder.append("+");
                }
                else if (i + 1 == colCount && j + 1 == colWidth) {
                    rowBuilder.append("-+");
                }
                else {
                    rowBuilder.append("-");
                }
            }
        }
        return rowBuilder.append("\n").toString();
    }

    private int getMaxItemLength(final List<String> colData) {
        int maxLength = 0;
        for (int i = 0; i < colData.size(); ++i) {
            maxLength = Math.max(colData.get(i).length(), maxLength);
        }
        return maxLength;
    }

    private int getMaxColumns(final String[] header, final String[][] data) {
        int maxColumns = 0;
        for (int i = 0; i < data.length; ++i) {
            maxColumns = Math.max(data[i].length, maxColumns);
        }
        maxColumns = Math.max(header.length, maxColumns);
        return maxColumns;
    }

    private List<Integer> getMaxColLengths(final int colCount, final String[] header, final String[][] data) {
        final List<Integer> colMaxLenList = new ArrayList<Integer>(colCount);
        List<String> colData = null;
        for (int i = 0; i < colCount; ++i) {
            colData = new ArrayList<String>();
            if (header != null && i < header.length) {
                colData.add(header[i]);
            }
            for (int j = 0; j < data.length; ++j) {
                if (i < data[j].length) {
                    colData.add(data[j][i]);
                }
                else {
                    colData.add("");
                }
            }
            final int maxLength = this.getMaxItemLength(colData);
            colMaxLenList.add(maxLength);
        }
        return colMaxLenList;
    }

    private String[] getHeaders(final ASCIITableHeader[] headerObjs) {
        String[] header = new String[0];
        if (headerObjs != null && headerObjs.length > 0) {
            header = new String[headerObjs.length];
            for (int i = 0; i < headerObjs.length; ++i) {
                header[i] = headerObjs[i].getHeaderName();
            }
        }
        return header;
    }
}