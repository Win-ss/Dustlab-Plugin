package com.winss.dustlab;

import com.winss.dustlab.commands.DustLabCommand;
import com.winss.dustlab.managers.ParticleModelManager;
import com.winss.dustlab.config.DustLabConfig;
import com.winss.dustlab.monitoring.PerformanceMonitor;
import org.bukkit.plugin.java.JavaPlugin;

public final class DustLab extends JavaPlugin {

    private ParticleModelManager particleModelManager;
    private DustLabConfig dustLabConfig;
    private PerformanceMonitor performanceMonitor;

    @Override
    public void onEnable() {
    getLogger().info("Starting up...");
        
        // Load configuration first
        this.dustLabConfig = new DustLabConfig(this);
        
    this.particleModelManager = new ParticleModelManager(this, dustLabConfig);
    this.performanceMonitor = new PerformanceMonitor(this);
    this.performanceMonitor.start();
        
        getCommand("dustlab").setExecutor(new DustLabCommand(this));
        
        particleModelManager.loadModels();
        
    getLogger().info("Enabled.");
    }

    @Override
    public void onDisable() {
    getLogger().info("Shutting down...");
        
        if (particleModelManager != null) {
            particleModelManager.cleanup();
        }

        if (performanceMonitor != null) {
            performanceMonitor.stop();
        }
        
        com.winss.dustlab.media.MediaProcessor.shutdown();
        
    getLogger().info("Disabled.");
    }

    public ParticleModelManager getParticleModelManager() {
        return particleModelManager;
    }
    
    public DustLabConfig getDustLabConfig() {
        return dustLabConfig;
    }

    public PerformanceMonitor getPerformanceMonitor() {
        return performanceMonitor;
    }
}
