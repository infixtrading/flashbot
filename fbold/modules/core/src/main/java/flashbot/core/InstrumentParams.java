package flashbot.core;

public class InstrumentParams {
    public double makerFee = Double.NaN;
    public double takerFee = Double.NaN;

    // The minimum amount that the price can move by.
    public double tickSize = Double.NaN;

    /**
      * This constructor must be empty, to serve as an implementation of "default" params.
      */
    public InstrumentParams() {
    }

    public InstrumentParams(double makerFee, double takerFee, double tickSize) {
        this.makerFee = makerFee;
        this.takerFee = takerFee;
        this.tickSize = tickSize;
    }

    public static final InstrumentParams DEFAULT = new InstrumentParams();
}
