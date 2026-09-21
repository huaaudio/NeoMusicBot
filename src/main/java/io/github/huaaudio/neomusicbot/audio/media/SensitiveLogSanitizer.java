package io.github.huaaudio.neomusicbot.audio.media;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Redacts extractor diagnostics before they can reach logs or Discord. */
public final class SensitiveLogSanitizer
{
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s\\]\\[<>\"']+");
    private static final Pattern SENSITIVE_HEADER = Pattern.compile(
            "(?i)(\\b(?:authorization|cookie)\\s*:\\s*)[^\\r\\n]*");
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(?i)(sessdata|po[_ -]?token|visitor[_ -]?data|access[_ -]?token|"
                    + "refresh[_ -]?token|discord[_ -]?token)(\\s*[:=]\\s*)"
                    + "(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s,;]+)");
    private static final Pattern ACCOUNT_FIELD = Pattern.compile(
            "(?i)(\\b(?:account|email|username|user[_ -]?id|channel[_ -]?id)"
                    + "\\s*[:=]\\s*)(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s,;]+)");
    private static final Pattern EMAIL_ADDRESS = Pattern.compile(
            "(?i)(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"
                    + "(?![A-Za-z0-9._%+-])");
    private static final Pattern DISCORD_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_-])(?:mfa\\.[A-Za-z0-9_-]{20,}|"
                    + "[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{6}\\.[A-Za-z0-9_-]{20,})"
                    + "(?![A-Za-z0-9_-])");

    private SensitiveLogSanitizer()
    {
    }

    /** Bounded failure summary; never attach the original cause/suppressed chain to a logger. */
    public static String describe(Throwable error)
    {
        if(error == null) return "UnknownFailure: no details";
        String message = error.getMessage();
        return error.getClass().getSimpleName() + ": "
                + (message == null || message.isBlank() ? "no details" : sanitize(message));
    }

    public static String sanitize(String value)
    {
        if (value == null || value.isBlank())
            return "No diagnostic was provided";

        Matcher matcher = URL.matcher(value);
        StringBuffer withoutUrls = new StringBuffer();
        while (matcher.find())
            matcher.appendReplacement(withoutUrls, Matcher.quoteReplacement(stripUrl(matcher.group())));
        matcher.appendTail(withoutUrls);

        String redacted = SENSITIVE_HEADER.matcher(withoutUrls).replaceAll("$1[redacted]");
        redacted = SECRET_FIELD.matcher(redacted).replaceAll("$1$2[redacted]");
        redacted = ACCOUNT_FIELD.matcher(redacted).replaceAll("$1[redacted]");
        redacted = EMAIL_ADDRESS.matcher(redacted).replaceAll("[email-redacted]");
        redacted = DISCORD_TOKEN.matcher(redacted).replaceAll("[discord-token-redacted]");

        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank())
            redacted = redacted.replace(home, "~");
        redacted = redacted.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return redacted.length() <= 1000 ? redacted : redacted.substring(0, 1000) + "...";
    }

    private static String stripUrl(String value)
    {
        // Uniform replacement avoids retaining URI user-info, host aliases or
        // signed path/query material when a malformed diagnostic URL is seen.
        return "[url-redacted]";
    }
}
