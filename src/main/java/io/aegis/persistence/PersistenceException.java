package io.aegis.persistence;

/** Wraps persistence failures without leaking credentials or connection strings. */
public final class PersistenceException extends RuntimeException {
    public PersistenceException(String message) { super(message); }
    public PersistenceException(String message, Throwable cause) { super(message, cause); }
}
