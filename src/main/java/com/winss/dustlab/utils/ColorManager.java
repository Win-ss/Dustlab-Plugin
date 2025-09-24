// package com.winss.dustlab.utils;

// import com.winss.dustlab.DustLab;
// // i might switch this is why its here but uh yeah 
// import java.util.HashMap;
// import java.util.Map;
// import java.util.regex.Pattern;

// public class ColorManager {

//     private final DustLab plugin;
//     private final Map<String, String> colorCache = new HashMap<>();
//     private final Pattern hexPattern = Pattern.compile("#([A-Fa-f0-9]{6})");

//     // Color keys
//     public static final String PRIMARY = "primary";
//     public static final String ACCENT = "accent";
//     public static final String TEXT = "text";
//     public static final String SECONDARY = "secondary";
//     public static final String SUCCESS = "success";
//     public static final String ERROR = "error";
//     public static final String WARNING = "warning";
//     public static final String INFO = "info";
//     public static final String COORDS = "coords";
//     public static final String LIFETIME = "lifetime";
//     public static final String PERSISTENT = "persistent";
//     public static final String FORCE = "force";

//     private static final Map<String, String> BRAND_COLORS;

//     static {
//         Map<String, String> colors = new HashMap<>();
//         colors.put(PRIMARY, "#4F46E5");
//         colors.put(ACCENT, "#06B6D4");
//         colors.put(TEXT, "#9CA3AF");
//         colors.put(SECONDARY, "#7e8692ff");
//         colors.put(SUCCESS, "#10B981");
//         colors.put(ERROR, "#DC2626");
//         colors.put(WARNING, "#F59E0B");
//         colors.put(INFO, "#3B82F6");
//         colors.put(COORDS, "#8B5CF6");
//         colors.put(LIFETIME, "#EC4899");
//         colors.put(PERSISTENT, "#059669");
//         colors.put(FORCE, "#EF4444");
//         BRAND_COLORS = Map.copyOf(colors);
//     }

//     public ColorManager(DustLab plugin) {
//         this.plugin = plugin;
//         loadColors();
//     }

//     public void loadColors() {
//         colorCache.clear();

//         for (Map.Entry<String, String> entry : BRAND_COLORS.entrySet()) {
//             colorCache.put(entry.getKey(), processColor(entry.getValue()));
//         }
//     }

//     private String processColor(String color) {
//         if (color == null || color.isEmpty()) {
//             return "§f";
//         }

//         if (hexPattern.matcher(color).matches()) {
//             return convertHexToMinecraft(color);
//         }

//         if (color.startsWith("&")) {
//             return color.replace("&", "§");
//         }

//         if (color.startsWith("§")) {
//             return color;
//         }

//         return "§f";
//     }

//     private String convertHexToMinecraft(String hex) {
//         hex = hex.replace("#", "");

//         StringBuilder result = new StringBuilder("§x");
//         for (char c : hex.toCharArray()) {
//             result.append("§").append(c);
//         }
//         return result.toString();
//     }

//     public String getColor(String key) {
//         return colorCache.getOrDefault(key, "§f");
//     }

//     public String primary() {
//         return getColor(PRIMARY);
//     }

//     public String accent() {
//         return getColor(ACCENT);
//     }

//     public String text() {
//         return getColor(TEXT);
//     }

//     public String secondary() {
//         return getColor(SECONDARY);
//     }

//     public String success() {
//         return getColor(SUCCESS);
//     }

//     public String error() {
//         return getColor(ERROR);
//     }

//     public String warning() {
//         return getColor(WARNING);
//     }

//     public String info() {
//         return getColor(INFO);
//     }

//     public String coords() {
//         return getColor(COORDS);
//     }

//     public String lifetime() {
//         return getColor(LIFETIME);
//     }

//     public String persistent() {
//         return getColor(PERSISTENT);
//     }

//     public String force() {
//         return getColor(FORCE);
//     }

//     public String prefix() {
//         return primary() + "DustLab " + secondary() + "» ";
//     }

//     public String errorPrefix() {
//         return primary() + "DustLab " + error() + "» ";
//     }

//     public String successPrefix() {
//         return primary() + "DustLab " + success() + "» ";
//     }

//     public String format(String message, Object... args) {
//         return String.format(message, args);
//     }

//     public String formatCoords(double x, double y, double z) {
//         return coords() + String.format("%.1f", x) + secondary() + ", " +
//                 coords() + String.format("%.1f", y) + secondary() + ", " +
//                 coords() + String.format("%.1f", z);
//     }

//     public String formatLifetime(int lifetimeSeconds) {
//         if (lifetimeSeconds == -1) {
//             return lifetime() + "infinite";
//         } else if (lifetimeSeconds == 0) {
//             return lifetime() + "one-time";
//         } else {
//             return lifetime() + lifetimeSeconds + "s";
//         }
//     }
// }
