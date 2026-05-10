package dev.shared.halizeur.log_overlay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import eu.darkbot.api.PluginAPI;
import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.events.EventHandler;
import eu.darkbot.api.events.Listener;
import eu.darkbot.api.extensions.Behavior;
import eu.darkbot.api.extensions.Configurable;
import eu.darkbot.api.extensions.Draw;
import eu.darkbot.api.extensions.Drawable;
import eu.darkbot.api.extensions.Feature;
import eu.darkbot.api.extensions.MapGraphics;
import eu.darkbot.api.managers.EventBrokerAPI;
import eu.darkbot.api.managers.GameLogAPI;

/**
 * Renders the latest in-game DarkOrbit log messages as an overlay on the
 * canvas (anchored to the top, no background, auto-fade).
 *
 * Source: {@link GameLogAPI.LogMessageEvent} emitted by DarkBot for each
 * new system message. Each line disappears after DISPLAY_MS ms so the
 * canvas does not get cluttered.
 *
 * Filter: only messages matching at least one keyword from the user's
 * checked categories ({@link LogOverlayConfig.Categories}) are kept;
 * keywords cover both FR and EN forms so the filter works regardless of
 * the active game locale.
 */
@Feature(name = "Log Overlay",
         description = "Shows the latest in-game log messages as an overlay on the canvas",
         enabledByDefault = false)
@Draw(value = Draw.Stage.OVERLAY)
public class LogOverlay implements Behavior, Drawable, Listener, Configurable<LogOverlayConfig> {

    private static final int LINE_HEIGHT = 16;
    private static final int TOP_MARGIN = 30;
    private static final int MAX_LINES = 5;
    private static final long DISPLAY_MS = 5000L;

    /** Keywords (FR + EN) for "loot / reward" log messages. */
    private static final String[] KW_GAINS = {
            // FR
            "gagn", "obtenu", "récup", "recup", "récompense", "recompense",
            "collect", "ramass",
            // EN
            "gained", "received", "reward", "earned"
    };

    /** Keywords for in-game currencies (uri, credits, honor, XP). */
    private static final String[] KW_CURRENCIES = {
            "uridium", "credit", "crédit",
            "honor", "honour", "honneur",
            "experience", "expérience", "xp "
    };

    /** Keywords for collectable / refinable resources. */
    private static final String[] KW_RESOURCES = {
            "prometium", "endurium", "terbium", "prometid", "duranium",
            "promerium", "seprom", "xenomit", "palladium"
    };

    /** Keywords for booster / drop messages. */
    private static final String[] KW_BOOSTERS = {
            "drop", "booster"
    };

    /** Keywords (FR + EN) for error / refusal messages. */
    private static final String[] KW_ERRORS = {
            // FR
            "impossible", "erreur", "échec", "echec",
            "refusé", "refuse", "plein", "indisponible",
            "non disponible", "interdit",
            // EN
            "error", "failed", "refused", "denied",
            "unavailable", "full", "cannot", "can't"
    };

    /** Keywords (FR + EN) for combat / kill messages. */
    private static final String[] KW_COMBAT = {
            // FR
            "tué", "tue", "détruit", "detruit", "dégât", "degat",
            // EN
            "kill", "destroyed", "boss", "damage"
    };

    private final Deque<Entry> entries = new ArrayDeque<>();
    private LogOverlayConfig config;

    public LogOverlay(PluginAPI api) {
        api.requireAPI(EventBrokerAPI.class).registerListener(this);
    }

    @Override
    public void setConfig(ConfigSetting<LogOverlayConfig> cfg) {
        this.config = cfg.getValue();
    }

    @Override
    public void onTickBehavior() {
        // Evict expired entries even when the canvas is not being redrawn.
        evictExpired(System.currentTimeMillis());
    }

    @EventHandler
    public void onLogMessage(GameLogAPI.LogMessageEvent event) {
        if (this.config == null || !this.config.enabled) return;
        String msg = event.getMessage();
        if (msg == null || msg.isEmpty()) return;
        if (!isAllowed(msg)) return;

        long expiresAt = System.currentTimeMillis() + DISPLAY_MS;
        synchronized (this.entries) {
            this.entries.addLast(new Entry(msg, expiresAt));
            // If more than MAX_LINES, drop the oldest one so the rest scrolls up.
            while (this.entries.size() > MAX_LINES) {
                this.entries.removeFirst();
            }
        }
    }

    /**
     * Whitelist filter: only display the message if at least one
     * keyword from a checked category appears in it (case-insensitive).
     */
    private boolean isAllowed(String msg) {
        String lower = msg.toLowerCase();
        LogOverlayConfig.Categories c = this.config.categories;
        if (c == null) return false;
        if (c.gains      && containsAny(lower, KW_GAINS))      return true;
        if (c.currencies && containsAny(lower, KW_CURRENCIES)) return true;
        if (c.resources  && containsAny(lower, KW_RESOURCES))  return true;
        if (c.boosters   && containsAny(lower, KW_BOOSTERS))   return true;
        if (c.errors     && containsAny(lower, KW_ERRORS))     return true;
        if (c.combat     && containsAny(lower, KW_COMBAT))     return true;
        return false;
    }

    private static boolean containsAny(String haystack, String[] needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    private void evictExpired(long now) {
        synchronized (this.entries) {
            Iterator<Entry> it = this.entries.iterator();
            while (it.hasNext()) {
                if (it.next().expiresAtMs <= now) {
                    it.remove();
                } else {
                    break; // entries are ordered chronologically
                }
            }
        }
    }

    @Override
    public void onDraw(MapGraphics mg) {
        if (this.config == null || !this.config.enabled) return;

        // Eviction is handled in onTickBehavior() which runs every tick.
        List<String> snapshot;
        synchronized (this.entries) {
            if (this.entries.isEmpty()) return;
            snapshot = new ArrayList<>(this.entries.size());
            for (Entry e : this.entries) snapshot.add(e.text);
        }

        int cx = mg.getWidthMiddle();
        int startY = TOP_MARGIN;

        mg.setColor("text_light");
        for (int i = 0; i < snapshot.size(); i++) {
            int y = startY + (i + 1) * LINE_HEIGHT;
            mg.drawString(cx, y, snapshot.get(i), MapGraphics.StringAlign.MID);
        }
    }

    private static final class Entry {
        final String text;
        final long expiresAtMs;

        Entry(String text, long expiresAtMs) {
            this.text = text;
            this.expiresAtMs = expiresAtMs;
        }
    }
}
