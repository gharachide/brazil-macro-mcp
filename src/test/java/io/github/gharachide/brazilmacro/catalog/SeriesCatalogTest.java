package io.github.gharachide.brazilmacro.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gharachide.brazilmacro.error.ErrorCode;
import io.github.gharachide.brazilmacro.error.ToolException;
import org.junit.jupiter.api.Test;

class SeriesCatalogTest {

    @Test
    void aliasesAndCodesAreUnique() {
        assertThat(SeriesCatalog.all()).extracting(SeriesDefinition::alias).doesNotHaveDuplicates();
        assertThat(SeriesCatalog.all()).extracting(SeriesDefinition::sgsCode).doesNotHaveDuplicates();
    }

    @Test
    void lookupIgnoresCaseAndSpaces() {
        assertThat(SeriesCatalog.require(" ipca ").sgsCode()).isEqualTo(433);
        assertThat(SeriesCatalog.require("cdi_monthly").sgsCode()).isEqualTo(4391);
    }

    @Test
    void unknownAliasListsTheValidOnes() {
        assertThatThrownBy(() -> SeriesCatalog.require("SELIC"))
                .isInstanceOf(ToolException.class)
                .satisfies(e -> assertThat(((ToolException) e).code()).isEqualTo(ErrorCode.INVALID_ARGUMENT))
                .hasMessageContaining("SELIC_TARGET")
                .hasMessageContaining("SELIC_MONTHLY");
    }

    @Test
    void onlyMonthlyRatesAndPriceIndicesAreCompoundable() {
        assertThat(SeriesCatalog.all())
                .filteredOn(SeriesDefinition::compoundable)
                .extracting(SeriesDefinition::alias)
                .containsExactlyInAnyOrder("IPCA", "INPC", "IGPM", "SELIC_MONTHLY", "CDI_MONTHLY");
        assertThat(SeriesCatalog.all())
                .filteredOn(SeriesDefinition::compoundable)
                .allSatisfy(d -> assertThat(d.frequency()).isEqualTo(Frequency.MONTHLY));
    }

    @Test
    void everySeriesLinksToItsSgsPage() {
        assertThat(SeriesCatalog.require("IPCA").seriesPage()).endsWith("hdOidSeriesSelecionadas=433");
    }

    @Test
    void focusIndicatorsAcceptCommonSpellings() {
        assertThat(FocusIndicator.require("pib total")).isEqualTo(FocusIndicator.PIB_TOTAL);
        assertThat(FocusIndicator.require("IGP-M")).isEqualTo(FocusIndicator.IGPM);
        assertThat(FocusIndicator.CAMBIO.apiName()).isEqualTo("Câmbio");
        assertThatThrownBy(() -> FocusIndicator.require("PIB")).isInstanceOf(ToolException.class);
    }
}
