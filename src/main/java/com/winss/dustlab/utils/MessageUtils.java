package com.winss.dustlab.utils;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;


public class MessageUtils {
    
    public static final String PREFIX_COLOR = "&9";
    public static final String ACCENT_COLOR = "&b";
    public static final String TEXT_COLOR = "&f";
    public static final String SECONDARY_COLOR = "&7";
    public static final String ERROR_COLOR = "&c";
    public static final String SUCCESS_COLOR = "&a";
    
    private static final String PLAYER_PREFIX = translateColors("&9DustLab &b» &f");
    

    public static void sendMessage(CommandSender sender, String message) {
        if (sender instanceof ConsoleCommandSender) {
            sender.sendMessage(stripColors(message));
        } else {
            sender.sendMessage(PLAYER_PREFIX + translateColors(message));
        }
    }
    

    @SuppressWarnings("deprecation")
    public static String translateColors(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    @SuppressWarnings("deprecation")
    public static String stripColors(String message) {
        return ChatColor.stripColor(translateColors(message));
    }

    public static String success(String message) {
        return SUCCESS_COLOR + message;
    }
    

    public static String error(String message) {
        return ERROR_COLOR + message;
    }
    

    public static String secondary(String message) {
        return SECONDARY_COLOR + message;
    }
    

    public static String accent(String message) {
        return ACCENT_COLOR + message;
    }
    

    /**
     * Get the clean prefix for player messages
     */
    public static String getPlayerPrefix() {
        return PLAYER_PREFIX;
    }
    
    /**
     * Log a message only if verbose logging is enabled
     */
    public static void logVerbose(org.bukkit.plugin.Plugin plugin, com.winss.dustlab.config.DustLabConfig config, String message) {
        if (config != null && config.isVerboseLogging()) {
            plugin.getLogger().info(stripColors(message));
        }
    }
}
