package flashbot.core;

import java.util.HashMap;
import java.util.Map;
import java.util.function.ToDoubleFunction;

public class ExchangeParams {
    private InstrumentParams baseParams;
    private Map<String, InstrumentParams> instrumentParams;

    public ExchangeParams(InstrumentParams baseParams,
                          Map<String, InstrumentParams> instrumentParams) {
        this.baseParams = baseParams;
        this.instrumentParams = instrumentParams == null ? new HashMap<>() : instrumentParams;
    }

    public double makerFee(String symbol) {
        return getDoubleParam(instrumentParams.get(symbol), p -> p.makerFee);
    }

    public double takerFee(String symbol) {
        return getDoubleParam(instrumentParams.get(symbol), p -> p.takerFee);
    }

    public double tickSize(String symbol) {
        return getDoubleParam(instrumentParams.get(symbol), p -> p.tickSize);
    }

    private Double getDoubleParam(InstrumentParams params, ToDoubleFunction<InstrumentParams> getter) {
        double defaultVal = getter.applyAsDouble(InstrumentParams.DEFAULT);

        if (params != null) {
            double instrVal = getter.applyAsDouble(params);
            if (areValuesDifferent(instrVal, defaultVal)) {
                return instrVal;
            }
        }

        if (baseParams != null) {
            double baseVal = getter.applyAsDouble(baseParams);
            if (areValuesDifferent(baseVal, defaultVal)) {
                return baseVal;
            }
        }

        return defaultVal;
    }

    // Double equality (unlike ==, treats NaN as equal to NaN)
    private boolean areValuesDifferent(double a, double b) {
        if (Double.isNaN(a) && Double.isNaN(b)) {
            return false;
        }
        return a != b;
    }
}
