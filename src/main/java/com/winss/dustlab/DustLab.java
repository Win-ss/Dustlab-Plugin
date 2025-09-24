package com.winss.dustlab;

import com.winss.dustlab.commands.DustLabCommand;
import com.winss.dustlab.managers.ParticleModelManager;
import com.winss.dustlab.config.DustLabConfig;
import org.bukkit.plugin.java.JavaPlugin;

public final class DustLab extends JavaPlugin {

    private ParticleModelManager particleModelManager;
    private DustLabConfig dustLabConfig;

    @Override
    public void onEnable() {
        getLogger().info("DustLab is starting up...");
        
        // Load configuration first
        this.dustLabConfig = new DustLabConfig(this);
        
        this.particleModelManager = new ParticleModelManager(this, dustLabConfig);
        
        getCommand("dustlab").setExecutor(new DustLabCommand(this));
        
        particleModelManager.loadModels();
        
        getLogger().info("DustLab has been enabled successfully!");
    }

    @Override
    public void onDisable() {
        getLogger().info("DustLab is shutting down...");
        
        if (particleModelManager != null) {
            particleModelManager.cleanup();
        }
        
        com.winss.dustlab.media.MediaProcessor.shutdown();
        
        getLogger().info("DustLab has been disabled.");
    }

    public ParticleModelManager getParticleModelManager() {
        return particleModelManager;
    }
    
    public DustLabConfig getDustLabConfig() {
        return dustLabConfig;
    }
}
