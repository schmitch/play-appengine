package play.core.server.servlet;

import akka.util.ByteString;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class IOUtils {

    public static final int EOF = -1;

    public static final int DEFAULT_BUFFER_SIZE = 1024 * 4;

    public static List<ByteString> readAll(final InputStream input) throws IOException {
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        int n;
        ArrayList<ByteString> list = new ArrayList<>();
        while (EOF != (n = input.read(buffer))) {
            list.add(ByteString.fromArray(buffer, 0, n));
        }
        return list;
    }


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
