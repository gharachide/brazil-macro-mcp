package io.github.gharachide.brazilmacro.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Statistics of the Focus survey for one indicator and reference year on one survey date.
 *
 * @param surveyDate date of the statistics
 * @param median median of the projections, the figure the weekly Focus report highlights
 * @param mean mean of the projections
 * @param standardDeviation standard deviation of the projections
 * @param minimum lowest projection
 * @param maximum highest projection
 * @param respondents number of institutions whose projections were used
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FocusSurvey(
        LocalDate surveyDate,
        BigDecimal median,
        BigDecimal mean,
        BigDecimal standardDeviation,
        BigDecimal minimum,
        BigDecimal maximum,
        Integer respondents) {
}
