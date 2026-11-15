package io.aegis.persistence;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Shared content-addressing helper. */
public final class OracleArtifactDigest {
    private OracleArtifactDigest() {}
    public static String sha256(byte[] content) {
        if (content == null) throw new IllegalArgumentException("content is required");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
