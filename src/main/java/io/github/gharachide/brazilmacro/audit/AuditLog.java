package io.github.gharachide.brazilmacro.audit;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Append-only JSON Lines record of every tool call. Each entry stores the SHA-256 of the
 * previous entry and its own hash, so editing, reordering or deleting a line breaks the chain
 * and is reported by {@link AuditVerifier}. Results are stored as a digest, not in full.
 */
public final class AuditLog implements AutoCloseable {

    /** What was written, for callers that want to log or expose the chain head. */
    public record Appended(long seq, String hash) {
    }

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    private static final int TAIL_BYTES = 64 * 1024;

    private final Path path;
    private final Clock clock;
    private final FileChannel channel;
    private long seq;
    private String lastHash;

    public AuditLog(Path path, Clock clock) throws IOException {
        this.path = path.toAbsolutePath();
        this.clock = clock;
        Path parent = this.path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ObjectNode last = lastEntry(this.path);
        this.seq = last == null ? 0 : last.path("seq").asLong();
        this.lastHash = last == null ? AuditHashing.GENESIS : last.path("hash").asString();
        this.channel = FileChannel.open(this.path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        if (!endsWithNewline(this.path)) {
            // Start new entries on their own line after an interrupted write.
            channel.write(ByteBuffer.wrap("\n".getBytes(StandardCharsets.UTF_8)));
        }
        log.info("Audit log {} (continuing after entry {})", this.path, seq);
    }

    public synchronized Appended append(String tool, Map<String, ?> arguments, String outcome, String errorCode,
            String resultSha256, long durationMs, String traceId) throws IOException {
        long next = seq + 1;
        ObjectNode entry = AuditHashing.MAPPER.createObjectNode();
        entry.put("seq", next);
        entry.put("ts", clock.instant().truncatedTo(ChronoUnit.MILLIS).toString());
        entry.put("tool", tool);
        entry.set("arguments", AuditHashing.MAPPER.valueToTree(arguments));
        entry.put("outcome", outcome);
        entry.put("errorCode", errorCode);
        entry.put("resultSha256", resultSha256);
        entry.put("durationMs", durationMs);
        entry.put("traceId", traceId);
        entry.put("prevHash", lastHash);
        String hash = AuditHashing.entryHash(entry);
        entry.put("hash", hash);
        byte[] line = (AuditHashing.MAPPER.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.wrap(line);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        channel.force(false);
        seq = next;
        lastHash = hash;
        return new Appended(next, hash);
    }

    public Path path() {
        return path;
    }

    @Override
    public synchronized void close() throws IOException {
        channel.close();
    }

    private static boolean endsWithNewline(Path path) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            if (file.length() == 0) {
                return true;
            }
            file.seek(file.length() - 1);
            return file.read() == '\n';
        }
    }

    /** Reads the last complete entry so that a restarted server continues the same chain. */
    private static ObjectNode lastEntry(Path path) throws IOException {
        if (!Files.exists(path) || Files.size(path) == 0) {
            return null;
        }
        String tail;
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            long length = file.length();
            long start = Math.max(0, length - TAIL_BYTES);
            byte[] bytes = new byte[(int) (length - start)];
            file.seek(start);
            file.readFully(bytes);
            tail = new String(bytes, StandardCharsets.UTF_8);
        }
        String[] lines = tail.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            try {
                JsonNode node = AuditHashing.MAPPER.readTree(line);
                if (node instanceof ObjectNode entry && entry.path("seq").isIntegralNumber()
                        && entry.path("hash").isString()) {
                    if (i != lines.length - 1 && !lines[lines.length - 1].isBlank()) {
                        log.warn("Audit log {} ends with an incomplete line; the verifier will report it", path);
                    }
                    return entry;
                }
            } catch (JacksonException e) {
                // A crash can leave a partial last line; keep looking for the last complete entry.
            }
        }
        log.warn("Audit log {} has no readable entry in its last {} bytes; starting a new chain", path, TAIL_BYTES);
        return null;
    }
}
