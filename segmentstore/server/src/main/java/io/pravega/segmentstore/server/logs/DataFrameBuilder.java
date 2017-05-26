/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.segmentstore.server.logs;

import com.google.common.base.Preconditions;
import io.pravega.common.ExceptionHelpers;
import io.pravega.common.Exceptions;
import io.pravega.common.ObjectClosedException;
import io.pravega.common.util.ArrayView;
import io.pravega.common.util.OrderedItemProcessor;
import io.pravega.segmentstore.server.LogItem;
import io.pravega.segmentstore.storage.DurableDataLog;
import io.pravega.segmentstore.storage.LogAddress;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds DataFrames from LogItems. Splits the serialization of LogItems across multiple Data Frames, if necessary,
 * and publishes the finished Data Frames to the given DataFrameLog.
 */
@Slf4j
@ThreadSafe
class DataFrameBuilder<T extends LogItem> implements AutoCloseable {
    //region Members

    @GuardedBy("this")
    private final DataFrameOutputStream outputStream;
    private final OrderedItemProcessor<ArrayView, LogAddress> frameProcessor;
    private final Args args;
    @GuardedBy("this")
    private boolean closed;
    @GuardedBy("this")
    private long lastSerializedSequenceNumber;
    @GuardedBy("this")
    private long lastStartedSequenceNumber;
    private final AtomicReference<Throwable> failureCause;

    //endregion

    //region Constructor

    /**
     * Creates a new instance of the DataFrameBuilder class.
     *
     * @param targetLog     A Function that, given a DataFrame, commits that DataFrame to a DurableDataLog and returns
     *                      a Future that indicates when the operation completes or errors out.
     * @param args          Arguments for the Builder.
     * @throws NullPointerException If any of the arguments are null.
     */
    DataFrameBuilder(DurableDataLog targetLog, Args args) {
        this.args = Preconditions.checkNotNull(args, "args");
        Preconditions.checkNotNull(args.commitSuccess, "args.commitSuccess");
        Preconditions.checkNotNull(args.commitFailure, "args.commitFailure");
        this.outputStream = new DataFrameOutputStream(targetLog.getMaxAppendLength(), this::handleDataFrameComplete);
        this.frameProcessor = new OrderedItemProcessor<>(args.maxWriteCapacity, createAppender(targetLog), args.executor);
        this.lastSerializedSequenceNumber = -1;
        this.lastStartedSequenceNumber = -1;
        this.failureCause = new AtomicReference<>();
    }

    private Function<ArrayView, CompletableFuture<LogAddress>> createAppender(DurableDataLog targetLog) {
        return dataFrameContents -> targetLog.append(dataFrameContents, this.args.writeTimeout);
    }

    //endregion

    //region AutoCloseable Implementation

    @Override
    public synchronized void close() {
        if (!this.closed) {
            this.closed = true;

            // Seal & ship whatever frame we currently have (if any).
            if (!this.outputStream.isClosed()) {
                this.outputStream.flush();
            }

            // Close the Frame Processor. This waits until all pending frames have been committed.
            this.frameProcessor.close();

            // Close the underlying stream (which destroys whatever we have in flight - but there shouldn't be any at this point).
            this.outputStream.close();
        }
    }

    //endregion

    //region Operations

    /**
     * Forces a flush of the current DataFrame. This should be invoked if there are no more items to add to the current
     * DataFrame, but it is desired to have its outstanding contents flushed to the underlying DurableDataLog.
     */
    synchronized void flush() {
        Exceptions.checkNotClosed(this.closed, this);
        this.outputStream.flush();
    }

    /**
     * If in a failed state (and thus closed), returns the original exception that caused the failure.
     *
     * @return The causing exception, or null if none.
     */
    Throwable failureCause() {
        return this.failureCause.get();
    }

    /**
     * Appends a LogItem to the DataFrameBuilder. If any exceptions happened during serialization, whatever contents was
     * written to the DataFrame will be discarded. Note that if a LogItem spans multiple DataFrames, in case of failure,
     * the content serialized to already committed DataFrames will not be discarded. That case will have to be dealt with
     * upon reading DataFrames from the DataFrameLog.
     * <p/>
     * Any exceptions that resulted from the Data Frame failing to commit will be routed through the dataFrameCommitFailureCallback
     * callback, as well as being thrown from this exception.
     *
     * @param logItem The LogItem to append.
     * @throws NullPointerException If logItem is null.
     * @throws IllegalArgumentException If attempted to add LogItems out of order (based on Sequence Number).
     * @throws IOException          If the LogItem failed to serialize to the DataLog, or if one of the DataFrames containing
     *                              the LogItem failed to commit to the DataFrameLog.
     * @throws ObjectClosedException If the DataFrameBuilder is closed (or in in a failed state) and cannot be used anymore.
     */
    synchronized void append(T logItem) throws IOException {
        Exceptions.checkNotClosed(this.closed, this);
        long seqNo = logItem.getSequenceNumber();
        Exceptions.checkArgument(this.lastSerializedSequenceNumber < seqNo, "logItem",
                "Invalid sequence number. Expected: greater than %d, given: %d.", this.lastSerializedSequenceNumber, seqNo);

        // Remember the last Started SeqNo, in case of failure.
        long previousLastStartedSequenceNumber = this.lastStartedSequenceNumber;
        try {
            // Indicate to the output stream that are about to write a new record.
            this.outputStream.startNewRecord();

            // Completely serialize the entry. Note that this may span more than one Data Frame.
            this.lastStartedSequenceNumber = seqNo;
            logItem.serialize(this.outputStream);

            // Indicate to the output stream that have finished writing the record.
            this.outputStream.endRecord();
            this.lastSerializedSequenceNumber = seqNo;
        } catch (Exception ex) {
            if (ex instanceof ObjectClosedException) {
                // OrderedItemProcessor has closed (most likely due to a DataFrame commit failure. We need to close as well.
                close();
            } else {
                // Discard any information that we have about this record (pretty much revert back to where startNewEntry()
                // would have begun writing).
                this.outputStream.discardRecord();
                this.lastStartedSequenceNumber = previousLastStartedSequenceNumber;
            }

            throw ex;
        }
    }

    /**
     * Publishes a data frame to the DataFrameLog. The outcome of the publish operation, whether success or failure, is
     * routed to the appropriate callback handlers given in this constructor. This method is called synchronously by the
     * DataFrameOutputStream, via the LogItem.serialize() method through the append() method, and as such, its execution
     * is synchronized on this object's instance.
     *
     * @param dataFrame The data frame to publish.
     * @throws NullPointerException     If the data frame is null.
     * @throws IllegalArgumentException If the data frame is not sealed.
     */
    @GuardedBy("this")
    private void handleDataFrameComplete(DataFrame dataFrame) {
        Exceptions.checkArgument(dataFrame.isSealed(), "dataFrame", "Cannot publish a non-sealed DataFrame.");

        // Write DataFrame to DataFrameLog.
        DataFrameCommitArgs commitArgs = new DataFrameCommitArgs(this.lastSerializedSequenceNumber, this.lastStartedSequenceNumber, dataFrame.getLength());

        try {
            this.args.beforeCommit.accept(commitArgs);
            this.frameProcessor
                    .process(dataFrame.getData())
                    .thenAcceptAsync(logAddress -> {
                        commitArgs.setLogAddress(logAddress);
                        this.args.commitSuccess.accept(commitArgs);
                    }, this.args.executor)
                    .exceptionally(ex -> handleProcessingException(ex, commitArgs));
        } catch (Throwable ex) {
            handleProcessingException(ex, commitArgs);

            // Even though we invoked the dataFrameCommitFailureCallback() - which was for the DurableLog to handle,
            // we still need to fail the current call, which most likely leads to failing the LogItem that triggered this.
            throw ex;
        }
    }

    private Void handleProcessingException(Throwable ex, DataFrameCommitArgs commitArgs) {
        // This failure is due to us being unable to commit a DataFrame, whether synchronously or via a callback. The
        // DataFrameBuilder cannot recover from this; as such it will close and will leave it to the caller to handle
        // the failure.
        ex = ExceptionHelpers.getRealException(ex);
        if (!(ex instanceof ObjectClosedException)) {
            // This is usually from a subsequent call. We want to store the actual failure cause.
            this.failureCause.compareAndSet(null, ex);
        }

        this.args.commitFailure.accept(ex, commitArgs);
        close();
        return null;
    }

    //endregion

    //region DataFrameCommitArgs

    /**
     * Contains Information about the committal of a DataFrame.
     */
    static class DataFrameCommitArgs {
        /**
         * The Sequence Number of the last LogItem that was fully serialized (and committed).
         * If this value is different than 'getLastStartedSequenceNumber' then we currently have a LogItem that was split
         * across multiple Data Frames, and the value returned from that function represents the Sequence Number for that entry.
         */
        @Getter
        private final long lastFullySerializedSequenceNumber;

        /**
         * The Sequence Number of the last LogItem that was started (but not necessarily committed).
         * If this value is different than 'getLastFullySerializedSequenceNumber' then we currently have a LogItem that was split
         * across multiple Data Frames, and the value returned from this function represents the Sequence Number for that entry.
         */
        @Getter
        private final long lastStartedSequenceNumber;

        private final AtomicReference<LogAddress> logAddress;

        /**
         * The length of the DataFrame that was just committed.
         */
        @Getter
        private final int dataFrameLength;

        /**
         * Creates a new instance of the DataFrameCommitArgs class.
         *
         * @param lastFullySerializedSequenceNumber The Sequence Number of the last LogItem that was fully serialized (and committed).
         * @param lastStartedSequenceNumber         The Sequence Number of the last LogItem that was started (but not necessarily committed).
         * @param dataFrameLength                   The length of the DataFrame that is to be committed.
         */
        private DataFrameCommitArgs(long lastFullySerializedSequenceNumber, long lastStartedSequenceNumber, int dataFrameLength) {
            assert lastFullySerializedSequenceNumber <= lastStartedSequenceNumber : "lastFullySerializedSequenceNumber (" +
                    lastFullySerializedSequenceNumber + ") is greater than lastStartedSequenceNumber (" + lastStartedSequenceNumber + ")";

            this.lastFullySerializedSequenceNumber = lastFullySerializedSequenceNumber;
            this.lastStartedSequenceNumber = lastStartedSequenceNumber;
            this.dataFrameLength = dataFrameLength;
            this.logAddress = new AtomicReference<>();
        }

        /**
         * Gets a value representing the LogAddress of the Data Frame that was committed.
         */
        LogAddress getLogAddress() {
            return this.logAddress.get();
        }

        private void setLogAddress(LogAddress address) {
            this.logAddress.set(address);
        }

        @Override
        public String toString() {
            return String.format("LastFullySerializedSN = %d, LastStartedSN = %d, Address = %s, Length = %d",
                    getLastFullySerializedSequenceNumber(), getLastStartedSequenceNumber(), this.logAddress, getDataFrameLength());
        }
    }

    //endregion

    //region Args

    @RequiredArgsConstructor
    static class Args {
        final int maxWriteCapacity;

        /**
         * A Callback that will be invoked synchronously upon a DataFrame's sealing, and right before it is about to be
         * submitted to the DurableDataLog processor. The invocation of this method does not imply that the DataFrame
         * has been successfully committed, or even attempted to be committed.
         */
        final Consumer<DataFrameCommitArgs> beforeCommit;

        /**
         * A Callback that will be invoked asynchronously upon every successful commit of a Data Frame. When this is
         * called, all entries added via append() that have a Sequence Number less than or equal to the arg's
         * LastFullySerializedSequenceNumber have been committed. Any entry with a Sequence Number higher than that
         * is not yet committed.
         */
        final Consumer<DataFrameCommitArgs> commitSuccess;

        /**
         * A Callback that will be invoked asynchronously upon a failed commit of a Data Frame. When this is called, all
         * entries added via append() that have a sequence number up to, and including, LastStartedSequenceNumber that
         * have not previously been acknowledged, should be failed.
         */
        final BiConsumer<Throwable, DataFrameCommitArgs> commitFailure;
        final Executor executor;
        final Duration writeTimeout = Duration.ofSeconds(30); // TODO: actual timeout.
    }

    //endregion
}