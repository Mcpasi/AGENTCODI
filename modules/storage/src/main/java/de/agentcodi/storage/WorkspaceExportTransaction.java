package de.agentcodi.storage;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Couples export preparation, destination writes, and destination rollback.
 *
 * <p>An Android document already exists when {@code ACTION_CREATE_DOCUMENT}
 * returns. Consequently, every failure in preparation, writing, flushing, or
 * closing must roll that document back even when no destination stream was
 * opened yet.</p>
 */
public final class WorkspaceExportTransaction {
    private WorkspaceExportTransaction() {
    }

    public static <Prepared, Result> Result execute(
        Destination destination,
        Preparation<Prepared> preparation,
        Writer<Prepared, Result> writer
    ) throws IOException {
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null");
        }
        if (preparation == null) {
            throw new IllegalArgumentException("preparation must not be null");
        }
        if (writer == null) {
            throw new IllegalArgumentException("writer must not be null");
        }
        try {
            Prepared prepared = preparation.prepare();
            OutputStream opened = destination.open();
            if (opened == null) {
                throw new IOException("Export destination did not open an output stream");
            }
            try (OutputStream output = opened) {
                return writer.write(prepared, output);
            }
        } catch (IOException failure) {
            rollbackAfterFailure(destination, failure);
            throw failure;
        } catch (RuntimeException failure) {
            rollbackAfterFailure(destination, failure);
            throw failure;
        } catch (Error failure) {
            rollbackAfterFailure(destination, failure);
            throw failure;
        }
    }

    private static void rollbackAfterFailure(
        Destination destination,
        Throwable failure
    ) {
        try {
            destination.rollback();
        } catch (Throwable rollbackFailure) {
            if (rollbackFailure != failure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }

    public interface Destination {
        OutputStream open() throws IOException;

        void rollback() throws IOException;
    }

    public interface Preparation<Prepared> {
        Prepared prepare() throws IOException;
    }

    public interface Writer<Prepared, Result> {
        Result write(Prepared prepared, OutputStream destination) throws IOException;
    }
}
