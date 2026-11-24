package io.github.gharachide.brazilmacro.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gharachide.brazilmacro.support.MutableClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditLogTest {

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-24T12:00:00Z"));

    private Path writeEntries(int count) throws IOException {
        Path file = dir.resolve("audit.jsonl");
        try (AuditLog log = new AuditLog(file, clock)) {
            for (int i = 0; i < count; i++) {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("series", "IPCA");
                args.put("count", i == 0 ? null : i);
                log.append("get_latest", args, "ok", null, "ab".repeat(32), 12, "4bf92f3577b34da6a3ce929d0e0e4736");
            }
        }
        return file;
    }

    @Test
    void anIntactChainVerifies() throws IOException {
        Path file = writeEntries(3);

        AuditVerifier.Result result = AuditVerifier.verify(file);

        assertThat(result.valid()).isTrue();
        assertThat(result.entries()).isEqualTo(3);
        assertThat(result.lastHash()).hasSize(64);
        assertThat(Files.readAllLines(file).getFirst())
                .contains("\"seq\":1").contains("\"prevHash\":\"" + "0".repeat(64) + "\"");
    }

    @Test
    void detectsAnEditedEntry() throws IOException {
        Path file = writeEntries(3);
        List<String> lines = new ArrayList<>(Files.readAllLines(file));
        lines.set(1, lines.get(1).replace("\"outcome\":\"ok\"", "\"outcome\":\"error\""));
        Files.write(file, lines);

        AuditVerifier.Result result = AuditVerifier.verify(file);

        assertThat(result.valid()).isFalse();
        assertThat(result.firstInvalidLine()).isEqualTo(2);
        assertThat(result.problem()).contains("hash mismatch");
    }

    @Test
    void detectsADeletedEntry() throws IOException {
        Path file = writeEntries(3);
        List<String> lines = new ArrayList<>(Files.readAllLines(file));
        lines.remove(1);
        Files.write(file, lines);

        AuditVerifier.Result result = AuditVerifier.verify(file);

        assertThat(result.valid()).isFalse();
        assertThat(result.problem()).contains("expected seq 2");
    }

    @Test
    void detectsReorderedEntries() throws IOException {
        Path file = writeEntries(3);
        List<String> lines = new ArrayList<>(Files.readAllLines(file));
        Files.write(file, List.of(lines.get(0), lines.get(2), lines.get(1)));

        assertThat(AuditVerifier.verify(file).valid()).isFalse();
    }

    @Test
    void aRestartedServerContinuesTheSameChain() throws IOException {
        Path file = writeEntries(2);
        try (AuditLog log = new AuditLog(file, clock)) {
            AuditLog.Appended appended = log.append("list_series", Map.of(), "ok", null, "cd".repeat(32), 1, null);
            assertThat(appended.seq()).isEqualTo(3);
        }

        assertThat(AuditVerifier.verify(file).entries()).isEqualTo(3);
        assertThat(AuditVerifier.verify(file).valid()).isTrue();
    }

    @Test
    void anIncompleteLastLineIsReportedAndDoesNotBlockAppends() throws IOException {
        Path file = writeEntries(2);
        Files.writeString(file, "{\"seq\":3,\"ts\":\"2026-", java.nio.file.StandardOpenOption.APPEND);

        try (AuditLog log = new AuditLog(file, clock)) {
            assertThat(log.append("list_series", Map.of(), "ok", null, "ef".repeat(32), 1, null).seq()).isEqualTo(3);
        }

        AuditVerifier.Result result = AuditVerifier.verify(file);
        assertThat(result.valid()).isFalse();
        assertThat(result.firstInvalidLine()).isEqualTo(3);
        assertThat(result.entries()).isEqualTo(2);
    }

    @Test
    void cliExitCodesTellIntactFromTampered() throws IOException {
        Path file = writeEntries(2);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThat(AuditVerifierCli.run(file.toString(), new PrintStream(out, true, StandardCharsets.UTF_8))).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).startsWith("OK: 2 entries");

        Files.writeString(file, Files.readString(file).replace("get_latest", "get_series"));
        assertThat(AuditVerifierCli.run(file.toString(), new PrintStream(new ByteArrayOutputStream()))).isEqualTo(1);
        assertThat(AuditVerifierCli.run(dir.resolve("missing.jsonl").toString(),
                new PrintStream(new ByteArrayOutputStream()))).isEqualTo(2);
    }

    @Test
    void parsesTheCliFlag() {
        assertThat(AuditVerifierCli.auditFileArgument(new String[] {"--verify-audit", "a.jsonl"})).contains("a.jsonl");
        assertThat(AuditVerifierCli.auditFileArgument(new String[] {"--verify-audit=b.jsonl"})).contains("b.jsonl");
        assertThat(AuditVerifierCli.auditFileArgument(new String[] {"--spring.profiles.active=stdio"})).isEmpty();
    }
}
