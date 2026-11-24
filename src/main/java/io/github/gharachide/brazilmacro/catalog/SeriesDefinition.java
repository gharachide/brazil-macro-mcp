package io.github.gharachide.brazilmacro.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One curated SGS series. The alias is what agents pass to tools; the code is what the BCB API
 * expects.
 *
 * @param alias stable name used in tool arguments
 * @param sgsCode series code in the BCB SGS
 * @param name name of the series, in Portuguese as published
 * @param kind meaning of the values, which decides valid calculations
 * @param unit unit of the values
 * @param frequency publication frequency
 * @param producer institution that computes the figure
 * @param notes caveats the agent must respect when using the numbers
 */
public record SeriesDefinition(
        String alias,
        int sgsCode,
        String name,
        SeriesKind kind,
        Unit unit,
        Frequency frequency,
        String producer,
        String notes) {

    private static final String SERIES_PAGE =
            "https://www3.bcb.gov.br/sgspub/consultarvalores/consultarValoresSeries.do"
                    + "?method=consultarGraficoPorId&hdOidSeriesSelecionadas=";

    @JsonProperty
    public boolean compoundable() {
        return kind.compoundable();
    }

    /** Human-readable page of the series in the SGS web interface. */
    @JsonProperty
    public String seriesPage() {
        return SERIES_PAGE + sgsCode;
    }
}
