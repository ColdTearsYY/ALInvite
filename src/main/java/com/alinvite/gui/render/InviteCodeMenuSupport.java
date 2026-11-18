package com.alinvite.gui.render;

import com.alinvite.gui.MenuItem;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** One fresh AVAILABLE snapshot supplies both the overview and the paged inventory. */
public final class InviteCodeMenuSupport {
    private InviteCodeMenuSupport() { }

    public static void populate(RenderContext context, List<String> codes, Function<String, String> text) {
        List<String> snapshot = List.copyOf(codes);
        // An empty snapshot is meaningful here; addList intentionally omits empty conditional lists.
        context.lists().put("available_invite_codes", snapshot);
        context.add("invite_code", snapshot.isEmpty() ? text.apply("codes_menu.none") : snapshot.get(0));
        context.add("invite_available_count", Integer.toString(snapshot.size()));
        context.add("invite_codes_summary", text.apply("codes_menu.summary").replace("{count}", Integer.toString(snapshot.size())));
        List<String> preview = new ArrayList<>();
        for (String code : snapshot.subList(0, Math.min(5, snapshot.size()))) {
            preview.add(text.apply("codes_menu.preview_row").replace("{code}", code));
        }
        if (snapshot.isEmpty()) preview.add(text.apply("codes_menu.none"));
        if (snapshot.size() > 5) preview.add(text.apply("codes_menu.more").replace("{count}", Integer.toString(snapshot.size() - 5)));
        context.addList("invite_code_preview", preview);
    }

    public static boolean legacyCodeLine(String line) {
        return line != null && (line.contains("%invite_code%") || line.contains("{invite_code}")
            || line.contains("%alinvite_code%"));
    }

    /** Upgrade the old single-code lore in memory; never rewrite a custom menu file. */
    public static List<String> upgradeLore(List<String> source, RenderContext context) {
        if (!context.lists().containsKey("available_invite_codes")) return source;
        List<String> upgraded = new ArrayList<>();
        boolean expanded = false;
        for (String line : source) {
            if (legacyCodeLine(line)) {
                if (!expanded) {
                    upgraded.add(context.strings().get("invite_codes_summary"));
                    upgraded.addAll(context.lists().get("invite_code_preview"));
                    expanded = true;
                }
            } else upgraded.add(line);
        }
        return upgraded;
    }

    /** Keep every configured action. Use an unassigned gesture only. */
    public static String entryTrigger(MenuItem item) {
        if (item.getTriggers().values().stream().flatMap(List::stream)
                .map(String::trim).anyMatch(action -> action.equalsIgnoreCase("open_codes")
                    || action.equalsIgnoreCase("action:open_codes"))) return null;
        if (!legacyCodeLine(item.getName()) && item.getLore().stream().noneMatch(line ->
                legacyCodeLine(line) || line.contains("%invite_codes_summary%")
                    || line.contains("%invite_code_preview%"))) return null;
        for (String trigger : List.of("right", "shift_right", "shift_left", "left")) {
            if (item.getTriggerActions(trigger).isEmpty()) return trigger;
        }
        return null;
    }
}
