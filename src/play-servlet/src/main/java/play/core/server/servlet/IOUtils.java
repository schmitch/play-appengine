package play.core.server.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class IOUtils {

    public static final int EOF = -1;

    public static final int DEFAULT_BUFFER_SIZE = 1024 * 4;

    public static long copyLarge(final InputStream input, final OutputStream output) throws IOException {
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        long count = 0;
        int n;
        while (EOF != (n = input.read(buffer))) {
            output.write(buffer, 0, n);
            count += n;
        }
        return count;
    }

}
