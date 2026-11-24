package io.github.gharachide.brazilmacro.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Canonical form and hashing shared by the writer and the verifier of the audit chain. */
final class AuditHashing {

    /** {@code prevHash} of the first entry. */
    static final String GENESIS = "0".repeat(64);

    /** Compact JSON with insertion order preserved: the same bytes on write and on verify. */
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private AuditHashing() {
    }

    /** SHA-256 of the entry serialized without its {@code hash} field. */
    static String entryHash(ObjectNode entry) {
        ObjectNode withoutHash = entry.deepCopy();
        withoutHash.remove("hash");
        return sha256Hex(MAPPER.writeValueAsString(withoutHash));
    }

    static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
