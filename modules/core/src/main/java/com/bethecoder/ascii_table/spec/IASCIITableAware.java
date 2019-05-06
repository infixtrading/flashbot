package com.bethecoder.ascii_table.spec;

import java.util.*;
import com.bethecoder.ascii_table.*;

public interface IASCIITableAware {

    List<ASCIITableHeader> getHeaders();

    List<List<Object>> getData();

    String formatData(ASCIITableHeader p0, int p1, int p2, Object p3);
}