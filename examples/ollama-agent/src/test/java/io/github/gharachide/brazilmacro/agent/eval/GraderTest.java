package io.github.gharachide.brazilmacro.agent.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gharachide.brazilmacro.agent.MacroAgent.ToolCall;
import io.github.gharachide.brazilmacro.agent.MacroAgent.Turn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GraderTest {

    private static final EvalCase ACCUMULATED = new EvalCase("acc", "accumulated",
            "Qual foi o IPCA acumulado de {LAST_YEAR}?", List.of("accumulated_change"),
            Map.of("series", "IPCA", "startMonth", "{LAST_YEAR}-01"), false).resolve(LocalDate.of(2026, 11, 24));

    private static final String RESULT = "[{\"type\":\"text\",\"text\":\"{\\\"accumulatedPercent\\\":4.8312,"
            + "\\\"months\\\":12,\\\"citation\\\":{\\\"citeAs\\\":\\\"Banco Central do Brasil, SGS series 433\\\"}}\"}]";

    @Test
    void placeholdersResolveToYearsRelativeToToday() {
        assertThat(ACCUMULATED.question()).isEqualTo("Qual foi o IPCA acumulado de 2025?");
        assertThat(ACCUMULATED.expectArgs()).containsEntry("startMonth", "2025-01");
    }

    @Test
    void aRoundedCitedAnswerFromTheToolPasses() {
        Turn turn = turn("O IPCA acumulou 4,83% em 2025 (Banco Central do Brasil, SGS series 433).",
                new ToolCall("accumulated_change", "{\"series\":\"ipca\",\"startMonth\":\"2025-01\",\"endMonth\":\"2025-12\"}",
                        RESULT, false));

        Grader.Grade grade = Grader.grade(ACCUMULATED, turn);

        assertThat(grade.passed()).isTrue();
    }

    @Test
    void numbersTheToolNeverReturnedAreUngrounded() {
        Turn turn = turn("O IPCA acumulou 4,91% em 2025, fonte SGS.",
                new ToolCall("accumulated_change", "{\"series\":\"IPCA\",\"startMonth\":\"2025-01\"}", RESULT, false));

        Grader.Grade grade = Grader.grade(ACCUMULATED, turn);

        assertThat(grade.grounded()).isFalse();
        assertThat(grade.ungrounded()).containsExactly("4,91");
        assertThat(grade.passed()).isFalse();
    }

    @Test
    void doingTheArithmeticWithoutTheToolFailsToolAndGrounding() {
        Turn turn = turn("Somando os meses, o IPCA de 2025 foi 4,70% (SGS).",
                new ToolCall("get_series", "{\"series\":\"IPCA\"}", "[{\"value\":0.16},{\"value\":1.31}]", false));

        Grader.Grade grade = Grader.grade(ACCUMULATED, turn);

        assertThat(grade.tool()).isFalse();
        assertThat(grade.grounded()).isFalse();
    }

    @Test
    void wrongArgumentsFail() {
        Turn turn = turn("4,83% (SGS)", new ToolCall("accumulated_change",
                "{\"series\":\"INPC\",\"startMonth\":\"2025-01\"}", RESULT, false));

        assertThat(Grader.grade(ACCUMULATED, turn).args()).isFalse();
    }

    @Test
    void aMissingCitationFails() {
        Turn turn = turn("Foi 4,83%.", new ToolCall("accumulated_change",
                "{\"series\":\"IPCA\",\"startMonth\":\"2025-01\"}", RESULT, false));

        Grader.Grade grade = Grader.grade(ACCUMULATED, turn);

        assertThat(grade.cited()).isFalse();
        assertThat(grade.passed()).isFalse();
    }

    @Test
    void trapsPassWhenTheModelDeclinesAndFailWhenItInventsAFigure() {
        EvalCase trap = new EvalCase("trap", "abstain", "Qual foi o IPCA de dezembro de 2030?", null, null, true);

        Turn declined = turn("Não há dado do IPCA para dezembro de 2030: o período ainda não ocorreu.",
                new ToolCall("get_series", "{}", "No data for IPCA in that range.", true));
        Turn invented = turn("O IPCA de dezembro de 2030 deve ficar em 0,35%.");

        assertThat(Grader.grade(trap, declined).passed()).isTrue();
        Grader.Grade bad = Grader.grade(trap, invented);
        assertThat(bad.abstained()).isFalse();
        assertThat(bad.grounded()).isFalse();
    }

    @Test
    void anAlternativeCorrectCallIsAccepted() {
        EvalCase twelveMonths = new EvalCase("12m", "latest", "IPCA em 12 meses?", List.of("get_latest"),
                Map.of("series", "IPCA_12M"), false,
                List.of(new EvalCase.Alternative("accumulated_change", Map.of("series", "IPCA"))));
        Turn compounded = turn("4,83% (SGS)", new ToolCall("accumulated_change",
                "{\"series\":\"IPCA\",\"startMonth\":\"2025-11\"}", RESULT, false));
        Turn wrongSeries = turn("4,83% (SGS)", new ToolCall("accumulated_change",
                "{\"series\":\"INPC\"}", RESULT, false));

        assertThat(Grader.grade(twelveMonths, compounded).passed()).isTrue();
        assertThat(Grader.grade(twelveMonths, wrongSeries).passed()).isFalse();
    }

    @Test
    void aTrapAnsweredWithTheFocusProjectionInsteadOfAnInventedValuePasses() {
        EvalCase trap = new EvalCase("trap", "abstain", "Qual foi o IPCA de dezembro de 2030?", null, null, true,
                List.of(new EvalCase.Alternative("market_expectations", Map.of("indicator", "IPCA", "referenceYear", "2030"))));
        Turn projection = turn("Dezembro de 2030 ainda não ocorreu. A mediana do Focus para 2030 é 3,95% (Banco Central).",
                new ToolCall("market_expectations", "{\"indicator\":\"IPCA\",\"referenceYear\":2030}",
                        "{\"median\":3.95}", false));

        assertThat(Grader.grade(trap, projection).passed()).isTrue();
    }

    @Test
    void withoutToolsACorrectlyRecalledFigurePassesAndAWrongOneIsInvented() {
        EvalCase withSource = new EvalCase("acc", "accumulated", "Qual foi o IPCA acumulado de 2025?",
                List.of("accumulated_change"), Map.of("series", "IPCA"), false);

        Grader.Grade recalled = Grader.gradeBaseline(withSource, turn("Foi 4,83%, segundo o IBGE e o Banco Central."), RESULT);
        Grader.Grade invented = Grader.gradeBaseline(withSource, turn("Foi 4,50%, segundo o Banco Central."), RESULT);
        Grader.Grade noFigure = Grader.gradeBaseline(withSource, turn("Não tenho dados atualizados."), RESULT);

        assertThat(recalled.passed()).isTrue();
        assertThat(recalled.tool()).isNull();
        assertThat(invented.invented()).isTrue();
        assertThat(invented.passed()).isFalse();
        assertThat(noFigure.invented()).isFalse();
        assertThat(noFigure.passed()).isFalse();
    }

    @Test
    void withoutToolsATrapPassesOnlyWithoutAFigure() {
        EvalCase trap = new EvalCase("trap", "abstain", "Qual foi o IPCA de dezembro de 2030?", null, null, true);

        assertThat(Grader.gradeBaseline(trap, turn("Esse mês ainda não aconteceu."), null).passed()).isTrue();
        Grader.Grade guessed = Grader.gradeBaseline(trap, turn("Deve ficar perto de 0,30%."), null);
        assertThat(guessed.passed()).isFalse();
        assertThat(guessed.invented()).isTrue();
    }

    @Test
    void referenceArgumentsDefaultToTheExpectedOnesAndResolveYears() {
        assertThat(ACCUMULATED.referenceArgs()).isEqualTo(ACCUMULATED.expectArgs());
        assertThat(ACCUMULATED.referenceTool()).isEqualTo("accumulated_change");
        EvalCase series = EvalRunner.load(null).stream().filter(c -> c.id().equals("series-usd-month")).findFirst()
                .orElseThrow().resolve(LocalDate.of(2026, 11, 24));
        assertThat(series.referenceArgs()).containsEntry("startDate", "2025-10-01").containsEntry("endDate", "2025-10-31");
    }

    @Test
    void readsBrazilianAndInternationalNumberFormats() {
        assertThat(Grader.parse("1.234,56")).isEqualByComparingTo(new BigDecimal("1234.56"));
        assertThat(Grader.parse("13,75")).isEqualByComparingTo(new BigDecimal("13.75"));
        assertThat(Grader.parse("5.4321")).isEqualByComparingTo(new BigDecimal("5.4321"));
        assertThat(Grader.matches(new BigDecimal("0.5"), new BigDecimal("-0.4987"))).isTrue();
    }

    @Test
    void percentilesPickTheNearestRank() {
        assertThat(EvalRunner.percentile(List.of(100L, 200L, 300L, 400L), 50)).isEqualTo(200L);
        assertThat(EvalRunner.percentile(List.of(100L, 200L, 300L, 400L), 95)).isEqualTo(400L);
        assertThat(EvalRunner.percentile(List.of(), 50)).isZero();
    }

    @Test
    void theBundledCasesLoadAndCoverEveryTool() {
        List<EvalCase> cases = EvalRunner.load(null);

        assertThat(cases).hasSizeGreaterThanOrEqualTo(20);
        assertThat(cases).extracting(EvalCase::id).doesNotHaveDuplicates();
        assertThat(cases.stream().flatMap(c -> c.expectTools().stream()).distinct())
                .contains("get_latest", "get_series", "accumulated_change", "real_return", "market_expectations");
        assertThat(cases).filteredOn(EvalCase::isTrap).isNotEmpty();
    }

    private static Turn turn(String answer, ToolCall... calls) {
        return new Turn(answer, List.of(calls), 1000);
    }
}
