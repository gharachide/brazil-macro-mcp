package io.github.gharachide.brazilmacro.audit;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Recomputes the hash chain of an audit log. Detects edited, reordered, inserted and deleted
 * entries. Truncation of the newest entries is only detectable against a chain head recorded
 * elsewhere, which is why the result reports {@code lastHash}.
 */
public final class AuditVerifier {

    /**
     * @param valid whether every entry is intact and correctly linked
     * @param entries number of entries checked
     * @param lastHash hash of the last valid entry, to anchor externally
     * @param firstInvalidLine 1-based line of the first problem, or 0
     * @param problem description of the first problem, or null
     */
    public record Result(boolean valid, long entries, String lastHash, long firstInvalidLine, String problem) {
    }

    private AuditVerifier() {
    }

    public static Result verify(Path path) throws IOException {
        String previousHash = AuditHashing.GENESIS;
        long previousSeq = 0;
        long lineNumber = 0;
        long entries = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                ObjectNode entry;
                try {
                    JsonNode node = AuditHashing.MAPPER.readTree(line);
                    if (!(node instanceof ObjectNode object)) {
                        return invalid(entries, previousHash, lineNumber, "not a JSON object");
                    }
                    entry = object;
                } catch (JacksonException e) {
                    return invalid(entries, previousHash, lineNumber, "not valid JSON (incomplete or edited line)");
                }
                long seq = entry.path("seq").asLong(-1);
                if (seq != previousSeq + 1) {
                    return invalid(entries, previousHash, lineNumber,
                            "expected seq " + (previousSeq + 1) + " but found " + seq + " (entries deleted or reordered)");
                }
                if (!previousHash.equals(entry.path("prevHash").asString(""))) {
                    return invalid(entries, previousHash, lineNumber, "prevHash does not match the previous entry");
                }
                String hash = entry.path("hash").asString("");
                if (!AuditHashing.entryHash(entry).equals(hash)) {
                    return invalid(entries, previousHash, lineNumber, "hash mismatch (the entry was modified)");
                }
                previousSeq = seq;
                previousHash = hash;
                entries++;
            }
        }
        return new Result(true, entries, previousHash, 0, null);
    }

    private static Result invalid(long entries, String lastHash, long line, String problem) {
        return new Result(false, entries, lastHash, line, problem);
    }
}
