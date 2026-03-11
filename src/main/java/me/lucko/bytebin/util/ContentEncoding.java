package me.lucko.bytebin.util;

import com.google.common.base.Splitter;
import io.jooby.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class ContentEncoding {
    private ContentEncoding() {}

    public static final String GZIP = "gzip";
    public static final String IDENTITY = "identity";

    private static final Splitter COMMA_SPLITTER = Splitter.on(Pattern.compile(",\\s*"));
    private static final Pattern RE_SEMICOLON = Pattern.compile(";\\s*");

    public static Set<String> getAcceptedEncoding(Context ctx) {
        String header = ctx.header("Accept-Encoding").valueOrNull();
        if (header == null || header.isEmpty()) {
            return Collections.singleton(IDENTITY);
        }

        Set<String> set = new HashSet<>();
        set.add(IDENTITY);

        for (String encoding : COMMA_SPLITTER.split(header)) {
            set.add(getEncodingName(RE_SEMICOLON.split(encoding)[0]));
        }

        return set;
    }

    public static List<String> getContentEncoding(String header) {
        if (header == null || header.isEmpty()) {
            return new ArrayList<>();
        }

        LinkedList<String> list = new LinkedList<>();
        for (String encoding : COMMA_SPLITTER.split(header)) {
            list.add(getEncodingName(encoding));
        }

        // remove 'identity' if it comes last
        while (!list.isEmpty() && list.getLast().equals(IDENTITY)) {
            list.removeLast();
        }

        return list;
    }

    private static String getEncodingName(String name) {
        if ("x-gzip".equals(name)) {
            return GZIP;
        }
        return name;
    }

}
