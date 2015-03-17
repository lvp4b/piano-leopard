package net.kreatious.pianoleopard.history;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.Optional;

import net.kreatious.pianoleopard.midi.InputModel;
import net.kreatious.pianoleopard.midi.OutputModel;

/**
 * Provides a history of user events.
 *
 * @author Jay-R Studer
 */
public class History {
    private final Optional<LogWriter> writer;

    private History(Optional<LogWriter> writer) {
        this.writer = writer;
    }

    /**
     * Creates a new {@link History} that manages historical events generated by
     * the user.
     *
     * @param log
     *            the log file to read and write from.
     * @param outputModel
     *            the {@link OutputModel} to listen to
     * @param inputModel
     *            the {@link InputModel} to listen to
     * @return a new functional {@link History} object if the log file can be
     *         written to, otherwise a new {@link History} object that does
     *         nothing.
     */
    public static History create(File log, OutputModel outputModel, InputModel inputModel) {
        try {
            // channel is closed when output model is closed
            @SuppressWarnings("resource")
            final FileChannel channel = new RandomAccessFile(log, "rw").getChannel();
            outputModel.addCloseable(channel);

            final Optional<FileLock> lock = Optional.ofNullable(channel.tryLock());
            if (!lock.isPresent()) {
                return new History(Optional.empty());
            }

            return new History(LogWriter.create(channel, outputModel, inputModel));
        } catch (final IOException e) {
            e.printStackTrace();
            return new History(Optional.empty());
        }
    }

    /**
     * Starts parsing the log file with the specified visitor, if the log file
     * exists.
     * <p>
     * This method blocks until the log file is completely parsed, during which
     * time visitor methods are called from the context of the current thread.
     * After this method has returned, the visitor's methods will be called from
     * various threads. It is guaranteed that two visitor's methods will not be
     * concurrently executed.
     * <p>
     * If the log file is unavailable, this method returns immediately and no
     * methods on the visitor will be called.
     *
     * @param visitor
     *            the visitor to register
     * @return nothing. Used to cause Java to select the callable overload when
     *         used in a lambda expression.
     * @throws IOException
     *             if an I/O error occurs
     */
    public Void startReading(HistoryVisitor visitor) throws IOException {
        if (writer.isPresent()) {
            writer.get().startReading(visitor);
        }
        return null;
    }

    /**
     * Stops reading the log file with the specified visitor.
     *
     * @param visitor
     *            the visitor to unregister
     */
    public void stopReading(HistoryVisitor visitor) {
        writer.ifPresent(w -> w.stopReading(visitor));
    }
}