package io.github.gharachide.brazilmacro.calc;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * Exact decimal arithmetic for rates published in percent. Compounding is done with 34
 * significant digits (IEEE 754 decimal128) and rounded only for presentation, so results do not
 * depend on how a language model would do the arithmetic.
 */
public final class RateMath {

    public static final MathContext PRECISION = MathContext.DECIMAL128;
    public static final int PERCENT_SCALE = 4;
    public static final int FACTOR_SCALE = 10;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private RateMath() {
    }

    /** Product of (1 + r/100) over percent rates, e.g. [1.0, 1.0] gives 1.0201. */
    public static BigDecimal compoundFactor(List<BigDecimal> percents) {
        BigDecimal factor = BigDecimal.ONE;
        for (BigDecimal percent : percents) {
            factor = factor.multiply(BigDecimal.ONE.add(percent.divide(HUNDRED, PRECISION), PRECISION), PRECISION);
        }
        return factor;
    }

    /** Converts a growth factor to a percent change: 1.0201 gives 2.01. */
    public static BigDecimal factorToPercent(BigDecimal factor) {
        return factor.subtract(BigDecimal.ONE, PRECISION).multiply(HUNDRED, PRECISION);
    }

    /**
     * Real return by the Fisher relation: (1 + nominal) / (1 + inflation) - 1, from growth
     * factors, in percent.
     */
    public static BigDecimal realReturnPercent(BigDecimal nominalFactor, BigDecimal inflationFactor) {
        if (inflationFactor.signum() <= 0) {
            throw new ArithmeticException("Inflation factor must be positive");
        }
        return factorToPercent(nominalFactor.divide(inflationFactor, PRECISION));
    }

    public static BigDecimal percentForDisplay(BigDecimal percent) {
        return percent.setScale(PERCENT_SCALE, RoundingMode.HALF_EVEN);
    }

    public static BigDecimal factorForDisplay(BigDecimal factor) {
        return factor.setScale(FACTOR_SCALE, RoundingMode.HALF_EVEN);
    }
}
