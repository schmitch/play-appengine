package play.core.server.servlet;

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static play.core.server.servlet.ObjectUtil.checkNotNull;

public class CharsetUtil {

    private static ConcurrentHashMap<Charset, CharsetDecoder> map = new ConcurrentHashMap<>();

    /**
     * Returns a new {@link CharsetDecoder} for the {@link Charset} with specified error actions.
     *
     * @param charset The specified charset
     * @param malformedInputAction The decoder's action for malformed-input errors
     * @param unmappableCharacterAction The decoder's action for unmappable-character errors
     * @return The decoder for the specified {@code charset}
     */
    public static CharsetDecoder decoder(Charset charset, CodingErrorAction malformedInputAction,
                                         CodingErrorAction unmappableCharacterAction) {
        checkNotNull(charset, "charset");
        CharsetDecoder d = charset.newDecoder();
        d.onMalformedInput(malformedInputAction).onUnmappableCharacter(unmappableCharacterAction);
        return d;
    }

    /**
     * Returns a cached thread-local {@link CharsetDecoder} for the specified {@link Charset}.
     *
     * @param charset The specified charset
     * @return The decoder for the specified {@code charset}
     */
    public static CharsetDecoder decoder(Charset charset) {
        checkNotNull(charset, "charset");

        CharsetDecoder d = map.get(charset);
        if (d != null) {
            d.reset().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
            return d;
        }

        d = decoder(charset, CodingErrorAction.REPLACE, CodingErrorAction.REPLACE);
        map.put(charset, d);
        return d;
    }

    private CharsetUtil() { }

}
