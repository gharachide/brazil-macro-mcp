package io.github.gharachide.brazilmacro.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RateMathTest {

    private static List<BigDecimal> percents(String values) {
        return Arrays.stream(values.split(" ")).map(BigDecimal::new).toList();
    }

    @Test
    void compoundsTwoMonthsOfOnePercent() {
        BigDecimal factor = RateMath.compoundFactor(percents("1.00 1.00"));

        assertThat(factor).isEqualByComparingTo("1.0201");
        assertThat(RateMath.factorToPercent(factor)).isEqualByComparingTo("2.01");
    }

    @Test
    void ipca2023MonthlyValuesCompoundToTheOfficialAnnualFigure() {
        // IBGE published IPCA 2023 as 4.62%.
        BigDecimal factor = RateMath.compoundFactor(
                percents("0.53 0.84 0.71 0.61 0.23 -0.08 0.12 0.23 0.26 0.24 0.28 0.56"));

        BigDecimal percent = RateMath.percentForDisplay(RateMath.factorToPercent(factor));

        assertThat(percent).isEqualByComparingTo("4.6211");
        assertThat(percent.setScale(2, java.math.RoundingMode.HALF_EVEN)).isEqualByComparingTo("4.62");
    }

    @Test
    void handlesDeflationMonths() {
        assertThat(RateMath.factorToPercent(RateMath.compoundFactor(percents("-0.50 -0.50"))))
                .isEqualByComparingTo("-0.9975");
    }

    @Test
    void realReturnUsesTheFisherRelationNotSubtraction() {
        BigDecimal nominal = new BigDecimal("1.10");
        BigDecimal inflation = new BigDecimal("1.05");

        BigDecimal real = RateMath.percentForDisplay(RateMath.realReturnPercent(nominal, inflation));

        assertThat(real).isEqualByComparingTo("4.7619"); // not 10 - 5 = 5
    }

    @Test
    void emptyPeriodHasNoChange() {
        assertThat(RateMath.compoundFactor(List.of())).isEqualByComparingTo("1");
    }

    @Test
    void rejectsNonPositiveInflationFactor() {
        assertThatThrownBy(() -> RateMath.realReturnPercent(BigDecimal.ONE, BigDecimal.ZERO))
                .isInstanceOf(ArithmeticException.class);
    }
}
