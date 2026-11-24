package io.github.gharachide.brazilmacro.audit;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** {@code --verify-audit <file>}: checks an audit log and exits 0 (intact), 1 (tampered) or 2 (error). */
public final class AuditVerifierCli {

    private static final String FLAG = "--verify-audit";

    private AuditVerifierCli() {
    }

    public static Optional<String> auditFileArgument(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals(FLAG) && i + 1 < args.length) {
                return Optional.of(args[i + 1]);
            }
            if (args[i].startsWith(FLAG + "=")) {
                return Optional.of(args[i].substring(FLAG.length() + 1));
            }
        }
        return Optional.empty();
    }

    public static int run(String file, PrintStream out) {
        Path path = Path.of(file);
        if (!Files.isRegularFile(path)) {
            out.println("Audit file not found: " + path);
            return 2;
        }
        try {
            AuditVerifier.Result result = AuditVerifier.verify(path);
            if (result.valid()) {
                out.println("OK: " + result.entries() + " entries, hash chain intact. Last hash: " + result.lastHash());
                return 0;
            }
            out.println("TAMPERED: line " + result.firstInvalidLine() + ": " + result.problem()
                    + ". Entries verified before it: " + result.entries() + ".");
            return 1;
        } catch (IOException e) {
            out.println("Cannot read " + path + ": " + e.getMessage());
            return 2;
        }
    }
}
