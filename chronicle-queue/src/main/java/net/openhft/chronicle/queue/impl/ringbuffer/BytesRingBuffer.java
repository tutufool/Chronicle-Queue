/*
 * Copyright 2015 Higher Frequency Trading
 *
 * http://www.higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.queue.impl.ringbuffer;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.BytesStore;
import net.openhft.chronicle.bytes.ReadBytesMarshallable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Multi writer single Reader, zero GC, ring buffer, which takes bytes
 *
 * @author Rob Austin.
 */
public class BytesRingBuffer {

    private static final int SIZE = 8;
    private static final int LOCKED = -1;
    private static final int FLAG = 1;

    @NotNull
    private final RingBuffer bytes;
    @NotNull
    private final Header header;

    private final long capacity;


    public BytesRingBuffer(@NotNull final BytesStore byteStore) {
        capacity = byteStore.writeLimit();
        this.header = new Header(byteStore, capacity - 24, capacity);
        this.bytes = new RingBuffer(byteStore, 0, capacity - 24);
        this.header.setWriteUpTo(capacity);
    }

    public void clear() {
        header.clear(capacity);
    }

    /**
     * Inserts the specified element at the tail of this queue if it is possible to do so
     * immediately without exceeding the queue's capacity,
     *
     * @param bytes0 the {@code bytes0} that you wish to add to the ring buffer
     * @return returning {@code true} upon success and {@code false} if this queue is full.
     */
    public boolean offer(@NotNull Bytes bytes0) throws InterruptedException {

        try {

            for (; ; ) {

                long writeLocation = this.writeLocation();
                //System.out.println("writeLocation=" + writeLocation);
                assert writeLocation >= 0;

                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedException();

                // if reading is occurring the remain capacity will only get larger, as have locked
                final long remainingForWrite = remainingForWrite(writeLocation);
                if (remainingForWrite < bytes0.readRemaining() + SIZE + FLAG)
                    return false;

                // write the size
                long len = bytes0.readLimit();

                long messageLen = SIZE + FLAG + len;

                long offset = writeLocation;

                // we want to ensure that only one thread ever gets in here at a time
                if (!header.compareAndSetWriteLocation(writeLocation, LOCKED))
                    continue;

                // we have to set the busy fag before the write location for the reader
                // this is why we have the compareAndSet above to ensure that only one thread
                // gets in here
                final long flagLoc = offset;
                offset = this.bytes.writeByte(offset, States.BUSY.ordinal());

                if (!header.compareAndSetWriteLocation(-1, writeLocation + messageLen))
                    continue;

                // write a size
                offset += this.bytes.write(offset, len);

                // write the data
                this.bytes.write(offset, bytes0);
                this.bytes.writeByte(flagLoc, States.READY.ordinal());

                return true;
            }

        } catch (IllegalStateException e) {
            // when the ring buffer is full
            return false;
        }
    }

    /**
     * @return spin loops to get a valid write location
     */
    private long writeLocation() {
        long writeLocation;
        for (; ; ) {
            if ((writeLocation = header.getWriteLocation()) != LOCKED)
                return writeLocation;
        }
    }

    private long remainingForWrite(long offset) {
        final long writeUpTo = header.getWriteUpTo();
        return (writeUpTo - 1) - offset;
    }


    /**
     * @param bytesProvider provides a bytes to read into
     * @return the Bytes written to
     * @throws InterruptedException
     * @throws IllegalStateException if the Bytes provided by the BytesProvider are not large
     *                               enough
     */
    @NotNull
    public Bytes take(@NotNull BytesProvider bytesProvider) throws
            InterruptedException,
            IllegalStateException {
        Bytes poll;
        do {
            poll = poll(bytesProvider);
        } while (poll == null);
        return poll;
    }


    /**
     * they similar to net.openhft.chronicle.queue.impl.ringbuffer.BytesRingBuffer#take(net.openhft.chronicle.queue.impl.ringbuffer.BytesRingBuffer.BytesProvider)
     *
     * @param readBytesMarshallable used to read the bytes
     * @return the number of bytes read, if ZERO its likely the read was blocked so you should call
     * this method again
     * @throws InterruptedException
     * @throws IllegalStateException
     */
    public long apply(@NotNull ReadBytesMarshallable readBytesMarshallable) throws
            InterruptedException {

        long writeLoc = writeLocation();
        long offset = header.getReadLocation();
        long readLocation = offset;//= this.readLocation.get();

        if (readLocation >= writeLoc) {
            return 0;
        }

        assert readLocation <= writeLoc : "reader has go ahead of the writer";

        long flag = offset;

        final byte state = bytes.readByte(flag);
        offset += 1;

        // the element is currently being written to, so let wait for the write to finish
        if (state == States.BUSY.ordinal()) return 0;

        assert state == States.READY.ordinal() : " we are reading a message that we " +
                "shouldn't,  state=" + state;

        final long elementSize = bytes.readLong(offset);
        offset += 8;

        final long next = offset + elementSize;

        bytes.read(readBytesMarshallable, offset, elementSize);
        bytes.write(flag, States.USED.ordinal());

        header.setWriteUpTo(next + bytes.capacity());
        header.setReadLocation(next);

        return 8;
    }

    /**
     * Retrieves and removes the head of this queue, or returns {@code null} if this queue is
     * empty.
     *
     * @return {@code null} if this queue is empty, or a populated buffer if the element was retried
     * @throws IllegalStateException is the {@code using} buffer is not large enough
     */
    @Nullable
    public Bytes poll(@NotNull BytesProvider bytesProvider) throws
            InterruptedException,
            IllegalStateException {

        long writeLoc = writeLocation();
        long offset = header.getReadLocation();
        long readLocation = offset;//= this.readLocation.get();

        if (readLocation >= writeLoc) {
            return null;
        }

        assert readLocation <= writeLoc : "reader has go ahead of the writer";

        long flag = offset;

        final byte state = bytes.readByte(flag);
        offset += 1;

        // the element is currently being written to, so let wait for the write to finish
        if (state == States.BUSY.ordinal()) return null;

        assert state == States.READY.ordinal() : " we are reading a message that we " +
                "shouldn't,  state=" + state;

        final long elementSize = bytes.readLong(offset);
        offset += 8;

        final long next = offset + elementSize;

        final Bytes using = bytesProvider.provide(elementSize);

        // checks that the 'using' bytes is large enough
        checkSize(using, elementSize);

        bytes.read(using, offset, elementSize);
        bytes.write(flag, States.USED.ordinal());

        header.setWriteUpTo(next + bytes.capacity());
        header.setReadLocation(next);

        return using;
    }


    private static void checkSize(@NotNull Bytes using, long elementSize) {
        if (using.writeRemaining() < elementSize)
            throw new IllegalStateException("requires size=" + elementSize +
                    " bytes, but only " + using.readRemaining() + " remaining.");
    }


    private enum States {BUSY, READY, USED}

    public interface BytesProvider {

        /**
         * sets up a buffer to back the ring buffer, the data wil be read into this buffer the size
         * of the buffer must be as big as {@code maxSize}
         *
         * @param maxSize the number of bytes required
         * @return a buffer of at least {@code maxSize} bytes remaining
         */
        @NotNull
        Bytes provide(long maxSize);
    }

    /**
     * used to store the locations around the ring buffer or reading and writing
     */
    private class Header {

        private final long writeLocationOffset;
        private final long writeUpToOffset;
        private final long readLocationOffset;
        private final BytesStore bytesStore;

        //private final Bytes readBytes;
        ////  private final Bytes writeBytes;


        /**
         * @param bytesStore the bytes for the header
         * @param start
         * @param end
         */
        private Header(@NotNull BytesStore bytesStore, long start, long end) {
            readLocationOffset = start;
            writeLocationOffset = (start += 8);
            writeUpToOffset = start + 8;
            this.bytesStore = bytesStore;
        }

        final AtomicLong writeLocationAtomic = new AtomicLong();

        final AtomicLong writeUpToOffsetAtomic = new AtomicLong();

        boolean compareAndSetWriteLocation(long expectedValue, long newValue) {
            //   return writeLocationAtomic.compareAndSet(expectedValue, newValue);
            // todo replace the above with this :
            return bytesStore.compareAndSwapLong(writeLocationOffset, expectedValue, newValue);
        }

        long getWriteLocation() {
            //   return writeLocationAtomic.get();
            // todo replace the above with this :
            //   System.out.println(writeLocationOffset);
            return bytesStore.readVolatileLong(writeLocationOffset);
        }

        /**
         * @return the point at which you should not write any additional bits
         */
        long getWriteUpTo() {
            return bytesStore.readVolatileLong(writeUpToOffset);

        }

        /**
         * sets the point at which you should not write any additional bits
         */
        void setWriteUpTo(long value) {
            bytesStore.writeOrderedLong(writeUpToOffset, value);
        }

        long getReadLocation() {
            return bytesStore.readVolatileLong(readLocationOffset);
        }

        void setReadLocation(long value) {
            bytesStore.writeOrderedLong(readLocationOffset, value);
        }


        @Override
        public String toString() {
            return "Header{" +
                    "writeLocation=" + bytesStore.readVolatileLong(writeLocationOffset) +
                    ", writeUpTo=" + bytesStore.readVolatileLong(writeUpToOffset) +
                    ", readLocation=" + bytesStore.readVolatileLong(readLocationOffset) + "}";

        }

        public void clear(long size) {
            setWriteUpTo(size);
            setReadLocation(0);
            bytesStore.writeOrderedLong(writeLocationOffset, 0);
        }
    }

    /**
     * This is a Bytes ( like ) implementation where the backing buffer is a ring buffer In the
     * future we could extend this class to implement Bytes.
     */
    private class RingBuffer {


        final boolean isBytesBigEndian;
        private final long capacity;
        private final BytesStore byteStore;


        public RingBuffer(BytesStore byteStore, int start, long end) {
            this.byteStore = byteStore;
            this.capacity = end - start;
            isBytesBigEndian = byteStore.byteOrder() == ByteOrder.BIG_ENDIAN;
        }

        private long write(long offset, @NotNull Bytes bytes0) {

            //  System.out.println("offset=" + offset + ",bytes=" + bytes0);
            long result = offset + bytes0.readRemaining();
            offset %= capacity();

            long len = bytes0.readRemaining();
            long endOffSet = nextOffset(offset, len);

            if (endOffSet >= offset) {
                this.byteStore.write(offset, bytes0, 0, len);
                return result;
            }

            //   long limit = bytes0.writeLimit();

            // bytes0.readLimit(capacity() - offset);
            this.byteStore.write(offset, bytes0, 0, len - endOffSet);

            //     bytes0.readPosition(bytes0.writeLimit());
            //   bytes0.readLimit(limit);

            this.byteStore.write(0, bytes0, len - endOffSet, endOffSet);
            return result;
        }

        long capacity() {
            return capacity;
        }

        long nextOffset(long offset, long increment) {

            long result = offset + increment;
            if (result < capacity())
                return result;

            return result % capacity();
        }

        private long write(long offset, long value) {


            offset %= capacity();

            if (nextOffset(offset, 8) > offset)
                byteStore.writeLong(offset, value);
            else if (isBytesBigEndian)
                putLongB(offset, value);
            else
                putLongL(offset, value);

            return 8;
        }

        public long writeByte(long l, int i) {
            byteStore.writeByte(l % capacity(), i);
            return l + 1;
        }


        private long read(@NotNull Bytes bytes, long offset, long len) {

            offset %= capacity();
            long endOffSet = nextOffset(offset, len);

            // can the data be read straight out of the buffer or is it split around the ring, in
            //other words is that start of the data at the end of the ring and the remaining
            //data at the start of the ring.
            if (endOffSet >= offset) {
                bytes.write(byteStore, offset, len);
                return endOffSet;
            }

            final long firstChunkLen = capacity() - offset;
            bytes.write(byteStore, offset, firstChunkLen);
            bytes.write(byteStore, 0, len - firstChunkLen);

            return endOffSet;
        }

        // if we want multi readers then we could later replace this with a thread-local
        final Bytes bytes = Bytes.elasticByteBuffer();

        private long read(@NotNull ReadBytesMarshallable readBytesMarshallable,
                          long offset,
                          long len) {

            offset %= capacity();
            long endOffSet = nextOffset(offset, len);

            if (endOffSet >= offset) {
                bytes.write(byteStore, offset, len);
                readBytesMarshallable.readMarshallable(bytes);
                return endOffSet;
            }

            final long firstChunkLen = capacity() - offset;
            bytes.write(byteStore, offset, firstChunkLen);
            bytes.write(byteStore, 0, len - firstChunkLen);
            readBytesMarshallable.readMarshallable(bytes);

            return endOffSet;
        }


        long readLong(long offset) {

            offset %= capacity();
            if (nextOffset(offset, 8) > offset)
                return byteStore.readLong(offset);

            return isBytesBigEndian ? makeLong(byteStore.readByte(offset),
                    byteStore.readByte(offset = nextOffset(offset)),
                    byteStore.readByte(offset = nextOffset(offset)),
                    byteStore.readByte(offset = nextOffset(offset)),
                    byteStore.readByte(offset = nextOffset(offset)),
                    byteStore.readByte(offset = nextOffset(offset)),
                    byteStore.readByte(offset = nextOffset(offset)),
                    byteStore.readByte(nextOffset(offset)))

                    : makeLong(byteStore.readByte(nextOffset(offset, 7L)),
                    byteStore.readByte(nextOffset(offset, 6L)),
                    byteStore.readByte(nextOffset(offset, 5L)),
                    byteStore.readByte(nextOffset(offset, 4L)),
                    byteStore.readByte(nextOffset(offset, 3L)),
                    byteStore.readByte(nextOffset(offset, 2L)),
                    byteStore.readByte(nextOffset(offset)),
                    byteStore.readByte(offset));
        }

        private long makeLong(byte b7, byte b6, byte b5, byte b4,
                              byte b3, byte b2, byte b1, byte b0) {
            return ((((long) b7) << 56) |
                    (((long) b6 & 0xff) << 48) |
                    (((long) b5 & 0xff) << 40) |
                    (((long) b4 & 0xff) << 32) |
                    (((long) b3 & 0xff) << 24) |
                    (((long) b2 & 0xff) << 16) |
                    (((long) b1 & 0xff) << 8) |
                    (((long) b0 & 0xff)));
        }

        long nextOffset(long offset) {
            return nextOffset(offset, 1);
        }

        public byte readByte(long l) {
            return byteStore.readByte(l % capacity);
        }

        void putLongB(long offset, long value) {
            byteStore.writeByte(offset, (byte) (value >> 56));
            byteStore.writeByte(offset = nextOffset(offset), (byte) (value >> 48));
            byteStore.writeByte(offset = nextOffset(offset), (byte) (value >> 40));
            byteStore.writeByte(offset = nextOffset(offset), (byte) (value >> 32));
            byteStore.writeByte(offset = nextOffset(offset), (byte) (value >> 24));
            byteStore.writeByte(offset = nextOffset(offset), (byte) (value >> 16));
            byteStore.writeByte(offset = nextOffset(offset), (byte) (value >> 8));
            byteStore.writeByte(nextOffset(offset), (byte) (value));
        }

        void putLongL(long offset, long value) {
            byteStore.writeByte(offset, (byte) (value));
            byteStore.writeByte(offset = nextOffset(offset), (byte) (value >> 8));
            byteStore.writeByte(offset = nextOffset(offset), (byte) (value >> 16));
            byteStore.writeByte(offset = nextOffset(offset), (byte) (value >> 24));
            byteStore.writeByte(offset = nextOffset(offset), (byte) (value >> 32));
            byteStore.writeByte(offset = nextOffset(offset), (byte) (value >> 40));
            byteStore.writeByte(offset = nextOffset(offset), (byte) (value >> 48));
            byteStore.writeByte(nextOffset(offset), (byte) (value >> 56));
        }
    }

}
