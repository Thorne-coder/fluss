/*
 * Copyright (c) 2024 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.fluss.row.arrow;

import com.alibaba.fluss.annotation.Internal;
import com.alibaba.fluss.compression.ArrowCompressionInfo;
import com.alibaba.fluss.memory.AbstractPagedOutputView;
import com.alibaba.fluss.row.InternalRow;
import com.alibaba.fluss.row.arrow.writers.ArrowFieldWriter;
import com.alibaba.fluss.shaded.arrow.org.apache.arrow.memory.BufferAllocator;
import com.alibaba.fluss.shaded.arrow.org.apache.arrow.vector.BaseFixedWidthVector;
import com.alibaba.fluss.shaded.arrow.org.apache.arrow.vector.BaseVariableWidthVector;
import com.alibaba.fluss.shaded.arrow.org.apache.arrow.vector.FieldVector;
import com.alibaba.fluss.shaded.arrow.org.apache.arrow.vector.VectorSchemaRoot;
import com.alibaba.fluss.shaded.arrow.org.apache.arrow.vector.VectorUnloader;
import com.alibaba.fluss.shaded.arrow.org.apache.arrow.vector.compression.CompressionCodec;
import com.alibaba.fluss.shaded.arrow.org.apache.arrow.vector.compression.CompressionUtil;
import com.alibaba.fluss.shaded.arrow.org.apache.arrow.vector.ipc.WriteChannel;
import com.alibaba.fluss.shaded.arrow.org.apache.arrow.vector.ipc.message.ArrowBlock;
import com.alibaba.fluss.shaded.arrow.org.apache.arrow.vector.ipc.message.ArrowRecordBatch;
import com.alibaba.fluss.shaded.arrow.org.apache.arrow.vector.ipc.message.MessageSerializer;
import com.alibaba.fluss.types.RowType;
import com.alibaba.fluss.utils.ArrowUtils;
import com.alibaba.fluss.utils.PagedMemorySegmentWritableChannel;
import com.alibaba.fluss.utils.Preconditions;

import java.io.IOException;

import static com.alibaba.fluss.utils.Preconditions.checkState;

/**
 * Writer which serializes the Fluss rows to Arrow record batches. The {@link ArrowWriter} is pooled
 * in {@link ArrowWriterPool}. See Javadoc of {@link VectorSchemaRoot} for more information about
 * pooling.
 */
@Internal
public class ArrowWriter implements AutoCloseable {
    /**
     * The initial capacity of the vectors which are used to store the rows. The capacity will be
     * expanded automatically if the rows exceed the initial capacity.
     */
    private static final int INITIAL_CAPACITY = 1024;

    /**
     * The buffer usage ratio which is used to determine whether the writer is full. The writer is
     * full if the buffer usage ratio exceeds the threshold.
     */
    public static final double BUFFER_USAGE_RATIO = 0.96;

    /**
     * The identifier of the writer which is used to identify the writer in the {@link
     * ArrowWriterPool}.
     */
    final String writerKey;

    /** Container that holds a set of vectors for the rows. */
    final VectorSchemaRoot root;

    /**
     * An array of writers which are responsible for the serialization of each column of the rows.
     */
    private final ArrowFieldWriter<InternalRow>[] fieldWriters;

    /** The provider which manages the {@link ArrowWriter} instances. */
    private final ArrowWriterProvider provider;

    /**
     * The metadata length of each serialized {@link ArrowRecordBatch} generated by this root. The
     * metadata length should be consistent if the arrow schema is not changed.
     */
    private final int metadataLength;

    private final RowType schema;

    private final CompressionCodec compressionCodec;

    private int writeLimitInBytes;

    private int estimatedMaxRecordsCount;
    private int recordsCount;

    /** identify the number of used times of the writer, used for idempotent recycle() invoking. */
    private long epoch;

    ArrowWriter(
            String writerKey,
            int bufferSizeInBytes,
            RowType schema,
            BufferAllocator allocator,
            ArrowWriterProvider provider,
            ArrowCompressionInfo compressionInfo) {
        this.writerKey = writerKey;
        this.schema = schema;
        this.root = VectorSchemaRoot.create(ArrowUtils.toArrowSchema(schema), allocator);
        this.provider = Preconditions.checkNotNull(provider);
        this.compressionCodec = compressionInfo.createCompressionCodec();

        this.metadataLength =
                ArrowUtils.estimateArrowMetadataLength(
                        root.getSchema(), CompressionUtil.createBodyCompression(compressionCodec));
        this.writeLimitInBytes = (int) (bufferSizeInBytes * BUFFER_USAGE_RATIO);
        this.estimatedMaxRecordsCount = -1;
        this.recordsCount = 0;
        this.epoch = 0;
        //noinspection unchecked
        this.fieldWriters = new ArrowFieldWriter[schema.getFieldCount()];
        for (int i = 0; i < fieldWriters.length; i++) {
            FieldVector fieldVector = root.getVector(i);
            initFieldVector(fieldVector);
            fieldWriters[i] = ArrowUtils.createArrowFieldWriter(fieldVector, schema.getTypeAt(i));
        }
    }

    public int getRecordsCount() {
        return recordsCount;
    }

    public int getWriteLimitInBytes() {
        return writeLimitInBytes;
    }

    public boolean isFull() {
        if (recordsCount > 0 && recordsCount >= estimatedMaxRecordsCount) {
            root.setRowCount(recordsCount);
            int metadataLength = getMetadataLength();
            int bodyLength = getBodyLength();
            int currentSize = metadataLength + bodyLength;
            if (currentSize >= writeLimitInBytes) {
                return true;
            } else {
                // update the estimated max records count
                estimatedMaxRecordsCount =
                        (int)
                                Math.ceil(
                                        (writeLimitInBytes - metadataLength)
                                                / (bodyLength / (recordsCount * 1.0)));
                return false;
            }
        } else {
            // skip the size check if the records count is less than the estimated
            // max records count, this avoids lots of heavy sizeInBytes() calls.
            return false;
        }
    }

    public void reset(int bufferSizeInBytes) {
        this.writeLimitInBytes = (int) (bufferSizeInBytes * BUFFER_USAGE_RATIO);
        for (int i = 0; i < fieldWriters.length; i++) {
            FieldVector fieldVector = root.getVector(i);
            initFieldVector(fieldVector);
            fieldWriters[i] = ArrowUtils.createArrowFieldWriter(fieldVector, schema.getTypeAt(i));
        }
        root.setRowCount(0);
        recordsCount = 0;
        // initial estimated count should < 0, so that we can estimate the count after the first row
        estimatedMaxRecordsCount = -1;
    }

    /** Writes the specified row which is serialized into Arrow format. */
    public void writeRow(InternalRow row) {
        if (isFull()) {
            throw new IllegalStateException(
                    "The arrow batch size is full and it shouldn't accept writing new rows, it's a bug.");
        }

        // need to handle safe if exceed initial capacity
        boolean handleSafe = recordsCount >= INITIAL_CAPACITY;
        for (int i = 0; i < fieldWriters.length; i++) {
            fieldWriters[i].write(row, i, handleSafe);
        }
        recordsCount++;
    }

    /**
     * Gets the metadata length of each serialized {@link ArrowRecordBatch} generated by this root.
     * The metadata length should be consistent if the arrow schema is not changed.
     */
    public int getMetadataLength() {
        return metadataLength;
    }

    /**
     * Gets the buffer body part length of each serialized {@link ArrowRecordBatch} generated by
     * this root.
     */
    public int getBodyLength() {
        long length = ArrowUtils.estimateArrowBodyLength(root);
        checkState(length <= Integer.MAX_VALUE, "The arrow batch body length is too large.");
        return (int) length;
    }

    /**
     * Gets the total size in bytes of each serialized {@link ArrowRecordBatch} generated by this
     * root.
     */
    public int sizeInBytes() {
        root.setRowCount(recordsCount);
        return getMetadataLength() + getBodyLength();
    }

    /** Serializes the current row batch to Arrow format and returns the written size in bytes. */
    public int serializeToOutputView(AbstractPagedOutputView outputView, int position)
            throws IOException {
        // Whether there is any record to write, we need to advance the position to make sure the
        // batch header will be written in outputView.
        outputView.setPosition(position);
        if (recordsCount == 0) {
            return 0;
        }

        // update row count only when we try to write records to the output.
        root.setRowCount(recordsCount);
        try (ArrowRecordBatch arrowBatch =
                new VectorUnloader(root, true, compressionCodec, true).getRecordBatch()) {
            PagedMemorySegmentWritableChannel channel =
                    new PagedMemorySegmentWritableChannel(outputView);
            ArrowBlock block = MessageSerializer.serialize(new WriteChannel(channel), arrowBatch);
            return (int) (block.getMetadataLength() + block.getBodyLength());
        }
    }

    public long getEpoch() {
        return epoch;
    }

    public void increaseEpoch() {
        epoch++;
    }

    /**
     * Return the root and writer resources to the pool. If the given epoch is not equal to the
     * current epoch, the method will do nothing, which means this writer has been recycled and
     * maybe been used by another user in a newer epoch.
     *
     * <p>Note: this only makes calling recycle() multiple times safe (idempotent), but doesn't
     * guarantee thread-safe.
     */
    public void recycle(long epoch) {
        if (this.epoch == epoch) {
            root.clear();
            provider.recycleWriter(this);
        }
    }

    @Override
    public void close() {
        this.recycle(epoch);
    }

    private void initFieldVector(FieldVector fieldVector) {
        fieldVector.setInitialCapacity(INITIAL_CAPACITY);

        // pre-allocate memory.
        if (fieldVector instanceof BaseFixedWidthVector) {
            ((BaseFixedWidthVector) fieldVector).allocateNew(INITIAL_CAPACITY);
        } else if (fieldVector instanceof BaseVariableWidthVector) {
            ((BaseVariableWidthVector) fieldVector).allocateNew(INITIAL_CAPACITY);
        } else {
            fieldVector.allocateNew();
        }
    }
}
