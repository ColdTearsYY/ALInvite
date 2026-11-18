package com.alinvite.commands;

import java.util.regex.Pattern;

/** Keeps command guidance consistent, including text from existing custom language/menu files. */
public final class CommandPresentation {
    private static final Pattern LEGACY_COMMAND = Pattern.compile(
        "(?i)(?:(?<![a-z0-9_:/])|(?<=[&§][0-9a-fk-orx]))/(?:alinvite:)?(?:alinvite|ali|invite)(?![a-z0-9_./:-])");

    private CommandPresentation() { }

    /** Rewrites explicit command roots only; plugin identity, permissions and URLs stay intact. */
    public static String preferTravel(String text) {
        if (text == null || text.isEmpty()) return text;
        return LEGACY_COMMAND.matcher(text).replaceAll("/travel");
    }
}
