package com.winss.dustlab.commands;

import com.winss.dustlab.DustLab;
import com.winss.dustlab.managers.ParticleModelManager;
import com.winss.dustlab.effects.ParticleEffects;
import com.winss.dustlab.models.ParticleModel;
import com.winss.dustlab.models.ParticleData;
import com.winss.dustlab.monitoring.PerformanceMonitor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DustLabCommand implements CommandExecutor, TabCompleter {
    
    private final DustLab plugin;
    // Per-player media creation queueing
    private static final UUID CONSOLE_UUID = new UUID(0L, 0L);
    private final Map<UUID, Deque<MediaRequest>> mediaQueues = new ConcurrentHashMap<>();
    private final Set<UUID> mediaActive = java.util.Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());
    private final Set<String> mediaNamesRunningOrQueued = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    // Pending deletions per sender (player or console)
    private final Map<UUID, PendingDeletion> pendingDeletions = new ConcurrentHashMap<>();

    private static class PendingDeletion {
        final String modelName;
        final long expiresAtMillis;
        PendingDeletion(String modelName, long expiresAtMillis) {
            this.modelName = modelName;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
    
    public DustLabCommand(DustLab plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Parse lifetime string with time units (s, m, h)
     * @param lifetimeArg The argument string (e.g., "30s", "5m", "2h", "120")
     * @return Lifetime in seconds, -1 for infinite, 0 for one-time
     */
    private int parseLifetime(String lifetimeArg) {
        if (lifetimeArg == null || lifetimeArg.trim().isEmpty()) {
            return 0; 
        }
        
        lifetimeArg = lifetimeArg.trim().toLowerCase();
        
        if (lifetimeArg.equals("infinite") || lifetimeArg.equals("loop")) {
            return -1;
        }
        if (lifetimeArg.equals("one-time") || lifetimeArg.equals("onetime")) {
            return 0;
        }
        
        if (lifetimeArg.endsWith("s")) {
            try {
                String numberPart = lifetimeArg.substring(0, lifetimeArg.length() - 1);
                int seconds = Integer.parseInt(numberPart);
                return Math.max(0, seconds);
            } catch (NumberFormatException e) {
                return -2;
            }
        } else if (lifetimeArg.endsWith("m")) {
            try {
                String numberPart = lifetimeArg.substring(0, lifetimeArg.length() - 1);
                int minutes = Integer.parseInt(numberPart);
                return Math.max(0, minutes * 60); 
            } catch (NumberFormatException e) {
                return -2;
            }
        } else if (lifetimeArg.endsWith("h")) {
            try {
                String numberPart = lifetimeArg.substring(0, lifetimeArg.length() - 1);
                int hours = Integer.parseInt(numberPart);
                return Math.max(0, hours * 3600);
            } catch (NumberFormatException e) {
                return -2; 
            }
        } else {
            // you got NO MONEY, NO UNITS, SO WE WILL GIVE YOU SECONDS!  
            try {
                int seconds = Integer.parseInt(lifetimeArg);
                return Math.max(0, seconds);
            } catch (NumberFormatException e) {
                return -2; 
            }
        }
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "load":
                return handleLoad(sender, args);
            case "loadfx":
                return handleLoadWithEffects(sender, args);
            case "playerload":
                return handlePlayerLoad(sender, args);
            case "create":
                return handleCreate(sender, args);
            case "delete":
                return handleDelete(sender, args);
            case "confirm":
                return handleConfirm(sender, args);
            case "unload":
                return handleUnload(sender, args);
            case "move":
                return handleMove(sender, args);
            case "list":
                return handleList(sender);
            case "active":
                return handleActive(sender);
            case "info":
                return handleInfo(sender, args);
            case "reload":
                return handleReload(sender);
            case "stats":
                return handleStats(sender);
            case "help":
                return handleHelp(sender, args);
            case "website":
                return handleWebsite(sender);
            default:
                sendHelp(sender);
                return true;
        }
    }
    
    private boolean handleLoad(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dustlab.load")) {
            sender.sendMessage("§9DustLab §c» §7You don't have permission to load particle models (dustlab.load).");
            return true;
        }
        
        if (args.length < 2) {
            sender.sendMessage("§9DustLab §c» §7Usage: /dustlab load <model_name> [x y z] [lifetime] [force/normal]");
            sender.sendMessage("§9DustLab §7» §7Coords: Optional coordinates (if not provided, uses your location)");
            sender.sendMessage("§9DustLab §7» §7Lifetime: §b<number>§7[§bs§7|§bm§7|§bh§7], §binfinite§7, or §bone-time§7 (e.g., 30s, 5m, 2h)");
            sender.sendMessage("§9DustLab §7» §7Persistence: Automatic for long-running effects (>1 minute) and infinite loops");
            sender.sendMessage("§9DustLab §7» §7Force: §bforce§7 or §bnormal§7 (force bypasses view permissions)");
            return true;
        }

        // Check if console is trying to use without coordinates
        boolean hasCoordinates = args.length >= 5;
        if (hasCoordinates && args.length >= 5) {
            try {
                Double.parseDouble(args[2]);
                Double.parseDouble(args[3]);
                Double.parseDouble(args[4]);
            } catch (NumberFormatException e) {
                hasCoordinates = false;
            }
        }

        if (!(sender instanceof Player) && !hasCoordinates) {
            sender.sendMessage("§9DustLab §4» §7Console must provide coordinates: /dustlab load <model_name> <x> <y> <z> [lifetime] [force/normal]");
            return true;
        }

        Player player = sender instanceof Player ? (Player) sender : null;
        String modelName = args[1];

        // If not fully loaded yet but loading in progress, subscribe and inform
        if (!plugin.getParticleModelManager().hasModel(modelName)) {
            if (plugin.getParticleModelManager().isModelLoading(modelName)) {
                plugin.getParticleModelManager().subscribeToLoading(modelName, sender);
                int pct = plugin.getParticleModelManager().getModelLoadingPercent(modelName);
                sender.sendMessage("§9DustLab §e» §7Model '§f" + modelName + "§7' is still loading §e" + pct + "%§7. You'll be notified when it's ready. Try again after completion.");
                return true;
            }
            sender.sendMessage("§9DustLab §4» §7Model '§f" + modelName + "§7' not found.");
            return true;
        }

        Location location = null;
        if (player != null) {
            location = player.getLocation();
        } else {
            location = new Location(plugin.getServer().getWorlds().get(0), 0, 100, 0);
        }
        int lifetimeSeconds = 0; 
        boolean force = false;

        int argIndex = 2;

        if (args.length >= 5 && argIndex + 2 < args.length) {
            try {
                double x = Double.parseDouble(args[argIndex]);
                double y = Double.parseDouble(args[argIndex + 1]);
                double z = Double.parseDouble(args[argIndex + 2]);
                location = new Location(player != null ? player.getWorld() : plugin.getServer().getWorlds().get(0), x, y, z);
                argIndex += 3;
            } catch (NumberFormatException e) {
                // Not coordinates, continue with default location
            }
        }

        // Parse lifetime parameter
        if (argIndex < args.length) {
            String lifetimeArg = args[argIndex];
            int parsedLifetime = parseLifetime(lifetimeArg);
            
            if (parsedLifetime == -2) {
                // Invalid format
                sender.sendMessage("§9DustLab §c» §7Invalid lifetime '§f" + lifetimeArg + "§7'. Use format: 30s, 5m, 2h, infinite, or one-time");
                return true;
            } else if (parsedLifetime >= -1) {
                // Valid lifetime parsed
                lifetimeSeconds = parsedLifetime;
                argIndex++;
            }
            // If not a valid lifetime, leave argIndex unchanged and use default
        }

        // Parse force parameter (skip persistence parameter)
        if (argIndex < args.length) {
            String arg = args[argIndex];
            if (arg.equalsIgnoreCase("force")) {
                force = true;
            } else if (arg.equalsIgnoreCase("normal")) {
                force = false;
            }
        }

        if (!plugin.getParticleModelManager().hasViewPermission(sender, modelName, force)) {
            if (!sender.hasPermission("dustlab.view")) {
                sender.sendMessage("§9DustLab §c» §7You don't have permission to view DustLab particles (dustlab.view).");
            } else {
                sender.sendMessage("§9DustLab §c» §7You don't have permission to view model '§f" + modelName + "§7' (dustlab.view." + modelName.toLowerCase() + ").");
                sender.sendMessage("§9DustLab §7» §7Add §bforce§7 to bypass permissions if you have dustlab.force permission.");
            }
            return true;
        }

    int effectId = plugin.getParticleModelManager().playModel(modelName, location, lifetimeSeconds);

        if (effectId == -1) {
            sender.sendMessage("§9DustLab §c» §7Failed to load model '§f" + modelName + "§7'.");
            return true;
        }

        String lifetimeText = getLifetimeDisplayText(lifetimeSeconds);
        String forceText = force ? " §c(forced)" : "";
        sender.sendMessage("§9DustLab §b» §7Loaded model '§f" + modelName + "§7' at " + 
            String.format("§b%.1f§7, §b%.1f§7, §b%.1f", location.getX(), location.getY(), location.getZ()) + 
            lifetimeText + forceText + " §7(ID: §f#" + effectId + "§7)");

        return true;
    }


    private String getLifetimeDisplayText(int lifetimeSeconds) {
        if (lifetimeSeconds == -1) {
            return " §b(infinite)";
        } else if (lifetimeSeconds == 0) {
            return " §7(one-time)";
        } else {
            return " §e(" + lifetimeSeconds + "s)";
        }
    }


    private boolean isValidEffectType(String effectType) {
        return Arrays.asList("rotate", "rotate-x", "rotate-y", "rotate-z", "rotate-xy", "rotate-xz", "rotate-yz", "rotate-xyz", 
                           "oscillate", "pulse", "bounce", "flow", "swirl", "wave", "orbit", "spiral").contains(effectType);
    }
    
    private boolean handleLoadWithEffects(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dustlab.loadfx")) {
            sender.sendMessage("§9DustLab §c» §7You don't have permission to load particle models with effects (dustlab.loadfx).");
            return true;
        }
        
        if (args.length < 3) {
            sender.sendMessage("§9DustLab §c» §7Usage: /dustlab loadfx <model_name> <effect_type> [x y z] [lifetime] [speed] [force/normal]");
            sender.sendMessage("§9DustLab §7» §7Effects: §brotate§7, §brotate-x§7, §brotate-y§7, §brotate-z§7, §brotate-xy§7, §brotate-xyz§7");
            sender.sendMessage("§9DustLab §7» §7Effects: §boscillate§7, §bpulse§7, §bbounce§7, §bflow§7, §bswirl§7, §bwave§7, §borbit§7, §bspiral§7");
            sender.sendMessage("§9DustLab §7» §7Coords: Optional coordinates (if not provided, uses your location)");
            sender.sendMessage("§9DustLab §7» §7Lifetime: §b<number>§7[§bs§7|§bm§7|§bh§7], §binfinite§7, or §bone-time§7 (e.g., 30s, 5m, 2h)");
            sender.sendMessage("§9DustLab §7» §7Persistence: Automatic for long-running effects (>1 minute) and infinite loops");
            sender.sendMessage("§9DustLab §7» §7Speed: §b<number>§7 (effect speed multiplier)");
            sender.sendMessage("§9DustLab §7» §7Force: §bforce§7 or §bnormal§7 (force bypasses view permissions)");
            return true;
        }

        boolean hasCoordinates = args.length >= 6;
        if (hasCoordinates && args.length >= 6) {
            try {
                Double.parseDouble(args[3]);
                Double.parseDouble(args[4]);
                Double.parseDouble(args[5]);
            } catch (NumberFormatException e) {
                hasCoordinates = false;
            }
        }

        if (!(sender instanceof Player) && !hasCoordinates) {
            sender.sendMessage("§9DustLab §c» §7Console must provide coordinates: /dustlab loadfx <model_name> <effect_type> <x> <y> <z> [lifetime] [speed] [force/normal]");
            return true;
        }

        Player player = sender instanceof Player ? (Player) sender : null;
        String modelName = args[1];
        String effectType = args[2].toLowerCase();

        if (!plugin.getParticleModelManager().hasModel(modelName)) {
            sender.sendMessage("§9DustLab §c» §7Model '§f" + modelName + "§7' not found.");
            return true;
        }

        if (!isValidEffectType(effectType)) {
            sender.sendMessage("§9DustLab §c» §7Invalid effect type '§f" + effectType + "§7'. Valid types:");
            sender.sendMessage("§9DustLab §7» §brotate§7, §brotate-x§7, §brotate-y§7, §brotate-z§7, §brotate-xy§7, §brotate-xyz§7");
            sender.sendMessage("§9DustLab §7» §boscillate§7, §bpulse§7, §bbounce§7, §bflow§7, §bswirl§7, §bwave§7, §borbit§7, §bspiral§7");
            return true;
        }

        // Parse arguments: [x y z] [lifetime] [speed] [force/normal]
        Location location = null;
        if (player != null) {
            location = player.getLocation();
        } else {
            location = new Location(plugin.getServer().getWorlds().get(0), 0, 100, 0); 
        } 
        int lifetimeSeconds = 0; 
        double speed = 1.0;
        boolean force = false;

        int argIndex = 3; 

        if (args.length >= 6 && argIndex + 2 < args.length) {
            try {
                double x = Double.parseDouble(args[argIndex]);
                double y = Double.parseDouble(args[argIndex + 1]);
                double z = Double.parseDouble(args[argIndex + 2]);
                location = new Location(player != null ? player.getWorld() : plugin.getServer().getWorlds().get(0), x, y, z);
                argIndex += 3; 
            } catch (NumberFormatException e) {
                // Not coordinates, continue with current location
            }
        }

        // Parse lifetime parameter
        if (argIndex < args.length) {
            String lifetimeArg = args[argIndex];
            int parsedLifetime = parseLifetime(lifetimeArg);
            
            if (parsedLifetime == -2) {
                // Invalid format
                sender.sendMessage("§9DustLab §c» §7Invalid lifetime '§f" + lifetimeArg + "§7'. Use format: 30s, 5m, 2h, infinite, or one-time");
                return true;
            } else if (parsedLifetime >= -1) {
                // Valid lifetime parsed
                lifetimeSeconds = parsedLifetime;
                argIndex++;
            }
            // If not a valid lifetime, leave argIndex unchanged and use default
        }
        
        // Parse speed parameter 
        if (argIndex < args.length) {
            try {
                double speedValue = Double.parseDouble(args[argIndex]);
                if (speedValue >= 0.1 && speedValue <= 10.0) {
                    speed = speedValue;
                    argIndex++;
                }
            } catch (NumberFormatException e) {
                // Not a speed value, could be force parameter
            }
        }
        
        // Parse force parameter
        if (argIndex < args.length) {
            String forceArg = args[argIndex];
            if (forceArg.equalsIgnoreCase("force")) {
                force = true;
            } else if (forceArg.equalsIgnoreCase("normal")) {
                force = false;
            }
        }

        if (!plugin.getParticleModelManager().hasViewPermission(sender, modelName, force)) {
            if (!sender.hasPermission("dustlab.view")) {
                sender.sendMessage("§9DustLab §c» §7You don't have permission to view DustLab particles (dustlab.view).");
            } else {
                sender.sendMessage("§9DustLab §c» §7You don't have permission to view model '§f" + modelName + "§7' (dustlab.view." + modelName.toLowerCase() + ").");
                sender.sendMessage("§9DustLab §7» §7Add §bforce§7 to bypass permissions if you have dustlab.force permission.");
            }
            return true;
        }

        ParticleEffects.EffectSettings effects = null;
        
        effects = ParticleEffects.EffectSettings.parseEffect(effectType);
        
        if (effects == null) {
            sender.sendMessage("§9DustLab §c» §7Invalid effect type '§f" + effectType + "§7'. Valid types:");
            sender.sendMessage("§9DustLab §7» §brotate§7, §brotate-x§7, §brotate-y§7, §brotate-z§7, §brotate-xy§7, §brotate-xyz§7");
            sender.sendMessage("§9DustLab §7» §boscillate§7, §bpulse§7, §bbounce§7, §bflow§7, §bswirl§7, §bwave§7, §borbit§7, §bspiral§7");
            return true;
        }
        
        if (speed != 1.0) {
            effects.rotationSpeed *= speed;
            effects.oscillationSpeed *= speed;
            effects.pulseSpeed *= speed;
            effects.bounceSpeed *= speed;
            effects.flowSpeed *= speed;
            effects.swirlSpeed *= speed;
            effects.waveSpeed *= speed;
            effects.orbitSpeed *= speed;
            effects.spiralSpeed *= speed;
        }

        int effectId = plugin.getParticleModelManager().playModelWithEffects(modelName, location, lifetimeSeconds, effects);

        if (effectId == -1) {
            sender.sendMessage("§9DustLab §c» §7Failed to load model '§f" + modelName + "§7' with effects.");
            return true;
        }

        String lifetimeText = getLifetimeDisplayText(lifetimeSeconds);
        String forceText = force ? " §c(forced)" : "";
        String speedText = speed != 1.0 ? " §d(speed: " + String.format("%.1f", speed) + "x)" : "";
        sender.sendMessage("§9DustLab §b» §7Loaded model '§f" + modelName + "§7' with §b" + effectType + "§7 effect at " + 
            String.format("§b%.1f§7, §b%.1f§7, §b%.1f", location.getX(), location.getY(), location.getZ()) + 
            lifetimeText + forceText + speedText + " §7(ID: §f#" + effectId + "§7)");

        return true;
    }
    
    private boolean handlePlayerLoad(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dustlab.playerload")) {
            sender.sendMessage("§9DustLab §c» §7You don't have permission to attach models to players (dustlab.playerload).");
            return true;
        }

        if (args.length < 5) {
            sender.sendMessage("§9DustLab §c» §7Usage: /dustlab playerload <player> <model_name> <lifetime> <effect/none> [whenstill/always] [force/normal]");
            sender.sendMessage("§9DustLab §7» §7Player: Target player name");
            sender.sendMessage("§9DustLab §7» §7Model: Name of the particle model to attach");
            sender.sendMessage("§9DustLab §7» §7Lifetime: §b<number>§7[§bs§7|§bm§7|§bh§7], §binfinite§7, or §bone-time§7 (e.g., 30s, 5m, 2h)");
            sender.sendMessage("§9DustLab §7» §7Effect: §brotate§7, §brotate-x§7, §brotate-y§7, §brotate-z§7, §brotate-xy§7, §brotate-xyz§7");
            sender.sendMessage("§9DustLab §7» §7Effect: §boscillate§7, §bpulse§7, §bbounce§7, §bflow§7, §bswirl§7, §bwave§7, §borbit§7, §bspiral§7, or §bnone§7");
            sender.sendMessage("§9DustLab §7» §7Movement: §bwhenstill§7 (only when not moving) or §balways§7 (always visible)");
            sender.sendMessage("§9DustLab §7» §7Force: §bforce§7 or §bnormal§7 (force bypasses view permissions)");
            return true;
        }

        String targetPlayerName = args[1];
        String modelName = args[2];
        String lifetimeArg = args[3];
        String effectArg = args[4];

        Player targetPlayer = plugin.getServer().getPlayer(targetPlayerName);
        if (targetPlayer == null || !targetPlayer.isOnline()) {
            sender.sendMessage("§9DustLab §c» §7Player '§f" + targetPlayerName + "§7' not found or not online.");
            return true;
        }

        if (!plugin.getParticleModelManager().hasModel(modelName)) {
            sender.sendMessage("§9DustLab §c» §7Model '§f" + modelName + "§7' not found.");
            return true;
        }

        int lifetimeSeconds = parseLifetime(lifetimeArg);
        if (lifetimeSeconds == -2) {
            sender.sendMessage("§9DustLab §c» §7Invalid lifetime '§f" + lifetimeArg + "§7'. Use format: 30s, 5m, 2h, infinite, or one-time");
            return true;
        }

        ParticleEffects.EffectSettings effects = null;
        if (!effectArg.equalsIgnoreCase("none")) {
            effects = ParticleEffects.EffectSettings.parseEffect(effectArg);
            if (effects == null) {
                sender.sendMessage("§9DustLab §c» §7Invalid effect '§f" + effectArg + "§7'. Valid effects:");
                sender.sendMessage("§9DustLab §7» §brotate§7, §brotate-x§7, §brotate-y§7, §brotate-z§7, §brotate-xy§7, §brotate-xyz§7");
                sender.sendMessage("§9DustLab §7» §boscillate§7, §bpulse§7, §bbounce§7, §bflow§7, §bswirl§7");
                sender.sendMessage("§9DustLab §7» §bwave§7, §borbit§7, §bspiral§7, §bnone§7");
                return true;
            }
        }

        boolean onlyWhenStill = false;
        boolean force = false;

        if (args.length > 5) {
            String movementArg = args[5].toLowerCase();
            if (movementArg.equals("whenstill")) {
                onlyWhenStill = true;
            } else if (movementArg.equals("always")) {
                onlyWhenStill = false;
            }
        }

        if (args.length > 6) {
            String forceArg = args[6].toLowerCase();
            if (forceArg.equals("force")) {
                force = true;
            } else if (forceArg.equals("normal")) {
                force = false;
            }
        }

        if (args.length > 5 && !args[5].equalsIgnoreCase("whenstill") && !args[5].equalsIgnoreCase("always")) {
            String possibleForceArg = args[5].toLowerCase();
            if (possibleForceArg.equals("force")) {
                force = true;
            } else if (possibleForceArg.equals("normal")) {
                force = false;
            }
        }

        if (!plugin.getParticleModelManager().hasViewPermission(sender, modelName, force)) {
            if (!sender.hasPermission("dustlab.view")) {
                sender.sendMessage("§9DustLab §c» §7You don't have permission to view DustLab particles (dustlab.view).");
            } else {
                sender.sendMessage("§9DustLab §c» §7You don't have permission to view model '§f" + modelName + "§7' (dustlab.view." + modelName.toLowerCase() + ").");
                sender.sendMessage("§9DustLab §7» §7Add §bforce§7 to bypass permissions if you have dustlab.force permission.");
            }
            return true;
        }

        int effectId = plugin.getParticleModelManager().playModelOnPlayer(modelName, targetPlayer, lifetimeSeconds, effects, onlyWhenStill, force);

        if (effectId == -1) {
            sender.sendMessage("§9DustLab §c» §7Failed to attach model '§f" + modelName + "§7' to player '§f" + targetPlayerName + "§7'.");
            return true;
        }

        String lifetimeText = getLifetimeDisplayText(lifetimeSeconds);
        String stillText = onlyWhenStill ? " §e(only when still)" : " §a(always visible)";
        String forceText = force ? " §c(forced)" : "";
        String effectText = effects != null ? " §d(effect: " + effectArg + ")" : "";
        
        sender.sendMessage("§9DustLab §b» §7Attached model '§f" + modelName + "§7' to player '§f" + targetPlayerName + "§7'" + 
            lifetimeText + effectText + stillText + forceText + " §7(ID: §f#" + effectId + "§7)");

        if (!sender.equals(targetPlayer)) {
            String notifyLifetime = lifetimeSeconds == -1 ? "infinite" : (lifetimeSeconds == 0 ? "one-time" : lifetimeSeconds + " seconds");
            String notifyEffect = effects != null ? " with " + effectArg + " effect" : "";
            targetPlayer.sendMessage("§9DustLab §b» §7Model '§f" + modelName + "§7' has been attached to you for " + notifyLifetime + notifyEffect + ".");
        }

        return true;
    }
    
    private boolean handleUnload(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dustlab.unload")) {
            sender.sendMessage("§9DustLab §c» §7You don't have permission to unload particle effects (dustlab.unload).");
            return true;
        }
        
        if (args.length < 2) {
            sender.sendMessage("§9DustLab §c» §7Usage: /dustlab unload <effect_id>");
            return true;
        }
        
        try {
            int effectId = Integer.parseInt(args[1]);
            
            ParticleModelManager.EffectInfo effectInfo = plugin.getParticleModelManager().getEffectInfo(effectId);
            
            if (effectInfo == null) {
                sender.sendMessage("§9DustLab §c» §7Effect with ID §f#" + effectId + " §7not found.");
                return true;
            }
            
            boolean success = plugin.getParticleModelManager().stopEffect(effectId);
            
            if (success) {
                sender.sendMessage("§9DustLab §b» §7Unloaded effect §f#" + effectId + " §7(model: §f" + 
                    effectInfo.modelName + "§7)");
            } else {
                sender.sendMessage("§9DustLab §c» §7Failed to unload effect §f#" + effectId + "§7.");
            }
            
        } catch (NumberFormatException e) {
            sender.sendMessage("§9DustLab §c» §7Invalid effect ID. Use a number like: /dustlab unload 1");
        }
        
        return true;
    }
    
    private boolean handleMove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dustlab.move")) {
            sender.sendMessage("§9DustLab §c» §7You don't have permission to move particle effects (dustlab.move).");
            return true;
        }
        
        if (!(sender instanceof Player)) {
            sender.sendMessage("§9DustLab §c» §7Only players can move particle effects.");
            return true;
        }
        
        if (args.length < 2) {
            sender.sendMessage("§9DustLab §c» §7Usage: /dustlab move <effect_id> [x y z]");
            sender.sendMessage("§9DustLab §7» §7If coordinates are not provided, uses your current location");
            return true;
        }
        
        Player player = (Player) sender;
        
        try {
            int effectId = Integer.parseInt(args[1]);
            
            ParticleModelManager.EffectInfo effectInfo = plugin.getParticleModelManager().getEffectInfo(effectId);
            
            if (effectInfo == null) {
                sender.sendMessage("§9DustLab §c» §7Effect with ID §f#" + effectId + " §7not found.");
                return true;
            }
            
            Location newLocation;
            if (args.length >= 5) {
                try {
                    double x = Double.parseDouble(args[2]);
                    double y = Double.parseDouble(args[3]);
                    double z = Double.parseDouble(args[4]);
                    newLocation = new Location(player.getWorld(), x, y, z);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§9DustLab §c» §7Invalid coordinates. Use numbers like: /dustlab move 1 100 65 200");
                    return true;
                }
            } else {
                newLocation = player.getLocation();
            }
            
            boolean success = plugin.getParticleModelManager().moveEffect(effectId, newLocation);
            
            if (success) {
                sender.sendMessage("§9DustLab §b» §7Moved effect §f#" + effectId + " §7(model: §f" + 
                    effectInfo.modelName + "§7) to " + String.format("§b%.1f§7, §b%.1f§7, §b%.1f", 
                    newLocation.getX(), newLocation.getY(), newLocation.getZ()));
            } else {
                sender.sendMessage("§9DustLab §c» §7Failed to move effect §f#" + effectId + "§7.");
            }
            
        } catch (NumberFormatException e) {
            sender.sendMessage("§9DustLab §c» §7Invalid effect ID. Use a number like: /dustlab move 1");
        }
        
        return true;
    }
    
    private boolean handleList(CommandSender sender) {
        if (!sender.hasPermission("dustlab.list")) {
            sender.sendMessage("§9DustLab §c» §7You don't have permission to list particle models (dustlab.list).");
            return true;
        }
        
        Map<String, ParticleModel> models = plugin.getParticleModelManager().getLoadedModels();
        
        if (models.isEmpty()) {
            sender.sendMessage("§9DustLab §b» §7No particle models loaded.");
            return true;
        }
        
        sender.sendMessage("§9DustLab §b» §7Available Models:");
        boolean hasVisibleModels = false;
        
        for (String modelName : models.keySet()) {
            if (plugin.getParticleModelManager().hasViewPermission(sender, modelName, false)) {
                ParticleModel model = models.get(modelName);
                sender.sendMessage("  §b• §f" + modelName + " §7(" + model.getParticleCount() + " particles, " + 
                    model.getDuration() + " ticks)");
                hasVisibleModels = true;
            }
        }
        
        if (!hasVisibleModels) {
            sender.sendMessage("§9DustLab §7» §7No models available - you need §bdustlab.view§7 and model-specific permissions.");
        }
        
        return true;
    }
    
    private boolean handleActive(CommandSender sender) {
        if (!sender.hasPermission("dustlab.active")) {
            sender.sendMessage("§9DustLab §c» §7You don't have permission to use this command.");
            return true;
        }
        
        Map<String, ParticleModelManager.EffectInfo> activeEffects = plugin.getParticleModelManager().getActiveEffects();
        
        if (activeEffects.isEmpty()) {
            sender.sendMessage("§9DustLab §b» §7No active particle effects.");
            return true;
        }
        
        sender.sendMessage("§9DustLab §b» §7Active Effects:");
        for (Map.Entry<String, ParticleModelManager.EffectInfo> entry : activeEffects.entrySet()) {
            ParticleModelManager.EffectInfo info = entry.getValue();
            String duration = String.format("%.1fs", (System.currentTimeMillis() - info.startTime) / 1000.0);
            String loopText = info.isLooping ? " §b(looping)" : "";
            
            if (info.attachedPlayer != null) {
                String playerName = info.attachedPlayer.isOnline() ? info.attachedPlayer.getName() : info.attachedPlayer.getName() + " §c(offline)";
                String stillText = info.onlyWhenStill ? " §e(when still)" : "";
                String forceText = info.forceVisible ? " §c(forced)" : "";
                
                sender.sendMessage("  §b• §f#" + info.id + " §7- §f" + info.modelName + " §7attached to §b" + playerName + 
                    " §7(running " + duration + ")" + loopText + stillText + forceText);
            } else {
                sender.sendMessage("  §b• §f#" + info.id + " §7- §f" + info.modelName + " §7at " + 
                    String.format("§b%.1f§7, §b%.1f§7, §b%.1f", 
                        info.location.getX(), info.location.getY(), info.location.getZ()) + 
                    " §7(running " + duration + ")" + loopText);
            }
        }
        
        return true;
    }
    
    private boolean handleInfo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dustlab.info")) {
            sender.sendMessage("§9DustLab §c» §7You don't have permission to use this command.");
            return true;
        }
        
        if (args.length < 2) {
            sender.sendMessage("§9DustLab §c» §7Usage: /dustlab info <model_name>");
            return true;
        }
        
        String modelName = args[1];
        ParticleModel model = plugin.getParticleModelManager().getModel(modelName);
        
        if (model == null) {
            sender.sendMessage("§9DustLab §c» §7Model '§f" + modelName + "§7' not found.");
            return true;
        }
        
        sender.sendMessage("§9§l❖ Model Info: §f" + modelName + " §9❖");
        sender.sendMessage("");
        
        if (model.getMetadata() != null) {
            sender.sendMessage("§bGenerated By: §f" + (model.getGeneratedBy() != null ? model.getGeneratedBy() : "Unknown"));
            sender.sendMessage("§9Website: §f" + (model.getWebsite() != null ? model.getWebsite() : "Unknown"));
            sender.sendMessage("§bGenerated On: §f" + (model.getGeneratedOn() != null ? model.getGeneratedOn() : "Unknown"));
            sender.sendMessage("§9Source File: §f" + (model.getSourceFile() != null ? model.getSourceFile() : "Unknown"));
            sender.sendMessage("§bParticle Count: §f" + model.getParticleCount());
            
            Map<String, Object> settings = model.getSettings();
            if (settings != null) {
                sender.sendMessage("");
                sender.sendMessage("§bSettings:");
                for (Map.Entry<String, Object> entry : settings.entrySet()) {
                    sender.sendMessage("  §7" + entry.getKey() + ": §f" + entry.getValue());
                }
            }
        } else {
            sender.sendMessage("§7No metadata available for this model.");
            sender.sendMessage("§bParticle Count: §f" + model.getParticleCount());
            sender.sendMessage("§bDuration: §f" + model.getDuration() + " ticks");
        }
        
        return true;
    }
    
    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("dustlab.reload")) {
            sender.sendMessage("§9DustLab §c» §7You don't have permission to reload the plugin (dustlab.reload).");
            return true;
        }
        
        sender.sendMessage("§9DustLab §b» §7Reloading configuration and particle models...");
        try {
            plugin.getDustLabConfig().reloadConfig();
            sender.sendMessage("§9DustLab §a» §7Configuration reloaded.");
        } catch (Exception e) {
            sender.sendMessage("§9DustLab §c» §7Failed to reload configuration: " + e.getMessage());
            plugin.getLogger().warning("Config reload failed: " + e.getMessage());
        }
        plugin.getParticleModelManager().forceSave(); 
        plugin.getParticleModelManager().reloadModels();
        
        int count = plugin.getParticleModelManager().getLoadedModels().size();
        sender.sendMessage("§9DustLab §b» §7Reloaded §b" + count + "§7 particle models.");
        
        return true;
    }
    
    private boolean handleStats(CommandSender sender) {
        if (!sender.hasPermission("dustlab.stats")) {
            sender.sendMessage("§9DustLab §c» §7You don't have permission to view plugin statistics (dustlab.stats).");
            return true;
        }
        
        sender.sendMessage("§9§l❖ DustLab Statistics ❖");
        sender.sendMessage("§7§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        ParticleModelManager manager = plugin.getParticleModelManager();
        int totalModels = manager.getAllModels().size();
        
        sender.sendMessage("§a▸ Total Loaded Models: §f" + totalModels);
        ParticleModelManager.MemoryUsageReport memory = manager.estimateMemoryUsage();
        sender.sendMessage("§7    §8• §bAnimated: §f" + memory.animatedModelCount() + " §7models §8(§f" + formatCount(memory.animationFrameCount()) + "§7 frames§8)");
        sender.sendMessage("§7    §8• §9Static: §f" + memory.staticModelCount() + " §7models");

        double totalMb = memory.totalBytes() / (1024.0D * 1024.0D);
        double packedMb = memory.packedBytes() / (1024.0D * 1024.0D);
        double legacyMb = memory.legacyBytes() / (1024.0D * 1024.0D);
        double optimizerMb = memory.optimizerBytes() / (1024.0D * 1024.0D);

        sender.sendMessage("§b▸ §7Plugin Heap Footprint: §f" + formatMb(totalMb) + " §7MB");
        sender.sendMessage("§7    §8• §9Packed model data: §f" + formatMb(packedMb) + " §7MB §8(§f" + formatCount(memory.packedParticleCount()) + "§7 particles§8)");
        if (memory.legacyBytes() > 0L) {
            sender.sendMessage("§7    §8• §1Legacy particle lists: §f" + formatMb(legacyMb) + " §7MB §8(§f" + formatCount(memory.legacyParticleCount()) + "§7 particles§8)");
        }
        sender.sendMessage("§7    §8• §9Optimizer cache: §f" + formatMb(optimizerMb) + " §7MB §8(§f" + formatCount(memory.optimizerTrackedParticles()) + "§7 tracked§8)");
        sender.sendMessage("§a▸ Active Effects: §f" + formatCount(memory.activeEffectCount()) +
                " §7(optimizer tracking §f" + formatCount(memory.optimizerTrackedEffects()) + "§7 effects)");

        PerformanceMonitor monitor = plugin.getPerformanceMonitor();
        if (monitor != null) {
            PerformanceMonitor.PerformanceSnapshot snapshot = monitor.snapshot();
            sender.sendMessage("§b▸ §7Process Usage:");
            sendPerformanceWindow(sender, "Last 10s", snapshot.tenSeconds());
            sendPerformanceWindow(sender, "Last 1m", snapshot.oneMinute());
            sendPerformanceWindow(sender, "Last 5m", snapshot.fiveMinutes());
        } else {
            sender.sendMessage("§c▸ §7Performance data not available.");
        }

        sender.sendMessage("§7§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        return true;
    }

    private void sendPerformanceWindow(CommandSender sender, String label, PerformanceMonitor.WindowAverages window) {
        if (window.samples() <= 0) {
            sender.sendMessage("§7  §8- §b" + label + " §8» §7Collecting data...");
            return;
        }

        String cpuText = window.hasCpuData()
                ? String.format(java.util.Locale.US, "§f%.1f§7%%", window.cpuPercent())
                : "§cN/A";
        String ramText = String.format(java.util.Locale.US, "§f%.1f §7MB", window.ramMb());
        sender.sendMessage("§7  §8- §b" + label + " §8» §9CPU: " + cpuText + " §8| §1RAM: " + ramText +
                " §8(§7" + window.samples() + " samples§8)");
    }

    private String formatMb(double value) {
        return String.format(java.util.Locale.US, "%.2f", value);
    }

    private String formatCount(long value) {
        return String.format(java.util.Locale.US, "%,d", value);
    }
    
    private boolean handleHelp(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dustlab.help")) {
            sender.sendMessage("§9DustLab §c» §7You don't have permission to view command help (dustlab.help).");
            return true;
        }
        
        if (args.length >= 2) {
            return showDetailedHelp(sender, args[1].toLowerCase());
        } else {
            sendHelp(sender);
            return true;
        }
    }
    
    private boolean handleWebsite(CommandSender sender) {
        if (!sender.hasPermission("dustlab.website")) {
            sender.sendMessage("§9DustLab §c» check the site out at https://winss.xyz/dustlab");
            return true;
        }
        
        sender.sendMessage("§9§l❖ DustLab Web ❖");
        sender.sendMessage("");
        sender.sendMessage("§7Create and design particle models online:");
        
        if (sender instanceof Player) {
            Player player = (Player) sender;
            Component message = Component.text("§b§n https://winss.xyz/dustlab")
                .clickEvent(ClickEvent.openUrl("https://winss.xyz/dustlab"))
                .hoverEvent(HoverEvent.showText(Component.text("§7Click to open the DustLab web interface")));
            player.sendMessage(message);
        } else {
            sender.sendMessage("§b§nhttps://winss.xyz/dustlab");
        }
        
        sender.sendMessage("");
        sender.sendMessage("§8  • §7Visual editor");
        sender.sendMessage("§8  • §7Images & 3D models to particle models");
        sender.sendMessage("§8  • §7Support for animated models (delay and batching)");
        
        return true;
    }
    
    private boolean showDetailedHelp(CommandSender sender, String command) {
        switch (command) {
            case "load":
                sender.sendMessage("§9§l❖ DustLab - Load Command ❖");
                sender.sendMessage("");
                sender.sendMessage("§b/dustlab load <model> [x y z] [lifetime] [force/normal]");
                sender.sendMessage("");
                sender.sendMessage("§7Load a particle model at the specified location with optional parameters.");
                sender.sendMessage("");
                sender.sendMessage("§bParameters:");
                sender.sendMessage("§7  §bmodel§7: Name of the model to load");
                sender.sendMessage("§7  §b[x y z]§7: Optional coordinates (uses your location if not provided)");
                sender.sendMessage("§7  §b[lifetime]§7: §b<number>§7[§bs§7|§bm§7|§bh§7], §binfinite§7, or §bone-time§7 (e.g., 30s, 5m, 2h)");
                if (sender.hasPermission("dustlab.force")) {
                    sender.sendMessage("§7  §b[force]§7: §bforce§7 or §bnormal§7 (force bypasses view permissions)");
                }
                sender.sendMessage("");
                sender.sendMessage("§bExamples:");
                sender.sendMessage("§7  §f/dl load heart_shape");
                sender.sendMessage("§7  §f/dl load star_shape 10 64 -5 infinite");
                sender.sendMessage("§7  §f/dl load magic_circle 30s");
                sender.sendMessage("§7  §f/dl load aurora 5m");
                return true;
                
            case "loadfx":
                sender.sendMessage("§9§l❖ DustLab - LoadFX Command ❖");
                sender.sendMessage("");
                sender.sendMessage("§b/dustlab loadfx <model> <effect> [x y z] [lifetime] [speed] [force/normal]");
                sender.sendMessage("");
                sender.sendMessage("§7Load a particle model with dynamic effects applied.");
                sender.sendMessage("");
                sender.sendMessage("§bEffect Types:");
                sender.sendMessage("§7  §bRotation:§7 rotate, rotate-x, rotate-y, rotate-z, rotate-xy, rotate-xyz");
                sender.sendMessage("§7  §bMotion:§7 oscillate, pulse, bounce, flow, swirl, wave, orbit, spiral");
                sender.sendMessage("");
                sender.sendMessage("§bParameters:");
                sender.sendMessage("§7  §bspeed§7: Effect animation speed (0.1-5.0, default: 1.0)");
                sender.sendMessage("");
                sender.sendMessage("§bExamples:");
                sender.sendMessage("§7  §f/dl loadfx dragon_breath rotate-xyz");
                sender.sendMessage("§7  §f/dl loadfx spiral_galaxy swirl 0 70 0 infinite 2.0");
                sender.sendMessage("§7  §f/dl loadfx heart_shape pulse 30s");
                sender.sendMessage("§7  §f/dl loadfx star_shape rotate 2m");
                return true;
                
            case "create":
                sender.sendMessage("§9§l❖ DustLab - Create Commands ❖");
                sender.sendMessage("");
                sender.sendMessage("§bPlayer Models:");
                sender.sendMessage("§7  §f/dl create player_avatar <name> <player> <resolution> <scale> [normal/temp]");
                sender.sendMessage("§7  §f/dl create player_body <name> <player> <resolution> <scale> [normal/temp]");
                sender.sendMessage("");
                sender.sendMessage("§bAnimated Media: §c§lExperimental!");
                sender.sendMessage("§7  §f/dl create media <name> <url> <width> <height> <maxparticles> [skip] [normal/temp]");
                sender.sendMessage("");
                sender.sendMessage("§bParameters:");
                sender.sendMessage("§7  §bresolution§7: Image resolution (8-512 pixels)");
                sender.sendMessage("§7  §bscale§7: Model size in blocks (0.1-50.0)");
                sender.sendMessage("§7  §bskip§7: Frame skip factor (2=every 2nd frame, 3=every 3rd, etc.)");
                sender.sendMessage("§7  §bwidth/height§7: Model dimensions in blocks");
                sender.sendMessage("§7  §bmaxparticles§7: Maximum particles per frame");
                sender.sendMessage("");
                sender.sendMessage("§bExamples:");
                sender.sendMessage("§7  §f/dl create player_avatar steve_head Steve 64 5.0");
                sender.sendMessage("§7  §f/dl create media my_gif https://example.com/animation.gif 20 20 5000 2 temp");
                return true;
                
            case "playerload":
                if (!sender.hasPermission("dustlab.playerload")) {
                    sender.sendMessage("§9DustLab §c» §7You don't have permission to view this command.");
                    return true;
                }
                sender.sendMessage("§9§l❖ DustLab - PlayerLoad Command ❖");
                sender.sendMessage("");
                sender.sendMessage("§b/dustlab playerload <player> <model> <lifetime> <effect/none> [whenstill/always] [force/normal]");
                sender.sendMessage("");
                sender.sendMessage("§7Attach a particle model to a player that follows them around.");
                sender.sendMessage("");
                sender.sendMessage("§bParameters:");
                sender.sendMessage("§7  §bplayer§7: Target player name");
                sender.sendMessage("§7  §bmodel§7: Name of the model to attach");
                sender.sendMessage("§7  §blifetime§7: Duration in seconds, §binfinite§7, or §bone-time");
                sender.sendMessage("§7  §beffect§7: Animation effect or §bnone");
                sender.sendMessage("§7  §bmovement§7: §bwhenstill§7 (only when not moving) or §balways§7");
                sender.sendMessage("");
                sender.sendMessage("§bExamples:");
                sender.sendMessage("§7  §f/dl playerload Steve heart_shape infinite pulse whenstill");
                return true;
                
            case "unload":
                sender.sendMessage("§9§l❖ DustLab - Unload Command ❖");
                sender.sendMessage("");
                sender.sendMessage("§b/dustlab unload <id>");
                sender.sendMessage("");
                sender.sendMessage("§7Remove a specific active particle effect by its ID.");
                sender.sendMessage("§7Use §f/dl active§7 to see effect IDs.");
                sender.sendMessage("");
                sender.sendMessage("§bExample:");
                sender.sendMessage("§7  §f/dl unload 12345");
                return true;
                
            case "move":
                sender.sendMessage("§9§l❖ DustLab - Move Command ❖");
                sender.sendMessage("");
                sender.sendMessage("§b/dustlab move <id> [x y z]");
                sender.sendMessage("");
                sender.sendMessage("§7Move an active particle effect to new coordinates.");
                sender.sendMessage("§7If coordinates are not provided, moves to your current location.");
                sender.sendMessage("");
                sender.sendMessage("§bExample:");
                sender.sendMessage("§7  §f/dl move 12345 10 64 -5");
                return true;
                
            case "list":
                sender.sendMessage("§9§l❖ DustLab - List Command ❖");
                sender.sendMessage("");
                sender.sendMessage("§b/dustlab list");
                sender.sendMessage("");
                sender.sendMessage("§7Display all available particle models that can be loaded.");
                return true;
                
            case "active":
                if (!sender.hasPermission("dustlab.active")) {
                    sender.sendMessage("§9DustLab §c» §7You don't have permission to view this command.");
                    return true;
                }
                sender.sendMessage("§9§l❖ DustLab - Active Command ❖");
                sender.sendMessage("");
                sender.sendMessage("§b/dustlab active");
                sender.sendMessage("");
                sender.sendMessage("§7Show all currently running particle effects with their IDs and details.");
                return true;
                
            case "info":
                if (!sender.hasPermission("dustlab.info")) {
                    sender.sendMessage("§9DustLab §c» §7You don't have permission to view this command.");
                    return true;
                }
                sender.sendMessage("§9§l❖ DustLab - Info Command ❖");
                sender.sendMessage("");
                sender.sendMessage("§b/dustlab info <model>");
                sender.sendMessage("");
                sender.sendMessage("§7Display detailed metadata and statistics about a specific model.");
                sender.sendMessage("");
                sender.sendMessage("§bExample:");
                sender.sendMessage("§7  §f/dl info heart_shape");
                return true;
                
            case "delete":
                if (!sender.hasPermission("dustlab.admin")) {
                    sender.sendMessage("§9DustLab §c» §7You don't have permission to view this command.");
                    return true;
                }
                sender.sendMessage("§9§l❖ DustLab - Delete Command ❖");
                sender.sendMessage("");
                sender.sendMessage("§b/dustlab delete <model>");
                sender.sendMessage("");
                sender.sendMessage("§c§lWARNING: §7This permanently deletes the model file!");
                sender.sendMessage("§7After running delete, confirm with §f/dl confirm§7 within the time window.");
                sender.sendMessage("");
                sender.sendMessage("§bExample:");
                sender.sendMessage("§7  §f/dl delete old_model");
                sender.sendMessage("§7  §f/dl confirm");
                return true;
                
            case "reload":
                if (!sender.hasPermission("dustlab.admin")) {
                    sender.sendMessage("§9DustLab §c» §7You don't have permission to view this command.");
                    return true;
                }
                sender.sendMessage("§9§l❖ DustLab - Reload Command ❖");
                sender.sendMessage("");
                sender.sendMessage("§b/dustlab reload");
                sender.sendMessage("");
                sender.sendMessage("§7Reload all particle models from disk. Active effects are preserved.");
                return true;
                
            case "stats":
                if (!sender.hasPermission("dustlab.admin")) {
                    sender.sendMessage("§9DustLab §c» §7You don't have permission to view this command.");
                    return true;
                }
                sender.sendMessage("§9§l❖ DustLab - Stats Command ❖");
                sender.sendMessage("");
                sender.sendMessage("§b/dustlab stats");
                sender.sendMessage("");
                sender.sendMessage("§7Display particle system statistics including optimization metrics.");
                sender.sendMessage("§7Shows active effects, tracked particles, and memory usage.");
                sender.sendMessage("");
                sender.sendMessage("§bOptimization Info:");
                sender.sendMessage("§7The system optimizes animated models by only updating particles");
                sender.sendMessage("§7when their color changes by more than 2%, reducing client lag.");
                return true;
                
            case "website":
                sender.sendMessage("§9§l❖ DustLab - Website Command ❖");
                sender.sendMessage("");
                sender.sendMessage("§b/dustlab website");
                sender.sendMessage("");
                sender.sendMessage("§7Opens the DustLab web interface where you can:");
                sender.sendMessage("§8  • §7Visual editor");
                sender.sendMessage("§8  • §7Images & 3D models to particle models");
                sender.sendMessage("§8  • §7Support for animated models (delay and batching)");
                sender.sendMessage("");
                sender.sendMessage("§7The web interface provides an easy way to create models");
                return true;
                
            default:
                sender.sendMessage("§9DustLab §c» §7Unknown command: §f" + command);
                sender.sendMessage("§7Use §b/dl help§7 to see all available commands.");
                return true;
        }
    }
    
    private void sendClickableCommand(CommandSender sender, String command, String description) {
        if (sender instanceof Player) {
            Player player = (Player) sender;
            Component commandText = Component.text("  ")
                .append(Component.text("/dl " + command)
                    .color(NamedTextColor.AQUA)
                    .decoration(TextDecoration.UNDERLINED, false)
                    .clickEvent(ClickEvent.runCommand("/dl help " + command))
                    .hoverEvent(HoverEvent.showText(Component.text("Click for more info on this command!"))))
                .append(Component.text(" - " + description)
                    .color(NamedTextColor.GRAY));
            player.sendMessage(commandText);
        } else {
            sender.sendMessage("  §b/dl " + command + "§7 - " + description);
        }
    }
    
    private void sendClickableCreateCommand(CommandSender sender, String fullCommand, String description) {
        if (sender instanceof Player) {
            Player player = (Player) sender;
            Component commandText = Component.text("  ")
                .append(Component.text("/dl " + fullCommand)
                    .color(NamedTextColor.AQUA)
                    .decoration(TextDecoration.UNDERLINED, false)
                    .clickEvent(ClickEvent.runCommand("/dl help create"))
                    .hoverEvent(HoverEvent.showText(Component.text("Click for create command help"))))
                .append(Component.text(" - " + description)
                    .color(NamedTextColor.GRAY));
            player.sendMessage(commandText);
        } else {
            sender.sendMessage("  §b/dl " + fullCommand + "§7 - " + description);
        }
    }
    
    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§9§l❖ §b§lDustLab Commands §9§l❖");
        sender.sendMessage("");
        
        boolean hasCoreCommands = false;
        StringBuilder coreHelp = new StringBuilder();
        
        if (sender.hasPermission("dustlab.load") || sender.hasPermission("dustlab.loadfx") || 
            sender.hasPermission("dustlab.playerload") || sender.hasPermission("dustlab.unload") || 
            sender.hasPermission("dustlab.move") || sender.hasPermission("dustlab.delete") || 
            sender.hasPermission("dustlab.reload")) {
            if (!hasCoreCommands) {
                coreHelp.append("§7§l▸ Core Commands:\n");
                hasCoreCommands = true;
            }
        }
        
        if (hasCoreCommands) {
            sender.sendMessage("§7§l▸ Core Commands:");
            if (sender.hasPermission("dustlab.load")) sendClickableCommand(sender, "load", "Load particle models");
            if (sender.hasPermission("dustlab.loadfx")) sendClickableCommand(sender, "loadfx", "Load models with effects");
            if (sender.hasPermission("dustlab.playerload")) sendClickableCommand(sender, "playerload", "Attach models to players");
            if (sender.hasPermission("dustlab.unload")) sendClickableCommand(sender, "unload", "Remove active effects");
            if (sender.hasPermission("dustlab.move")) sendClickableCommand(sender, "move", "Move active effects");
            if (sender.hasPermission("dustlab.delete")) sendClickableCommand(sender, "delete", "Delete models permanently");
            if (sender.hasPermission("dustlab.reload")) sendClickableCommand(sender, "reload", "Reload all models");
        }
        
        // Creation commands
        if (sender.hasPermission("dustlab.create")) {
            if (hasCoreCommands) sender.sendMessage("");
            sender.sendMessage("§7§l▸ Creation Commands:");
            sendClickableCreateCommand(sender, "create player_avatar", "Create player head models");
            sendClickableCreateCommand(sender, "create player_body", "Create player body models");
            if (sender instanceof Player) {
                Player player = (Player) sender;
                Component mediaCommand = Component.text("  ")
                    .append(Component.text("/dl create media")
                        .color(NamedTextColor.AQUA)
                        .decoration(TextDecoration.UNDERLINED, false)
                        .clickEvent(ClickEvent.runCommand("/dl help create"))
                        .hoverEvent(HoverEvent.showText(Component.text("Click for create command help"))))
                    .append(Component.text(" ")
                        .color(NamedTextColor.RED)
                        .decoration(TextDecoration.BOLD, true)
                        .append(Component.text("Experimental!")
                            .color(NamedTextColor.RED)
                            .decoration(TextDecoration.BOLD, true)))
                    .append(Component.text(" - Create animated models")
                        .color(NamedTextColor.GRAY));
                player.sendMessage(mediaCommand);
            } else {
                sender.sendMessage("  §b/dl create media !§7 - Create animated models §c§lExperimental");
            }
        }
        
        // Information Commands
        if (sender.hasPermission("dustlab.list") || sender.hasPermission("dustlab.active") || 
            sender.hasPermission("dustlab.info") || sender.hasPermission("dustlab.stats")) {
            
            if (hasCoreCommands || sender.hasPermission("dustlab.create")) sender.sendMessage("");
            sender.sendMessage("§7§l▸ Information Commands:");
            
            // Information/viewing commands
            if (sender.hasPermission("dustlab.list")) sendClickableCommand(sender, "list", "List available models");
            if (sender.hasPermission("dustlab.active")) sendClickableCommand(sender, "active", "Show active effects");
            if (sender.hasPermission("dustlab.info")) sendClickableCommand(sender, "info", "Show model details");
            if (sender.hasPermission("dustlab.stats")) sendClickableCommand(sender, "stats", "Show system statistics");
            if (sender.hasPermission("dustlab.website")) sendClickableCommand(sender, "website", "Open DustLab web interface");
        }
        
        sender.sendMessage("");
        if (sender.hasPermission("dustlab.help")) {
            sender.sendMessage("§7Use §b/dl help <command>§7 for detailed information about a specific command.");
        }
        sender.sendMessage("§7Plugin by §bWinss §7| Version §f" + plugin.getPluginMeta().getVersion());
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            List<String> subCommands = new ArrayList<>();
            
            if (sender.hasPermission("dustlab.load")) subCommands.add("load");
            if (sender.hasPermission("dustlab.loadfx")) subCommands.add("loadfx");
            if (sender.hasPermission("dustlab.playerload")) subCommands.add("playerload");
            if (sender.hasPermission("dustlab.create")) subCommands.add("create");
            if (sender.hasPermission("dustlab.delete")) subCommands.add("delete");
            if (sender.hasPermission("dustlab.delete")) subCommands.add("confirm");
            if (sender.hasPermission("dustlab.unload")) subCommands.add("unload");
            if (sender.hasPermission("dustlab.move")) subCommands.add("move");
            if (sender.hasPermission("dustlab.reload")) subCommands.add("reload");
            if (sender.hasPermission("dustlab.stats")) subCommands.add("stats");
            if (sender.hasPermission("dustlab.list")) subCommands.add("list");
            if (sender.hasPermission("dustlab.active")) subCommands.add("active");
            if (sender.hasPermission("dustlab.info")) subCommands.add("info");
            if (sender.hasPermission("dustlab.help")) subCommands.add("help");
            if (sender.hasPermission("dustlab.website")) subCommands.add("website");
            
            for (String subCommand : subCommands) {
                if (subCommand.toLowerCase().startsWith(args[0].toLowerCase())) {
                    completions.add(subCommand);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("playerload")) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                    completions.add(player.getName());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("playerload")) {
            for (String modelName : plugin.getParticleModelManager().getLoadedModels().keySet()) {
                if (modelName.toLowerCase().startsWith(args[2].toLowerCase()) && 
                    plugin.getParticleModelManager().hasViewPermission(sender, modelName, sender.hasPermission("dustlab.force"))) {
                    completions.add(modelName);
                }
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("playerload")) {
            List<String> lifetimeOptions = Arrays.asList("infinite", "one-time", "30", "60", "300", "600");
            for (String option : lifetimeOptions) {
                if (option.toLowerCase().startsWith(args[3].toLowerCase())) {
                    completions.add(option);
                }
            }
        } else if (args.length == 5 && args[0].equalsIgnoreCase("playerload")) {
            List<String> effectOptions = Arrays.asList("rotate", "rotate-x", "rotate-y", "rotate-z", "rotate-xy", "rotate-xz", "rotate-yz", "rotate-xyz",
                                                       "oscillate", "pulse", "bounce", "flow", "swirl", "wave", "orbit", "spiral", "none");
            for (String option : effectOptions) {
                if (option.toLowerCase().startsWith(args[4].toLowerCase())) {
                    completions.add(option);
                }
            }
        } else if (args.length == 6 && args[0].equalsIgnoreCase("playerload")) {
            List<String> movementOptions = Arrays.asList("whenstill", "always");
            for (String option : movementOptions) {
                if (option.toLowerCase().startsWith(args[5].toLowerCase())) {
                    completions.add(option);
                }
            }
        } else if (args.length == 7 && args[0].equalsIgnoreCase("playerload")) {
            if (sender.hasPermission("dustlab.force")) {
                List<String> forceOptions = Arrays.asList("force", "normal");
                for (String option : forceOptions) {
                    if (option.toLowerCase().startsWith(args[6].toLowerCase())) {
                        completions.add(option);
                    }
                }
            } else {
                addIfMatches(completions, args[6], "normal");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("help")) {
            List<String> helpCommands = Arrays.asList("load", "loadfx", "playerload", "create", "unload", "move", "list", "active", "info", "delete", "reload");
            for (String cmdName : helpCommands) {
                if (cmdName.toLowerCase().startsWith(args[1].toLowerCase())) {
                    completions.add(cmdName);
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("load") || args[0].equalsIgnoreCase("loadfx") || args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("delete"))) {
            for (String modelName : plugin.getParticleModelManager().getLoadedModels().keySet()) {
                if (modelName.toLowerCase().startsWith(args[1].toLowerCase())) {
                    if (args[0].equalsIgnoreCase("load") || args[0].equalsIgnoreCase("loadfx") || args[0].equalsIgnoreCase("info")) {
                        if (plugin.getParticleModelManager().hasViewPermission(sender, modelName, sender.hasPermission("dustlab.force"))) {
                            completions.add(modelName);
                        }
                    } else if (args[0].equalsIgnoreCase("delete")) {
                        completions.add(modelName);
                    }
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("loadfx")) {
            List<String> effectTypes = Arrays.asList("rotate", "rotate-x", "rotate-y", "rotate-z", "rotate-xy", "rotate-xz", "rotate-yz", "rotate-xyz",
                                                     "oscillate", "pulse", "bounce", "flow", "swirl", "wave", "orbit", "spiral");
            for (String effectType : effectTypes) {
                if (effectType.toLowerCase().startsWith(args[2].toLowerCase())) {
                    completions.add(effectType);
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("unload") || args[0].equalsIgnoreCase("move"))) {
            Map<String, ParticleModelManager.EffectInfo> activeEffects = plugin.getParticleModelManager().getActiveEffects();
            for (ParticleModelManager.EffectInfo info : activeEffects.values()) {
                String idStr = String.valueOf(info.id);
                if (idStr.startsWith(args[1])) {
                    completions.add(idStr);
                }
            }
        } else if (args[0].equalsIgnoreCase("create") && args.length >= 2) {
            return getCreateTabCompletion(sender, args);
        } else if (args[0].equalsIgnoreCase("load") && args.length >= 3) {
            return getLoadTabCompletion(sender, args);
        } else if (args[0].equalsIgnoreCase("loadfx") && args.length >= 4) {
            return getLoadFxTabCompletion(sender, args);
        }
        
        return completions;
    }
    
    private List<String> getLoadTabCompletion(CommandSender sender, String[] args) {
        List<String> completions = new ArrayList<>();
        
        int paramIndex = args.length - 1;
        int startIndex = 2; 
        
        if (args.length >= 5) {
            try {
                Double.parseDouble(args[2]);
                Double.parseDouble(args[3]);
                Double.parseDouble(args[4]);
                startIndex = 5; 
            } catch (NumberFormatException e) {
            }
        }
        
        int currentParam = paramIndex - startIndex + 1;
        
        if (currentParam == 1) {
            addIfMatches(completions, args[paramIndex], "infinite", "one-time", "30", "60", "300");
        } else if (currentParam == 2) {
            if (sender.hasPermission("dustlab.force")) {
                addIfMatches(completions, args[paramIndex], "force", "normal");
            } else {
                addIfMatches(completions, args[paramIndex], "normal");
            }
        }
        
        return completions;
    }
    
    private List<String> getLoadFxTabCompletion(CommandSender sender, String[] args) {
        List<String> completions = new ArrayList<>();
        
        int paramIndex = args.length - 1;
        int startIndex = 3;
        
        if (args.length >= 6) {
            try {
                Double.parseDouble(args[3]);
                Double.parseDouble(args[4]);
                Double.parseDouble(args[5]);
                startIndex = 6;
            } catch (NumberFormatException e) {
            }
        }
        
        int currentParam = paramIndex - startIndex + 1; 
        
        if (currentParam == 1) {
            addIfMatches(completions, args[paramIndex], "infinite", "one-time", "30", "60", "300");
        } else if (currentParam == 2) {
            addIfMatches(completions, args[paramIndex], "0.5", "1.0", "2.0", "3.0");
        } else if (currentParam == 3) {
            if (sender.hasPermission("dustlab.force")) {
                addIfMatches(completions, args[paramIndex], "force", "normal");
            } else {
                addIfMatches(completions, args[paramIndex], "normal");
            }
        }
        
        return completions;
    }
    
    private void addIfMatches(List<String> completions, String input, String... options) {
        for (String option : options) {
            if (option.toLowerCase().startsWith(input.toLowerCase())) {
                completions.add(option);
            }
        }
    }


    private List<String> getCreateTabCompletion(CommandSender sender, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 2) {
            addIfMatches(completions, args[1], "player_avatar", "player_body", "media");
        } else if (args.length == 3) {
            return completions;
        } else if (args.length >= 4) {
            String type = args[1].toLowerCase();
            
            if (type.equals("player_avatar") || type.equals("player_body")) {
                return getCreatePlayerTabCompletion(sender, args);
            } else if (type.equals("media")) {
                return getCreateMediaTabCompletion(sender, args);
            }
        }
        
        return completions;
    }
    
    /**
     * Handle tab completion for player creation commands
     */
    private List<String> getCreatePlayerTabCompletion(CommandSender sender, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 4) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(args[3].toLowerCase())) {
                    completions.add(player.getName());
                }
            }
        } else if (args.length == 5) {
            addIfMatches(completions, args[4], "16", "32", "64", "128", "256");
        } else if (args.length == 6) {
            addIfMatches(completions, args[5], "1", "2", "3", "5", "10");
        } else if (args.length == 7) {
            addIfMatches(completions, args[6], "normal", "temp");
        }
        
        return completions;
    }
    

    private List<String> getCreateMediaTabCompletion(CommandSender sender, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 4) {
            return completions;
        } else if (args.length == 5) {
            addIfMatches(completions, args[4], "5", "10", "15", "20", "25", "30");
        } else if (args.length == 6) {
            addIfMatches(completions, args[5], "5", "10", "15", "20", "25", "30");
        } else if (args.length == 7) {
            addIfMatches(completions, args[6], "500", "1000", "2000", "5000", "10000");
        } else if (args.length == 8) {
            addIfMatches(completions, args[7], "2", "3", "4", "5", "normal", "temp");
        } else if (args.length == 9) {
            addIfMatches(completions, args[8], "normal", "temp");
        }
        
        return completions;
    }


    private boolean handleCreate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dustlab.create")) {
            sender.sendMessage("§9DustLab §c» §7You don't have permission to create particle models (dustlab.create).");
            return true;
        }
        
        if (args.length < 2) {
            sender.sendMessage("§9DustLab §c» §7Usage:");
            sender.sendMessage("§9DustLab §7» §b/dustlab create player_avatar <modelName> <playerName> <resolution> <scale> [normal/temp]");
            sender.sendMessage("§9DustLab §7» §b/dustlab create player_body <modelName> <playerName> <resolution> <scale> [normal/temp]");
            sender.sendMessage("§9DustLab §7» §b/dustlab create media <modelName> <url> <blockwidth> <blockheight> <maxparticles> [skip] [normal/temp] [gzip] §c§lExperimental!");
            sender.sendMessage("");
            sender.sendMessage("§e§l💡§7 Use §b/dl website§7 to access the §9DustLab§7 web app for model creation!");
            return true;
        }

        String type = args[1].toLowerCase();
        
        switch (type) {
            case "player_avatar":
            case "player_body":
                return handleCreatePlayer(sender, args);
            case "media":
                return handleCreateMedia(sender, args);
            default:
                sender.sendMessage("§9DustLab §c» §7Invalid creation type. Use §bplayer_avatar§7, §bplayer_body§7, or §bmedia§7.");
                return true;
        }
    }


    private boolean handleCreatePlayer(CommandSender sender, String[] args) {
        if (args.length < 6) {
            sender.sendMessage("§9DustLab §c» §7Usage: /dustlab create <type> <modelName> <playerName> <resolution> <scale> [normal/temp]");
            sender.sendMessage("§9DustLab §7» §7Type: §bplayer_avatar§7 or §bplayer_body &7or §bmedia");
            sender.sendMessage("§9DustLab §7» §7ModelName: §bName for the generated model");
            sender.sendMessage("§9DustLab §7» §7PlayerName: §bMinecraft player name");
            sender.sendMessage("§9DustLab §7» §7Resolution: §b32§7, §b64§7, §b128§7, etc.");
            sender.sendMessage("§9DustLab §7» §7Scale: §bMax blocks for image size (e.g., 5)");
            return true;
        }

        String type = args[1].toLowerCase();
        String modelName = args[2];
        String playerName = args[3];
        String resolutionStr = args[4];
        String scaleStr = args[5];
        String persistence = args.length > 6 ? args[6].toLowerCase() : "normal";

        if (!type.equals("player_avatar") && !type.equals("player_body")) {
            sender.sendMessage("§9DustLab §c» §7Invalid type. Use §bplayer_avatar§7 or §bplayer_body§7.");
            return true;
        }

        int resolution;
        try {
            resolution = Integer.parseInt(resolutionStr);
            if (resolution < 8 || resolution > 512) {
                sender.sendMessage("§9DustLab §c» §7Resolution must be between 8 and 512.");
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§9DustLab §c» §7Invalid resolution. Must be a number (e.g., 64).");
            return true;
        }

        double scale;
        try {
            scale = Double.parseDouble(scaleStr);
            if (scale < 0.1 || scale > 50.0) {
                sender.sendMessage("§9DustLab §c» §7Scale must be between 0.1 and 50.0 blocks.");
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§9DustLab §c» §7Invalid scale. Must be a number (e.g., 5.0).");
            return true;
        }

        boolean isTemporary = persistence.equals("temp");

        if (plugin.getParticleModelManager().hasModel(modelName)) {
            sender.sendMessage("§9DustLab §c» §7Model '§f" + modelName + "§7' already exists.");
            return true;
        }

        sender.sendMessage("§9DustLab §7» §7Creating §b" + type + "§7 model for player §b" + playerName + "§7...");

        com.winss.dustlab.media.MediaProcessor.submitAsync(() -> {
            try {
                createPlayerModel(sender, type, modelName, playerName, resolution, scale, isTemporary);
            } catch (Exception e) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    sender.sendMessage("§9DustLab §c» §7Failed to create model: " + e.getMessage());
                    plugin.getLogger().warning("Failed to create player model: " + e.getMessage());
                });
            }
        });

        return true;
    }


    private boolean handleCreateMedia(CommandSender sender, String[] args) {
        if (args.length < 7) {
            sender.sendMessage("§9DustLab §c» §c§lExperimental! §7Usage: /dustlab create media <modelName> <url> <blockwidth> <blockheight> <maxparticles> [skip] [normal/temp] [gzip]");
            sender.sendMessage("§9DustLab §7» §7ModelName: §bName for the animated model");
            int maxFramesHelp = plugin.getDustLabConfig().getMediaMaxFrames();
            int maxMbHelp = plugin.getDustLabConfig().getMediaMaxFileSizeMB();
            sender.sendMessage("§9DustLab §7» §7URL: §bDirect link to GIF/WebP/APNG (max " + maxMbHelp + "MB, " + maxFramesHelp + " frames)");
            sender.sendMessage("§9DustLab §7» §7BlockWidth: §bWidth in blocks (1-32)");
            sender.sendMessage("§9DustLab §7» §7BlockHeight: §bHeight in blocks (1-32)");
            sender.sendMessage("§9DustLab §7» §7MaxParticles: §bMaximum particles per frame (100-50000)");
            sender.sendMessage("§9DustLab §7» §7Skip: §bOptional frame skip (1=all frames, 2=every 2nd, etc.)");
            sender.sendMessage("§9DustLab §7» §7Persistence: §bnormal§7 (permanent) or §btemp§7 (temporary)");
            sender.sendMessage("§9DustLab §7» §7Compression: optional §bgzip§7 to save as .json.gz (default: " + (plugin.getDustLabConfig().isMediaDefaultGzipEnabled() ? "§aON" : "§cOFF") + "§7)");
            return true;
        }

        String modelName = args[2];
        String url = args[3];
        String blockWidthStr = args[4];
        String blockHeightStr = args[5];
        String maxParticlesStr = args[6];
        
        int frameSkip = 1;
    String persistence = "normal";
    boolean gzip = plugin.getDustLabConfig().isMediaDefaultGzipEnabled();
        
        if (args.length > 7) {
            try {
                frameSkip = Integer.parseInt(args[7]);
                if (frameSkip < 1 || frameSkip > 50) {
                    sender.sendMessage("§9DustLab §c» §7Frame skip must be between 1 and 50.");
                    return true;
                }
                if (args.length > 8) {
                    persistence = args[8].toLowerCase();
                    if (args.length > 9) {
                        gzip = "gzip".equalsIgnoreCase(args[9]);
                    }
                }
            } catch (NumberFormatException e) {
                persistence = args[7].toLowerCase();
                if (args.length > 8) {
                    gzip = "gzip".equalsIgnoreCase(args[8]);
                }
            }
        }

        int blockWidth, blockHeight, maxParticles;
        try {
            blockWidth = Integer.parseInt(blockWidthStr);
            if (blockWidth < 1 || blockWidth > 32) {
                sender.sendMessage("§9DustLab §c» §7Block width must be between 1 and 32.");
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§9DustLab §c» §7Invalid block width. Must be a number.");
            return true;
        }

        try {
            blockHeight = Integer.parseInt(blockHeightStr);
            if (blockHeight < 1 || blockHeight > 32) {
                sender.sendMessage("§9DustLab §c» §7Block height must be between 1 and 32.");
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§9DustLab §c» §7Invalid block height. Must be a number.");
            return true;
        }

        try {
            maxParticles = Integer.parseInt(maxParticlesStr);
            if (maxParticles < 100 || maxParticles > 50000) { 
                sender.sendMessage("§9DustLab §c» §7Max particles must be between 100 and 50000.");
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§9DustLab §c» §7Invalid max particles. Must be a number.");
            return true;
        }

        boolean isTemporary = persistence.equals("temp");

        if (plugin.getParticleModelManager().hasModel(modelName)) {
            sender.sendMessage("§9DustLab §c» §7Model '§f" + modelName + "§7' already exists.");
            return true;
        }

        if (!isValidMediaUrl(url)) {
            sender.sendMessage("§9DustLab §c» §7Invalid URL. Must be a direct link to an image/animation file.");
            return true;
        }
        // Prevent duplicate names across running + queued
        String nameKey = modelName.toLowerCase();
        if (mediaNamesRunningOrQueued.contains(nameKey)) {
            sender.sendMessage("§9DustLab §c» §7A media creation task with the name '§f" + modelName + "§7' is already running or queued.");
            return true;
        }

        UUID ownerId = (sender instanceof Player) ? ((Player) sender).getUniqueId() : CONSOLE_UUID;
        Deque<MediaRequest> queue = mediaQueues.computeIfAbsent(ownerId, id -> new ArrayDeque<>());

    MediaRequest request = new MediaRequest(sender, modelName, url, blockWidth, blockHeight, maxParticles, frameSkip, isTemporary, gzip);

        // Allow unlimited concurrency for console: always start immediately
        boolean isConsole = ownerId.equals(CONSOLE_UUID);
        if (!isConsole && mediaActive.contains(ownerId)) {
            synchronized (queue) {
                queue.addLast(request);
            }
            mediaNamesRunningOrQueued.add(nameKey);
            int position;
            synchronized (queue) { position = queue.size(); }
            sender.sendMessage("§9DustLab §b» §7Your media task '§f" + modelName + "§7' has been queued (position §b" + position + "§7). Only one active media task per player.");
            return true;
        }

        // Start immediately
        if (!isConsole) mediaActive.add(ownerId);
        mediaNamesRunningOrQueued.add(nameKey);
        startMediaProcessing(ownerId, request);
        return true;
    }

    // Start processing a media request and chain completion to start next queued one
    private void startMediaProcessing(UUID ownerId, MediaRequest req) {
        CommandSender sender = req.sender;
        String modelName = req.modelName;
        String url = req.url;
        int blockWidth = req.blockWidth;
        int blockHeight = req.blockHeight;
        int maxParticles = req.maxParticles;
        int frameSkip = req.frameSkip;
    boolean isTemporary = req.isTemporary;
    boolean gzip = req.gzip;

    // Mark active and reserve the name (covers queued->running path as well)
    boolean isConsole = ownerId.equals(CONSOLE_UUID);
    if (!isConsole) mediaActive.add(ownerId);
    mediaNamesRunningOrQueued.add(modelName.toLowerCase());

    sender.sendMessage("§9DustLab §7» §c§lExperimental! §7Creating animated model §b" + modelName + "§7 from: §f" + url);
        sender.sendMessage("§9DustLab §7» §7Dimensions: §b" + blockWidth + "x" + blockHeight + "§7 blocks, max §b" + maxParticles + "§7 particles per frame");
        sender.sendMessage("§9DustLab §7» §7Particle scale: §b" + plugin.getDustLabConfig().getMediaParticleScale() + "§7 (configurable in config)");
        if (frameSkip > 1) {
            sender.sendMessage("§9DustLab §7» §7Frame skip: §b" + frameSkip + "§7 (using every " + frameSkip + " frames)");
        }
    int maxFrames = plugin.getDustLabConfig().getMediaMaxFrames();
    int maxMb = plugin.getDustLabConfig().getMediaMaxFileSizeMB();
    sender.sendMessage("§9DustLab §7» §7Processing limits: §b" + maxMb + "MB file size, " + maxFrames + " frame maximum");
    sender.sendMessage("§9DustLab §7» §7Starting download and processing... This may take a moment.");
    if (gzip) sender.sendMessage("§9DustLab §7» §7Saving with compression: §bgzip§7 (.json.gz)");

        com.winss.dustlab.media.MediaProcessor processor = new com.winss.dustlab.media.MediaProcessor(plugin);
        processor.processMediaUrl(url, modelName, blockWidth, blockHeight, maxParticles, !isTemporary, frameSkip)
            .thenAccept(animatedModel -> {
                if (!plugin.isEnabled()) { onMediaProcessingComplete(ownerId, modelName); return; }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!plugin.isEnabled()) { onMediaProcessingComplete(ownerId, modelName); return; }
                    try {
                        // Tag compression preference in metadata for the manager to decide file extension
                        java.util.Map<String, Object> md = animatedModel.getMetadata();
                        if (md == null) md = new java.util.HashMap<>();
                        md.put("compressed", gzip);
                        animatedModel.setMetadata(md);
                        plugin.getParticleModelManager().registerAnimatedModel(animatedModel, !isTemporary);
                        
                        long totalMs = 0L;
                        for (com.winss.dustlab.media.FrameData f : animatedModel.getFrames()) totalMs += Math.max(0, f.getDelayMs());
                        long totalSeconds = Math.max(1L, totalMs / 1000L);
                        sender.sendMessage("§9DustLab §b» §7Successfully created animated model §b" + modelName + "§7!");
                        sender.sendMessage("§9DustLab §7» §7Frames: §b" + animatedModel.getTotalFrames() + "§7, Total particles: §b" + animatedModel.getTotalParticleCount() + "§7, Playtime: §b" + totalSeconds + "s");
                        
                        if (isTemporary) {
                            sender.sendMessage("§9DustLab §7» §7Model will be automatically removed after configured time.");
                        } else {
                            sender.sendMessage("§9DustLab §7» §7Model saved successfully!");
                        }
                    } catch (Exception e) {
                        sender.sendMessage("§9DustLab §c» §7Failed to register animated model: " + e.getMessage());
                        plugin.getLogger().severe("Failed to register animated model: " + e.getMessage());
                    } finally {
                        // Complete and start next
                        onMediaProcessingComplete(ownerId, modelName);
                    }
                });
            })
            .exceptionally(throwable -> {
                if (!plugin.isEnabled()) { return null; }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!plugin.isEnabled()) { return; }
                    sender.sendMessage("§9DustLab §c» §7Failed to create animated model: " + throwable.getMessage());
                    onMediaProcessingComplete(ownerId, modelName);
                });
                return null;
            });
    }

    private void onMediaProcessingComplete(UUID ownerId, String modelName) {
        String nameKey = modelName.toLowerCase();
        mediaNamesRunningOrQueued.remove(nameKey);
        Deque<MediaRequest> queue = mediaQueues.get(ownerId);
        MediaRequest next = null;
        if (queue != null) {
            synchronized (queue) {
                next = queue.pollFirst();
            }
        }
        if (next != null) {
            // Keep owner marked active and proceed with next task
            startMediaProcessing(ownerId, next);
        } else {
            mediaActive.remove(ownerId);
        }
    }

    private static class MediaRequest {
        final CommandSender sender;
        final String modelName;
        final String url;
        final int blockWidth;
        final int blockHeight;
        final int maxParticles;
        final int frameSkip;
    final boolean isTemporary;
    final boolean gzip;

        MediaRequest(CommandSender sender, String modelName, String url, int blockWidth, int blockHeight, int maxParticles, int frameSkip, boolean isTemporary, boolean gzip) {
            this.sender = sender;
            this.modelName = modelName;
            this.url = url;
            this.blockWidth = blockWidth;
            this.blockHeight = blockHeight;
            this.maxParticles = maxParticles;
            this.frameSkip = frameSkip;
            this.isTemporary = isTemporary;
            this.gzip = gzip;
        }
    }


    private boolean isValidMediaUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        
        url = url.toLowerCase();
        return url.startsWith("http://") || url.startsWith("https://");
    }


    private void createPlayerModel(CommandSender sender, String type, String modelName, 
                                 String playerName, int resolution, double scale, boolean isTemporary) {
        try {
            String imageUrl;
            if (type.equals("player_avatar")) {
                imageUrl = "https://minotar.net/avatar/" + playerName + "/" + resolution;
            } else {
                imageUrl = "https://minotar.net/armor/body/" + playerName + "/" + resolution + ".png";
            }

            URL url = new URL(imageUrl);
            BufferedImage image = ImageIO.read(url);

            if (image == null) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    sender.sendMessage("§9DustLab §c» §7Failed to download image for player §f" + playerName + "§7.");
                });
                return;
            }

            List<ParticleData> particles = convertImageToParticles(image, scale);

            ParticleModel model = new ParticleModel();
            model.setName(modelName);
            model.setParticles(particles);
            
            java.util.Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("generatedBy", "DustLab v1.1");
            metadata.put("website", "https://winss.xyz/dustlab");
            metadata.put("generatedOn", java.time.Instant.now().toString());
            metadata.put("sourceFile", imageUrl);
            metadata.put("particleCount", particles.size());
            
            java.util.Map<String, Object> settings = new java.util.HashMap<>();
            settings.put("outputWidth", (int)(image.getWidth() * scale / Math.max(image.getWidth(), image.getHeight())));
            settings.put("outputHeight", (int)(image.getHeight() * scale / Math.max(image.getWidth(), image.getHeight())));
            settings.put("particleScale", scale);
            settings.put("coordinateMode", "local");
            settings.put("coordinateAxis", "X-Y");
            settings.put("rotation", 0);
            settings.put("version", "1.20.4+");
            settings.put("colorFixed", false);
            settings.put("animationType", type);
            settings.put("playerName", playerName);
            settings.put("resolution", resolution);
            
            metadata.put("settings", settings);
            model.setMetadata(metadata);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    plugin.getParticleModelManager().saveModel(model, isTemporary);
                    sender.sendMessage("§9DustLab §b» §7Successfully created model §b" + modelName + 
                                     "§7 with §b" + particles.size() + "§7 particles!");
                    
                    if (isTemporary) {
                        sender.sendMessage("§9DustLab §7» §7Model will be automatically removed after configured time.");
                    }
                } catch (Exception e) {
                    sender.sendMessage("§9DustLab §c» §7Failed to save model: " + e.getMessage());
                }
            });

        } catch (IOException e) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                sender.sendMessage("§9DustLab §c» §7Failed to download player skin. Check if player name is valid.");
            });
        }
    }

    private List<ParticleData> convertImageToParticles(BufferedImage image, double scale) {
        List<ParticleData> particles = new ArrayList<>();
        
        int width = image.getWidth();
        int height = image.getHeight();
        
        double particleSpacing = scale / Math.max(width, height);
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                
                int alpha = (rgb >> 24) & 0xFF;
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                
                if (alpha < 128) {
                    continue;
                }
                
                double particleX = x * particleSpacing - (width * particleSpacing / 2.0);
                double particleY = (height - 1 - y) * particleSpacing - (height * particleSpacing / 2.0);
                double particleZ = 0.0;
                
                ParticleData particle = new ParticleData();
                particle.setX(particleX);
                particle.setY(particleY);
                particle.setZ(particleZ);
                particle.setR(red / 255.0); 
                particle.setG(green / 255.0); 
                particle.setB(blue / 255.0); 
                particle.setDelay(0); 
                
                particles.add(particle);
            }
        }
        
        return particles;
    }


    private boolean handleDelete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dustlab.delete")) {
            sender.sendMessage("§9DustLab §c» §7You don't have permission to delete models (dustlab.delete).");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("§9DustLab §c» §7Usage: /dl delete <modelName>");
            return true;
        }

        String modelName = args[1];

        if (!plugin.getParticleModelManager().hasModel(modelName)) {
            sender.sendMessage("§9DustLab §c» §7Model '§f" + modelName + "§7' not found.");
            return true;
        }

        // Reject inline "confirm" usage explicitly
        if (args.length > 2 && args[2].equalsIgnoreCase("confirm")) {
            sender.sendMessage("§9DustLab §e» §7Inline confirmation is no longer supported. Please run §b/dl confirm§7.");
        }

        UUID who = (sender instanceof Player) ? ((Player) sender).getUniqueId() : CONSOLE_UUID;
        int timeoutSec = plugin.getDustLabConfig().getDeleteConfirmTimeoutSeconds();
        long expiresAt = System.currentTimeMillis() + (timeoutSec * 1000L);
        pendingDeletions.put(who, new PendingDeletion(modelName, expiresAt));

        sender.sendMessage("§9DustLab §c» §7Are you sure you want to delete '§f" + modelName + "§7'? Run §b/dl confirm§7 within §b" + timeoutSec + "§7 seconds to proceed.");

        // Schedule cleanup after timeout to avoid stale entries
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            PendingDeletion pd = pendingDeletions.get(who);
            if (pd != null && (System.currentTimeMillis() >= pd.expiresAtMillis)) {
                pendingDeletions.remove(who);
            }
        }, timeoutSec * 20L);

        return true;
    }

    private boolean handleConfirm(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dustlab.delete")) {
            sender.sendMessage("§9DustLab §c» §7You don't have permission to confirm deletions (dustlab.delete).");
            return true;
        }

        UUID who = (sender instanceof Player) ? ((Player) sender).getUniqueId() : CONSOLE_UUID;
        PendingDeletion pd = pendingDeletions.get(who);
        if (pd == null) {
            sender.sendMessage("§9DustLab §e» §7You have no pending deletions. Run §b/dl delete <model>§7 first.");
            return true;
        }
        if (System.currentTimeMillis() > pd.expiresAtMillis) {
            pendingDeletions.remove(who);
            sender.sendMessage("§9DustLab §e» §7Your deletion request expired. Run §b/dl delete <model>§7 again.");
            return true;
        }

        String modelName = pd.modelName;
        pendingDeletions.remove(who);
        if (!plugin.getParticleModelManager().hasModel(modelName)) {
            sender.sendMessage("§9DustLab §c» §7Model '§f" + modelName + "§7' not found.");
            return true;
        }
        boolean success = plugin.getParticleModelManager().deleteModel(modelName);
        if (success) {
            sender.sendMessage("§9DustLab §b» §7Successfully deleted model '§f" + modelName + "§7'.");
        } else {
            sender.sendMessage("§9DustLab §c» §7Failed to delete model '§f" + modelName + "§7'. Check console for details.");
        }
        return true;
    }
}
