package com.winss.dustlab.config;

import com.winss.dustlab.DustLab;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

public class DustLabConfig {
    
    private final DustLab plugin;
    private File configFile;
    private FileConfiguration config;
    
    private int maxParticlesPerTickPerModel = 250;
    private int tempModelLifetimeMinutes = 30;
    private int largeModelThreshold = 4500;
    private int veryLargeModelThreshold = 15000;
    private int particlesPerBatch = 500;
    // Progressive loading options
    private boolean progressiveLoadingEnabled = true;
    private int progressiveLargeModelThreshold = 50000; // start progressive at > 50k particles
    private int progressiveParseBatchSize = 5000; // particles parsed per async batch
    private int progressiveApplyBatchPerTick = 2500; // particles appended per tick on main thread
    private int progressiveProgressLogPercent = 10; // log every X percent
    private int progressiveMaxConcurrent = 2; // max models parsed concurrently
    private double maxRenderDistance = 48.0;
    private boolean enableAutoSave = true;
    private int autoSaveIntervalMinutes = 30;
    private boolean enableAntiEpilepsy = true;
    private int fadeInDurationTicks = 40;
    private float mediaParticleScale = 1.25f;
    public enum AnimatedParticleMode { REDSTONE, TRANSITION }
    private AnimatedParticleMode animatedParticleMode = AnimatedParticleMode.REDSTONE;
    private int mediaParticleLifespanTicks = 1; 
    private int mediaMaxFrames = 150;
    private int mediaMaxFileSizeMB = 25;
    private boolean mediaDefaultGzip = false;
    
    private String messagePrefix = "&9DustLab &b»";
    private boolean consoleUseColors = false;
    private boolean consoleCleanOutput = true;
    private boolean verboseLogging = false;
    
    public DustLabConfig(DustLab plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "dustlab-config.yml");
        loadConfig();
    }
    
    private void loadConfig() {
        if (!configFile.exists()) {
            createDefaultConfig();
        }
        
        config = YamlConfiguration.loadConfiguration(configFile);
        
        maxParticlesPerTickPerModel = config.getInt("performance.max-particles-per-tick-per-model", 250);
        tempModelLifetimeMinutes = config.getInt("temp-models.lifetime-minutes", 30);
        largeModelThreshold = config.getInt("performance.large-model-threshold", 4500);
        veryLargeModelThreshold = config.getInt("performance.very-large-model-threshold", 15000);
        particlesPerBatch = config.getInt("performance.particles-per-batch", 500);
    // Progressive loading
    progressiveLoadingEnabled = config.getBoolean("progressive.enabled", true);
    progressiveLargeModelThreshold = config.getInt("progressive.large-model-threshold", 50000);
    progressiveParseBatchSize = config.getInt("progressive.parse-batch-size", 5000);
    progressiveApplyBatchPerTick = config.getInt("progressive.apply-batch-per-tick", 2500);
    progressiveProgressLogPercent = config.getInt("progressive.progress-log-interval-percent", 10);
    progressiveMaxConcurrent = Math.max(1, config.getInt("progressive.max-async-loads", 2));
        maxRenderDistance = config.getDouble("performance.max-render-distance", 48.0);
        enableAutoSave = config.getBoolean("persistence.enable-auto-save", true);
        autoSaveIntervalMinutes = config.getInt("persistence.auto-save-interval-minutes", 30);
        enableAntiEpilepsy = config.getBoolean("safety.enable-anti-epilepsy", true);
        fadeInDurationTicks = config.getInt("safety.fade-in-duration-ticks", 40);
        mediaParticleScale = (float) config.getDouble("media.particle-scale", 1.25);
        try {
            String modeStr = config.getString("media.animated-particle-mode", "REDSTONE");
            animatedParticleMode = AnimatedParticleMode.valueOf(modeStr.toUpperCase());
        } catch (Exception e) {
            animatedParticleMode = AnimatedParticleMode.REDSTONE;
        }
        mediaParticleLifespanTicks = config.getInt("media.particle-lifespan-ticks", 1);
        // Media limits
    mediaMaxFrames = config.getInt("media.max-frames", 150);
        mediaMaxFileSizeMB = config.getInt("media.max-file-size-mb", 25);
    mediaDefaultGzip = config.getBoolean("media.default-gzip", false);
    // Delete confirmation timeout
    deleteConfirmTimeoutSeconds = config.getInt("delete.confirm-timeout-seconds", 30);
        
        // Load message and color settings
        messagePrefix = config.getString("messages.prefix", "&9DustLab &b»");
        consoleUseColors = config.getBoolean("messages.console.use-colors", false);
        consoleCleanOutput = config.getBoolean("messages.console.clean-output", true);
        verboseLogging = config.getBoolean("messages.console.verbose-logging", false);
        
        plugin.getLogger().info("Configuration loaded successfully");
    }
    
    private void createDefaultConfig() {
        try {
            configFile.getParentFile().mkdirs();
            configFile.createNewFile();
            
            config = new YamlConfiguration();
            
            config.set("performance.max-particles-per-tick-per-model", 250);
            config.set("performance.large-model-threshold", 4500);
            config.set("performance.very-large-model-threshold", 15000);
            config.set("performance.particles-per-batch", 500);
            // Progressive loading defaults
            config.set("progressive.enabled", true);
            config.set("progressive.large-model-threshold", 50000);
            config.set("progressive.parse-batch-size", 5000);
            config.set("progressive.apply-batch-per-tick", 2500);
            config.set("progressive.progress-log-interval-percent", 10);
            config.set("progressive.max-async-loads", 2);
            config.set("performance.max-render-distance", 48.0);
            
            config.set("temp-models.lifetime-minutes", 30);
            config.set("temp-models.cleanup-on-startup", true);
            
            config.set("persistence.enable-auto-save", true);
            config.set("persistence.auto-save-interval-minutes", 30);
            config.set("persistence.backup-before-save", false);
            
            config.set("safety.enable-anti-epilepsy", true);
            config.set("safety.fade-in-duration-ticks", 40);
            config.set("safety.max-concurrent-models", 10);
            
            config.set("model-creation.max-resolution", 512);
            config.set("model-creation.min-resolution", 8);
            config.set("model-creation.max-scale", 50.0);
            config.set("model-creation.default-scale", 5.0);
            
            config.set("media.particle-scale", 1.25);
            config.set("media.animated-particle-mode", "REDSTONE");
            config.set("media.particle-lifespan-ticks", 1);
            config.set("media.max-frames", 150);
            config.set("media.max-file-size-mb", 25);
            config.set("media.default-gzip", false);

            // Delete confirmation settings
            config.set("delete.confirm-timeout-seconds", 30);
            
            config.set("messages.prefix", "&9DustLab &b»");
            config.set("messages.console.use-colors", false);
            config.set("messages.console.clean-output", true);
            config.set("messages.console.verbose-logging", false);
            
            config.set("colors.primary", "&9");      
            config.set("colors.accent", "&b");
            config.set("colors.text", "&f");         
            config.set("colors.secondary", "&7");    
            config.set("colors.success", "&a");      
            config.set("colors.error", "&c");        
            config.set("colors.warning", "&e");      
            
            // Comments
            
            config.setComments("safety", java.util.Arrays.asList(
                "Safety settings for rapid flashing prevention and performance protection (you do not want to know why it was named like that)",
                "enable-anti-epilepsy: Enables fade-in effects and limits rapid flashing (default: true)",
                "fade-in-duration-ticks: Duration of fade-in effect in ticks (default: 40 = 2 seconds)",
                "max-concurrent-models: Maximum number of models that can play simultaneously (default: 10)"
            ));
            
            config.setComments("persistence", java.util.Arrays.asList(
                "Settings for model saving and auto-save functionality",
                "enable-auto-save: Whether to automatically save models periodically (default: true)",
                "auto-save-interval-minutes: Minutes between automatic saves (default: 30)",
                "backup-before-save: Whether to create backup before saving (default: false)"
            ));
            
            config.setComments("model-creation", java.util.Arrays.asList(
                "Limits and defaults for creating new particle models",
                "max-resolution: Maximum image resolution allowed for media models (default: 512)",
                "min-resolution: Minimum image resolution required (default: 8)",
                "max-scale: Maximum scale multiplier allowed (default: 50.0)",
                "default-scale: Default scale when none specified (default: 5.0)"
            ));
            
            config.setComments("media", java.util.Arrays.asList(
                "Settings specific to media (image/GIF) processing",
                "particle-scale: Scale multiplier for media-based particles (default: 1.25)",
                "animated-particle-mode: Particle type for animated frames: REDSTONE or TRANSITION (default: REDSTONE)",
                "max-frames: Maximum number of frames to import from media (default: 150)",
                "max-file-size-mb: Maximum download size for media files in megabytes (default: 25)",
                "default-gzip: If true, '/dl create media' saves models as .json.gz unless the user explicitly omits it"
            ));
            
            config.setComments("media", java.util.Arrays.asList(
                "Media processing settings",
                "particle-scale: Scale factor for particles when creating models from images/GIFs (default: 1.25)"
            ));
            
            config.setComments("messages", java.util.Arrays.asList(
                "Message and UI configuration",
                "prefix: Chat prefix for player messages (supports color codes with &)",
                "console.use-colors: Whether to use ANSI colors in console output (default: false)",
                "console.clean-output: Whether to strip color codes from console messages (default: true)",
                "console.verbose-logging: Show detailed operational messages in console (default: false)"
            ));

            config.setComments("delete", java.util.Arrays.asList(
                "Delete confirmation settings",
                "confirm-timeout-seconds: Time window for '/dl confirm' after '/dl delete <model>' (default: 30)"
            ));
            
            config.save(configFile);
            plugin.getLogger().info("Created default configuration file");
            
        } catch (IOException e) {
            plugin.getLogger().severe("DustLab: Failed to create config file: " + e.getMessage());
        }
    }
    
    public void reloadConfig() {
        loadConfig();
    }
    
    public void saveConfig() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().severe("DustLab: Failed to save config file: " + e.getMessage());
        }
    }
    
    public int getMaxParticlesPerTickPerModel() { return maxParticlesPerTickPerModel; }
    public int getTempModelLifetimeMinutes() { return tempModelLifetimeMinutes; }
    public int getLargeModelThreshold() { return largeModelThreshold; }
    public int getVeryLargeModelThreshold() { return veryLargeModelThreshold; }
    public int getParticlesPerBatch() { return particlesPerBatch; }
    public double getMaxRenderDistance() { return maxRenderDistance; }
    // Progressive getters
    public boolean isProgressiveLoadingEnabled() { return progressiveLoadingEnabled; }
    public int getProgressiveLargeModelThreshold() { return progressiveLargeModelThreshold; }
    public int getProgressiveParseBatchSize() { return progressiveParseBatchSize; }
    public int getProgressiveApplyBatchPerTick() { return progressiveApplyBatchPerTick; }
    public int getProgressiveProgressLogPercent() { return progressiveProgressLogPercent; }
    public int getProgressiveMaxConcurrent() { return progressiveMaxConcurrent; }
    public boolean isAutoSaveEnabled() { return enableAutoSave; }
    public int getAutoSaveIntervalMinutes() { return autoSaveIntervalMinutes; }
    public boolean isAntiEpilepsyEnabled() { return enableAntiEpilepsy; }
    public int getFadeInDurationTicks() { return fadeInDurationTicks; }
    public float getMediaParticleScale() { return mediaParticleScale; }
    public AnimatedParticleMode getAnimatedParticleMode() { return animatedParticleMode; }
    public int getMediaParticleLifespanTicks() { return mediaParticleLifespanTicks; }
    public int getMediaMaxFrames() { return mediaMaxFrames; }
    public int getMediaMaxFileSizeMB() { return mediaMaxFileSizeMB; }
    public long getMediaMaxFileSizeBytes() { return (long) mediaMaxFileSizeMB * 1024L * 1024L; }
    public boolean isMediaDefaultGzipEnabled() { return mediaDefaultGzip; }
    public int getDeleteConfirmTimeoutSeconds() { return deleteConfirmTimeoutSeconds; }
    
    // Message and color getters
    public String getMessagePrefix() { return messagePrefix; }
    public boolean isConsoleUseColors() { return consoleUseColors; }
    public boolean isConsoleCleanOutput() { return consoleCleanOutput; }
    public boolean isVerboseLogging() { return verboseLogging; }
    
    public long getTempModelLifetimeTicks() {
        return tempModelLifetimeMinutes * 60 * 20L; 
    }
    
    public double getMaxRenderDistanceSquared() {
        return maxRenderDistance * maxRenderDistance;
    }

    // Field for delete confirm timeout
    private int deleteConfirmTimeoutSeconds = 30;
}