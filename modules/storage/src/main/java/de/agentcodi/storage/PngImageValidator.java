package de.agentcodi.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Complete, bounded PNG container and inflated scanline validation. */
final class PngImageValidator {
    private static final byte[] SIGNATURE = new byte[] {
        (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a
    };
    private static final int MAXIMUM_CHUNKS = 65536;
    private static final long MAXIMUM_INFLATED_BYTES = 256L * 1024L * 1024L;
    private static final int BUFFER_BYTES = 8192;

    private static final int[] ADAM7_X_START = {0, 4, 0, 2, 0, 1, 0};
    private static final int[] ADAM7_Y_START = {0, 0, 4, 0, 2, 0, 1};
    private static final int[] ADAM7_X_STEP = {8, 8, 4, 4, 2, 2, 1};
    private static final int[] ADAM7_Y_STEP = {8, 8, 8, 4, 4, 2, 2};

    private PngImageValidator() {
    }

    static void validate(
        WorkspaceFileAccess.Source source,
        byte[] prefix,
        int prefixLength,
        long byteCount,
        OutputStream copyDestination
    ) throws IOException {
        if (source == null || prefix == null || prefixLength < 0
            || prefixLength > prefix.length || byteCount < prefixLength) {
            throw invalid("source framing is invalid");
        }
        BoundedInput input = new BoundedInput(
            source,
            prefix,
            prefixLength,
            byteCount,
            copyDestination
        );
        byte[] signature = new byte[SIGNATURE.length];
        input.readFully(signature, 0, signature.length);
        for (int index = 0; index < SIGNATURE.length; index++) {
            if (signature[index] != SIGNATURE[index]) {
                throw invalid("signature is missing or invalid");
            }
        }

        boolean ihdrSeen = false;
        boolean plteSeen = false;
        boolean idatSeen = false;
        boolean idatClosed = false;
        int bitDepth = 0;
        int colorType = 0;
        InflatedShape shape = null;
        Inflater inflater = new Inflater();
        byte[] chunkHeader = new byte[8];
        byte[] dataBuffer = new byte[BUFFER_BYTES];
        byte[] inflateBuffer = new byte[BUFFER_BYTES];
        int chunkCount = 0;
        try {
            while (input.remaining() > 0L) {
                chunkCount++;
                if (chunkCount > MAXIMUM_CHUNKS) {
                    throw invalid("chunk count exceeds the validation limit");
                }
                if (input.remaining() < 12L) {
                    throw invalid("chunk header, data, or CRC is truncated");
                }
                input.readFully(chunkHeader, 0, chunkHeader.length);
                long chunkLength = unsignedInt(chunkHeader, 0);
                if (chunkLength > 0x7fffffffL
                    || chunkLength > input.remaining() - 4L) {
                    throw invalid("chunk length crosses the PNG boundary");
                }
                validateChunkType(chunkHeader, 4);
                boolean isIhdr = chunkTypeIs(chunkHeader, "IHDR");
                boolean isPlte = chunkTypeIs(chunkHeader, "PLTE");
                boolean isIdat = chunkTypeIs(chunkHeader, "IDAT");
                boolean isIend = chunkTypeIs(chunkHeader, "IEND");

                if (!ihdrSeen) {
                    if (!isIhdr || chunkCount != 1 || chunkLength != 13L) {
                        throw invalid("IHDR is not the unique first 13-byte chunk");
                    }
                } else if (isIhdr) {
                    throw invalid("IHDR appears more than once");
                } else if (isPlte) {
                    if (plteSeen || idatSeen || colorType == 0 || colorType == 4
                        || chunkLength == 0L || chunkLength > 768L
                        || chunkLength % 3L != 0L
                        || (colorType == 3
                            && chunkLength / 3L > (1L << bitDepth))) {
                        throw invalid("PLTE length or ordering is invalid for IHDR");
                    }
                } else if (isIdat) {
                    if (idatClosed || (colorType == 3 && !plteSeen)) {
                        throw invalid("IDAT ordering is invalid for IHDR and PLTE");
                    }
                } else if (isIend) {
                    if (chunkLength != 0L) {
                        throw invalid("IEND must have an empty data field");
                    }
                } else if ((chunkHeader[4] & 0x20) == 0) {
                    throw invalid("unknown critical chunk is not supported");
                }

                CRC32 crc = new CRC32();
                crc.update(chunkHeader, 4, 4);
                byte[] ihdr = null;
                if (isIhdr) {
                    ihdr = new byte[13];
                    input.readFully(ihdr, 0, ihdr.length);
                    crc.update(ihdr, 0, ihdr.length);
                } else if (isIdat) {
                    if (!idatSeen) {
                        idatSeen = true;
                    }
                    readChunkData(
                        input,
                        chunkLength,
                        dataBuffer,
                        crc,
                        inflater,
                        shape,
                        inflateBuffer
                    );
                } else {
                    readChunkData(
                        input,
                        chunkLength,
                        dataBuffer,
                        crc,
                        null,
                        null,
                        null
                    );
                }
                byte[] storedCrc = new byte[4];
                input.readFully(storedCrc, 0, storedCrc.length);
                if (crc.getValue() != unsignedInt(storedCrc, 0)) {
                    throw invalid("chunk CRC does not match its type and data");
                }

                if (isIhdr) {
                    long width = unsignedInt(ihdr, 0);
                    long height = unsignedInt(ihdr, 4);
                    bitDepth = unsigned(ihdr[8]);
                    colorType = unsigned(ihdr[9]);
                    int interlace = unsigned(ihdr[12]);
                    if (width == 0L || height == 0L
                        || width > 0x7fffffffL || height > 0x7fffffffL
                        || !isValidBitDepth(colorType, bitDepth)
                        || unsigned(ihdr[10]) != 0 || unsigned(ihdr[11]) != 0
                        || interlace > 1) {
                        throw invalid("IHDR fields or dimensions are invalid");
                    }
                    shape = new InflatedShape(
                        width,
                        height,
                        bitDepth,
                        colorType,
                        interlace
                    );
                    ihdrSeen = true;
                } else if (isPlte) {
                    plteSeen = true;
                } else if (isIend) {
                    if (!idatSeen || !inflater.finished() || shape == null
                        || !shape.isComplete()) {
                        throw invalid(
                            "IEND precedes a complete IHDR-shaped IDAT stream"
                        );
                    }
                    if (input.remaining() != 0L) {
                        throw invalid("bytes or chunks follow IEND");
                    }
                    return;
                } else if (!isIdat && idatSeen) {
                    idatClosed = true;
                }
            }
            throw invalid("IEND is missing");
        } finally {
            inflater.end();
        }
    }

    private static void readChunkData(
        BoundedInput input,
        long length,
        byte[] buffer,
        CRC32 crc,
        Inflater inflater,
        InflatedShape shape,
        byte[] inflateBuffer
    ) throws IOException {
        long remaining = length;
        while (remaining > 0L) {
            int count = (int) Math.min((long) buffer.length, remaining);
            input.readFully(buffer, 0, count);
            crc.update(buffer, 0, count);
            if (inflater != null) {
                if (shape == null) {
                    throw invalid("IDAT appeared before a valid IHDR");
                }
                feedInflater(inflater, shape, buffer, count, inflateBuffer);
            }
            remaining -= count;
        }
    }

    private static void feedInflater(
        Inflater inflater,
        InflatedShape shape,
        byte[] input,
        int length,
        byte[] output
    ) throws IOException {
        if (length == 0) {
            return;
        }
        if (inflater.finished()) {
            throw invalid("IDAT contains bytes after the zlib stream");
        }
        inflater.setInput(input, 0, length);
        while (!inflater.needsInput()) {
            int count;
            try {
                count = inflater.inflate(output);
            } catch (DataFormatException error) {
                throw invalid("IDAT is not a valid zlib stream", error);
            }
            if (count > 0) {
                shape.accept(output, 0, count);
            }
            if (inflater.finished()) {
                if (inflater.getRemaining() != 0) {
                    throw invalid("IDAT contains trailing compressed data");
                }
                return;
            }
            if (inflater.needsDictionary()) {
                throw invalid("IDAT unexpectedly requires a preset dictionary");
            }
            if (count == 0 && !inflater.needsInput()) {
                throw invalid("IDAT inflater made no bounded progress");
            }
        }
    }

    private static void validateChunkType(byte[] header, int offset) throws IOException {
        for (int index = 0; index < 4; index++) {
            int value = unsigned(header[offset + index]);
            if (!((value >= 'A' && value <= 'Z')
                || (value >= 'a' && value <= 'z'))) {
                throw invalid("chunk type contains a non-letter byte");
            }
        }
        if ((header[offset + 2] & 0x20) != 0) {
            throw invalid("chunk type uses the reserved lowercase bit");
        }
    }

    private static boolean chunkTypeIs(byte[] header, String type) {
        return header[4] == type.charAt(0)
            && header[5] == type.charAt(1)
            && header[6] == type.charAt(2)
            && header[7] == type.charAt(3);
    }

    private static boolean isValidBitDepth(int colorType, int bitDepth) {
        switch (colorType) {
            case 0:
                return bitDepth == 1 || bitDepth == 2 || bitDepth == 4
                    || bitDepth == 8 || bitDepth == 16;
            case 2:
                return bitDepth == 8 || bitDepth == 16;
            case 3:
                return bitDepth == 1 || bitDepth == 2 || bitDepth == 4
                    || bitDepth == 8;
            case 4:
            case 6:
                return bitDepth == 8 || bitDepth == 16;
            default:
                return false;
        }
    }

    private static int channelCount(int colorType) {
        switch (colorType) {
            case 0:
            case 3:
                return 1;
            case 2:
                return 3;
            case 4:
                return 2;
            case 6:
                return 4;
            default:
                return 0;
        }
    }

    private static long unsignedInt(byte[] bytes, int offset) {
        return ((long) unsigned(bytes[offset]) << 24)
            | ((long) unsigned(bytes[offset + 1]) << 16)
            | ((long) unsigned(bytes[offset + 2]) << 8)
            | (long) unsigned(bytes[offset + 3]);
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private static IOException invalid(String detail) {
        return new IOException(
            "Workspace PNG is not a complete valid bounded image: " + detail
        );
    }

    private static IOException invalid(String detail, Throwable cause) {
        return new IOException(
            "Workspace PNG is not a complete valid bounded image: " + detail,
            cause
        );
    }

    private static final class BoundedInput {
        private final WorkspaceFileAccess.Source source;
        private final byte[] prefix;
        private final int prefixLength;
        private final OutputStream destination;
        private int prefixOffset;
        private long remaining;

        BoundedInput(
            WorkspaceFileAccess.Source source,
            byte[] prefix,
            int prefixLength,
            long byteCount,
            OutputStream destination
        ) {
            this.source = source;
            this.prefix = prefix;
            this.prefixLength = prefixLength;
            this.remaining = byteCount;
            this.destination = destination;
        }

        long remaining() {
            return remaining;
        }

        void readFully(byte[] buffer, int offset, int length) throws IOException {
            int total = 0;
            while (total < length) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Workspace image export was cancelled");
                }
                if (remaining <= 0L) {
                    throw invalid("file ended inside a PNG field");
                }
                int maximum = (int) Math.min(
                    (long) (length - total),
                    remaining
                );
                int count;
                if (prefixOffset < prefixLength) {
                    count = Math.min(maximum, prefixLength - prefixOffset);
                    System.arraycopy(prefix, prefixOffset, buffer, offset + total, count);
                    prefixOffset += count;
                } else {
                    count = source.read(buffer, offset + total, maximum);
                    if (count < 0) {
                        throw invalid("file is truncated before its declared size");
                    }
                    if (count == 0) {
                        continue;
                    }
                }
                if (destination != null) {
                    destination.write(buffer, offset + total, count);
                }
                total += count;
                remaining -= count;
            }
        }
    }

    private static final class InflatedShape {
        private final long[] rowBytes = new long[7];
        private final long[] rows = new long[7];
        private int passCount;
        private int passIndex;
        private long rowsRemaining;
        private long rowDataRemaining;
        private long expectedBytes;
        private long receivedBytes;

        InflatedShape(
            long width,
            long height,
            int bitDepth,
            int colorType,
            int interlace
        ) throws IOException {
            int channels = channelCount(colorType);
            if (channels == 0) {
                throw invalid("IHDR color type is invalid");
            }
            long bitsPerPixel = (long) channels * bitDepth;
            int candidatePasses = interlace == 0 ? 1 : 7;
            for (int index = 0; index < candidatePasses; index++) {
                long xStart = interlace == 0 ? 0L : ADAM7_X_START[index];
                long yStart = interlace == 0 ? 0L : ADAM7_Y_START[index];
                long xStep = interlace == 0 ? 1L : ADAM7_X_STEP[index];
                long yStep = interlace == 0 ? 1L : ADAM7_Y_STEP[index];
                if (width <= xStart || height <= yStart) {
                    continue;
                }
                long passWidth = (width - xStart + xStep - 1L) / xStep;
                long passHeight = (height - yStart + yStep - 1L) / yStep;
                if (passWidth <= 0L || passHeight <= 0L
                    || passWidth > (Long.MAX_VALUE - 7L) / bitsPerPixel) {
                    throw invalid("IHDR dimensions overflow the scanline shape");
                }
                long bytesPerRow = (passWidth * bitsPerPixel + 7L) / 8L;
                if (bytesPerRow >= MAXIMUM_INFLATED_BYTES
                    || passHeight > (MAXIMUM_INFLATED_BYTES - expectedBytes)
                        / (bytesPerRow + 1L)) {
                    throw invalid(
                        "inflated scanline shape exceeds the validation limit"
                    );
                }
                rowBytes[passCount] = bytesPerRow;
                rows[passCount] = passHeight;
                expectedBytes += passHeight * (bytesPerRow + 1L);
                passCount++;
            }
            if (passCount == 0 || expectedBytes == 0L
                || expectedBytes > MAXIMUM_INFLATED_BYTES) {
                throw invalid("IHDR does not describe a bounded non-empty image");
            }
            rowsRemaining = rows[0];
        }

        void accept(byte[] bytes, int offset, int length) throws IOException {
            int end = offset + length;
            while (offset < end) {
                if (receivedBytes >= expectedBytes) {
                    throw invalid("IDAT expands beyond the IHDR scanline shape");
                }
                if (rowDataRemaining == 0L) {
                    while (passIndex < passCount && rowsRemaining == 0L) {
                        passIndex++;
                        if (passIndex < passCount) {
                            rowsRemaining = rows[passIndex];
                        }
                    }
                    if (passIndex >= passCount) {
                        throw invalid("IDAT contains excess decompressed bytes");
                    }
                    if (unsigned(bytes[offset++]) > 4) {
                        throw invalid("IDAT contains an invalid scanline filter");
                    }
                    receivedBytes++;
                    rowsRemaining--;
                    rowDataRemaining = rowBytes[passIndex];
                    continue;
                }
                int consumed = (int) Math.min(
                    rowDataRemaining,
                    (long) (end - offset)
                );
                offset += consumed;
                rowDataRemaining -= consumed;
                receivedBytes += consumed;
            }
        }

        boolean isComplete() {
            return receivedBytes == expectedBytes && rowDataRemaining == 0L;
        }
    }
}
