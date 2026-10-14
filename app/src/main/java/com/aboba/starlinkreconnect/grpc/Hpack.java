package com.aboba.starlinkreconnect.grpc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Minimal HPACK (RFC 7541) implementation: a full decoder (static + dynamic table, Huffman)
 * and a trivial encoder that only emits "literal header field without indexing" entries,
 * which every HTTP/2 peer must accept.
 */
final class Hpack {
    private Hpack() {}

    // ------------------------------------------------------------------ encoder

    /** Appends one header as a literal-without-indexing field with raw (non-Huffman) strings. */
    static void encodeLiteral(ByteArrayOutputStream out, String name, String value) {
        out.write(0x00); // literal header field without indexing, new name (index 0, 4-bit prefix)
        writeString(out, name);
        writeString(out, value);
    }

    private static void writeString(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeInt(out, b.length, 7, 0x00); // H bit = 0
        out.write(b, 0, b.length);
    }

    static void writeInt(ByteArrayOutputStream out, int value, int prefixBits, int firstByteFlags) {
        int max = (1 << prefixBits) - 1;
        if (value < max) {
            out.write(firstByteFlags | value);
            return;
        }
        out.write(firstByteFlags | max);
        value -= max;
        while (value >= 128) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    // ------------------------------------------------------------------ decoder

    static final class Decoder {
        private final ArrayDeque<String[]> dynamic = new ArrayDeque<>(); // first = most recent
        private int dynamicSize = 0;
        private int maxDynamicSize = 4096;
        private final int settingsMaxDynamicSize;

        Decoder(int settingsMaxDynamicSize) {
            this.settingsMaxDynamicSize = settingsMaxDynamicSize;
            this.maxDynamicSize = settingsMaxDynamicSize;
        }

        List<String[]> decode(byte[] block, int off, int len) throws IOException {
            try {
                return decodeBlock(block, off, len);
            } catch (RuntimeException e) {
                throw new IOException("HPACK: malformed header block (" + e + ")", e);
            }
        }

        private List<String[]> decodeBlock(byte[] block, int off, int len) throws IOException {
            List<String[]> headers = new ArrayList<>();
            Reader r = new Reader(block, off, len);
            while (r.hasMore()) {
                int b = r.peek();
                if ((b & 0x80) != 0) {
                    int index = r.readInt(7);
                    if (index == 0) throw new IOException("HPACK: indexed field with index 0");
                    headers.add(entry(index));
                } else if ((b & 0xC0) == 0x40) {
                    int index = r.readInt(6);
                    String name = index == 0 ? r.readString() : entry(index)[0];
                    String value = r.readString();
                    headers.add(new String[] {name, value});
                    add(name, value);
                } else if ((b & 0xE0) == 0x20) {
                    int newMax = r.readInt(5);
                    if (newMax > settingsMaxDynamicSize) {
                        throw new IOException("HPACK: table size update above SETTINGS limit: " + newMax);
                    }
                    maxDynamicSize = newMax;
                    evict();
                } else { // 0000xxxx without indexing, 0001xxxx never indexed
                    int index = r.readInt(4);
                    String name = index == 0 ? r.readString() : entry(index)[0];
                    String value = r.readString();
                    headers.add(new String[] {name, value});
                }
            }
            return headers;
        }

        private String[] entry(int index) throws IOException {
            if (index >= 1 && index <= HpackTables.STATIC.length) {
                return HpackTables.STATIC[index - 1];
            }
            int d = index - HpackTables.STATIC.length - 1;
            if (d < 0 || d >= dynamic.size()) {
                throw new IOException("HPACK: invalid index " + index);
            }
            Iterator<String[]> it = dynamic.iterator();
            String[] e = null;
            for (int i = 0; i <= d; i++) e = it.next();
            return e;
        }

        private void add(String name, String value) {
            int size = entrySize(name, value);
            if (size > maxDynamicSize) {
                dynamic.clear();
                dynamicSize = 0;
                return;
            }
            dynamic.addFirst(new String[] {name, value});
            dynamicSize += size;
            evict();
        }

        private void evict() {
            while (dynamicSize > maxDynamicSize && !dynamic.isEmpty()) {
                String[] e = dynamic.removeLast();
                dynamicSize -= entrySize(e[0], e[1]);
            }
        }

        private static int entrySize(String name, String value) {
            return name.getBytes(StandardCharsets.UTF_8).length
                    + value.getBytes(StandardCharsets.UTF_8).length + 32;
        }
    }

    private static final class Reader {
        private final byte[] buf;
        private int pos;
        private final int end;

        Reader(byte[] buf, int off, int len) {
            this.buf = buf;
            this.pos = off;
            this.end = off + len;
        }

        boolean hasMore() {
            return pos < end;
        }

        int peek() throws IOException {
            if (pos >= end) throw new IOException("HPACK: truncated block");
            return buf[pos] & 0xFF;
        }

        int next() throws IOException {
            if (pos >= end) throw new IOException("HPACK: truncated block");
            return buf[pos++] & 0xFF;
        }

        int readInt(int prefixBits) throws IOException {
            int max = (1 << prefixBits) - 1;
            long value = next() & max;
            if (value < max) return (int) value;
            int shift = 0;
            int b;
            do {
                b = next();
                if (shift > 28) throw new IOException("HPACK: integer too large");
                value += (long) (b & 0x7F) << shift;
                shift += 7;
                if (value > Integer.MAX_VALUE) throw new IOException("HPACK: integer too large");
            } while ((b & 0x80) != 0);
            return (int) value;
        }

        String readString() throws IOException {
            boolean huffman = (peek() & 0x80) != 0;
            int len = readInt(7);
            if (len < 0 || len > end - pos) throw new IOException("HPACK: string overruns block");
            String s;
            if (huffman) {
                s = Huffman.decode(buf, pos, len);
            } else {
                s = new String(buf, pos, len, StandardCharsets.UTF_8);
            }
            pos += len;
            return s;
        }
    }

    // ------------------------------------------------------------------ Huffman

    static final class Huffman {
        private static final int EOS = 256;
        // Binary trie: children[node][bit] -> node id, or -1. symbol[node] -> symbol or -1.
        private static final int[][] CHILDREN;
        private static final int[] SYMBOL;

        static {
            int capacity = 1;
            for (int i = 0; i < 257; i++) capacity += HpackTables.HUFFMAN_LENGTHS[i];
            int[][] children = new int[capacity][2];
            int[] symbol = new int[capacity];
            for (int i = 0; i < capacity; i++) {
                children[i][0] = -1;
                children[i][1] = -1;
                symbol[i] = -1;
            }
            int nodes = 1;
            for (int sym = 0; sym < 257; sym++) {
                int code = HpackTables.HUFFMAN_CODES[sym];
                int len = HpackTables.HUFFMAN_LENGTHS[sym];
                int node = 0;
                for (int bitIndex = len - 1; bitIndex >= 0; bitIndex--) {
                    int bit = (code >>> bitIndex) & 1;
                    if (children[node][bit] == -1) {
                        children[node][bit] = nodes++;
                    }
                    node = children[node][bit];
                }
                symbol[node] = sym;
            }
            CHILDREN = children;
            SYMBOL = symbol;
        }

        private Huffman() {}

        static String decode(byte[] buf, int off, int len) throws IOException {
            if (len < 0 || off < 0 || off + len > buf.length) throw new IOException("HPACK: bad Huffman range");
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16, Math.min(len * 2, 1 << 20)));
            int node = 0;
            int depth = 0;
            for (int i = off; i < off + len; i++) {
                int b = buf[i] & 0xFF;
                for (int bit = 7; bit >= 0; bit--) {
                    int v = (b >>> bit) & 1;
                    node = CHILDREN[node][v];
                    depth++;
                    if (node == -1) throw new IOException("HPACK: invalid Huffman code");
                    int sym = SYMBOL[node];
                    if (sym != -1) {
                        if (sym == EOS) throw new IOException("HPACK: EOS inside Huffman string");
                        out.write(sym);
                        node = 0;
                        depth = 0;
                    }
                }
            }
            // Remaining bits are padding: must be <= 7 bits, all ones (prefix of EOS). Be lenient on
            // the bit pattern, strict on the length.
            if (depth > 7) throw new IOException("HPACK: Huffman padding too long");
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
