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
import com.winss.dustlab.media.FrameData;
import com.winss.dustlab.packed.PackedParticleArray;
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
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.Semaphore;
import com.google.gson.stream.JsonReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

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
    private static final double MAX_RENDER_DISTANCE = Math.sqrt(MAX_RENDER_DISTANCE_SQUARED);
    
    private final DustLab plugin;
    private final DustLabConfig config;
    private final Gson gson;
    private final Map<String, ParticleModel> loadedModels;
    private final Map<String, BukkitTask> activeEffects;
    private final Map<String, EffectInfo> activeEffectInfo;
    private final Map<Integer, String> effectIdMap; 
    private final ParticleOptimizer particleOptimizer; 
    private int nextEffectId = 1;
    // Track and reserve used effect IDs to avoid reuse across restarts
    private final java.util.Set<Integer> usedEffectIds = java.util.Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    // IDs discovered in persistence and reserved for restore; allowed to be reused exactly
    private final java.util.Set<Integer> reservedForRestoreIds = java.util.Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    private long lastSaveLogTime = 0; 
    // Progressive loading state
    private final Map<String, LoadJob> loadingJobs = new ConcurrentHashMap<>();
    private final Semaphore loadConcurrency;
    private BukkitTask autoSaveTask;
    private BukkitTask optimizerCleanupTask;
    private volatile boolean shuttingDown = false;
    // Track async parse futures for graceful shutdown
    private final java.util.List<java.util.concurrent.Future<?>> parseFutures = new java.util.concurrent.CopyOnWriteArrayList<>();
    // Load tracking for concise summary and deferred persistence
    private final java.util.concurrent.atomic.AtomicInteger scheduledLoadCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger completedLoadCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger loadWarningCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private BukkitTask loadWatcherTask;
    private java.util.List<java.util.Map<String, Object>> pendingPersistentInstances;
    // Track async save futures (e.g., animated model writes) to avoid leaving temp files on shutdown
    private final java.util.List<java.util.concurrent.Future<?>> saveFutures = new java.util.concurrent.CopyOnWriteArrayList<>();
    
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
    
    // Utility: SHA1 string for optional integrity checks
    private static String sha1String(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] bytes = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // Canonical JSON string builder for deterministic checksum (sorted keys, normalized numbers)
    private static String canonicalJsonString(java.util.Map<String, Object> map) {
        try {
            com.google.gson.Gson g = new com.google.gson.GsonBuilder()
                    .serializeNulls()
                    .disableHtmlEscaping()
                    .create();
            com.google.gson.JsonElement el = g.toJsonTree(map);
            com.google.gson.JsonElement can = canonicalizeJson(el);
            return g.toJson(can);
        } catch (Exception ex) {
            return new com.google.gson.Gson().toJson(map);
        }
    }

    private static com.google.gson.JsonElement canonicalizeJson(com.google.gson.JsonElement el) {
        if (el == null || el.isJsonNull()) return com.google.gson.JsonNull.INSTANCE;
        if (el.isJsonObject()) {
            com.google.gson.JsonObject obj = el.getAsJsonObject();
            java.util.List<String> keys = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : obj.entrySet()) keys.add(e.getKey());
            java.util.Collections.sort(keys);
            com.google.gson.JsonObject out = new com.google.gson.JsonObject();
            for (String k : keys) out.add(k, canonicalizeJson(obj.get(k)));
            return out;
        } else if (el.isJsonArray()) {
            com.google.gson.JsonArray arr = el.getAsJsonArray();
            com.google.gson.JsonArray out = new com.google.gson.JsonArray();
            for (com.google.gson.JsonElement e : arr) out.add(canonicalizeJson(e));
            return out;
        } else if (el.isJsonPrimitive()) {
            com.google.gson.JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isNumber()) {
                try {
                    java.math.BigDecimal bd = new java.math.BigDecimal(p.getAsString());
                    bd = bd.stripTrailingZeros();
                    return new com.google.gson.JsonPrimitive(bd);
                } catch (Exception ignore) {}
            }
            return p;
        }
        return el;
    }

    // Utility: get .json filename from .json.gz
    private static String stripGzExtension(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".json.gz")) return name.substring(0, name.length() - 3); // remove .gz
        return name;
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
    this.loadConcurrency = new Semaphore(Math.max(1, (config != null ? config.getProgressiveMaxConcurrent() : 2)));
        
        File modelsDir = new File(plugin.getDataFolder(), "models");
        if (!modelsDir.exists()) {
            modelsDir.mkdirs();
            plugin.getLogger().info("Created models directory at: " + modelsDir.getPath());
        }
        
    loadPersistedModels();
        
        autoSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            savePersistedModels(true); 
        }, 36000L, 36000L); 
        
        optimizerCleanupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long currentTick = plugin.getServer().getCurrentTick();
            for (String effectKey : activeEffectInfo.keySet()) {
                particleOptimizer.cleanupEffect(effectKey, currentTick, 12000L);
            }
        }, 6000L, 6000L); 

    // Attempt to recover any leftover temporary model files from previous crashes
    try { recoverOrQuarantineTempFiles(); } catch (Exception ignored) {}
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
        
        // reset counters
        scheduledLoadCount.set(0);
        completedLoadCount.set(0);
        loadWarningCount.set(0);
        int scheduled = 0;
        for (File jsonFile : jsonFiles) {
            try {
                startLoadJob(jsonFile, null);
                scheduled++;
            } catch (Exception e) {
                loadWarningCount.incrementAndGet();
                plugin.getLogger().warning("Failed to schedule load for " + jsonFile.getName() + ": " + e.getMessage());
            }
        }
        scheduledLoadCount.set(scheduled);
        plugin.getLogger().info("Scheduled loading for " + scheduled + " particle models (async).");
        startLoadWatcher();
        // persist later after jobs complete periodically
    }
    
    @SuppressWarnings("unused")
    private void loadModel(File file) throws IOException {
        boolean gz = file.getName().toLowerCase().endsWith(".json.gz");
        java.io.Reader reader = null;
        try {
                if (gz) {
                    java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(new java.io.FileInputStream(file));
                    reader = new InputStreamReader(gis, StandardCharsets.UTF_8);
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

    // Progressive/async loading
    private static class LoadJob {
        final File file;
        final boolean gz;
        final String fileBaseName;
        volatile boolean canceled = false;
        volatile int totalParticles = -1;
        volatile int parsedParticles = 0;
        volatile long bytesRead = 0L;
        volatile long fileSize = 0L;
    volatile String modelName;
        final Set<CommandSender> subscribers = new HashSet<>();
        final java.util.List<Runnable> readyCallbacks = new java.util.ArrayList<>();
        // Progress logging fields removed for clean console

        LoadJob(File file) {
            this.file = file;
            this.gz = file.getName().toLowerCase().endsWith(".json.gz");
            String fname = file.getName();
            this.fileBaseName = fname.endsWith(".json.gz") ? fname.substring(0, fname.length() - 8) : fname.substring(0, fname.lastIndexOf('.'));
            this.fileSize = file.length();
        }
    }

    public boolean isModelLoading(String name) {
        return loadingJobs.containsKey(name.toLowerCase());
    }

    // Allocate a fresh unique effect ID (max(existing)+1), skipping any used IDs
    private synchronized int allocateEffectId() {
        // Advance nextEffectId to at least max(used)+1
        if (!usedEffectIds.isEmpty() || !reservedForRestoreIds.isEmpty()) {
            int max = nextEffectId;
            for (int id : usedEffectIds) if (id > max) max = id;
            for (int id : reservedForRestoreIds) if (id > max) max = id;
            if (max >= nextEffectId) nextEffectId = max + 1;
        }
        int id = nextEffectId++;
        while (usedEffectIds.contains(id) || reservedForRestoreIds.contains(id)) {
            id = nextEffectId++;
        }
        usedEffectIds.add(id);
        return id;
    }

    // Reserve a specific effect ID if free, otherwise return a new allocated one. Only warn on real conflicts.
    private synchronized int reserveSpecificEffectId(int desiredId) {
        if (desiredId > 0) {
            if (usedEffectIds.contains(desiredId)) {
                int newId = allocateEffectId();
                plugin.getLogger().warning("Persistent effect ID conflict for id=" + desiredId + ", assigned new id=" + newId);
                return newId;
            } else if (reservedForRestoreIds.contains(desiredId)) {
                // Promote reserved restore ID to used
                reservedForRestoreIds.remove(desiredId);
                usedEffectIds.add(desiredId);
                if (desiredId >= nextEffectId) nextEffectId = desiredId + 1;
                return desiredId;
            } else {
                usedEffectIds.add(desiredId);
                if (desiredId >= nextEffectId) nextEffectId = desiredId + 1;
                return desiredId;
            }
        }
        // No valid desired ID provided; allocate a fresh one silently
        return allocateEffectId();
    }

    public int getModelLoadingPercent(String name) {
        LoadJob job = loadingJobs.get(name.toLowerCase());
        if (job == null) return 100;
        if (job.totalParticles > 0) {
            return Math.min(99, (int) Math.floor((job.parsedParticles * 100.0) / job.totalParticles));
        }
        if (job.fileSize > 0) {
            return Math.min(99, (int) Math.floor((job.bytesRead * 100.0) / job.fileSize));
        }
        return 0;
    }

    public void subscribeToLoading(String name, CommandSender sender) {
        LoadJob job = loadingJobs.get(name.toLowerCase());
        if (job != null) {
            synchronized (job.subscribers) {
                job.subscribers.add(sender);
            }
        }
    }

    public void onModelReady(String name, Runnable callback) {
        LoadJob job = loadingJobs.get(name.toLowerCase());
        if (job == null) {
            // Already ready
            Bukkit.getScheduler().runTask(plugin, callback);
            return;
        }
        synchronized (job.readyCallbacks) {
            job.readyCallbacks.add(callback);
        }
    }

    private void startLoadJob(File file, CommandSender subscriber) {
        if (shuttingDown || !plugin.isEnabled()) {
            return;
        }
        // Decide progressive vs regular after peeking size and config
        LoadJob job = new LoadJob(file);
        if (subscriber != null) job.subscribers.add(subscriber);

        // Create placeholder model early for static models; animated handled at end
        loadingJobs.put(job.fileBaseName.toLowerCase(), job);

        java.util.concurrent.Future<?> f = com.winss.dustlab.media.MediaProcessor.submitAsyncFuture(() -> {
            try {
                loadConcurrency.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                loadingJobs.remove(job.fileBaseName.toLowerCase());
                return;
            }
            try {
                parseModelStreaming(job);
            } finally {
                loadConcurrency.release();
            }
        });
        parseFutures.add(f);
    }

    private void parseModelStreaming(LoadJob job) {
        try (java.io.InputStream fis = new java.io.FileInputStream(job.file);
             java.io.InputStream is = job.gz ? new java.util.zip.GZIPInputStream(fis) : fis;
             InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
             CountingReader countingReader = new CountingReader(isr, (c) -> job.bytesRead = c);
             JsonReader reader = new JsonReader(countingReader)) {

            reader.beginObject();
            String detectedName = null;
            Map<String, Object> metadata = null;
            String metadataChecksumStored = null;
            Integer duration = null;
            Integer particleCountFromMeta = null;
            // Potential animated fields
            Boolean looping = null; String sourceUrl = ""; Integer blockWidth = 1; Integer blockHeight = 1; Integer maxParticleCount = 10000;
            java.util.List<com.winss.dustlab.media.FrameData> animatedFrames = null;

            while (reader.hasNext()) {
                if (job.canceled || shuttingDown) {
                    return; // exit early during cancel/shutdown
                }
                String key = reader.nextName();
                if (key.equals("name")) {
                    detectedName = safeReadString(reader);
                } else if (key.equals("frames")) {
                    // Animated - parse frames array now; we will construct after finishing object
                    animatedFrames = parseFramesArray(reader);
                } else if (key.equals("metadataChecksumSha1")) {
                    try { metadataChecksumStored = reader.nextString(); } catch (Exception e) { reader.skipValue(); }
                } else if (key.equals("metadata")) {
                    // Best-effort: read into map for particleCount
                    try {
                        java.lang.reflect.Type mapType = new com.google.gson.reflect.TypeToken<java.util.Map<String, Object>>() {}.getType();
                        metadata = gson.fromJson(reader, mapType);
                        if (metadata != null && metadata.containsKey("particleCount")) {
                            Object pc = metadata.get("particleCount");
                            if (pc instanceof Number) particleCountFromMeta = ((Number) pc).intValue();
                        }
                        if (metadata != null && metadataChecksumStored != null) {
                            try {
                                // Compute once to validate format; ignore result intentionally
                                sha1String(canonicalJsonString(metadata));
                                // Do not warn on mismatch; treat as advisory only to avoid false positives
                            } catch (Exception ignore) {}
                        }
                    } catch (Exception ex) {
                        reader.skipValue();
                    }
                } else if (key.equals("duration")) {
                    try { duration = reader.nextInt(); } catch (Exception e) { reader.skipValue(); }
                } else if (key.equals("looping")) {
                    try { looping = reader.nextBoolean(); } catch (Exception e) { reader.skipValue(); }
                } else if (key.equals("sourceUrl")) {
                    String v = safeReadString(reader); if (v != null) sourceUrl = v;
                } else if (key.equals("blockWidth")) {
                    try { blockWidth = reader.nextInt(); } catch (Exception e) { reader.skipValue(); }
                } else if (key.equals("blockHeight")) {
                    try { blockHeight = reader.nextInt(); } catch (Exception e) { reader.skipValue(); }
                } else if (key.equals("maxParticleCount")) {
                    try { maxParticleCount = reader.nextInt(); } catch (Exception e) { reader.skipValue(); }
                } else if (key.equals("particles")) {
                    String modelName = (detectedName != null ? detectedName : job.fileBaseName);
                    job.modelName = modelName;
                    job.totalParticles = (particleCountFromMeta != null ? particleCountFromMeta : -1);
                    final ParticleModel placeholder = new ParticleModel();
                    placeholder.setName(modelName);
                    if (metadata != null) {
                        placeholder.setMetadata(metadata);
                    }
                    if (duration != null) {
                        placeholder.setDuration(duration);
                    }
                    if (shuttingDown) {
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> loadedModels.put(modelName.toLowerCase(), placeholder));

                    int expected = job.totalParticles > 0 ? job.totalParticles : 1024;
                    PackedParticleArray.Builder builder = PackedParticleArray.builder(expected);

                    reader.beginArray();
                    while (reader.hasNext()) {
                        if (job.canceled || shuttingDown) {
                            reader.skipValue();
                            continue;
                        }
                        ParticleData pd = parseParticle(reader);
                        builder.add(pd);
                        job.parsedParticles++;
                    }
                    reader.endArray();
                    PackedParticleArray packed = builder.build();
                    job.parsedParticles = packed.size();

                    if (shuttingDown) {
                        return;
                    }

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        placeholder.setPackedParticles(packed);
                        loadingJobs.remove(job.fileBaseName.toLowerCase());
                        completedLoadCount.incrementAndGet();

                        java.util.List<Runnable> toRun;
                        synchronized (job.readyCallbacks) {
                            toRun = new java.util.ArrayList<>(job.readyCallbacks);
                            job.readyCallbacks.clear();
                        }
                        for (Runnable r : toRun) {
                            try {
                                Bukkit.getScheduler().runTask(plugin, r);
                            } catch (Exception ignored) {
                            }
                        }

                        synchronized (job.subscribers) {
                            for (CommandSender s : job.subscribers) {
                                try {
                                    s.sendMessage("§9DustLab §a» §7Model '§f" + modelName + "§7' is ready (" + packed.size() + " particles)");
                                } catch (Exception ignored) {
                                }
                            }
                            job.subscribers.clear();
                        }
                    });
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            // If animated frames were parsed, register animated model now
            if (animatedFrames != null) {
                String modelName = (detectedName != null ? detectedName : job.fileBaseName);
                AnimatedModel animated = new AnimatedModel(modelName, animatedFrames, looping != null ? looping : false,
                        sourceUrl, blockWidth != null ? blockWidth : 1, blockHeight != null ? blockHeight : 1,
                        maxParticleCount != null ? maxParticleCount : 10000);
                if (duration != null && duration > 0) animated.setDuration(duration);
                if (metadata != null) animated.setMetadata(metadata);
                if (shuttingDown) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    loadedModels.put(animated.getName().toLowerCase(), animated);
                    // Remove using the original key used when scheduling (fileBaseName)
                    loadingJobs.remove(job.fileBaseName.toLowerCase());
                    completedLoadCount.incrementAndGet();
                    // fire callbacks registered for this job
                    java.util.List<Runnable> toRun;
                    synchronized (job.readyCallbacks) { toRun = new java.util.ArrayList<>(job.readyCallbacks); job.readyCallbacks.clear(); }
                    for (Runnable r : toRun) {
                        try { Bukkit.getScheduler().runTask(plugin, r); } catch (Exception ignored) {}
                    }
                    // notify subscribers for this job
                    synchronized (job.subscribers) {
                        for (CommandSender s : job.subscribers) {
                            try { s.sendMessage("§9DustLab §a» §7Model '§f" + animated.getName() + "§7' is ready (animated)"); } catch (Exception ignored) {}
                        }
                        job.subscribers.clear();
                    }
                });
            }
        } catch (Exception ex) {
            String name = (job.modelName != null ? job.modelName : job.fileBaseName);
            String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            boolean zlibEof = msg.contains("Unexpected end of ZLIB input stream");
            if (job.canceled || shuttingDown) {
                plugin.getLogger().info("Streaming canceled for '" + name + "'" + (shuttingDown ? " during shutdown." : "."));
            } else {
                if (job.gz) {
                    loadWarningCount.incrementAndGet();
                    plugin.getLogger().warning("Failed to load compressed model '" + name + "' (" + msg + ") — attempting .json fallback.");
                    boolean fallbackStarted = attemptJsonFallback(job);
                    if (fallbackStarted) {
                        return; // fallback will handle removal/logs
                    }
                    // No .json fallback exists; quarantine the bad gzip file to avoid repeat errors
                    try {
                        File bad = job.file;
                        if (bad.exists()) {
                            File quarantineDir = new File(plugin.getDataFolder(), "quarantine");
                            if (!quarantineDir.exists()) quarantineDir.mkdirs();
                            String ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                            File target = new File(quarantineDir, bad.getName().replaceFirst("\\.json\\.gz$", "") + "." + ts + ".json.gz");
                            java.nio.file.Files.move(bad.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            plugin.getLogger().warning("Quarantined corrupted compressed model to '" + target.getName() + "'.");
                        }
                    } catch (Exception qex) {
                        plugin.getLogger().warning("Failed to quarantine corrupted model '" + name + "': " + qex.getMessage());
                    }
                }
                if (zlibEof) {
                    loadWarningCount.incrementAndGet();
                    plugin.getLogger().warning("Model '" + name + "' appears to be a truncated/corrupted .json.gz (" + msg + ").");
                } else {
                    loadWarningCount.incrementAndGet();
                    plugin.getLogger().warning("Failed to load model (streaming) from " + job.file.getName() + ": " + msg);
                }
            }
            loadingJobs.remove(name.toLowerCase());
        }
    }

    // Watcher to emit one concise summary and trigger persistence restore after all loads complete
    private void startLoadWatcher() {
        if (loadWatcherTask != null) {
            try { loadWatcherTask.cancel(); } catch (Exception ignored) {}
        }
        loadWatcherTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (shuttingDown) { try { loadWatcherTask.cancel(); } catch (Exception ignored) {} return; }
            int scheduled = scheduledLoadCount.get();
            int completed = completedLoadCount.get();
            boolean anyLoading = !loadingJobs.isEmpty();
            // When there were scheduled loads and all have completed and no active jobs remain
            if (scheduled >= 0 && completed >= scheduled && !anyLoading) {
                try { loadWatcherTask.cancel(); } catch (Exception ignored) {}
                int issues = loadWarningCount.get();
                if (scheduled > 0) {
                    if (issues > 0) {
                        plugin.getLogger().info("Loaded " + completed + " particle models (" + issues + " warnings).");
                    } else {
                        plugin.getLogger().info("Loaded " + completed + " particle models.");
                    }
                }
                // Restore persisted instances now that models are available
                if (pendingPersistentInstances != null && !pendingPersistentInstances.isEmpty()) {
                    restorePersistentInstances(pendingPersistentInstances);
                    pendingPersistentInstances = null;
                }
            }
        }, 20L, 20L);
    }

    // Try to load the .json file when .json.gz fails
    private boolean attemptJsonFallback(LoadJob gzJob) {
        try {
            File json = new File(gzJob.file.getParentFile(), gzJob.fileBaseName + ".json");
            if (!json.exists()) return false;
            LoadJob jsonJob = new LoadJob(json);
            jsonJob.subscribers.addAll(gzJob.subscribers);
            // Keep same loadingJobs key already present
            parseModelStreaming(jsonJob);
            return true;
        } catch (Exception e) {
            // parseModelStreaming already logs and manages loadingJobs on failure
            return false;
        }
    }

    private void cancelAllLoadJobs() {
        for (Map.Entry<String, LoadJob> e : loadingJobs.entrySet()) {
            LoadJob job = e.getValue();
            job.canceled = true;
        }
        // Attempt to cancel futures and wait briefly
        for (java.util.concurrent.Future<?> f : parseFutures) {
            try { f.cancel(true); } catch (Exception ignored) {}
        }
        parseFutures.clear();
        loadingJobs.clear();
    }

    // Progress logging suppressed for clean console

    private static class CountingReader extends java.io.Reader {
        private final java.io.Reader delegate;
        private long count = 0;
        private final java.util.function.LongConsumer onUpdate;
        CountingReader(java.io.Reader delegate, java.util.function.LongConsumer onUpdate) { this.delegate = delegate; this.onUpdate = onUpdate; }
        @Override public int read(char[] cbuf, int off, int len) throws IOException { int n = delegate.read(cbuf, off, len); if (n > 0) { count += n; onUpdate.accept(count);} return n; }
        @Override public void close() throws IOException { delegate.close(); }
    }

    private String safeReadString(JsonReader reader) throws IOException {
        try { return reader.nextString(); } catch (Exception e) { reader.skipValue(); return null; }
    }

    private ParticleData parseParticle(JsonReader r) throws IOException {
        double x=0,y=0,z=0; int delay=0; double rC=1,gC=0,bC=0; float scale=1.0f;
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            switch (k) {
                case "x": x = safeReadDouble(r); break;
                case "y": y = safeReadDouble(r); break;
                case "z": z = safeReadDouble(r); break;
                case "delay": delay = safeReadInt(r); break;
                case "scale": scale = (float) safeReadDouble(r); break;
                case "r": case "red": rC = normalizeColor(safeReadDouble(r)); break;
                case "g": case "green": gC = normalizeColor(safeReadDouble(r)); break;
                case "b": case "blue": bC = normalizeColor(safeReadDouble(r)); break;
                case "dustOptions":
                    r.beginObject();
                    while (r.hasNext()) {
                        String dk = r.nextName();
                        if (dk.equals("red")) rC = normalizeColor(safeReadDouble(r));
                        else if (dk.equals("green")) gC = normalizeColor(safeReadDouble(r));
                        else if (dk.equals("blue")) bC = normalizeColor(safeReadDouble(r));
                        else if (dk.equals("size")) scale = (float) safeReadDouble(r);
                        else r.skipValue();
                    }
                    r.endObject();
                    break;
                default:
                    r.skipValue();
            }
        }
        r.endObject();
        ParticleData pd = new ParticleData(x,y,z,rC,gC,bC);
        pd.setDelay(delay);
        pd.setScale(scale);
        return pd;
    }

    private double normalizeColor(double v) { return v > 1.0 ? Math.max(0.0, Math.min(1.0, v/255.0)) : Math.max(0.0, Math.min(1.0, v)); }
    private double safeReadDouble(JsonReader r) throws IOException { try { return r.nextDouble(); } catch (Exception e) { r.skipValue(); return 0.0; } }
    private int safeReadInt(JsonReader r) throws IOException { try { return r.nextInt(); } catch (Exception e) { r.skipValue(); return 0; } }

    private java.util.List<com.winss.dustlab.media.FrameData> parseFramesArray(JsonReader reader) throws IOException {
        java.util.List<com.winss.dustlab.media.FrameData> frames = new java.util.ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            reader.beginObject();
            int frameIndex = frames.size();
            int delayMs = 50;
            PackedParticleArray.Builder frameBuilder = PackedParticleArray.builder();
            while (reader.hasNext()) {
                String k = reader.nextName();
                if (k.equals("frameIndex")) { try { frameIndex = reader.nextInt(); } catch (Exception e) { reader.skipValue(); } }
                else if (k.equals("delayMs")) { try { delayMs = reader.nextInt(); } catch (Exception e) { reader.skipValue(); } }
                else if (k.equals("particles")) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        ParticleData parsed = parseParticle(reader);
                        frameBuilder.add(parsed);
                    }
                    reader.endArray();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            PackedParticleArray packed = frameBuilder.build();
            frames.add(new com.winss.dustlab.media.FrameData(packed, frameIndex, delayMs));
        }
        reader.endArray();
        return frames;
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
                    PackedParticleArray.Builder frameBuilder = PackedParticleArray.builder();
                    com.google.gson.JsonElement pel = fo.get("particles");
                    if (pel != null && pel.isJsonArray()) {
                        for (com.google.gson.JsonElement pe : pel.getAsJsonArray()) {
                            if (!pe.isJsonObject()) continue;
                            com.google.gson.JsonObject po = pe.getAsJsonObject();
                            com.winss.dustlab.models.ParticleData pd = parseParticle(po, globalSize);
                            if (pd != null) frameBuilder.add(pd);
                        }
                    }
                    frames.add(new com.winss.dustlab.media.FrameData(frameBuilder.build(), frameIndex, delayMs));
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
                PackedParticleArray.Builder baseBuilder = PackedParticleArray.builder();
                for (com.google.gson.JsonElement pe : root.get("particles").getAsJsonArray()) {
                    if (!pe.isJsonObject()) continue;
                    com.winss.dustlab.models.ParticleData pd = parseParticle(pe.getAsJsonObject(), globalSize);
                    if (pd != null) baseBuilder.add(pd);
                }
                PackedParticleArray packedBase = baseBuilder.build();
                model.setPackedParticles(packedBase);
            } else if (!frames.isEmpty()) {
                PackedParticleArray packedFallback = frames.get(0).getPackedParticles();
                if (packedFallback != null) {
                    model.setPackedParticles(packedFallback);
                } else {
                    model.setParticles(frames.get(0).getParticles());
                }
            }
            return model;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse animated model: " + e.getMessage());
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
            plugin.getLogger().severe("Failed to create example model: " + e.getMessage());
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
            plugin.getLogger().warning("Model not found: " + modelName);
            return -1;
        }
        
        if (player == null || !player.isOnline()) {
            plugin.getLogger().warning("Invalid or offline player for model: " + modelName);
            return -1;
        }
        
        if (model.getParticles() == null || model.getParticles().isEmpty()) {
            plugin.getLogger().warning("Model has no particles: " + modelName);
            return -1;
        }
        
    int effectId = allocateEffectId();
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
                PackedParticleArray currentPacked = null;
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
                        currentPacked = currentFrame.getPackedParticles();
                    } else {
                        currentParticles = Collections.emptyList();
                        emitThisTick = false;
                    }
                } else {
                    currentParticles = model.getParticles() != null ? model.getParticles() : Collections.emptyList();
                    if (model.hasPackedParticles()) {
                        currentPacked = model.getPackedParticles();
                    }
                }
                
                if (shouldShow && emitThisTick && !currentParticles.isEmpty()) {
                    boolean shouldSpawnThisTick = true;
                    if (isMoving) {
                        shouldSpawnThisTick = (tick % 2 == 0);
                    }
                    
                    if (shouldSpawnThisTick) {
                        processParticlesForPlayer(currentParticles, currentPacked, player, currentLocation, effects, tick, lifetimeSeconds, maxTicks, forceVisible, effectKey, isAnimatedModel);
                    }
                }
                
                tick++;
            }
        }, 0L, 1L); 
        
        activeEffects.put(effectKey, task);
        return effectId;
    }

    // Internal helper to spawn a player-attached model with a fixed effect ID when restoring
    @SuppressWarnings("unused")
    private int playModelOnPlayerWithId(String modelName, Player player, int lifetimeSeconds, ParticleEffects.EffectSettings effects, boolean onlyWhenStill, boolean forceVisible, int desiredId) {
        ParticleModel model = getModel(modelName);
        if (model == null) {
            plugin.getLogger().warning("Model not found: " + modelName);
            return -1;
        }
        if (player == null || !player.isOnline()) {
            plugin.getLogger().warning("Invalid or offline player for model: " + modelName);
            return -1;
        }
        if (model.getParticles() == null || model.getParticles().isEmpty()) {
            plugin.getLogger().warning("Model has no particles: " + modelName);
            return -1;
        }

        int effectId = reserveSpecificEffectId(desiredId);
        if (effectId != desiredId) {
            plugin.getLogger().warning("Effect ID conflict for restore: requested " + desiredId + ", assigned " + effectId + ".");
        }
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
                PackedParticleArray currentPacked = null;
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
                        currentPacked = currentFrame.getPackedParticles();
                    } else {
                        currentParticles = Collections.emptyList();
                        emitThisTick = false;
                    }
                } else {
                    currentParticles = model.getParticles() != null ? model.getParticles() : Collections.emptyList();
                    if (model.hasPackedParticles()) {
                        currentPacked = model.getPackedParticles();
                    }
                }

                if (shouldShow && emitThisTick && !currentParticles.isEmpty()) {
                    boolean shouldSpawnThisTick = true;
                    if (isMoving) {
                        shouldSpawnThisTick = (tick % 2 == 0);
                    }

                    if (shouldSpawnThisTick) {
                        processParticlesForPlayer(currentParticles, currentPacked, player, currentLocation, effects, tick, lifetimeSeconds, maxTicks, forceVisible, effectKey, isAnimatedModel);
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
            plugin.getLogger().warning("Model not found: " + modelName);
            return -1;
        }
        
        if (location == null || location.getWorld() == null) {
            plugin.getLogger().warning("Invalid location for model: " + modelName);
            return -1;
        }
        
        if (model.getParticles() == null || model.getParticles().isEmpty()) {
            plugin.getLogger().warning("Model has no particles: " + modelName);
            return -1;
        }
        
        
    int effectId = allocateEffectId();
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
                PackedParticleArray currentPacked = null;
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
                        currentPacked = currentFrame.getPackedParticles();
                        
                    } else {
                        currentParticles = Collections.emptyList();
                    }
                } else {
                    currentParticles = model.getParticles() != null ? model.getParticles() : Collections.emptyList();
                    if (model.hasPackedParticles()) {
                        currentPacked = model.getPackedParticles();
                    }
                }
                
                if (!currentParticles.isEmpty()) {
                    processParticlesSimple(currentParticles, currentPacked, null, location, effects, tick, lifetimeSeconds, maxTicks, effectKey, isAnimatedModel);
                }
                
                tick++;
            }
        }, 0L, tickRate);
        
        activeEffects.put(effectKey, task);
        return effectId;
    }

    // Internal helper to spawn on location with a fixed effect ID (no tick offset)
    private int playModelOnLocationWithEffectsWithId(String modelName, Location location, int lifetimeSeconds, boolean persistent, ParticleEffects.EffectSettings effects, int desiredId) {
        ParticleModel model = getModel(modelName);
        if (model == null) {
            plugin.getLogger().warning("Model not found: " + modelName);
            return -1;
        }

        if (location == null || location.getWorld() == null) {
            plugin.getLogger().warning("Invalid location for model: " + modelName);
            return -1;
        }

        if (model.getParticles() == null || model.getParticles().isEmpty()) {
            plugin.getLogger().warning("Model has no particles: " + modelName);
            return -1;
        }

        int effectId = reserveSpecificEffectId(desiredId);
        if (effectId != desiredId) {
            plugin.getLogger().warning("Effect ID conflict for restore: requested " + desiredId + ", assigned " + effectId + ".");
        }
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

    // Internal helper to spawn on location reusing an existing effect ID (no reservation, used for moveEffect)
    private int playModelOnLocationWithEffectsWithExistingId(String modelName, Location location, int lifetimeSeconds, boolean persistent, ParticleEffects.EffectSettings effects, int existingId) {
        ParticleModel model = getModel(modelName);
        if (model == null) {
            plugin.getLogger().warning("Model not found: " + modelName);
            return -1;
        }

        if (location == null || location.getWorld() == null) {
            plugin.getLogger().warning("Invalid location for model: " + modelName);
            return -1;
        }

        if (model.getParticles() == null || model.getParticles().isEmpty()) {
            plugin.getLogger().warning("Model has no particles: " + modelName);
            return -1;
        }

        final int effectId = existingId;
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
        
    int effectId = allocateEffectId();
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
                PackedParticleArray currentPacked = null;
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
                        currentPacked = currentFrame.getPackedParticles();
                        
                    } else {
                        currentParticles = Collections.emptyList();
                    }
                } else {
                    currentParticles = model.getParticles() != null ? model.getParticles() : Collections.emptyList();
                    if (model.hasPackedParticles()) {
                        currentPacked = model.getPackedParticles();
                    }
                }
                
                if (!currentParticles.isEmpty()) {
                    processParticlesSimple(currentParticles, currentPacked, null, location, effects, tick, lifetimeSeconds, maxTicks, effectKey, isAnimatedModel);
                }
                
                tick++;
            }
        }, 0L, tickRate);
        
        activeEffects.put(effectKey, task);
        return effectId;
    }

    // Internal helper to spawn on location with a fixed effect ID and tick offset (animated restore)
    private int playModelOnLocationWithEffectsAndTickOffsetWithId(String modelName, Location location, int lifetimeSeconds, boolean persistent, ParticleEffects.EffectSettings effects, long initialTickOffset, int desiredId) {
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

        int effectId = reserveSpecificEffectId(desiredId);
        if (effectId != desiredId) {
            plugin.getLogger().warning("Effect ID conflict for restore: requested " + desiredId + ", assigned " + effectId + ".");
        }
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
        processParticlesSimple(particles, null, previousParticles, baseLocation, effects, tick, lifetimeSeconds, maxTicks, effectId, isAnimated);
    }

    private void processParticlesSimple(List<ParticleData> particles, PackedParticleArray packedParticles,
                                       List<ParticleData> previousParticles, Location baseLocation,
                                       ParticleEffects.EffectSettings effects, int tick,
                                       int lifetimeSeconds, int maxTicks, String effectId, boolean isAnimated) {
        Collection<Player> viewers = collectViewers(baseLocation, false);
        if (viewers.isEmpty()) {
            return;
        }

        int particleCount = packedParticles != null ? packedParticles.size() : particles.size();
        if (particleCount == 0) {
            return;
        }

        ParticleData reusableParticle = packedParticles != null ? new ParticleData() : null;
        
        int veryLargeThreshold = getVeryLargeModelThreshold();
        int largeThreshold = getLargeModelThreshold();
        
        // For animated media frames we must render the exact snapshot per frame without persistence tricks
        if (!isAnimated) {
            if (particleCount > veryLargeThreshold) {
                processVeryLargeModel(particles, packedParticles, baseLocation, viewers, effects, tick, lifetimeSeconds, maxTicks);
                return;
            }

            if (particleCount > largeThreshold) {
                processLargeModelWithPersistence(particles, packedParticles, baseLocation, viewers, effects, tick, lifetimeSeconds, maxTicks);
                return;
            }
        }

        // Animated: draw the complete frame snapshot this tick (no sampling)
        if (isAnimated) {
            for (int i = 0; i < particleCount; i++) {
                ParticleData particle;
                if (packedParticles != null) {
                    packedParticles.copyInto(i, reusableParticle);
                    particle = reusableParticle;
                } else {
                    particle = particles.get(i);
                }
                if (particle == null) continue;
                ParticleData prev = (previousParticles != null && i < previousParticles.size()) ? previousParticles.get(i) : null;
                spawnParticleWithEffects(particle, prev, baseLocation, viewers, effects, tick, true);
            }
            return;
        }

        // Static/non-animated: original scheduling with per-particle delays
        for (int i = 0; i < particleCount; i++) {
            ParticleData particle;
            if (packedParticles != null) {
                packedParticles.copyInto(i, reusableParticle);
                particle = reusableParticle;
            } else {
                particle = particles.get(i);
            }
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
                spawnParticleWithEffects(particle, baseLocation, viewers, effects, tick);
            }
        }
    }
    
    private void processLargeModelWithPersistence(List<ParticleData> particles, PackedParticleArray packedParticles,
                                                Location baseLocation, Collection<Player> viewers, ParticleEffects.EffectSettings effects, int tick,
                                                int lifetimeSeconds, int maxTicks) {
        
        int particleCount = packedParticles != null ? packedParticles.size() : particles.size();
        ParticleData reusable = packedParticles != null ? new ParticleData() : null;
        
        int fadeInDuration = 60;
        int particlesPerTick = Math.max(1, particleCount / fadeInDuration);
        int maxVisibleParticles = Math.min(particleCount, (tick + 1) * particlesPerTick);
        
        int baseOutlineInterval = Math.max(1, particleCount / 100); 
        
        for (int i = 0; i < particleCount; i++) {
            ParticleData particle;
            if (packedParticles != null) {
                packedParticles.copyInto(i, reusable);
                particle = reusable;
            } else {
                particle = particles.get(i);
            }
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
                spawnParticleWithEffects(particle, baseLocation, viewers, effects, tick);
            }
        }
    }
    
    /**
     * Process very large models (10k+) with smooth section transitions to prevent epilepsy triggers
     */
    private void processVeryLargeModel(List<ParticleData> particles, PackedParticleArray packedParticles,
                                     Location baseLocation, Collection<Player> viewers, ParticleEffects.EffectSettings effects, int tick,
                                     int lifetimeSeconds, int maxTicks) {
        
        int particleCount = packedParticles != null ? packedParticles.size() : particles.size();
        ParticleData reusable = packedParticles != null ? new ParticleData() : null;
        
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
            ParticleData particle;
            if (packedParticles != null) {
                packedParticles.copyInto(i, reusable);
                particle = reusable;
            } else {
                particle = particles.get(i);
            }
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
                spawnParticleWithEffects(particle, baseLocation, viewers, effects, tick);
            }
        }
    }

    private Collection<Player> collectViewers(Location origin, boolean forceVisible) {
        return collectViewers(origin, MAX_RENDER_DISTANCE, forceVisible);
    }

    private Collection<Player> collectViewers(Location origin, double radius, boolean forceVisible) {
        if (origin == null) {
            return Collections.emptyList();
        }
        World world = origin.getWorld();
        if (world == null) {
            return Collections.emptyList();
        }
        Collection<Player> nearby = world.getNearbyPlayers(origin, radius);
        if (nearby.isEmpty()) {
            return Collections.emptyList();
        }
        List<Player> viewers = new ArrayList<>(nearby.size());
        for (Player viewer : nearby) {
            if (forceVisible || canSeeParticles(viewer)) {
                viewers.add(viewer);
            }
        }
        return viewers;
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
    
    private void spawnParticleWithEffects(ParticleData particle, Location baseLocation, Collection<Player> viewers,
                                         ParticleEffects.EffectSettings effects, long tick, boolean isAnimated) {
        if (viewers.isEmpty()) {
            return;
        }
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

            for (Player player : viewers) {
                spawnParticleForViewer(player, particleLocation, dustOptions, null, particleCount, offsetX, offsetY, offsetZ, extra, isAnimated);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error spawning particle: " + e.getMessage());
        }
    }

    // Overload: includes previous particle for animated transitions
    private void spawnParticleWithEffects(ParticleData particle, ParticleData previous, Location baseLocation,
                                         Collection<Player> viewers, ParticleEffects.EffectSettings effects, long tick, boolean isAnimated) {
        if (viewers.isEmpty()) {
            return;
        }
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

            for (Player player : viewers) {
                spawnParticleForViewer(player, particleLocation, dustOptions, prevDust, particleCount, offsetX, offsetY, offsetZ, extra, isAnimated);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error spawning particle: " + e.getMessage());
        }
    }

    private void spawnParticleWithEffects(ParticleData particle, Location baseLocation, Collection<Player> viewers,
                                         ParticleEffects.EffectSettings effects, long tick) {
        spawnParticleWithEffects(particle, baseLocation, viewers, effects, tick, false);
    }
    

    @SuppressWarnings("unused")
    private void processParticlesForPlayer(List<ParticleData> particles, Player player, Location playerLocation,
                                         ParticleEffects.EffectSettings effects, int tick,
                                         int lifetimeSeconds, int maxTicks, boolean forceVisible, String effectId, boolean isAnimated) {
        processParticlesForPlayer(particles, null, player, playerLocation, effects, tick, lifetimeSeconds, maxTicks, forceVisible, effectId, isAnimated);
    }

    private void processParticlesForPlayer(List<ParticleData> particles, PackedParticleArray packedParticles, Player player, Location playerLocation,
                                         ParticleEffects.EffectSettings effects, int tick,
                                         int lifetimeSeconds, int maxTicks, boolean forceVisible, String effectId, boolean isAnimated) {
        
        int particleCount = packedParticles != null ? packedParticles.size() : particles.size();
        if (particleCount == 0) {
            return;
        }

        ParticleData reusable = packedParticles != null ? new ParticleData() : null;
        
        Location currentPlayerLocation = player.getLocation();
        Collection<Player> viewers = collectViewers(currentPlayerLocation, MAX_RENDER_DISTANCE * 0.8, forceVisible);
        if (viewers.isEmpty()) {
            return;
        }
        
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
            ParticleData particle;
            if (packedParticles != null) {
                packedParticles.copyInto(i, reusable);
                particle = reusable;
            } else {
                particle = particles.get(i);
            }
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
                spawnParticleForPlayer(particle, currentPlayerLocation, player, viewers, effects, tick, isAnimated);
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
                                      Collection<Player> viewers, ParticleEffects.EffectSettings effects, long tick, boolean isAnimated) {
        try {
            World world = playerLocation.getWorld();
            if (world == null || !attachedPlayer.isOnline() || viewers.isEmpty()) {
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

            for (Player viewer : viewers) {
                spawnParticleForViewer(viewer, particleLocation, dustOptions, null, particleCount, offsetX, offsetY, offsetZ, extra, isAnimated);
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
        Collection<Player> viewers = collectViewers(baseLocation, false);
        if (viewers.isEmpty()) {
            return;
        }
        spawnParticleWithEffects(particle, baseLocation, viewers, null, 0L);
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
        
        // Remove old references
        activeEffectInfo.remove(effectKey);
        effectIdMap.remove(effectId);

        // Respawn using the same existing effect ID (no re-allocation)
        int respawnedId = playModelOnLocationWithEffectsWithExistingId(
            oldInfo.modelName,
            newLocation,
            oldInfo.lifetimeSeconds,
            oldInfo.isPersistent,
            oldInfo.effectSettings,
            effectId
        );

        return respawnedId == effectId;
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
        File tempFile = new File(plugin.getDataFolder(), "persistent_instances.json.tmp");
        try {
            // Build the in-memory structure first to minimize time holding IO resources
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

            // Write to a temp file first
            try (FileWriter writer = new FileWriter(tempFile)) {
                gson.toJson(persistentData, writer);
                writer.flush();
            }

            // Atomically move temp into place
            try {
                java.nio.file.Files.move(tempFile.toPath(), persistentFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception moveEx) {
                // Fallback non-atomic rename
                if (!tempFile.renameTo(persistentFile)) {
                    throw new IOException("Failed to move persistent_instances.json.tmp into place: " + moveEx.getMessage(), moveEx);
                }
            }

            long currentTime = System.currentTimeMillis();
            if (instances.size() > 0 && (forceLog || (currentTime - lastSaveLogTime) >= 1800000)) {
                plugin.getLogger().info("Saved " + instances.size() + " persistent model instances to storage.");
                lastSaveLogTime = currentTime;
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save persistent instances: " + e.getMessage());
            // Best-effort cleanup of temp file on failure
            try { java.nio.file.Files.deleteIfExists(tempFile.toPath()); } catch (Exception ignore) {}
        } finally {
            // Once saved, these IDs are now fully owned by usedEffectIds; no need to keep in reserved set
            reservedForRestoreIds.clear();
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
            plugin.getLogger().info("Found " + instances.size() + " persistent instance(s); queuing restore after models load.");
            
            // Pre-reserve all effect IDs found in persistence to avoid reassignment before restore
            int maxId = nextEffectId;
            for (Map<String, Object> inst : instances) {
                try {
                    if (inst.containsKey("metadata")) {
                        Map<String, Object> md = (Map<String, Object>) inst.get("metadata");
                        Object idObj = md.get("effect_id");
                        if (idObj instanceof Number) {
                            int id = ((Number) idObj).intValue();
                            if (id > 0) {
                                reservedForRestoreIds.add(id);
                                if (id > maxId) maxId = id;
                            }
                        }
                    }
                } catch (Exception ignore) {}
            }
            if (maxId >= nextEffectId) {
                nextEffectId = maxId + 1;
            }

            // Defer actual restoration until after initial model loading finishes
            this.pendingPersistentInstances = instances;
            
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to load persistent instances: " + e.getMessage());
        }
    }
    

    @SuppressWarnings("unchecked")
    private void restorePersistentInstances(List<Map<String, Object>> instances) {
        int restoredCount = 0;
        int restoredInfinite = 0;
        int restoredTimed = 0;
        
        for (Map<String, Object> instance : instances) {
            try {
                String modelId;
                String worldName;
                Double x, y, z;
                int lifetimeSeconds;
                int desiredEffectId = -1;
                boolean markedExpired = false;
                
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

                    // If metadata present, pick up effect_id and has_expired
                    if (instance.containsKey("metadata")) {
                        Map<String, Object> metadata = (Map<String, Object>) instance.get("metadata");
                        Object idObj = metadata.get("effect_id");
                        if (idObj instanceof Number) {
                            desiredEffectId = ((Number) idObj).intValue();
                        }
                        Object expiredObj = metadata.get("has_expired");
                        if (expiredObj instanceof Boolean) {
                            markedExpired = (Boolean) expiredObj;
                        }
                    }
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
                    plugin.getLogger().warning("Cannot restore persistent instance: model '" + modelId + "' not found in models folder");
                    continue;
                }
                
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    plugin.getLogger().warning("Cannot restore persistent instance: world '" + worldName + "' not found");
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
                long stopsAt = -1L;
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
                    // Determine remaining lifetime if applicable
                    Object stopsAtObj = lifespanInfo.get("stops_at");
                    if (stopsAtObj instanceof Number) {
                        stopsAt = ((Number) stopsAtObj).longValue();
                    }
                }

                // If metadata marks as expired, skip
                if (markedExpired) {
                    continue;
                }

                // Compute effective lifetime: infinite (-1), one-time (0), or remaining seconds for timed
                if (lifetimeSeconds > 0) {
                    long now = System.currentTimeMillis();
                    if (stopsAt > 0) {
                        long remaining = Math.max(0L, (stopsAt - now) / 1000L);
                        if (remaining <= 0) {
                            // Already expired, skip restore
                            continue;
                        }
                        lifetimeSeconds = (int) Math.min(Integer.MAX_VALUE, remaining);
                    }
                }
                
                // Restore the model with proper animation timing
                if (isAnimated && animationStartTime != -1) {
                    long currentTime = System.currentTimeMillis();
                    long elapsedMs = currentTime - animationStartTime;
                    long tickOffset = elapsedMs / 50; 
                    
                    if (effects != null) {
                        playModelOnLocationWithEffectsAndTickOffsetWithId(modelId, location, lifetimeSeconds, true, effects, tickOffset, desiredEffectId);
                    } else {
                        playModelOnLocationWithEffectsAndTickOffsetWithId(modelId, location, lifetimeSeconds, true, null, tickOffset, desiredEffectId);
                    }
                } else {
                    // Non-animated model or old format - use regular restoration
                    if (effects != null) {
                        playModelOnLocationWithEffectsWithId(modelId, location, lifetimeSeconds, true, effects, desiredEffectId);
                    } else {
                        playModelOnLocationWithEffectsWithId(modelId, location, lifetimeSeconds, true, null, desiredEffectId);
                    }
                }
                restoredCount++;
                if (lifetimeSeconds == -1) restoredInfinite++; else if (lifetimeSeconds > 0) restoredTimed++;

                // Per-effect debug line
                String worldN = location.getWorld() != null ? location.getWorld().getName() : "unknown";
                plugin.getLogger().info("Restored persistent effect #" + (desiredEffectId > 0 ? desiredEffectId : "?")
                    + " (model=" + modelId + ", world=" + worldN
                    + ", x=" + String.format(java.util.Locale.US, "%.3f", location.getX())
                    + ", y=" + String.format(java.util.Locale.US, "%.3f", location.getY())
                    + ", z=" + String.format(java.util.Locale.US, "%.3f", location.getZ()) + ")");
                
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to restore persistent instance: " + e.getMessage());
            }
        }
        
        if (restoredCount > 0) {
            plugin.getLogger().info("Restored " + restoredCount + " persistent effects (" + restoredTimed + " active, " + restoredInfinite + " infinite).");
            // Write back updated timestamps (remaining_seconds) immediately
            savePersistedModels(true);
        }
    }

    
    public void cleanup() {
        shuttingDown = true;
        savePersistedModels();
        stopAllEffectsAndClearMemory();
        cancelAllLoadJobs();
        // Give in-flight executor tasks a moment to settle before disabling
        try { Thread.sleep(50L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        // Wait briefly for async saves to finish so we don't leave .tmp files behind
        for (java.util.concurrent.Future<?> f : saveFutures) {
            boolean done = false;
            while (!done) {
                try {
                    f.get();
                    done = true;
                } catch (java.util.concurrent.CancellationException cancelEx) {
                    done = true;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (java.util.concurrent.ExecutionException execEx) {
                    plugin.getLogger().warning("Async save task failed during shutdown: " + execEx.getCause().getMessage());
                    done = true;
                }
            }
        }
        saveFutures.clear();
        try {
            if (autoSaveTask != null) autoSaveTask.cancel();
        } catch (Exception ignored) {}
        try {
            if (optimizerCleanupTask != null) optimizerCleanupTask.cancel();
        } catch (Exception ignored) {}
        // Hard cancel any remaining tasks for this plugin to appease Paper's nag if any slipped through
        try { plugin.getServer().getScheduler().cancelTasks(plugin); } catch (Exception ignored) {}
        // do not reset nextEffectId here; it is managed by allocate/reserve
    }

    private void recoverOrQuarantineTempFiles() {
        File modelsDir = new File(plugin.getDataFolder(), "models");
        if (!modelsDir.exists()) return;
        File[] temps = modelsDir.listFiles((dir, name) -> name.endsWith(".json.gz.tmp") || name.endsWith(".json.tmp"));
        if (temps == null) return;
        for (File tmp : temps) {
            String name = tmp.getName();
            boolean gz = name.endsWith(".json.gz.tmp");
            String finalName = name.substring(0, name.length() - 4); // strip .tmp
            File finalFile = new File(modelsDir, finalName);
            try {
                if (finalFile.exists()) {
                    // Final already exists; remove leftover temp
                    if (tmp.delete()) {
                        plugin.getLogger().info("Removed leftover temp file '" + name + "'.");
                    }
                    continue;
                }

                boolean valid = false;
                if (gz) {
                    try (java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(new java.io.FileInputStream(tmp));
                         java.io.InputStreamReader isr = new java.io.InputStreamReader(gis, java.nio.charset.StandardCharsets.UTF_8);
                         com.google.gson.stream.JsonReader jr = new com.google.gson.stream.JsonReader(isr)) {
                        jr.setLenient(true);
                        // Read entire JSON to ensure gzip trailer and JSON are intact
                        jr.beginObject();
                        while (jr.hasNext()) {
                            jr.nextName();
                            jr.skipValue();
                        }
                        jr.endObject();
                        // drain to EOF for CRC validation
                        byte[] buf = new byte[4096];
                        while (gis.read(buf) != -1) { /* drain */ }
                        valid = true;
                    } catch (Exception ex) {
                        valid = false;
                    }
                } else {
                    try (java.io.FileReader fr = new java.io.FileReader(tmp);
                         com.google.gson.stream.JsonReader jr = new com.google.gson.stream.JsonReader(fr)) {
                        jr.setLenient(true);
                        jr.beginObject();
                        while (jr.hasNext()) {
                            jr.nextName();
                            jr.skipValue();
                        }
                        jr.endObject();
                        valid = true;
                    } catch (Exception ex) {
                        valid = false;
                    }
                }

                if (valid) {
                    // Recover by promoting temp to final
                    try {
                        java.nio.file.Files.move(tmp.toPath(), finalFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    } catch (Exception ex) {
                        if (!tmp.renameTo(finalFile)) throw ex;
                    }
                    plugin.getLogger().info("Recovered model from temp file -> '" + finalFile.getName() + "'.");
                } else {
                    // Quarantine instead of deleting to preserve data for manual recovery
                    File quarantine = new File(modelsDir, finalName + ".corrupt");
                    try {
                        java.nio.file.Files.move(tmp.toPath(), quarantine.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception ex2) {
                        // fallback
                        if (!tmp.renameTo(quarantine)) {
                            plugin.getLogger().warning("Could not quarantine temp file '" + name + "'.");
                        }
                    }
                    plugin.getLogger().warning("Detected leftover temp model '" + name + "'. It did not finish saving previously and was quarantined as '" + quarantine.getName() + "'.");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error handling temp model '" + name + "': " + e.getMessage());
            }
        }
        // Also handle temps from the dedicated tmp-saving directory
        try {
            File tempDir = new File(plugin.getDataFolder(), "tmp-saving");
            if (tempDir.exists()) {
                File[] orphanTemps = tempDir.listFiles((d, n) -> n.endsWith(".tmp"));
                if (orphanTemps != null) {
                    for (File tmp : orphanTemps) {
                        String n = tmp.getName();
                        // Derive intended final filename from pattern: <name>.json(.gz).<random>.tmp
                        String finalName = null;
                        int idxGz = n.indexOf(".json.gz.");
                        int idxJson = n.indexOf(".json.");
                        boolean gz = false;
                        if (idxGz > 0) { finalName = n.substring(0, idxGz + ".json.gz".length()); gz = true; }
                        else if (idxJson > 0) { finalName = n.substring(0, idxJson + ".json".length()); gz = false; }
                        if (finalName == null) { try { tmp.delete(); } catch (Exception ignore) {} continue; }
                        File finalFile = new File(modelsDir, finalName);
                        boolean valid = false;
                        if (gz) {
                            try (java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(new java.io.FileInputStream(tmp));
                                 java.io.InputStreamReader isr = new java.io.InputStreamReader(gis, java.nio.charset.StandardCharsets.UTF_8);
                                 com.google.gson.stream.JsonReader jr = new com.google.gson.stream.JsonReader(isr)) {
                                jr.setLenient(true);
                                jr.beginObject();
                                while (jr.hasNext()) { jr.nextName(); jr.skipValue(); }
                                jr.endObject();
                                byte[] buf = new byte[4096];
                                while (gis.read(buf) != -1) { /* drain */ }
                                valid = true;
                            } catch (Exception ex) { valid = false; }
                        } else {
                            try (java.io.FileReader fr = new java.io.FileReader(tmp);
                                 com.google.gson.stream.JsonReader jr = new com.google.gson.stream.JsonReader(fr)) {
                                jr.setLenient(true);
                                jr.beginObject();
                                while (jr.hasNext()) { jr.nextName(); jr.skipValue(); }
                                jr.endObject();
                                valid = true;
                            } catch (Exception ex) { valid = false; }
                        }

                        if (valid) {
                            try {
                                java.nio.file.Files.move(tmp.toPath(), finalFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                            } catch (Exception ex) {
                                if (!tmp.renameTo(finalFile)) { /* if move fails, delete to avoid noise */ try { java.nio.file.Files.deleteIfExists(tmp.toPath()); } catch (Exception ignore) {} }
                            }
                            plugin.getLogger().info("Recovered model from temp-saving -> '" + finalFile.getName() + "'.");
                        } else {
                            File quarantine = new File(modelsDir, finalName + ".corrupt");
                            try {
                                java.nio.file.Files.move(tmp.toPath(), quarantine.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            } catch (Exception ex2) {
                                if (!tmp.renameTo(quarantine)) { try { tmp.delete(); } catch (Exception ignore) {} }
                            }
                            plugin.getLogger().warning("Detected leftover temp model '" + n + "' in tmp-saving. It did not finish saving previously and was quarantined as '" + quarantine.getName() + "'.");
                        }
                    }
                }
            }
        } catch (Exception ignore) {}
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
                                Collection<Player> viewers = collectViewers(effectInfo.location, effectInfo.forceVisible);
                                if (viewers.isEmpty()) {
                                    tick++;
                                    return;
                                }
                                for (ParticleData particle : currentParticles) {
                                    if (particle != null) {
                                        if (effectInfo.isLooping) {
                                            int cycleLength = Math.max(maxTicks, 100); 
                                            int cycleTick = tick % cycleLength;
                                            
                                            if (cycleTick >= particle.getDelay()) {
                                                if ((cycleTick - particle.getDelay()) % 3 == 0) {
                                                    spawnParticleWithEffects(particle, effectInfo.location, viewers, effectInfo.effectSettings, tick, isAnimated);
                                                }
                                            }
                                        } else {
                                            if (tick >= particle.getDelay() && tick < maxTicks) {
                                                if ((tick - particle.getDelay()) % 3 == 0) {
                                                    spawnParticleWithEffects(particle, effectInfo.location, viewers, effectInfo.effectSettings, tick, isAnimated);
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
                plugin.getLogger().warning("Cannot restore effect for model '" + effectInfo.modelName + "' - model not found after reload");
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
    File tempFile = new File(modelsDir, fileName + ".tmp");
        
        if (modelFile.exists()) {
            throw new IOException("Model file already exists: " + fileName);
        }
        
        try (FileWriter writer = new FileWriter(tempFile)) {
            Gson gson = new GsonBuilder().create();
            gson.toJson(model, writer);
            writer.flush();
        }
        // Atomic move temp -> final
        try {
            java.nio.file.Files.move(tempFile.toPath(), modelFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ex) {
            if (!tempFile.renameTo(modelFile)) {
                throw new IOException("Failed to move temp model file into place: " + ex.getMessage(), ex);
            }
        }
        
    loadedModels.put(model.getName().toLowerCase(), model);
        
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
        
    // Add to memory immediately for availability (use lowercase key for consistency)
    loadedModels.put(animatedModel.getName().toLowerCase(), animatedModel);
        
        // Write JSON asynchronously to prevent server hanging (use shared executor for clean shutdown)
        java.util.concurrent.Future<?> saveFuture = com.winss.dustlab.media.MediaProcessor.submitAsyncFuture(() -> {
            try {
                writeAnimatedModelStreaming(animatedModel, modelFile);
                
                if (shuttingDown || !plugin.isEnabled()) return;
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
                boolean interrupted = Thread.currentThread().isInterrupted();
                if (shuttingDown || !plugin.isEnabled()) {
                    if (interrupted) {
                        plugin.getLogger().warning("Save of animated model '" + animatedModel.getName() + "' was interrupted during shutdown; any partial file was discarded.");
                    }
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    loadedModels.remove(animatedModel.getName());
                    if (interrupted) {
                        plugin.getLogger().warning("Save of animated model '" + animatedModel.getName() + "' was interrupted; please retry the media creation.");
                    } else {
                        plugin.getLogger().severe("Failed to save animated model '" + animatedModel.getName() + "': " + e.getMessage());
                    }
                });
            }
        });
        saveFutures.add(saveFuture);
    }
    
    private void writeAnimatedModelStreaming(AnimatedModel animatedModel, File modelFile) throws IOException {
        boolean gz = modelFile.getName().toLowerCase().endsWith(".json.gz");
        // Use a dedicated temp directory to avoid leaving *.tmp files in models/
        File tempDir = new File(plugin.getDataFolder(), "tmp-saving");
        if (!tempDir.exists()) tempDir.mkdirs();
        // Create a unique temp file to minimize collision across concurrent saves
        File tempFile = File.createTempFile(modelFile.getName() + ".", ".tmp", tempDir);
        boolean promoted = false; // track whether temp was moved to final
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Interrupted before writing model data");
        }
        // Write JSON with full try-with-resources and UTF-8 to ensure proper closure and encoding
        if (gz) {
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile);
                            java.io.BufferedOutputStream bos = new java.io.BufferedOutputStream(fos);
                            java.util.zip.GZIPOutputStream gos = new java.util.zip.GZIPOutputStream(bos);
                            java.io.OutputStreamWriter out = new java.io.OutputStreamWriter(gos, java.nio.charset.StandardCharsets.UTF_8);
                            com.google.gson.stream.JsonWriter writer = new com.google.gson.stream.JsonWriter(out)) {
                
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
                // Optional integrity: checksum of metadata
                if (animatedModel.getMetadata() != null && !animatedModel.getMetadata().isEmpty()) {
                    String checksum = sha1String(canonicalJsonString(animatedModel.getMetadata()));
                    writer.name("metadataChecksumSha1").value(checksum);
                    writer.name("metadata");
                    new com.google.gson.Gson().toJson(animatedModel.getMetadata(), java.util.Map.class, writer);
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
                        if (Thread.currentThread().isInterrupted()) {
                            throw new IOException("Interrupted during streaming write");
                        }
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
                    
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Interrupted during streaming write");
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
                // Ensure all JSON is written through the writer and underlying streams
                writer.flush();
                out.flush();
                // Explicitly finish the GZIP stream to force trailer write before try-with-resources closes it
                gos.finish();
                // Flush OS buffers and sync to disk before promotion
                bos.flush();
                try { fos.getFD().sync(); } catch (Exception ignore) {}
            }
            // Verify gzip integrity on temp before promoting to final
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Interrupted before verifying compressed model");
            }
            boolean ok = false;
            try (java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(new java.io.FileInputStream(tempFile))) {
                byte[] buf = new byte[8192];
                while (gis.read(buf) != -1) { /* drain */ }
                ok = true;
            } catch (Exception verifyEx) {
                ok = false;
            }
            if (!ok) {
                // Delete bad temp and fall back to uncompressed JSON to preserve data
                try { java.nio.file.Files.deleteIfExists(tempFile.toPath()); } catch (Exception ignore) {}
                File jsonFallback = new File(modelFile.getParentFile(), stripGzExtension(modelFile.getName()));
                writeAnimatedModelStreaming(animatedModel, jsonFallback);
                plugin.getLogger().warning("Compressed save failed pre-move verification; saved uncompressed model '" + jsonFallback.getName() + "' instead.");
                return;
            }
        } else {
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile);
                 java.io.OutputStreamWriter out = new java.io.OutputStreamWriter(fos, java.nio.charset.StandardCharsets.UTF_8);
                 com.google.gson.stream.JsonWriter writer = new com.google.gson.stream.JsonWriter(out)) {
                
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
                    // Still include checksum field for parity with gz
                    String checksum = sha1String(canonicalJsonString(animatedModel.getMetadata()));
                    writer.name("metadataChecksumSha1").value(checksum);
                    writer.name("metadata");
                    new com.google.gson.Gson().toJson(animatedModel.getMetadata(), java.util.Map.class, writer);
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
                        if (Thread.currentThread().isInterrupted()) {
                            throw new IOException("Interrupted during streaming write");
                        }
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
                    
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Interrupted during streaming write");
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
                // Ensure all data is pushed to disk before moving temp into place
                writer.flush();
                out.flush();
                try { fos.getFD().sync(); } catch (Exception ignore) {}
            }
        }
        
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Interrupted before promoting model file");
        }
        // Atomic replace
        try {
            java.nio.file.Files.move(tempFile.toPath(), modelFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            promoted = true;
        } catch (Exception ex) {
            // Fallback without atomic move
            if (tempFile.renameTo(modelFile)) {
                promoted = true;
            } else {
                // Ensure temp file is not left behind on failure
                try { java.nio.file.Files.deleteIfExists(tempFile.toPath()); } catch (Exception ignore) {}
                throw new IOException("Failed to move temp model file into place: " + ex.getMessage(), ex);
            }
        } finally {
            // Best-effort cleanup if something slipped through
            if (!promoted) {
                try { java.nio.file.Files.deleteIfExists(tempFile.toPath()); } catch (Exception ignore) {}
            }
        }
        // Note: gzip integrity already verified pre-move
    }
    

    private void scheduleTemporaryModelDeletion(String modelName, File modelFile) {
        long deletionDelay = config != null ? config.getTempModelLifetimeTicks() : (20 * 60 * 30);
        
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            // Remove from live registry and stop active effects using it
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

            // Archive, do not delete user data
            try {
                if (modelFile.exists()) {
                    File archiveDir = new File(plugin.getDataFolder(), "temp-archive");
                    if (!archiveDir.exists()) archiveDir.mkdirs();
                    String original = modelFile.getName();
                    int firstDot = original.indexOf('.') ;
                    String base = firstDot > 0 ? original.substring(0, firstDot) : original;
                    String ext = firstDot > 0 ? original.substring(firstDot) : "";
                    String ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                    File target = new File(archiveDir, base + "." + ts + ext);
                    java.nio.file.Files.move(modelFile.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    plugin.getLogger().info("Archived temporary model '" + modelName + "' to '" + target.getName() + "'.");
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to archive temporary model '" + modelName + "': " + ex.getMessage());
            }
        }, deletionDelay);
    }
    
    public Map<String, ParticleModel> getLoadedModels() {
        return new HashMap<>(loadedModels);
    }
    
    public Map<String, EffectInfo> getActiveEffects() {
        return new HashMap<>(activeEffectInfo);
    }
    
    public MemoryUsageReport estimateMemoryUsage() {
        IdentityHashMap<PackedParticleArray, Boolean> seenPacked = new IdentityHashMap<>();
        long packedBytes = 0L;
        long packedParticles = 0L;
        long legacyBytes = 0L;
        long legacyParticles = 0L;
        long animationFrames = 0L;
        int animatedCount = 0;
        
        List<ParticleModel> modelsSnapshot = new ArrayList<>(loadedModels.values());
        for (ParticleModel model : modelsSnapshot) {
            if (model instanceof AnimatedModel animated) {
                animatedCount++;
                for (FrameData frame : animated.getFrames()) {
                    animationFrames++;
                    PackedParticleArray packed = frame.getPackedParticles();
                    if (packed != null) {
                        if (!seenPacked.containsKey(packed)) {
                            seenPacked.put(packed, Boolean.TRUE);
                            packedBytes += packed.approximateSizeBytes();
                            packedParticles += packed.size();
                        }
                    } else {
                        List<ParticleData> particles = frame.getParticles();
                        legacyBytes += estimateParticleListBytes(particles);
                        legacyParticles += particles != null ? particles.size() : 0;
                    }
                }
            } else {
                PackedParticleArray packed = model.getPackedParticles();
                if (packed != null) {
                    if (!seenPacked.containsKey(packed)) {
                        seenPacked.put(packed, Boolean.TRUE);
                        packedBytes += packed.approximateSizeBytes();
                        packedParticles += packed.size();
                    }
                } else {
                    List<ParticleData> particles = model.getParticles();
                    legacyBytes += estimateParticleListBytes(particles);
                    legacyParticles += particles != null ? particles.size() : 0;
                }
            }
        }
        
        long optimizerBytes = particleOptimizer != null ? particleOptimizer.estimateMemoryBytes() : 0L;
        int optimizerEffects = particleOptimizer != null ? particleOptimizer.getActiveEffectCount() : 0;
        long optimizerParticles = particleOptimizer != null ? particleOptimizer.getTotalParticleCount() : 0L;
        
        return new MemoryUsageReport(
                modelsSnapshot.size(),
                animatedCount,
                animationFrames,
                packedBytes,
                legacyBytes,
                optimizerBytes,
                packedParticles,
                legacyParticles,
                optimizerEffects,
                optimizerParticles,
                activeEffectInfo.size());
    }
    
    private static long estimateParticleListBytes(List<ParticleData> particles) {
        if (particles == null || particles.isEmpty()) {
            return 0L;
        }
        final long perParticleBytes = 96L;
        final long listOverhead = 48L;
        return listOverhead + (long) particles.size() * perParticleBytes;
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
                // Clean any related temp-saving files
                try {
                    File tempDir = new File(plugin.getDataFolder(), "tmp-saving");
                    if (tempDir.exists()) {
                        String prefix = modelFile.getName() + ".";
                        File[] leftovers = tempDir.listFiles((d, n) -> n.startsWith(prefix) && n.endsWith(".tmp"));
                        if (leftovers != null) {
                            for (File lf : leftovers) { try { lf.delete(); } catch (Exception ignore) {} }
                        }
                    }
                } catch (Exception ignore) {}
                if (deleted) {
                    plugin.getLogger().info("Deleted model '" + modelName + "' (stopped " + effectsToRemove.size() + " active effects)");
                    
                    savePersistedModels();
                    
                    return true;
                } else {
                    plugin.getLogger().warning("Failed to delete model file: " + modelFile.getPath());
                    return false;
                }
            } else {
                plugin.getLogger().warning("Model file not found: " + modelFile.getPath());
                return false;
            }
            
        } catch (Exception e) {
            plugin.getLogger().severe("Error deleting model '" + modelName + "': " + e.getMessage());
            return false;
        }
    }

    public record MemoryUsageReport(
            int loadedModelCount,
            int animatedModelCount,
            long animationFrameCount,
            long packedBytes,
            long legacyBytes,
            long optimizerBytes,
            long packedParticleCount,
            long legacyParticleCount,
            int optimizerTrackedEffects,
            long optimizerTrackedParticles,
            int activeEffectCount) {

        public long totalBytes() {
            return packedBytes + legacyBytes + optimizerBytes;
        }

        public long totalParticleCount() {
            return packedParticleCount + legacyParticleCount;
        }

        public int staticModelCount() {
            return loadedModelCount - animatedModelCount;
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
