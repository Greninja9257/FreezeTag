package com.freezetag.util;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Utility class for handling colored messages, placeholders, and message loading.
 */
public class MessageUtil {

    private static FileConfiguration messagesConfig;
    private static String prefix = "&b[FreezeTag] &r";
    private static Logger logger;

    private MessageUtil() {}

    /**
     * Initialize with the messages configuration and plugin prefix.
     */
    public static void init(FileConfiguration messages, String pluginPrefix, Logger log) {
        messagesConfig = messages;
        prefix = pluginPrefix != null ? pluginPrefix : "&b[FreezeTag] &r";
        logger = log;
    }

    /**
     * Translate '&' color codes to '§' codes.
     */
    public static String colorize(String text) {
        if (text == null) return "";
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /**
     * Replace {key} placeholders in text with values from the map.
     */
    public static String format(String text, Map<String, String> placeholders) {
        if (text == null) return "";
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            text = text.replace("{" + entry.getKey() + "}", entry.getValue() != null ? entry.getValue() : "");
        }
        return text;
    }

    /**
     * Replace {key} placeholders and colorize.
     */
    public static String formatColor(String text, Map<String, String> placeholders) {
        return colorize(format(text, placeholders));
    }

    /**
     * Send a colored message to a player, prepending the plugin prefix.
     */
    public static void send(CommandSender sender, String message) {
        if (sender == null || message == null) return;
        sender.sendMessage(colorize(prefix) + colorize(message));
    }

    /**
     * Send a raw colored message (no prefix) to a player.
     */
    public static void sendRaw(CommandSender sender, String message) {
        if (sender == null || message == null) return;
        sender.sendMessage(colorize(message));
    }

    /**
     * Send a message with placeholders replaced.
     */
    public static void sendFormatted(CommandSender sender, String message, Map<String, String> placeholders) {
        send(sender, format(message, placeholders));
    }

    /**
     * Broadcast a message to all players in the collection.
     */
    public static void broadcast(Collection<? extends Player> players, String message) {
        if (players == null || message == null) return;
        String formatted = colorize(prefix) + colorize(message);
        for (Player player : players) {
            if (player != null && player.isOnline()) {
                player.sendMessage(formatted);
            }
        }
    }

    /**
     * Broadcast a message with placeholders to all players in the collection.
     */
    public static void broadcastFormatted(Collection<? extends Player> players, String message,
                                          Map<String, String> placeholders) {
        broadcast(players, format(message, placeholders));
    }

    /**
     * Retrieve a message from messages.yml by dot-notation key.
     * Falls back to the key itself if not found.
     */
    public static String getMessage(String key) {
        if (messagesConfig == null) return key;
        String value = messagesConfig.getString(key);
        if (value == null) {
            if (logger != null) {
                logger.warning("[FreezeTag] Missing message key: " + key);
            }
            return "&c[Missing message: " + key + "]";
        }
        return value;
    }

    /**
     * Get a message, format with placeholders, and colorize.
     */
    public static String get(String key, Map<String, String> placeholders) {
        String raw = getMessage(key);
        return formatColor(raw, placeholders);
    }

    /**
     * Get a message and colorize (no placeholders).
     */
    public static String get(String key) {
        return colorize(getMessage(key));
    }

    /**
     * Send a message from messages.yml to a player.
     */
    public static void sendMessage(CommandSender sender, String key) {
        send(sender, getMessage(key));
    }

    /**
     * Send a message from messages.yml with placeholders to a player.
     */
    public static void sendMessage(CommandSender sender, String key, Map<String, String> placeholders) {
        send(sender, format(getMessage(key), placeholders));
    }

    /**
     * Get the configured plugin prefix.
     */
    public static String getPrefix() {
        return prefix;
    }

    /**
     * Format a duration in seconds as mm:ss string.
     */
    public static String formatTime(int seconds) {
        int mins = seconds / 60;
        int secs = seconds % 60;
        return String.format("%d:%02d", mins, secs);
    }
}
