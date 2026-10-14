package com.aboba.starlinkreconnect.grpc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Just enough protobuf wire-format support (no schema) to build requests and read replies. */
public final class Proto {
    private Proto() {}

    public static final int WIRE_VARINT = 0;
    public static final int WIRE_FIXED64 = 1;
    public static final int WIRE_LENGTH = 2;
    public static final int WIRE_FIXED32 = 5;

    // ------------------------------------------------------------------ writing

    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public Writer varint(int field, long value) {
            tag(field, WIRE_VARINT);
            writeVarint(out, value);
            return this;
        }

        public Writer bytes(int field, byte[] value) {
            tag(field, WIRE_LENGTH);
            writeVarint(out, value.length);
            out.write(value, 0, value.length);
            return this;
        }

        public Writer string(int field, String value) {
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        /** Embeds an empty sub-message (e.g. RebootRequest {}). */
        public Writer emptyMessage(int field) {
            return bytes(field, new byte[0]);
        }

        public Writer message(int field, Writer sub) {
            return bytes(field, sub.toByteArray());
        }

        public byte[] toByteArray() {
            return out.toByteArray();
        }

        private void tag(int field, int wireType) {
            writeVarint(out, ((long) field << 3) | wireType);
        }
    }

    static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    // ------------------------------------------------------------------ reading

    public static final class Field {
        public final int number;
        public final int wireType;
        public final long varint;      // WIRE_VARINT / FIXED32 / FIXED64 values
        public final byte[] bytes;     // WIRE_LENGTH payload

        Field(int number, int wireType, long varint, byte[] bytes) {
            this.number = number;
            this.wireType = wireType;
            this.varint = varint;
            this.bytes = bytes;
        }

        public String asString() {
            return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** Parses one message level into a flat list of fields; returns null when malformed. */
    public static List<Field> parse(byte[] data) {
        List<Field> fields = new ArrayList<>();
        int pos = 0;
        try {
            while (pos < data.length) {
                long[] v = readVarint(data, pos);
                pos = (int) v[1];
                int number = (int) (v[0] >>> 3);
                int wireType = (int) (v[0] & 7);
                switch (wireType) {
                    case WIRE_VARINT: {
                        long[] val = readVarint(data, pos);
                        pos = (int) val[1];
                        fields.add(new Field(number, wireType, val[0], null));
                        break;
                    }
                    case WIRE_FIXED64: {
                        long val = 0;
                        for (int i = 7; i >= 0; i--) val = (val << 8) | (data[pos + i] & 0xFF);
                        pos += 8;
                        fields.add(new Field(number, wireType, val, null));
                        break;
                    }
                    case WIRE_LENGTH: {
                        long[] len = readVarint(data, pos);
                        pos = (int) len[1];
                        int n = (int) len[0];
                        if (n < 0 || pos + n > data.length) return null;
                        byte[] b = new byte[n];
                        System.arraycopy(data, pos, b, 0, n);
                        pos += n;
                        fields.add(new Field(number, wireType, 0, b));
                        break;
                    }
                    case WIRE_FIXED32: {
                        long val = 0;
                        for (int i = 3; i >= 0; i--) val = (val << 8) | (data[pos + i] & 0xFF);
                        pos += 4;
                        fields.add(new Field(number, wireType, val, null));
                        break;
                    }
                    default:
                        return null; // groups and unknown wire types are not supported
                }
            }
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
        return fields;
    }

    public static Field find(List<Field> fields, int number) {
        if (fields == null) return null;
        for (Field f : fields) if (f.number == number) return f;
        return null;
    }

    private static long[] readVarint(byte[] data, int pos) {
        long result = 0;
        int shift = 0;
        while (true) {
            int b = data[pos++] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift > 63) throw new IndexOutOfBoundsException("varint too long");
        }
        return new long[] {result, pos};
    }
}
