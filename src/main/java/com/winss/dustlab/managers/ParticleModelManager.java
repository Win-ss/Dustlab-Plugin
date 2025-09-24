package com.winss.dustlab.managers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.winss.dustlab.DustLab;
import com.winss.dustlab.config.DustLabConfig;
import com.winss.dustlab.models.ParticleModel;
import com.winss.dustlab.models.ParticleData;
import com.winss.dustlab.effects.ParticleEffects;
import com.winss.dustlab.effects.ParticleOptimizer;
import com.winss.dustlab.media.AnimatedModel;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class ParticleModelManager {
    
    @SuppressWarnings("unused")
    private static final int SMALL_MODEL_THRESHOLD = 100;
    @SuppressWarnings("unused")
    private static final int MEDIUM_MODEL_THRESHOLD = 1000;
    @SuppressWarnings("unused")
    private static final int LARGE_MODEL_THRESHOLD = 15000;
    @SuppressWarnings("unused")
    private static final int ANIMATION_TICK_RATE = 1; 
    @SuppressWarnings("unused")
    private static final int STATIC_TICK_RATE = 3; 
    @SuppressWarnings("unused")
    private static final int LARGE_MODEL_TICK_RATE = 4; 
    
    private static final int PARTICLES_PER_BATCH = 500; 
    private static final int MAX_PARTICLES_PER_TICK = 200; // PER MODEL BTW
    private static final int LARGE_MODEL_THRESHOLD_STRICT = 4500; 
    
    @SuppressWarnings("unused")
    private static final double CLOSE_DISTANCE = 20.0; 
    @SuppressWarnings("unused")
    private static final double MEDIUM_DISTANCE = 50.0;  
    @SuppressWarnings("unused")
    private static final double FAR_DISTANCE = 100.0; 
    private static final double MAX_RENDER_DISTANCE_SQUARED = 48.0 * 48.0; 
    
    private final DustLab plugin;
    private final DustLabConfig config;
    private final Gson gson;
    private final Map<String, ParticleModel> loadedModels;
    private final Map<String, BukkitTask> activeEffects;
    private final Map<String, EffectInfo> activeEffectInfo;
    private final Map<Integer, String> effectIdMap; 
    private final ParticleOptimizer particleOptimizer; 
    private int nextEffectId = 1;
    private long lastSaveLogTime = 0; 
    
    public static class EffectInfo {
        public final int id;
        public final String modelName;
        public final Location location;
        public final boolean isLooping; // BC
        public final int lifetimeSeconds; 
        public final boolean isPersistent;
        public final long startTime;
        public final ParticleEffects.EffectSettings effectSettings;
        public final Player attachedPlayer; 
        public final boolean onlyWhenStill;
        public final boolean forceVisible;
        
        public EffectInfo(int id, String modelName, Location location, int lifetimeSeconds, boolean isPersistent, ParticleEffects.EffectSettings effectSettings) {
            this.id = id;
            this.modelName = modelName;
            this.location = location;
            this.lifetimeSeconds = lifetimeSeconds;
            this.isLooping = (lifetimeSeconds == -1);
            this.isPersistent = isPersistent;
            this.startTime = System.currentTimeMillis();
            this.effectSettings = effectSettings != null ? effectSettings : new ParticleEffects.EffectSettings();
            this.attachedPlayer = null;
            this.onlyWhenStill = false;
            this.forceVisible = false;
        }
        
        public EffectInfo(int id, String modelName, Player attachedPlayer, int lifetimeSeconds, boolean onlyWhenStill, boolean forceVisible, ParticleEffects.EffectSettings effectSettings) {
            this.id = id;
            this.modelName = modelName;
            this.location = attachedPlayer.getLocation().clone();
            this.lifetimeSeconds = lifetimeSeconds;
            this.isLooping = (lifetimeSeconds == -1);
            this.isPersistent = true;
            this.startTime = System.currentTimeMillis();
            this.effectSettings = effectSettings != null ? effectSettings : new ParticleEffects.EffectSettings();
            this.attachedPlayer = attachedPlayer;
            this.onlyWhenStill = onlyWhenStill;
            this.forceVisible = forceVisible;
        }
        
        public EffectInfo(int id, String modelName, Location location, boolean isLooping, boolean isPersistent, ParticleEffects.EffectSettings effectSettings) {
            this(id, modelName, location, isLooping ? -1 : 0, isPersistent, effectSettings);
        }
        
        public EffectInfo(int id, String modelName, Location location, boolean isLooping, ParticleEffects.EffectSettings effectSettings) {
            this(id, modelName, location, isLooping ? -1 : 0, isLooping, effectSettings);
        }
        
        public EffectInfo(int id, String modelName, Location location, boolean isLooping) {
            this(id, modelName, location, isLooping ? -1 : 0, isLooping, null);
        }
        
        public boolean isPersistent() {
            return isPersistent;
        }
        
        public ParticleEffects.EffectSettings getEffects() {
            return effectSettings;
        }
        
        public boolean isInfinite() {
            return lifetimeSeconds == -1;
        }
        
        public boolean hasExpired() {
            if (lifetimeSeconds <= 0) return false;
            long currentTime = System.currentTimeMillis();
            long elapsedSeconds = (currentTime - startTime) / 1000;
            return elapsedSeconds >= lifetimeSeconds;
        }
        
        public int getRemainingSeconds() {
            if (lifetimeSeconds <= 0) return -1;
            long currentTime = System.currentTimeMillis();
            long elapsedSeconds = (currentTime - startTime) / 1000;
            return Math.max(0, lifetimeSeconds - (int) elapsedSeconds);
        }
    }
    
    public ParticleModelManager(DustLab plugin, DustLabConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();
        this.loadedModels = new ConcurrentHashMap<>();
        this.activeEffects = new ConcurrentHashMap<>();
        this.activeEffectInfo = new ConcurrentHashMap<>();
        this.effectIdMap = new ConcurrentHashMap<>();
        this.particleOptimizer = new ParticleOptimizer();
        
        File modelsDir = new File(plugin.getDataFolder(), "models");
        if (!modelsDir.exists()) {
            modelsDir.mkdirs();
            plugin.getLogger().info("Created models directory at: " + modelsDir.getPath());
        }
        
        loadPersistedModels();
        
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            savePersistedModels(true); 
        }, 36000L, 36000L); 
        
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long currentTick = plugin.getServer().getCurrentTick();
            for (String effectKey : activeEffectInfo.keySet()) {
                particleOptimizer.cleanupEffect(effectKey, currentTick, 12000L);
            }
        }, 6000L, 6000L); 
    }
    
    private int getMaxParticlesPerTick() {
        return config != null ? config.getMaxParticlesPerTickPerModel() : MAX_PARTICLES_PER_TICK;
    }
    
    private int getParticlesPerBatch() {
        return config != null ? config.getParticlesPerBatch() : PARTICLES_PER_BATCH;
    }
    
    private int getLargeModelThreshold() {
        return config != null ? config.getLargeModelThreshold() : LARGE_MODEL_THRESHOLD_STRICT;
    }
    
    private int getVeryLargeModelThreshold() {
        return config != null ? config.getVeryLargeModelThreshold() : LARGE_MODEL_THRESHOLD;
    }
    
    public void loadModels() {
        File modelsDir = new File(plugin.getDataFolder(), "models");
        File[] jsonFiles = modelsDir.listFiles((dir, name) -> {
            String n = name.toLowerCase();
            return n.endsWith(".json") || n.endsWith(".json.gz");
        });
        
        if (jsonFiles == null || jsonFiles.length == 0) {
            plugin.getLogger().info("No particle models found in models directory.");
            
            copyBundledModels();
            
            jsonFiles = modelsDir.listFiles((dir, name) -> {
                String n = name.toLowerCase();
                return n.endsWith(".json") || n.endsWith(".json.gz");
            });
            
            if (jsonFiles == null || jsonFiles.length == 0) {
                createExampleModel();
                return;
            }
        }
        
        int loaded = 0;
        for (File jsonFile : jsonFiles) {
            try {
                loadModel(jsonFile);
                loaded++;
            } catch (Exception e) {
                plugin.getLogger().warning("DustLab: Failed to load model from " + jsonFile.getName() + ": " + e.getMessage());
            }
        }
        
        plugin.getLogger().info("Loaded " + loaded + " particle models.");
        
        savePersistedModels();
    }
    
    private void loadModel(File file) throws IOException {
        boolean gz = file.getName().toLowerCase().endsWith(".json.gz");
        java.io.Reader reader = null;
        try {
            if (gz) {
                java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(new java.io.FileInputStream(file));
                reader = new java.io.InputStreamReader(gis, java.nio.charset.StandardCharsets.UTF_8);
            } else {
                reader = new FileReader(file);
            }
            com.google.gson.JsonElement jsonElement = gson.fromJson(reader, com.google.gson.JsonElement.class);
            if (jsonElement != null && jsonElement.isJsonObject()) {
                com.google.gson.JsonObject jsonObject = jsonElement.getAsJsonObject();
                if (jsonObject.has("frames")) {
                    AnimatedModel animatedModel = parseAnimatedModel(jsonObject, file.getName());
                    if (animatedModel != null) {
                        if (animatedModel.getName() == null || animatedModel.getName().isEmpty()) {
                            String fileName = file.getName();
                            String base = fileName.endsWith(".json.gz") ? fileName.substring(0, fileName.length() - 8) : fileName.substring(0, fileName.lastIndexOf('.'));
                            animatedModel.setName(base);
                        }
                        loadedModels.put(animatedModel.getName().toLowerCase(), animatedModel);
                        plugin.getLogger().info("Loaded animated model: " + animatedModel.getName() + " with " + animatedModel.getTotalFrames() + " frames");
                    }
                } else {
                    ParticleModel model = gson.fromJson(jsonElement, ParticleModel.class);
                    if (model != null) {
                        if (model.getName() == null || model.getName().isEmpty()) {
                            String fileName = file.getName();
                            String base = fileName.endsWith(".json.gz") ? fileName.substring(0, fileName.length() - 8) : fileName.substring(0, fileName.lastIndexOf('.'));
                            model.setName(base);
                        }
                        loadedModels.put(model.getName().toLowerCase(), model);
                    }
                }
            }
        } finally {
            if (reader != null) try { reader.close(); } catch (Exception ignored) {}
        }
    }

    private AnimatedModel parseAnimatedModel(com.google.gson.JsonObject root, String fileName) {
        try {
            String name = root.has("name") ? root.get("name").getAsString() : null;
            boolean looping = root.has("looping") && root.get("looping").getAsBoolean();
            String sourceUrl = root.has("sourceUrl") ? root.get("sourceUrl").getAsString() : "";
            int blockWidth = root.has("blockWidth") ? root.get("blockWidth").getAsInt() : 1;
            int blockHeight = root.has("blockHeight") ? root.get("blockHeight").getAsInt() : 1;
            int maxParticleCount = root.has("maxParticleCount") ? root.get("maxParticleCount").getAsInt() : 10000;

            double globalSize = 1.0;
            if (root.has("metadata") && root.get("metadata").isJsonObject()) {
                com.google.gson.JsonObject md = root.getAsJsonObject("metadata");
                if (md.has("globalParticleSize")) {
                    try { globalSize = md.get("globalParticleSize").getAsDouble(); } catch (Exception ignored) {}
                }
            }

            java.util.List<com.winss.dustlab.media.FrameData> frames = new java.util.ArrayList<>();
            com.google.gson.JsonElement framesEl = root.get("frames");
            if (framesEl != null && framesEl.isJsonArray()) {
                for (com.google.gson.JsonElement fe : framesEl.getAsJsonArray()) {
                    if (!fe.isJsonObject()) continue;
                    com.google.gson.JsonObject fo = fe.getAsJsonObject();
                    int frameIndex = fo.has("frameIndex") ? fo.get("frameIndex").getAsInt() : frames.size();
                    int delayMs = fo.has("delayMs") ? fo.get("delayMs").getAsInt() : 50;
                    java.util.List<com.winss.dustlab.models.ParticleData> plist = new java.util.ArrayList<>();
                    com.google.gson.JsonElement pel = fo.get("particles");
                    if (pel != null && pel.isJsonArray()) {
                        for (com.google.gson.JsonElement pe : pel.getAsJsonArray()) {
                            if (!pe.isJsonObject()) continue;
                            com.google.gson.JsonObject po = pe.getAsJsonObject();
                            com.winss.dustlab.models.ParticleData pd = parseParticle(po, globalSize);
                            if (pd != null) plist.add(pd);
                        }
                    }
                    frames.add(new com.winss.dustlab.media.FrameData(plist, frameIndex, delayMs));
                }
            }

            AnimatedModel model = new AnimatedModel(name != null ? name : fileName, frames, looping, sourceUrl, blockWidth, blockHeight, maxParticleCount);
            model.setDuration(root.has("duration") ? root.get("duration").getAsInt() : model.getDuration());
            if (root.has("metadata") && root.get("metadata").isJsonObject()) {
                java.lang.reflect.Type mapType = new com.google.gson.reflect.TypeToken<java.util.Map<String, Object>>() {}.getType();
                java.util.Map<String, Object> metaMap = gson.fromJson(root.get("metadata"), mapType);
                model.setMetadata(metaMap);
            }
            // Base particles
            if (root.has("particles") && root.get("particles").isJsonArray()) {
                java.util.List<com.winss.dustlab.models.ParticleData> base = new java.util.ArrayList<>();
                for (com.google.gson.JsonElement pe : root.get("particles").getAsJsonArray()) {
                    if (!pe.isJsonObject()) continue;
                    com.winss.dustlab.models.ParticleData pd = parseParticle(pe.getAsJsonObject(), globalSize);
                    if (pd != null) base.add(pd);
                }
                model.setParticles(base);
            } else if (!frames.isEmpty()) {
                model.setParticles(frames.get(0).getParticles());
            }
            return model;
        } catch (Exception e) {
            plugin.getLogger().warning("DustLab: Failed to parse animated model: " + e.getMessage());
            return null;
        }
    }

    private com.winss.dustlab.models.ParticleData parseParticle(com.google.gson.JsonObject po, double globalSize) {
        try {
            double x = po.has("x") ? po.get("x").getAsDouble() : 0.0;
            double y = po.has("y") ? po.get("y").getAsDouble() : 0.0;
            double z = po.has("z") ? po.get("z").getAsDouble() : 0.0;
            int delay = po.has("delay") ? po.get("delay").getAsInt() : 0;

            double r = 1.0, g = 0.0, b = 0.0;
            float scale = (float) globalSize;

            if (po.has("dustOptions") && po.get("dustOptions").isJsonObject()) {
                com.google.gson.JsonObject d = po.getAsJsonObject("dustOptions");
                double red = getNormalizedColor(d, "red");
                double green = getNormalizedColor(d, "green");
                double blue = getNormalizedColor(d, "blue");
                r = red; g = green; b = blue;
                if (d.has("size")) {
                    try { scale = (float) d.get("size").getAsDouble(); } catch (Exception ignored) {}
                }
            } else {
                if (po.has("r") || po.has("red")) r = getNormalizedColor(po, "r", "red");
                if (po.has("g") || po.has("green")) g = getNormalizedColor(po, "g", "green");
                if (po.has("b") || po.has("blue")) b = getNormalizedColor(po, "b", "blue");
                if (po.has("scale")) { try { scale = (float) po.get("scale").getAsDouble(); } catch (Exception ignored) {} }
            }

            com.winss.dustlab.models.ParticleData pd = new com.winss.dustlab.models.ParticleData(x, y, z, r, g, b);
            pd.setDelay(delay);
            pd.setScale(scale);
            return pd;
        } catch (Exception e) {
            return null;
        }
    }

    private double getNormalizedColor(com.google.gson.JsonObject obj, String... keys) {
        for (String k : keys) {
            if (obj.has(k)) {
                try {
                    double v = obj.get(k).getAsDouble();
                    if (v > 1.0) return Math.max(0.0, Math.min(1.0, v / 255.0));
                    return Math.max(0.0, Math.min(1.0, v));
                } catch (Exception ignored) {}
            }
        }
        return 1.0;
    }
    

    private void copyBundledModels() {
        File modelsDir = new File(plugin.getDataFolder(), "models");
        
        String[] bundledModels = {
            "animated_checkerboard.json",
            "animated_wave.json",
            "animated_ripple.json", 
            "animated_spiral.json",
            "animated_rainbow_circle.json",
            "aurora.json",
            "dragon_breath.json", 
            "glowlights.json",
            "heart_shape.json",
            "iris.json",
            "lightning_bolt.json",
            "magic_circle.json",
            "prismatic_burst.json",
            "spellcast.json",
            "spiral_galaxy.json",
            "star_shape.json",
            "test_scale.json"
        };
        
        int copiedCount = 0;
        for (String modelFile : bundledModels) {
            try {
                java.io.InputStream inputStream = plugin.getResource("models/" + modelFile);
                if (inputStream != null) {
                    File targetFile = new File(modelsDir, modelFile);
                    
                    try (java.io.FileOutputStream outputStream = new java.io.FileOutputStream(targetFile)) {
                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = inputStream.read(buffer)) > 0) {
                            outputStream.write(buffer, 0, length);
                        }
                        copiedCount++;

                    }
                    inputStream.close();
                } else {
                    plugin.getLogger().warning("Bundled model not found in JAR: " + modelFile);
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to copy bundled model " + modelFile + ": " + e.getMessage());
            }
        }
        
        if (copiedCount > 0) {
            plugin.getLogger().info("Successfully copied " + copiedCount + " bundled models to server.");
        }
    }
    
    private void createExampleModel() {
        File modelsDir = new File(plugin.getDataFolder(), "models");
        File exampleFile = new File(modelsDir, "example.json");
        
        try {
            ParticleModel example = new ParticleModel();
            example.setName("example");
            example.setDuration(60);
            

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("generatedBy", "DustLab v1.0.0");
            metadata.put("website", "https://winss.xyz/dustlab");
            metadata.put("generatedOn", java.time.Instant.now().toString());
            metadata.put("sourceFile", "example_generated.internal");
            metadata.put("particleCount", 16);
            
            Map<String, Object> settings = new HashMap<>();
            settings.put("outputWidth", 4);
            settings.put("outputHeight", 4);
            settings.put("coordinateMode", "local");
            settings.put("coordinateAxis", "X-Z");
            settings.put("rotation", 0);
            settings.put("version", "1.20.4+");
            settings.put("colorFixed", false);
            settings.put("fixedColor", null);
            metadata.put("settings", settings);
            
            example.setMetadata(metadata);
            
            java.util.List<ParticleData> particles = new java.util.ArrayList<>();
            
            for (int i = 0; i < 16; i++) {
                double angle = (2 * Math.PI * i) / 16;
                double x = Math.cos(angle) * 2.0;
                double z = Math.sin(angle) * 2.0;
                
                float hue = (float) i / 16.0f;
                java.awt.Color awtColor = java.awt.Color.getHSBColor(hue, 1.0f, 1.0f);
                
                ParticleData particle = new ParticleData(x, 0, z, 
                    awtColor.getRed() / 255.0, 
                    awtColor.getGreen() / 255.0, 
                    awtColor.getBlue() / 255.0);
                particle.setDelay(i * 2);
                particles.add(particle);
            }
            
            example.setParticles(particles);
            
            try (java.io.FileWriter writer = new java.io.FileWriter(exampleFile)) {
                gson.toJson(example, writer);
            }
            
            plugin.getLogger().info("Created example model at: " + exampleFile.getPath());
            loadedModels.put("example", example);
            
        } catch (IOException e) {
            plugin.getLogger().severe("DustLab: Failed to create example model: " + e.getMessage());
        }
    }
    
    public boolean hasModel(String name) {
        return loadedModels.containsKey(name.toLowerCase());
    }
    
    public ParticleModel getModel(String name) {
        return loadedModels.get(name.toLowerCase());
    }
    
    public boolean hasViewPermission(CommandSender sender, String modelName, boolean force) {
        if (force && sender.hasPermission("dustlab.force")) {
            return true;
        }
        
        if (!sender.hasPermission("dustlab.view")) {
            return false;
        }
        
        String modelPermission = "dustlab.view." + modelName.toLowerCase();
        return sender.hasPermission(modelPermission);
    }
    

    public boolean canSeeParticles(Player player) {
        return player.hasPermission("dustlab.view");
    }
    
    /**
     * Determines if an effect should be persistent based on its lifetime.
     * 
     * Automatic Persistence Logic:
     * - Infinite effects (-1 seconds): Always persistent
     * - One-time effects (0 seconds): Never persistent  
     * - Timed effects (>0 seconds): Persistent if longer than 60 seconds
     * 
     * This removes the need for users to specify persistence flags in commands.
     * 
     * @param lifetimeSeconds -1 for infinite, 0 for one-time, >0 for timed
     * @return true if effect should persist across server restarts
     */
    private boolean shouldBePersistent(int lifetimeSeconds) {
        if (lifetimeSeconds == -1) {
            return true;
        }
        if (lifetimeSeconds == 0) {
            return false;
        }
        return lifetimeSeconds > 60;
    }
    
    public int playModel(String modelName, Location location, boolean loop) {
        int lifetimeSeconds = loop ? -1 : 0;
        return playModel(modelName, location, lifetimeSeconds, shouldBePersistent(lifetimeSeconds));
    }
    
    public int playModel(String modelName, Location location, boolean loop, boolean persistent) {
        return playModel(modelName, location, loop ? -1 : 0, persistent);
    }
    
    public int playModel(String modelName, Location location, int lifetimeSeconds) {
        return playModelWithEffects(modelName, location, lifetimeSeconds, shouldBePersistent(lifetimeSeconds), null);
    }
    
    public int playModel(String modelName, Location location, int lifetimeSeconds, boolean persistent) {
        return playModelWithEffects(modelName, location, lifetimeSeconds, persistent, null);
    }
    
    public int playModelWithEffects(String modelName, Location location, boolean loop, ParticleEffects.EffectSettings effects) {
        int lifetimeSeconds = loop ? -1 : 0;
        return playModelWithEffects(modelName, location, lifetimeSeconds, shouldBePersistent(lifetimeSeconds), effects);
    }
    
    public int playModelWithEffects(String modelName, Location location, boolean loop, boolean persistent, ParticleEffects.EffectSettings effects) {
        return playModelWithEffects(modelName, location, loop ? -1 : 0, persistent, effects);
    }
    
    public int playModelWithEffects(String modelName, Location location, int lifetimeSeconds, ParticleEffects.EffectSettings effects) {
        return playModelOnLocationWithEffects(modelName, location, lifetimeSeconds, shouldBePersistent(lifetimeSeconds), effects);
    }
    
    public int playModelWithEffects(String modelName, Location location, int lifetimeSeconds, boolean persistent, ParticleEffects.EffectSettings effects) {
        return playModelOnLocationWithEffects(modelName, location, lifetimeSeconds, persistent, effects);
    }
    
    public int playModelWithTickOffset(String modelName, Location location, int lifetimeSeconds, boolean persistent, long tickOffset) {
        return playModelOnLocationWithEffectsAndTickOffset(modelName, location, lifetimeSeconds, persistent, null, tickOffset);
    }
    
    public int playModelWithEffectsAndTickOffset(String modelName, Location location, int lifetimeSeconds, boolean persistent, ParticleEffects.EffectSettings effects, long tickOffset) {
        return playModelOnLocationWithEffectsAndTickOffset(modelName, location, lifetimeSeconds, persistent, effects, tickOffset);
    }
    
    public int playModelOnPlayer(String modelName, Player player, int lifetimeSeconds, boolean onlyWhenStill, boolean forceVisible) {
        return playModelOnPlayer(modelName, player, lifetimeSeconds, null, onlyWhenStill, forceVisible);
    }
    
    public int playModelOnPlayer(String modelName, Player player, int lifetimeSeconds, ParticleEffects.EffectSettings effects, boolean onlyWhenStill, boolean forceVisible) {
        ParticleModel model = getModel(modelName);
        if (model == null) {
            plugin.getLogger().warning("DustLab: Model not found: " + modelName);
            return -1;
        }
        
        if (player == null || !player.isOnline()) {
            plugin.getLogger().warning("DustLab: Invalid or offline player for model: " + modelName);
            return -1;
        }
        
        if (model.getParticles() == null || model.getParticles().isEmpty()) {
            plugin.getLogger().warning("DustLab: Model has no particles: " + modelName);
            return -1;
        }
        
        int effectId = nextEffectId++;
        String effectKey = modelName + "_player_" + player.getName() + "_" + effectId + "_" + System.currentTimeMillis();
        
        activeEffectInfo.put(effectKey, new EffectInfo(effectId, modelName, player, lifetimeSeconds, onlyWhenStill, forceVisible, effects));
        effectIdMap.put(effectId, effectKey);
        
    Location[] lastLocation = {player.getLocation().clone()};
        
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int tick = 0;
            private final int maxTicks = Math.max(model.getDuration(), getMaxParticleDelay(model) + 60);
            private final boolean isAnimatedModel = model instanceof com.winss.dustlab.media.AnimatedModel;
            private long startMs = System.currentTimeMillis();
            private int lastFrameIndex = -1;
            private int lastFrameChangeTick = 0;
            
            @Override
            public void run() {
                EffectInfo currentEffect = activeEffectInfo.get(effectKey);
                if (currentEffect == null) {
                    BukkitTask currentTask = activeEffects.remove(effectKey);
                    if (currentTask != null) {
                        currentTask.cancel();
                    }
                    return;
                }
                
                if (!player.isOnline()) {
                    BukkitTask currentTask = activeEffects.remove(effectKey);
                    activeEffectInfo.remove(effectKey);
                    effectIdMap.remove(effectId);
                    if (currentTask != null) {
                        currentTask.cancel();
                    }
                    return;
                }
                
                if (currentEffect.hasExpired()) {
                    BukkitTask currentTask = activeEffects.remove(effectKey);
                    activeEffectInfo.remove(effectKey);
                    effectIdMap.remove(effectId);
                    if (currentTask != null) {
                        currentTask.cancel();
                    }
                    return;
                }
                
                if (tick >= maxTicks && lifetimeSeconds == 0) {
                    BukkitTask currentTask = activeEffects.remove(effectKey);
                    activeEffectInfo.remove(effectKey);
                    effectIdMap.remove(effectId);
                    if (currentTask != null) {
                        currentTask.cancel();
                    }
                    return;
                }
                
                Location currentLocation = player.getLocation();
                boolean shouldShow = true;
                boolean isMoving = false;
                
                if (onlyWhenStill) {
                    double distance = lastLocation[0].distance(currentLocation);
                    shouldShow = distance < 0.1; 
                    isMoving = distance >= 0.05; 
                } else {
                    double distance = lastLocation[0].distance(currentLocation);
                    isMoving = distance >= 0.05; 
                }
                
                lastLocation[0] = currentLocation.clone();
                
                List<ParticleData> currentParticles;
                boolean emitThisTick = true;
                if (isAnimatedModel) {
                    com.winss.dustlab.media.AnimatedModel animatedModel = (com.winss.dustlab.media.AnimatedModel) model;
                    com.winss.dustlab.media.FrameData currentFrame = animatedModel.isTickAligned()
                        ? animatedModel.getFrameAtTick(tick)
                        : animatedModel.getFrameAtTime(System.currentTimeMillis() - startMs);
                    if (currentFrame != null) {
                        int frameIndex = currentFrame.getFrameIndex();
                        if (frameIndex != lastFrameIndex) {
                            lastFrameIndex = frameIndex;
                            lastFrameChangeTick = tick;
                            emitThisTick = true;
                        } else {
                            int lifespan = Math.max(1, config.getMediaParticleLifespanTicks());
                            emitThisTick = (tick - lastFrameChangeTick) < lifespan;
                        }
                        currentParticles = currentFrame.getParticles();
                    } else {
                        currentParticles = new ArrayList<>();
                        emitThisTick = false;
                    }
                } else {
                    currentParticles = model.getParticles() != null ? model.getParticles() : new ArrayList<>();
                }
                
                if (shouldShow && emitThisTick && !currentParticles.isEmpty()) {
                    boolean shouldSpawnThisTick = true;
                    if (isMoving) {
                        shouldSpawnThisTick = (tick % 2 == 0);
                    }
                    
                    if (shouldSpawnThisTick) {
                        processParticlesForPlayer(currentParticles, player, currentLocation, effects, tick, lifetimeSeconds, maxTicks, forceVisible, effectKey, isAnimatedModel);
                    }
                }
                
                tick++;
            }
        }, 0L, 1L); 
        
        activeEffects.put(effectKey, task);
        return effectId;
    }
    
    private int playModelOnLocationWithEffects(String modelName, Location location, int lifetimeSeconds, boolean persistent, ParticleEffects.EffectSettings effects) {
        ParticleModel model = getModel(modelName);
        if (model == null) {
            plugin.getLogger().warning("DustLab: Model not found: " + modelName);
            return -1;
        }
        
        if (location == null || location.getWorld() == null) {
            plugin.getLogger().warning("DustLab: Invalid location for model: " + modelName);
            return -1;
        }
        
        if (model.getParticles() == null || model.getParticles().isEmpty()) {
            plugin.getLogger().warning("DustLab: Model has no particles: " + modelName);
            return -1;
        }
        
        
        int effectId = nextEffectId++;
        String effectKey = modelName + "_" + effectId + "_" + System.currentTimeMillis();
        
        activeEffectInfo.put(effectKey, new EffectInfo(effectId, modelName, location.clone(), lifetimeSeconds, persistent, effects));
        effectIdMap.put(effectId, effectKey);
        
        int tickRate = 1;
        boolean isLargeModel = model.getParticles() != null && model.getParticles().size() > LARGE_MODEL_THRESHOLD_STRICT;
        
        if (isLargeModel) {
            int particleCount = model.getParticles().size();
            if (particleCount > 10000) {
                com.winss.dustlab.utils.MessageUtils.logVerbose(plugin, config, "Loading very large model '" + modelName + "' (" + particleCount + " particles) - using sectioned rendering with persistent outline");
            } else {
                com.winss.dustlab.utils.MessageUtils.logVerbose(plugin, config, "Loading large model '" + modelName + "' (" + particleCount + " particles) - using persistence overlap rendering");
            }
        }
        
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int tick = 0;
            private final int maxTicks = Math.max(model.getDuration(), getMaxParticleDelay(model) + 60);
            @SuppressWarnings("unused")
            private final int particleCount = model.getParticles() != null ? model.getParticles().size() : 0;
            private final boolean isAnimatedModel = model instanceof com.winss.dustlab.media.AnimatedModel;
            private long startMs = System.currentTimeMillis();
            private int lastFrameIndex = -1; // to gate per-frame emission
            
            @Override
            public void run() {
                EffectInfo currentEffect = activeEffectInfo.get(effectKey);
                if (currentEffect == null) {
                    BukkitTask currentTask = activeEffects.remove(effectKey);
                    if (currentTask != null) {
                        currentTask.cancel();
                    }
                    return;
                }
                
                if (currentEffect.hasExpired()) {
                    BukkitTask currentTask = activeEffects.remove(effectKey);
                    activeEffectInfo.remove(effectKey);
                    effectIdMap.remove(effectId);
                    if (currentTask != null) {
                        currentTask.cancel();
                    }
                    return;
                }
                
                if (tick >= maxTicks && lifetimeSeconds == 0) {
                    BukkitTask currentTask = activeEffects.remove(effectKey);
                    activeEffectInfo.remove(effectKey);
                    effectIdMap.remove(effectId);
                    if (currentTask != null) {
                        currentTask.cancel();
                    }
                    return;
                }
                
                List<ParticleData> currentParticles;
                if (isAnimatedModel) {
                    com.winss.dustlab.media.AnimatedModel animatedModel = (com.winss.dustlab.media.AnimatedModel) model;
                    com.winss.dustlab.media.FrameData currentFrame = animatedModel.isTickAligned()
                        ? animatedModel.getFrameAtTick(tick)
                        : animatedModel.getFrameAtTime(System.currentTimeMillis() - startMs);
                    // Per-frame emission gating: only emit when frame index advances or on first tick
                    if (currentFrame != null) {
                        int frameIndex = currentFrame.getFrameIndex();
                        if (frameIndex == lastFrameIndex) {
                            tick++;
                            return;
                        }
                        lastFrameIndex = frameIndex;
                        currentParticles = currentFrame.getParticles();
                        
                    } else {
                        currentParticles = new ArrayList<>();
                    }
                } else {
                    currentParticles = model.getParticles() != null ? model.getParticles() : new ArrayList<>();
                }
                
                if (!currentParticles.isEmpty()) {
                    processParticlesSimple(currentParticles, null, location, effects, tick, lifetimeSeconds, maxTicks, effectKey, isAnimatedModel);
                }
                
                tick++;
            }
        }, 0L, tickRate);
        
        activeEffects.put(effectKey, task);
        return effectId;
    }
    
    private int playModelOnLocationWithEffectsAndTickOffset(String modelName, Location location, int lifetimeSeconds, boolean persistent, ParticleEffects.EffectSettings effects, long initialTickOffset) {
        ParticleModel model = getModel(modelName);
        if (model == null) {
            plugin.getLogger().warning("DustLab: Model not found: " + modelName);
            return -1;
        }
        
        if (location == null || location.getWorld() == null) {
            plugin.getLogger().warning("DustLab: Invalid location for model: " + modelName);
            return -1;
        }
        
        if (model.getParticles() == null || model.getParticles().isEmpty()) {
            plugin.getLogger().warning("DustLab: Model has no particles: " + modelName);
            return -1;
        }
        
        int effectId = nextEffectId++;
        String effectKey = modelName + "_" + effectId + "_" + System.currentTimeMillis();
        
        activeEffectInfo.put(effectKey, new EffectInfo(effectId, modelName, location.clone(), lifetimeSeconds, persistent, effects));
        effectIdMap.put(effectId, effectKey);
        
        int tickRate = 1;
        boolean isLargeModel = model.getParticles() != null && model.getParticles().size() > LARGE_MODEL_THRESHOLD_STRICT;
        
        if (isLargeModel) {
            int particleCount = model.getParticles().size();
            if (particleCount > 10000) {
                com.winss.dustlab.utils.MessageUtils.logVerbose(plugin, config, "Loading very large model '" + modelName + "' (" + particleCount + " particles) - using sectioned rendering with persistent outline");
            } else {
                com.winss.dustlab.utils.MessageUtils.logVerbose(plugin, config, "Loading large model '" + modelName + "' (" + particleCount + " particles) - using persistence overlap rendering");
            }
        }
        
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int tick = (int) initialTickOffset; // Start with the calculated offset
            private final int maxTicks = Math.max(model.getDuration(), getMaxParticleDelay(model) + 60);
            @SuppressWarnings("unused")
            private final int particleCount = model.getParticles() != null ? model.getParticles().size() : 0;
            private final boolean isAnimatedModel = model instanceof com.winss.dustlab.media.AnimatedModel;
            private long startMs = System.currentTimeMillis() - (initialTickOffset * 50L);
            private int lastFrameIndex = -1;
            
            @Override
            public void run() {
                EffectInfo currentEffect = activeEffectInfo.get(effectKey);
                if (currentEffect == null) {
                    BukkitTask currentTask = activeEffects.remove(effectKey);
                    if (currentTask != null) {
                        currentTask.cancel();
                    }
                    return;
                }
                
                if (currentEffect.hasExpired()) {
                    BukkitTask currentTask = activeEffects.remove(effectKey);
                    activeEffectInfo.remove(effectKey);
                    effectIdMap.remove(effectId);
                    if (currentTask != null) {
                        currentTask.cancel();
                    }
                    return;
                }
                
                if (tick >= maxTicks && lifetimeSeconds == 0) {
                    BukkitTask currentTask = activeEffects.remove(effectKey);
                    activeEffectInfo.remove(effectKey);
                    effectIdMap.remove(effectId);
                    if (currentTask != null) {
                        currentTask.cancel();
                    }
                    return;
                }
                
                List<ParticleData> currentParticles;
                if (isAnimatedModel) {
                    com.winss.dustlab.media.AnimatedModel animatedModel = (com.winss.dustlab.media.AnimatedModel) model;
                    com.winss.dustlab.media.FrameData currentFrame = animatedModel.isTickAligned()
                        ? animatedModel.getFrameAtTick(tick)
                        : animatedModel.getFrameAtTime(System.currentTimeMillis() - startMs);
                    if (currentFrame != null) {
                        int frameIndex = currentFrame.getFrameIndex();
                        if (frameIndex == lastFrameIndex) {
                            tick++;
                            return;
                        }
                        lastFrameIndex = frameIndex;
                        currentParticles = currentFrame.getParticles();
                        
                    } else {
                        currentParticles = new ArrayList<>();
                    }
                } else {
                    currentParticles = model.getParticles() != null ? model.getParticles() : new ArrayList<>();
                }
                
                if (!currentParticles.isEmpty()) {
                    processParticlesSimple(currentParticles, null, location, effects, tick, lifetimeSeconds, maxTicks, effectKey, isAnimatedModel);
                }
                
                tick++;
            }
        }, 0L, tickRate);
        
        activeEffects.put(effectKey, task);
        return effectId;
    }
    

    
    /**
     * Optimized particle processing for client-side particle limits
     */
    private void processParticlesSimple(List<ParticleData> particles, List<ParticleData> previousParticles, Location baseLocation,
                                       ParticleEffects.EffectSettings effects, int tick,
                                       int lifetimeSeconds, int maxTicks, String effectId, boolean isAnimated) {
        
        int particleCount = particles.size();
        
        int veryLargeThreshold = getVeryLargeModelThreshold();
        int largeThreshold = getLargeModelThreshold();
        
        // For animated media frames we must render the exact snapshot per frame without persistence tricks
        if (!isAnimated) {
            if (particleCount > veryLargeThreshold) {
                processVeryLargeModel(particles, baseLocation, effects, tick, lifetimeSeconds, maxTicks);
                return;
            }

            if (particleCount > largeThreshold) {
                processLargeModelWithPersistence(particles, baseLocation, effects, tick, lifetimeSeconds, maxTicks);
                return;
            }
        }

        // Animated: draw the complete frame snapshot this tick (no sampling)
        if (isAnimated) {
            for (int i = 0; i < particleCount; i++) {
                ParticleData particle = particles.get(i);
                if (particle == null) continue;
                ParticleData prev = (previousParticles != null && i < previousParticles.size()) ? previousParticles.get(i) : null;
                spawnParticleWithEffects(particle, prev, baseLocation, effects, tick, true);
            }
            return;
        }

        // Static/non-animated: original scheduling with per-particle delays
        for (ParticleData particle : particles) {
            if (particle == null) continue;

            boolean shouldSpawn = false;

            if (lifetimeSeconds == -1) {
                int cycleLength = Math.max(maxTicks, 100);
                int cycleTick = tick % cycleLength;

                if (cycleTick >= particle.getDelay()) {
                    int spawnInterval = effects != null ? 1 : 3;
                    if ((cycleTick - particle.getDelay()) % spawnInterval == 0) {
                        shouldSpawn = true;
                    }
                }
            } else {
                if (tick >= particle.getDelay() && (lifetimeSeconds > 0 || tick < maxTicks)) {
                    int spawnInterval = effects != null ? 1 : 3;
                    if ((tick - particle.getDelay()) % spawnInterval == 0) {
                        shouldSpawn = true;
                    }
                }
            }

            if (shouldSpawn) {
                spawnParticleWithEffects(particle, baseLocation, effects, tick);
            }
        }
    }
    
    private void processLargeModelWithPersistence(List<ParticleData> particles, Location baseLocation, 
                                                ParticleEffects.EffectSettings effects, int tick, 
                                                int lifetimeSeconds, int maxTicks) {
        
        int particleCount = particles.size();
        
        int fadeInDuration = 60;
        int particlesPerTick = Math.max(1, particleCount / fadeInDuration);
        int maxVisibleParticles = Math.min(particleCount, (tick + 1) * particlesPerTick);
        
        int baseOutlineInterval = Math.max(1, particleCount / 100); 
        
        for (int i = 0; i < particleCount; i++) {
            ParticleData particle = particles.get(i);
            if (particle == null) continue;
            
            boolean shouldSpawn = false;
            boolean isBaseOutline = (i % baseOutlineInterval == 0);
            boolean isWithinFadeIn = (i < maxVisibleParticles);
            
            if (lifetimeSeconds == -1) { 
                int cycleLength = Math.max(maxTicks, 100);
                int cycleTick = tick % cycleLength;
                
                if (cycleTick >= particle.getDelay()) {
                    if (isBaseOutline) {
                        shouldSpawn = true;
                    }
                    else if (isWithinFadeIn && (tick % 2 == 0)) {
                        shouldSpawn = true;
                    }
                }
            } else {
                if (tick >= particle.getDelay() && (lifetimeSeconds > 0 || tick < maxTicks)) {
                    if (isBaseOutline) {
                        shouldSpawn = true;
                    } else if (isWithinFadeIn && (tick % 2 == 0)) {
                        shouldSpawn = true;
                    }
                }
            }
            
            if (shouldSpawn) {
                spawnParticleWithEffects(particle, baseLocation, effects, tick);
            }
        }
    }
    
    /**
     * Process very large models (10k+) with smooth section transitions to prevent epilepsy triggers
     */
    private void processVeryLargeModel(List<ParticleData> particles, Location baseLocation, 
                                     ParticleEffects.EffectSettings effects, int tick, 
                                     int lifetimeSeconds, int maxTicks) {
        
        int particleCount = particles.size();
        
        int maxParticlesPerSection = 2000;
        int totalSections = (int) Math.ceil((double) particleCount / maxParticlesPerSection);
        
        int sectionRotationSpeed = 8; 
        int currentSection = (tick / sectionRotationSpeed) % totalSections;
        
        // FADE TRANSITION
        int transitionTicks = 4;
        int tickInCycle = tick % sectionRotationSpeed;
        boolean isInTransition = (tickInCycle >= sectionRotationSpeed - transitionTicks);
        int nextSection = (currentSection + 1) % totalSections;
        
        int currentSectionStart = currentSection * maxParticlesPerSection;
        int currentSectionEnd = Math.min(currentSectionStart + maxParticlesPerSection, particleCount);
        int nextSectionStart = nextSection * maxParticlesPerSection;
        int nextSectionEnd = Math.min(nextSectionStart + maxParticlesPerSection, particleCount);
        
        int persistentInterval = Math.max(1, particleCount / 150); 
        
        for (int i = 0; i < particleCount; i++) {
            ParticleData particle = particles.get(i);
            if (particle == null) continue;
            
            boolean shouldSpawn = false;
            boolean isInCurrentSection = (i >= currentSectionStart && i < currentSectionEnd);
            boolean isInNextSection = (i >= nextSectionStart && i < nextSectionEnd);
            boolean isPersistentParticle = (i % persistentInterval == 0);
            
            if (lifetimeSeconds == -1) { 
                int cycleLength = Math.max(maxTicks, 100);
                int cycleTick = tick % cycleLength;
                
                if (cycleTick >= particle.getDelay()) {
                    if (isPersistentParticle) {
                        shouldSpawn = true;
                    }
                    else if (isInCurrentSection) {
                        shouldSpawn = true;
                    }
                    else if (isInTransition && isInNextSection && (tick % 3 == 0)) {
                        shouldSpawn = true;
                    }
                }
            } else {
                if (tick >= particle.getDelay() && (lifetimeSeconds > 0 || tick < maxTicks)) {
                    if (isPersistentParticle) {
                        shouldSpawn = true;
                    } else if (isInCurrentSection) {
                        shouldSpawn = true;
                    } else if (isInTransition && isInNextSection && (tick % 3 == 0)) {
                        shouldSpawn = true;
                    }
                }
            }
            
            if (shouldSpawn) {
                spawnParticleWithEffects(particle, baseLocation, effects, tick);
            }
        }
    }
    
    public int playModel(String modelName, Location location) {
        return playModel(modelName, location, false);
    }

    private int getMaxParticleDelay(ParticleModel model) {
        int maxDelay = 0;
        if (model.getParticles() != null) {
            for (ParticleData particle : model.getParticles()) {
                if (particle != null && particle.getDelay() > maxDelay) {
                    maxDelay = particle.getDelay();
                }
            }
        }
        return maxDelay;
    }
    
    private void spawnParticleWithEffects(ParticleData particle, Location baseLocation, ParticleEffects.EffectSettings effects, long tick, boolean isAnimated) {
        try {
            World world = baseLocation.getWorld();
            if (world == null) {
                return;
            }
            
            Location particleLocation;
            
            if (effects != null) {
                particleLocation = ParticleEffects.applyEffects(particle, baseLocation, effects, tick);
            } else {
                double x = particle.getX();
                double y = particle.getY();
                double z = particle.getZ();
                particleLocation = baseLocation.clone().add(x, y, z);
            }
            
            Particle.DustOptions dustOptions = particle.getDustOptions();
            
            int particleCount = 1;
            double offsetX = 0.0, offsetY = 0.0, offsetZ = 0.0;
            double extra = 0.0;
            
            if (effects != null && (effects.rotationSpeed != 0 || effects.orbitRadius > 0 || effects.spiralExpansion != 0)) {
                offsetX = 0.01; 
                offsetY = 0.01;
                offsetZ = 0.01;
                extra = 0.0;
                
                if (tick % 2 != 0) {
                    return;
                }
            }
            
            Location baseLoc = particleLocation; 
            
            for (Player player : world.getPlayers()) {
                if (player.getLocation().distanceSquared(baseLoc) <= MAX_RENDER_DISTANCE_SQUARED) {
                    if (canSeeParticles(player)) {
                        spawnParticleForViewer(player, particleLocation, dustOptions, null, particleCount, offsetX, offsetY, offsetZ, extra, isAnimated);
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("DustLab: Error spawning particle: " + e.getMessage());
        }
    }

    // Overload: includes previous particle for animated transitions
    private void spawnParticleWithEffects(ParticleData particle, ParticleData previous, Location baseLocation, ParticleEffects.EffectSettings effects, long tick, boolean isAnimated) {
        try {
            World world = baseLocation.getWorld();
            if (world == null) {
                return;
            }

            Location particleLocation;

            if (effects != null) {
                particleLocation = ParticleEffects.applyEffects(particle, baseLocation, effects, tick);
            } else {
                double x = particle.getX();
                double y = particle.getY();
                double z = particle.getZ();
                particleLocation = baseLocation.clone().add(x, y, z);
            }

            Particle.DustOptions dustOptions = particle.getDustOptions();
            Particle.DustOptions prevDust = previous != null ? previous.getDustOptions() : null;

            int particleCount = 1;
            double offsetX = 0.0, offsetY = 0.0, offsetZ = 0.0;
            double extra = 0.0;

            if (effects != null && (effects.rotationSpeed != 0 || effects.orbitRadius > 0 || effects.spiralExpansion != 0)) {
                offsetX = 0.01;
                offsetY = 0.01;
                offsetZ = 0.01;
                extra = 0.0;

                if (tick % 2 != 0) {
                    return;
                }
            }

            Location baseLoc = particleLocation;

            for (Player player : world.getPlayers()) {
                if (player.getLocation().distanceSquared(baseLoc) <= MAX_RENDER_DISTANCE_SQUARED) {
                    if (canSeeParticles(player)) {
                        spawnParticleForViewer(player, particleLocation, dustOptions, prevDust, particleCount, offsetX, offsetY, offsetZ, extra, isAnimated);
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("DustLab: Error spawning particle: " + e.getMessage());
        }
    }
    
    private void spawnParticleWithEffects(ParticleData particle, Location baseLocation, ParticleEffects.EffectSettings effects, long tick) {
        spawnParticleWithEffects(particle, baseLocation, effects, tick, false);
    }
    

    private void processParticlesForPlayer(List<ParticleData> particles, Player player, Location playerLocation,
                                         ParticleEffects.EffectSettings effects, int tick,
                                         int lifetimeSeconds, int maxTicks, boolean forceVisible, String effectId, boolean isAnimated) {
        
        int particleCount = particles.size();
        
        Location currentPlayerLocation = player.getLocation();
        
        double movementDistance = 0.0;
        if (playerLocation != null) {
            movementDistance = playerLocation.distance(currentPlayerLocation);
        }
        
        int processLimit;
        int spawnInterval;
        
        if (isAnimated) {
            processLimit = particleCount;
            spawnInterval = 1;
        } else {
            if (movementDistance > 0.1) { 
                processLimit = Math.min(particleCount / 3, getMaxParticlesPerTick() / 2);
                spawnInterval = 2; 
            } else if (movementDistance > 0.05) {  
                processLimit = Math.min(particleCount / 2, getMaxParticlesPerTick());
                spawnInterval = 1;
            } else { 
                processLimit = Math.min(particleCount, getMaxParticlesPerTick());
                spawnInterval = 1;
            }
        }
        
        int batchSize = Math.min(processLimit, getParticlesPerBatch());
        
        int totalToProcess = Math.min(particleCount, processLimit);

        int iStart = 0;
        int iStep = 1;

        for (int i = iStart, processed = 0; i < particleCount && processed < totalToProcess; i += iStep, processed++) {
            ParticleData particle = particles.get(i);
            if (particle == null) continue;
            
            boolean shouldSpawn = false;

            if (isAnimated) {
                // Always spawn selected particles for this frame snapshot
                shouldSpawn = true;
            } else {
                if (lifetimeSeconds == -1) {
                    int cycleLength = Math.max(maxTicks, 100);
                    int cycleTick = tick % cycleLength;

                    if (cycleTick >= particle.getDelay()) {
                        if ((cycleTick - particle.getDelay()) % spawnInterval == 0) {
                            shouldSpawn = true;
                        }
                    }
                } else {
                    if (tick >= particle.getDelay() && (lifetimeSeconds > 0 || tick < maxTicks)) {
                        if ((tick - particle.getDelay()) % spawnInterval == 0) {
                            shouldSpawn = true;
                        }
                    }
                }
            }
            
            if (shouldSpawn) {
                // TEMPORARILY DISABLED ALSO VERY OLD, WILL CLEAN UP THE CODE LATER: Particle optimization causing animation issues
                /*
                // For animated models, use particle optimizer to reduce unnecessary spawns
                if (isAnimated) {
                    if (particleOptimizer.shouldUpdateParticle(effectId, particle, tick)) {
                        // Use current location for real-time positioning
                        spawnParticleForPlayer(particle, currentPlayerLocation, player, effects, tick, forceVisible);
                    }
                } else {
                    // Normal models spawn without optimization
                    spawnParticleForPlayer(particle, currentPlayerLocation, player, effects, tick, forceVisible);
                }
                */
                // For animated frames, mark as animated to use minimal-persistence particle styling
                spawnParticleForPlayer(particle, currentPlayerLocation, player, effects, tick, forceVisible, isAnimated);
            }
            
            // Dynamic batch limiting based on movement (disabled for animated frames)
            if (!isAnimated) {
                if (i % (batchSize + (movementDistance > 0.05 ? batchSize : 0)) == 0 && i > 0) {
                    break; 
                }
            }
        }
    }
    
    /**
     * Spawn particle for player-attached effect
     */
    private void spawnParticleForPlayer(ParticleData particle, Location playerLocation, Player attachedPlayer,
                                      ParticleEffects.EffectSettings effects, long tick, boolean forceVisible, boolean isAnimated) {
        try {
            World world = playerLocation.getWorld();
            if (world == null || !attachedPlayer.isOnline()) {
                return;
            }
            
            Location particleLocation;
            
            if (effects != null) {
                particleLocation = ParticleEffects.applyEffects(particle, playerLocation, effects, tick);
            } else {
                double x = particle.getX();
                double y = particle.getY();
                double z = particle.getZ();
                particleLocation = playerLocation.clone().add(x, y, z);
            }
            
            Particle.DustOptions dustOptions = particle.getDustOptions();
            
            int particleCount = 1;
            double offsetX = 0.0, offsetY = 0.0, offsetZ = 0.0;
            double extra = 0.0; 
            
            Location baseLoc = particleLocation;
            
            for (Player viewer : world.getPlayers()) {
                if (viewer.getLocation().distanceSquared(baseLoc) <= (MAX_RENDER_DISTANCE_SQUARED * 0.6)) {
                    boolean canView = forceVisible || canSeeParticles(viewer);
                    
                    if (canView) {
                        spawnParticleForViewer(viewer, particleLocation, dustOptions, null, particleCount, offsetX, offsetY, offsetZ, extra, isAnimated);
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to spawn player particle: " + e.getMessage());
        }
    }

    private void spawnParticleForViewer(Player viewer, Location location, Particle.DustOptions dustOptions, Particle.DustOptions previousDust,
                                      int count, double offsetX, double offsetY, double offsetZ, double extra, boolean isAnimated) {
        if (isAnimated) {
            // Choose particle type for animated frames based on config (default REDSTONE)
            com.winss.dustlab.config.DustLabConfig.AnimatedParticleMode mode = config.getAnimatedParticleMode();
            // Use minimal persistence: 1 particle with small extra and zero offsets
            count = 1;
            offsetX = 0.0; offsetY = 0.0; offsetZ = 0.0;
            // Lower extra helps reduce lingering visuals (implementation-specific)
            extra = 0.0;
            if (mode == com.winss.dustlab.config.DustLabConfig.AnimatedParticleMode.TRANSITION) {
                try {
                    // Even if configured, to avoid smear/ghosting, use same->same in animated path
                    Particle.DustTransition dustTransition = new Particle.DustTransition(
                        dustOptions.getColor(),
                        dustOptions.getColor(),
                        dustOptions.getSize()
                    );
                    viewer.spawnParticle(
                        Particle.DUST_COLOR_TRANSITION,
                        location,
                        count,
                        offsetX,
                        offsetY,
                        offsetZ,
                        extra,
                        dustTransition
                    );
                } catch (Exception e) {
                    viewer.spawnParticle(
                        Particle.REDSTONE,
                        location,
                        count,
                        offsetX,
                        offsetY,
                        offsetZ,
                        extra,
                        dustOptions
                    );
                }
            } else {
                viewer.spawnParticle(
                    Particle.REDSTONE,
                    location,
                    count,
                    offsetX,
                    offsetY,
                    offsetZ,
                    extra,
                    dustOptions
                );
            }
        } else {
            try {
                Particle.DustTransition dustTransition = new Particle.DustTransition(
                    dustOptions.getColor(), 
                    dustOptions.getColor(), 
                    dustOptions.getSize()
                );
                
                viewer.spawnParticle(
                    Particle.DUST_COLOR_TRANSITION,
                    location,
                    count, 
                    offsetX, 
                    offsetY,
                    offsetZ,
                    extra,
                    dustTransition
                );
            } catch (Exception e) {
                viewer.spawnParticle(
                    Particle.REDSTONE,
                    location,
                    count, 
                    offsetX, 
                    offsetY,
                    offsetZ,
                    extra,
                    dustOptions 
                );
            }
        }
    }
    
    @SuppressWarnings("unused")
    private void spawnParticle(ParticleData particle, Location baseLocation) {
        spawnParticleWithEffects(particle, baseLocation, null, 0);
    }
    
    public void stopAllEffects() {
        for (BukkitTask task : activeEffects.values()) {
            task.cancel();
        }
        activeEffects.clear();
        effectIdMap.clear();
        
        // I am an idiot, apparently this was causing persistent effects to be lost on server shutdown 
    }
    
    public void stopAllEffectsAndClearMemory() {
        for (BukkitTask task : activeEffects.values()) {
            task.cancel();
        }
        activeEffects.clear();
        activeEffectInfo.clear();
        effectIdMap.clear();
    }
    
    public boolean stopEffect(int effectId) {
        String effectKey = effectIdMap.get(effectId);
        if (effectKey == null) {
            return false; 
        }
        
        BukkitTask task = activeEffects.remove(effectKey);
        activeEffectInfo.remove(effectKey);
        effectIdMap.remove(effectId);
        
        particleOptimizer.removeEffect(effectKey);
        
        if (task != null) {
            task.cancel();
            return true;
        }
        return false;
    }
    
    public boolean moveEffect(int effectId, Location newLocation) {
        String effectKey = effectIdMap.get(effectId);
        if (effectKey == null) {
            return false; 
        }
        
        EffectInfo oldInfo = activeEffectInfo.get(effectKey);
        if (oldInfo == null) {
            return false;
        }
        
        BukkitTask task = activeEffects.remove(effectKey);
        if (task != null) {
            task.cancel();
        }
        
        activeEffectInfo.remove(effectKey);
        effectIdMap.remove(effectId);
        
        int newEffectId;
        if (oldInfo.effectSettings != null) {
            newEffectId = playModelWithEffects(oldInfo.modelName, newLocation, oldInfo.lifetimeSeconds, 
                                             oldInfo.isPersistent, oldInfo.effectSettings);
        } else {
            newEffectId = playModel(oldInfo.modelName, newLocation, oldInfo.lifetimeSeconds, 
                                  oldInfo.isPersistent);
        }
        
        return newEffectId != -1;
    }
    
    public EffectInfo getEffectInfo(int effectId) {
        String effectKey = effectIdMap.get(effectId);
        if (effectKey == null) {
            return null;
        }
        return activeEffectInfo.get(effectKey);
    }
    

    private void savePersistedModels() {
        savePersistedModels(false);
    }
    

    private void savePersistedModels(boolean forceLog) {
        File persistentFile = new File(plugin.getDataFolder(), "persistent_instances.json");
        try (FileWriter writer = new FileWriter(persistentFile)) {
            Map<String, Object> persistentData = new HashMap<>();
            List<Map<String, Object>> instances = new ArrayList<>();
            
            for (Map.Entry<String, EffectInfo> entry : activeEffectInfo.entrySet()) {
                EffectInfo effect = entry.getValue();
                if (effect.isPersistent()) {
                    Map<String, Object> instance = new HashMap<>();
                    
                    instance.put("model", effect.modelName);
                    
                    Map<String, Object> coordinates = new HashMap<>();
                    coordinates.put("world", effect.location.getWorld().getName());
                    coordinates.put("x", Math.round(effect.location.getX() * 1000.0) / 1000.0);
                    coordinates.put("y", Math.round(effect.location.getY() * 1000.0) / 1000.0);
                    coordinates.put("z", Math.round(effect.location.getZ() * 1000.0) / 1000.0);
                    instance.put("coordinates", coordinates);
                    
                    Map<String, Object> lifespan = new HashMap<>();
                    lifespan.put("duration_seconds", effect.lifetimeSeconds);
                    lifespan.put("type", effect.lifetimeSeconds == -1 ? "infinite" : 
                                       effect.lifetimeSeconds == 0 ? "one-time" : "timed");
                    lifespan.put("started_at", effect.startTime);
                    
                    // Add animation start time for animated models
                    ParticleModel model = getModel(effect.modelName);
                    if (model instanceof com.winss.dustlab.media.AnimatedModel) {
                        lifespan.put("animation_start_time", effect.startTime);
                        lifespan.put("is_animated", true);
                    } else {
                        lifespan.put("is_animated", false);
                    }
                    
                    // Calculate when it will stop (if not infinite)
                    if (effect.lifetimeSeconds > 0) {
                        long stopTime = effect.startTime + (effect.lifetimeSeconds * 1000L);
                        lifespan.put("stops_at", stopTime);
                        lifespan.put("remaining_seconds", Math.max(0, (stopTime - System.currentTimeMillis()) / 1000));
                    } else if (effect.lifetimeSeconds == -1) {
                        lifespan.put("stops_at", "never");
                        lifespan.put("remaining_seconds", "infinite");
                    }
                    instance.put("lifespan", lifespan);
                    
                    // Effects information
                    Map<String, Object> effectsInfo = new HashMap<>();
                    if (effect.effectSettings != null && effect.effectSettings.hasEffects()) {
                        if (effect.effectSettings.rotationSpeed > 0) {
                            effectsInfo.put("type", "rotation");
                            effectsInfo.put("speed", effect.effectSettings.rotationSpeed);
                        } else if (effect.effectSettings.oscillationSpeed > 0) {
                            effectsInfo.put("type", "oscillation");
                            effectsInfo.put("speed", effect.effectSettings.oscillationSpeed);
                        } else if (effect.effectSettings.waveSpeed > 0) {
                            effectsInfo.put("type", "wave");
                            effectsInfo.put("speed", effect.effectSettings.waveSpeed);
                        } else if (effect.effectSettings.orbitSpeed > 0) {
                            effectsInfo.put("type", "orbit");
                            effectsInfo.put("speed", effect.effectSettings.orbitSpeed);
                        } else if (effect.effectSettings.spiralSpeed > 0) {
                            effectsInfo.put("type", "spiral");
                            effectsInfo.put("speed", effect.effectSettings.spiralSpeed);
                        } else {
                            effectsInfo.put("type", "none");
                            effectsInfo.put("speed", 1.0);
                        }
                    } else {
                        effectsInfo.put("type", "none");
                        effectsInfo.put("speed", 1.0);
                    }
                    instance.put("effects", effectsInfo);
                    
                    // Additional metadata
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("effect_id", effect.id);
                    metadata.put("is_persistent", effect.isPersistent());
                    metadata.put("is_infinite", effect.isInfinite());
                    metadata.put("has_expired", effect.hasExpired());
                    metadata.put("force_loaded", false); // this is not tracked currently, but could be added
                    instance.put("metadata", metadata);
                    
                    instances.add(instance);
                }
            }
            
            persistentData.put("persistent_instances", instances);
            
            Map<String, Object> fileMetadata = new HashMap<>();
            fileMetadata.put("saved_at", System.currentTimeMillis());
            fileMetadata.put("saved_date", java.time.Instant.now().toString());
            fileMetadata.put("format_version", "2.0");
            fileMetadata.put("plugin_version", "DustLab 1.1");
            fileMetadata.put("total_persistent_effects", instances.size());
            fileMetadata.put("description", "Persistent particle effects saved by the plugin");
            persistentData.put("metadata", fileMetadata);
            
            gson.toJson(persistentData, writer);
            
            long currentTime = System.currentTimeMillis();
            if (instances.size() > 0 && (forceLog || (currentTime - lastSaveLogTime) >= 1800000)) { 
                plugin.getLogger().info("Saved " + instances.size() + " persistent model instances to storage.");
                lastSaveLogTime = currentTime;
            }
        } catch (IOException e) {
            plugin.getLogger().warning("DustLab: Failed to save persistent instances: " + e.getMessage());
        }
    }
    
    private void loadPersistedModels() {
        File persistentFile = new File(plugin.getDataFolder(), "persistent_instances.json");
        if (persistentFile.exists()) {
            loadPersistentInstances();
        }
    }
    

    @SuppressWarnings("unchecked")
    private void loadPersistentInstances() {
        File persistentFile = new File(plugin.getDataFolder(), "persistent_instances.json");
        
        try (FileReader reader = new FileReader(persistentFile)) {
            Map<String, Object> persistentData = gson.fromJson(reader, Map.class);
            if (persistentData == null || !persistentData.containsKey("persistent_instances")) {
                return;
            }
            
            List<Map<String, Object>> instances = (List<Map<String, Object>>) persistentData.get("persistent_instances");
            if (instances.isEmpty()) {
                return;
            }
            
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                restorePersistentInstances(instances);
            }, 60L);
            
        } catch (IOException e) {
            plugin.getLogger().warning("DustLab: Failed to load persistent instances: " + e.getMessage());
        }
    }
    

    @SuppressWarnings("unchecked")
    private void restorePersistentInstances(List<Map<String, Object>> instances) {
        int restoredCount = 0;
        
        for (Map<String, Object> instance : instances) {
            try {
                String modelId;
                String worldName;
                Double x, y, z;
                int lifetimeSeconds;
                
                // Support both old and new JSON formats
                if (instance.containsKey("model")) {
                    // New format
                    modelId = (String) instance.get("model");
                    Map<String, Object> coordinates = (Map<String, Object>) instance.get("coordinates");
                    worldName = (String) coordinates.get("world");
                    x = ((Number) coordinates.get("x")).doubleValue();
                    y = ((Number) coordinates.get("y")).doubleValue();
                    z = ((Number) coordinates.get("z")).doubleValue();
                    
                    Map<String, Object> lifespan = (Map<String, Object>) instance.get("lifespan");
                    lifetimeSeconds = ((Number) lifespan.get("duration_seconds")).intValue();
                } else {
                    // Old format (backward compatibility)
                    modelId = (String) instance.get("model_id");
                    worldName = (String) instance.get("world");
                    x = (Double) instance.get("x");
                    y = (Double) instance.get("y");
                    z = (Double) instance.get("z");
                    
                    lifetimeSeconds = -1;
                    if (instance.containsKey("lifetime_seconds")) {
                        Object lifetimeObj = instance.get("lifetime_seconds");
                        if (lifetimeObj instanceof Number) {
                            lifetimeSeconds = ((Number) lifetimeObj).intValue();
                        }
                    }
                }
                
                if (!hasModel(modelId)) {
                    plugin.getLogger().warning("DustLab: Cannot restore persistent instance: model '" + modelId + "' not found in models folder");
                    continue;
                }
                
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    plugin.getLogger().warning("DustLab: Cannot restore persistent instance: world '" + worldName + "' not found");
                    continue;
                }
                
                Location location = new Location(world, x, y, z);
                
                // Handle effects for both formats
                ParticleEffects.EffectSettings effects = null;
                String effectType = "none";
                double speed = 1.0;
                
                if (instance.containsKey("effects")) {
                    // New format
                    Map<String, Object> effectsInfo = (Map<String, Object>) instance.get("effects");
                    effectType = (String) effectsInfo.get("type");
                    speed = ((Number) effectsInfo.get("speed")).doubleValue();
                } else {
                    // Old format
                    effectType = (String) instance.getOrDefault("effect", "none");
                    if (instance.containsKey("speed")) {
                        Object speedObj = instance.get("speed");
                        if (speedObj instanceof Number) {
                            speed = ((Number) speedObj).doubleValue();
                        }
                    }
                }
                
                if (!"none".equals(effectType)) {
                    effects = new ParticleEffects.EffectSettings();
                    switch (effectType) {
                        case "rotate":
                        case "rotation":
                            effects.rotationSpeed = speed;
                            break;
                        case "oscillate":
                        case "oscillation":
                            effects.oscillationSpeed = speed;
                            break;
                        case "wave":
                            effects.waveSpeed = speed;
                            break;
                        case "orbit":
                            effects.orbitSpeed = speed;
                            break;
                        case "spiral":
                            effects.spiralSpeed = speed;
                            break;
                    }
                }
                
                // Check if this is an animated model and calculate tick offset
                long animationStartTime = -1;
                boolean isAnimated = false;
                if (instance.containsKey("lifespan")) {
                    Map<String, Object> lifespanInfo = (Map<String, Object>) instance.get("lifespan");
                    if (lifespanInfo.containsKey("is_animated")) {
                        isAnimated = (Boolean) lifespanInfo.get("is_animated");
                    }
                    if (lifespanInfo.containsKey("animation_start_time")) {
                        Object startTimeObj = lifespanInfo.get("animation_start_time");
                        if (startTimeObj instanceof Number) {
                            animationStartTime = ((Number) startTimeObj).longValue();
                        }
                    }
                }
                
                // Restore the model with proper animation timing
                if (isAnimated && animationStartTime != -1) {
                    long currentTime = System.currentTimeMillis();
                    long elapsedMs = currentTime - animationStartTime;
                    long tickOffset = elapsedMs / 50; 
                    
                    if (effects != null) {
                        playModelWithEffectsAndTickOffset(modelId, location, lifetimeSeconds, true, effects, tickOffset);
                    } else {
                        playModelWithTickOffset(modelId, location, lifetimeSeconds, true, tickOffset);
                    }
                } else {
                    // Non-animated model or old format - use regular restoration
                    if (effects != null) {
                        playModelWithEffects(modelId, location, lifetimeSeconds, true, effects);
                    } else {
                        playModel(modelId, location, lifetimeSeconds, true);
                    }
                }
                restoredCount++;
                
            } catch (Exception e) {
                plugin.getLogger().warning("DustLab: Failed to restore persistent instance: " + e.getMessage());
            }
        }
        
        if (restoredCount > 0) {
            plugin.getLogger().info("Successfully restored " + restoredCount + " persistent model instances from storage.");
        }
    }

    
    public void cleanup() {
        savePersistedModels();
        stopAllEffectsAndClearMemory();
        nextEffectId = 1; 
    }
    
    public void reloadModels() {
        Map<String, EffectInfo> savedEffects = new HashMap<>(activeEffectInfo);
        @SuppressWarnings("unused")
        Map<String, BukkitTask> savedTasks = new HashMap<>(activeEffects);
        @SuppressWarnings("unused")
        Map<Integer, String> savedIdMap = new HashMap<>(effectIdMap);
        
        for (BukkitTask task : activeEffects.values()) {
            task.cancel();
        }
        activeEffects.clear();
        activeEffectInfo.clear();
        effectIdMap.clear();
        
        loadModels();
        
        for (Map.Entry<String, EffectInfo> entry : savedEffects.entrySet()) {
            String effectKey = entry.getKey();
            EffectInfo effectInfo = entry.getValue();
            
            if (hasModel(effectInfo.modelName)) {
                activeEffectInfo.put(effectKey, effectInfo);
                effectIdMap.put(effectInfo.id, effectKey);
                
                ParticleModel model = getModel(effectInfo.modelName);
                if (model != null) {
                    BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                        private int tick = 0;
                        private final int maxTicks = Math.max(model.getDuration(), getMaxParticleDelay(model) + 60);
                        
                        @Override
                        public void run() {
                            if (tick >= maxTicks && !effectInfo.isLooping) {
                                BukkitTask currentTask = activeEffects.remove(effectKey);
                                activeEffectInfo.remove(effectKey);
                                effectIdMap.remove(effectInfo.id);
                                if (currentTask != null) {
                                    currentTask.cancel();
                                }
                                return;
                            }
                            
                            List<ParticleData> currentParticles;
                            
                            boolean isAnimated = model instanceof com.winss.dustlab.media.AnimatedModel;
                            
                            if (isAnimated) {
                                com.winss.dustlab.media.AnimatedModel animatedModel = (com.winss.dustlab.media.AnimatedModel) model;
                                com.winss.dustlab.media.FrameData currentFrame = animatedModel.getFrameAtTick(tick);
                                currentParticles = currentFrame != null ? currentFrame.getParticles() : new ArrayList<>();
                            } else {
                                currentParticles = model.getParticles();
                            }
                            
                            if (currentParticles != null) {
                                for (ParticleData particle : currentParticles) {
                                    if (particle != null) {
                                        if (effectInfo.isLooping) {
                                            int cycleLength = Math.max(maxTicks, 100); 
                                            int cycleTick = tick % cycleLength;
                                            
                                            if (cycleTick >= particle.getDelay()) {
                                                if ((cycleTick - particle.getDelay()) % 3 == 0) {
                                                    spawnParticleWithEffects(particle, effectInfo.location, effectInfo.effectSettings, tick, isAnimated);
                                                }
                                            }
                                        } else {
                                            if (tick >= particle.getDelay() && tick < maxTicks) {
                                                if ((tick - particle.getDelay()) % 3 == 0) {
                                                    spawnParticleWithEffects(particle, effectInfo.location, effectInfo.effectSettings, tick, isAnimated);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            
                            tick++;
                        }
                    }, 0L, 1L); 
                    
                    activeEffects.put(effectKey, task);
                }
            } else {
                plugin.getLogger().warning("DustLab: Cannot restore effect for model '" + effectInfo.modelName + "' - model not found after reload");
            }
        }
        
        plugin.getLogger().info("Reloaded models and restored " + activeEffects.size() + " active effects.");
    }
    

    public void forceSave() {
        savePersistedModels(true);
    }

    public void saveModel(ParticleModel model, boolean isTemporary) throws IOException {
        String fileName = model.getName() + ".json";
        File modelsDir = new File(plugin.getDataFolder(), "models");
        
        if (!modelsDir.exists()) {
            modelsDir.mkdirs();
        }
        
        File modelFile = new File(modelsDir, fileName);
        
        if (modelFile.exists()) {
            throw new IOException("Model file already exists: " + fileName);
        }
        
        try (FileWriter writer = new FileWriter(modelFile)) {
            Gson gson = new GsonBuilder().create();
            gson.toJson(model, writer);
        }
        
        loadedModels.put(model.getName(), model);
        
        if (isTemporary) {
            scheduleTemporaryModelDeletion(model.getName(), modelFile);
        }
        
        com.winss.dustlab.utils.MessageUtils.logVerbose(plugin, config, "Saved new model '" + model.getName() + "' with " + 
                               model.getParticles().size() + " particles" + 
                               (isTemporary ? " (temporary)" : ""));
    }
    

    public void registerAnimatedModel(AnimatedModel animatedModel, boolean persistent) throws IOException {
        boolean compressed = false;
        if (animatedModel.getMetadata() != null) {
            Object c = animatedModel.getMetadata().get("compressed");
            if (c instanceof Boolean) compressed = (Boolean) c;
        }
        String fileName = animatedModel.getName() + (compressed ? ".json.gz" : ".json");
        File modelsDir = new File(plugin.getDataFolder(), "models");
        
        if (!modelsDir.exists()) {
            modelsDir.mkdirs();
        }
        
        File modelFile = new File(modelsDir, fileName);
        
        if (modelFile.exists()) {
            throw new IOException("Model file already exists: " + fileName);
        }
        
        // Add to memory immediately for availability
        loadedModels.put(animatedModel.getName(), animatedModel);
        
        // Write JSON asynchronously to prevent server hanging
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                writeAnimatedModelStreaming(animatedModel, modelFile);
                
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!persistent) {
                        scheduleTemporaryModelDeletion(animatedModel.getName(), modelFile);
                    }
                    
                    com.winss.dustlab.utils.MessageUtils.logVerbose(plugin, config, "Registered animated model '" + animatedModel.getName() + "' with " + 
                                           animatedModel.getTotalFrames() + " frames, " + 
                                           animatedModel.getTotalParticleCount() + " total particles" +
                                           (persistent ? " (persistent)" : " (temporary)"));
                });
                
            } catch (IOException e) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    loadedModels.remove(animatedModel.getName());
                    plugin.getLogger().severe("Failed to save animated model '" + animatedModel.getName() + "': " + e.getMessage());
                });
            }
        });
    }
    
    private void writeAnimatedModelStreaming(AnimatedModel animatedModel, File modelFile) throws IOException {
        boolean gz = modelFile.getName().toLowerCase().endsWith(".json.gz");
        java.io.Writer out;
        if (gz) {
            java.util.zip.GZIPOutputStream gos = new java.util.zip.GZIPOutputStream(new java.io.FileOutputStream(modelFile));
            out = new java.io.OutputStreamWriter(gos, java.nio.charset.StandardCharsets.UTF_8);
        } else {
            out = new FileWriter(modelFile);
        }
    try (com.google.gson.stream.JsonWriter writer = new com.google.gson.stream.JsonWriter(out)) {
            
            writer.setIndent("  "); 
            
            writer.beginObject();
            
            // Writing the basic properties
            writer.name("name").value(animatedModel.getName());
            writer.name("totalFrames").value(animatedModel.getTotalFrames());
            writer.name("looping").value(animatedModel.isLooping());
            writer.name("sourceUrl").value(animatedModel.getSourceUrl());
            writer.name("createdTime").value(animatedModel.getCreatedTime());
            writer.name("blockWidth").value(animatedModel.getBlockWidth());
            writer.name("blockHeight").value(animatedModel.getBlockHeight());
            writer.name("maxParticleCount").value(animatedModel.getMaxParticleCount());
            writer.name("duration").value(animatedModel.getDuration());
            if (animatedModel.getMetadata() != null && !animatedModel.getMetadata().isEmpty()) {
                writer.name("metadata");
                new Gson().toJson(animatedModel.getMetadata(), java.util.Map.class, writer);
            }
            
            writer.name("frames");
            writer.beginArray();
            
            int frameCount = animatedModel.getFrames().size();
            int batchSize = Math.max(1, Math.min(50, frameCount / 10));
            double globalSize = 1.0;
            try {
                if (animatedModel.getMetadata() != null) {
                    Object gs = animatedModel.getMetadata().get("globalParticleSize");
                    if (gs instanceof Number) globalSize = ((Number) gs).doubleValue();
                }
            } catch (Exception ignored) {}
            
            for (int i = 0; i < frameCount; i += batchSize) {
                int endIndex = Math.min(i + batchSize, frameCount);
                
                for (int frameIndex = i; frameIndex < endIndex; frameIndex++) {
                    com.winss.dustlab.media.FrameData frame = animatedModel.getFrames().get(frameIndex);
                    
                    writer.beginObject();
                    writer.name("frameIndex").value(frame.getFrameIndex());
                    writer.name("delayMs").value(frame.getDelayMs());
                    writer.name("particleCount").value(frame.getParticleCount());
                    
                    writer.name("particles");
                    writer.beginArray();
                    
                    for (com.winss.dustlab.models.ParticleData particle : frame.getParticles()) {
                        writer.beginObject();
                        writer.name("x").value(particle.getX());
                        writer.name("y").value(particle.getY());
                        writer.name("z").value(particle.getZ());
                        writer.name("delay").value(particle.getDelay());
                        
                        org.bukkit.Particle.DustOptions dustOptions = particle.getDustOptions();
                        writer.name("dustOptions");
                        writer.beginObject();
                        writer.name("red").value(dustOptions.getColor().getRed());
                        writer.name("green").value(dustOptions.getColor().getGreen());
                        writer.name("blue").value(dustOptions.getColor().getBlue());
                        // Include size only if overriding global
                        if (Math.abs(particle.getScale() - globalSize) > 1e-6) {
                            writer.name("size").value(dustOptions.getSize());
                        }
                        writer.endObject();
                        
                        writer.endObject();
                    }
                    
                    writer.endArray();
                    writer.endObject();
                }
                
                if (i % (batchSize * 5) == 0) {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during streaming write", e);
                    }
                }
            }
            
            writer.endArray(); 
            
            // Write base particles (first frame for compatibility)
            if (!animatedModel.getFrames().isEmpty()) {
                writer.name("particles");
                writer.beginArray();
                
                for (com.winss.dustlab.models.ParticleData particle : animatedModel.getParticles()) {
                    writer.beginObject();
                    writer.name("x").value(particle.getX());
                    writer.name("y").value(particle.getY());
                    writer.name("z").value(particle.getZ());
                    writer.name("delay").value(particle.getDelay());
                    
                    org.bukkit.Particle.DustOptions dustOptions = particle.getDustOptions();
                    writer.name("dustOptions");
                    writer.beginObject();
                    writer.name("red").value(dustOptions.getColor().getRed());
                    writer.name("green").value(dustOptions.getColor().getGreen());
                    writer.name("blue").value(dustOptions.getColor().getBlue());
                    writer.endObject();
                    
                    writer.endObject();
                }
                
                writer.endArray();
            }
            
            writer.endObject(); 
        }
    }
    

    private void scheduleTemporaryModelDeletion(String modelName, File modelFile) {
        long deletionDelay = config != null ? config.getTempModelLifetimeTicks() : (20 * 60 * 30);
        
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            loadedModels.remove(modelName);
            
            List<String> effectsToRemove = new ArrayList<>();
            for (Map.Entry<String, EffectInfo> entry : activeEffectInfo.entrySet()) {
                if (entry.getValue().modelName.equals(modelName)) {
                    effectsToRemove.add(entry.getKey());
                }
            }
            
            for (String effectKey : effectsToRemove) {
                BukkitTask task = activeEffects.get(effectKey);
                if (task != null) {
                    task.cancel();
                    activeEffects.remove(effectKey);
                }
                activeEffectInfo.remove(effectKey);
            }
            
            if (modelFile.exists()) {
                modelFile.delete();
                plugin.getLogger().info("Deleted temporary model '" + modelName + "'");
            }
        }, deletionDelay);
    }
    
    public Map<String, ParticleModel> getLoadedModels() {
        return new HashMap<>(loadedModels);
    }
    
    public Map<String, EffectInfo> getActiveEffects() {
        return new HashMap<>(activeEffectInfo);
    }
    
    public boolean deleteModel(String modelName) {
        try {
            List<String> effectsToRemove = new ArrayList<>();
            for (Map.Entry<String, EffectInfo> entry : activeEffectInfo.entrySet()) {
                if (entry.getValue().modelName.equals(modelName)) {
                    effectsToRemove.add(entry.getKey());
                }
            }
            
            for (String effectKey : effectsToRemove) {
                BukkitTask task = activeEffects.get(effectKey);
                if (task != null) {
                    task.cancel();
                    activeEffects.remove(effectKey);
                }
                EffectInfo effectInfo = activeEffectInfo.remove(effectKey);
                if (effectInfo != null) {
                    effectIdMap.remove(effectInfo.id);
                }
            }
            
            loadedModels.remove(modelName);
            
            File modelsDir = new File(plugin.getDataFolder(), "models");
            File modelFileJson = new File(modelsDir, modelName + ".json");
            File modelFileGz = new File(modelsDir, modelName + ".json.gz");
            File modelFile = modelFileJson.exists() ? modelFileJson : modelFileGz;
            if (!modelFile.exists()) {
                // Try .json first by default
                modelFile = modelFileJson;
            }
            
            if (modelFile.exists()) {
                boolean deleted = modelFile.delete();
                // Attempt to delete the alternate extension too
                if (modelFile == modelFileJson && modelFileGz.exists()) modelFileGz.delete();
                if (modelFile == modelFileGz && modelFileJson.exists()) modelFileJson.delete();
                if (deleted) {
                    plugin.getLogger().info("Deleted model '" + modelName + "' (stopped " + effectsToRemove.size() + " active effects)");
                    
                    savePersistedModels();
                    
                    return true;
                } else {
                    plugin.getLogger().warning("DustLab: Failed to delete model file: " + modelFile.getPath());
                    return false;
                }
            } else {
                plugin.getLogger().warning("DustLab: Model file not found: " + modelFile.getPath());
                return false;
            }
            
        } catch (Exception e) {
            plugin.getLogger().severe("DustLab: Error deleting model '" + modelName + "': " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get particle optimization statistics
     * @return String with optimization stats
     */
    public String getOptimizationStats() {
        int trackedEffects = particleOptimizer.getActiveEffectCount();
        int trackedParticles = particleOptimizer.getTotalParticleCount();
        int activeEffects = activeEffectInfo.size();
        
        return String.format("Particle Optimization Stats:\n" +
                "- Active Effects: %d\n" +
                "- Tracked Effects for Optimization: %d\n" +
                "- Tracked Particles: %d\n" +
                "- Memory Usage: Effects tracking %d particles", 
                activeEffects, trackedEffects, trackedParticles, trackedParticles);
    }


    public Map<String, ParticleModel> getAllModels() {
        return getLoadedModels();
    }
    
    public int getActiveEffectCount() {
        return activeEffectInfo.size();
    }
}
