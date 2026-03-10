package me.lucko.bytebin.util;

import com.google.common.base.Preconditions;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Randomly generates tokens for new content uploads
 */
public class TokenGenerator {
    /** Pattern to match invalid tokens */
    public static final Pattern INVALID_TOKEN_PATTERN = Pattern.compile("[^a-zA-Z0-9]");

    /** Characters to include in a token */
    public static final String CHARACTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final int length;
    private final SecureRandom random = new SecureRandom();

    public TokenGenerator(int length) {
        Preconditions.checkArgument(length > 1);
        this.length = length;
    }

    public String generate() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < this.length; i++) {
            sb.append(CHARACTERS.charAt(this.random.nextInt(CHARACTERS.length())));
        }
        return sb.toString();
    }
}
