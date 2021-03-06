/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package play.core.server.servlet;

/**
 * String utility class.
 */
public final class StringUtil {

    public static final String EMPTY_STRING = "";

    public static final char SPACE = 0x20;

    private static final String[] BYTE2HEX_PAD = new String[256];
    private static final String[] BYTE2HEX_NOPAD = new String[256];

    static {
        // Generate the lookup table that converts a byte into a 2-digit hexadecimal integer.
        int i;
        for (i = 0; i < 10; i++) {
            BYTE2HEX_PAD[i] = "0" + i;
            BYTE2HEX_NOPAD[i] = String.valueOf(i);
        }
        for (; i < 16; i++) {
            char c = (char) ('a' + i - 10);
            BYTE2HEX_PAD[i] = "0" + c;
            BYTE2HEX_NOPAD[i] = String.valueOf(c);
        }
        for (; i < BYTE2HEX_PAD.length; i++) {
            String str = Integer.toHexString(i);
            BYTE2HEX_PAD[i] = str;
            BYTE2HEX_NOPAD[i] = str;
        }
    }

    private StringUtil() {
        // Unused.
    }

    /**
     * Helper to decode half of a hexadecimal number from a string.
     * @param c The ASCII character of the hexadecimal number to decode.
     * Must be in the range {@code [0-9a-fA-F]}.
     * @return The hexadecimal value represented in the ASCII character
     * given, or {@code -1} if the character is invalid.
     */
    public static int decodeHexNibble(final char c) {
        // Character.digit() is not used here, as it addresses a larger
        // set of characters (both ASCII and full-width latin letters).
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 0xA;
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 0xA;
        }
        return -1;
    }

    /**
     * Decode a 2-digit hex byte from within a string.
     */
    public static byte decodeHexByte(CharSequence s, int pos) {
        int hi = decodeHexNibble(s.charAt(pos));
        int lo = decodeHexNibble(s.charAt(pos + 1));
        if (hi == -1 || lo == -1) {
            throw new IllegalArgumentException(String.format(
                    "invalid hex byte '%s' at index %d of '%s'", s.subSequence(pos, pos + 2), pos, s));
        }
        return (byte) ((hi << 4) + lo);
    }

}