package com.oxclient.module;

import com.oxclient.events.EventHandler;
import com.oxclient.events.impl.GameTickEvent;
import com.oxclient.events.impl.RenderEvent;
import com.oxclient.module.base.Module;
import com.oxclient.module.base.ModuleCategory;
import com.oxclient.settings.impl.BooleanSetting;
import com.oxclient.settings.impl.NumberSetting;
import com.oxclient.settings.impl.ModeSetting;
import com.oxclient.utils.EntityUtils;
import com.oxclient.utils.MathUtils;
import com.oxclient.utils.PlayerUtils;
import com.oxclient.utils.RenderUtils;
import com.oxclient.utils.RotationUtils;
import com.oxclient.utils.TimerUtils;

import java.util.ArrayList;
import java.util.List;

public class KillAura extends Module {
    
    private NumberSetting range = new NumberSetting("Range", 4.0, 1.0, 6.0, 0.1);
    private NumberSetting aps = new NumberSetting("APS", 12.0, 1.0, 20.0, 0.5);
    private BooleanSetting autoBlock = new BooleanSetting("AutoBlock", true);
    private BooleanSetting raycast = new BooleanSetting("Raycast", true);
    private BooleanSetting rotations = new BooleanSetting("Rotations", true);
    private ModeSetting priority = new ModeSetting("Priority", "Distance", "Distance", "Health", "Angle");
    private BooleanSetting throughWalls = new BooleanSetting("ThroughWalls", false);
    private BooleanSetting players = new BooleanSetting("Players", true);
    private BooleanSetting mobs = new BooleanSetting("Mobs", true);
    private BooleanSetting animals = new BooleanSetting("Animals", false);
    private BooleanSetting invisibles = new BooleanSetting("Invisibles", false);
    private BooleanSetting friends = new BooleanSetting("IgnoreFriends", true);
    private NumberSetting fov = new NumberSetting("FOV", 180, 30, 180, 5);
    private BooleanSetting esp = new BooleanSetting("ESP", true);
    private BooleanSetting switchDelay = new BooleanSetting("SwitchDelay", false);
    private NumberSetting switchTime = new NumberSetting("SwitchTime", 200, 50, 1000, 50);
    
    private TimerUtils attackTimer = new TimerUtils();
    private TimerUtils switchTimer = new TimerUtils();
    
    private Object currentTarget;
    private Object lastTarget;
    private List<Object> targets = new ArrayList<>();
    
    private float[] targetRotations;
    private boolean isBlocking = false;
    private long lastSwitchTime = 0;
    
    public KillAura() {
        super("KillAura", "Automatically attacks nearby entities", ModuleCategory.COMBAT);
        
        addSettings(range, aps, autoBlock, raycast, rotations, priority, 
                   throughWalls, players, mobs, animals, invisibles, friends, 
                   fov, esp, switchDelay, switchTime);
    }
    
    @Override
    public void onEnable() {
        currentTarget = null;
        lastTarget = null;
        targets.clear();
        isBlocking = false;
        targetRotations = null;
    }
    
    @Override
    public void onDisable() {
        if (isBlocking) {
            stopBlocking();
        }
        currentTarget = null;
        lastTarget = null;
        targets.clear();
    }
    
    @EventHandler
    public void onTick(GameTickEvent event) {
        if (!PlayerUtils.isPlayerValid()) {
            return;
        }
        
        updateTargets();
        
        if (targets.isEmpty()) {
            if (isBlocking) {
                stopBlocking();
            }
            currentTarget = null;
            return;
        }
        
        selectTarget();
        
        if (currentTarget == null) {
            return;
        }
        
        // Check switch delay
        if (switchDelay.getValue() && currentTarget != lastTarget) {
            if (!switchTimer.hasTimeElapsed((long) switchTime.getValue())) {
                return;
            }
            lastTarget = currentTarget;
            switchTimer.reset();
        }
        
        // Handle rotations
        if (rotations.getValue()) {
            float[] rotations = RotationUtils.getRotationsToEntity(currentTarget);
            if (rotations != null) {
                targetRotations = rotations;
                RotationUtils.setPlayerRotations(rotations[0], rotations[1]);
            }
        }
        
        // Handle auto block
        if (autoBlock.getValue() && PlayerUtils.isHoldingSword()) {
            if (!isBlocking) {
                startBlocking();
            }
        }
        
        // Attack
        if (canAttack()) {
            attack();
        }
    }
    
    @EventHandler
    public void onRender(RenderEvent event) {
        if (!esp.getValue() || currentTarget == null) {
            return;
        }
        
        // Render ESP box around target
        double[] coords = EntityUtils.getEntityCoords(currentTarget);
        if (coords != null) {
            RenderUtils.drawEntityESP(coords[0], coords[1], coords[2], 
                                    EntityUtils.getEntityWidth(currentTarget),
                                    EntityUtils.getEntityHeight(currentTarget),
                                    0xFF0000, 2.0f);
        }
    }
    
    private void updateTargets() {
        targets.clear();
        
        List<Object> entities = EntityUtils.getEntitiesInRange(range.getValue());
        
        for (Object entity : entities) {
            if (!isValidTarget(entity)) {
                continue;
            }
            
            // FOV check
            if (fov.getValue() < 180) {
                float[] rotations = RotationUtils.getRotationsToEntity(entity);
                if (rotations != null) {
                    float angleDiff = Math.abs(RotationUtils.getAngleDifference(
                        PlayerUtils.getPlayerYaw(), rotations[0]));
                    if (angleDiff > fov.getValue() / 2.0f) {
                        continue;
                    }
                }
            }
            
            // Raycast check
            if (raycast.getValue() && !throughWalls.getValue()) {
                if (!EntityUtils.canSeeEntity(entity)) {
                    continue;
                }
            }
            
            targets.add(entity);
        }
    }
    
    private boolean isValidTarget(Object entity) {
        if (entity == null || !EntityUtils.isEntityAlive(entity)) {
            return false;
        }
        
        if (EntityUtils.isPlayer(entity)) {
            if (!players.getValue()) return false;
            if (friends.getValue() && EntityUtils.isFriend(entity)) return false;
        } else if (EntityUtils.isMonster(entity)) {
            if (!mobs.getValue()) return false;
        } else if (EntityUtils.isAnimal(entity)) {
            if (!animals.getValue()) return false;
        } else {
            return false;
        }
        
        if (!invisibles.getValue() && EntityUtils.isInvisible(entity)) {
            return false;
        }
        
        return true;
    }
    
    private void selectTarget() {
        if (targets.isEmpty()) {
            currentTarget = null;
            return;
        }
        
        Object bestTarget = null;
        double bestValue = Double.MAX_VALUE;
        
        for (Object entity : targets) {
            double value = getTargetPriority(entity);
            if (value < bestValue) {
                bestValue = value;
                bestTarget = entity;
            }
        }
        
        currentTarget = bestTarget;
    }
    
    private double getTargetPriority(Object entity) {
        switch (priority.getValue()) {
            case "Distance":
                return EntityUtils.getDistanceToEntity(entity);
                
            case "Health":
                return EntityUtils.getEntityHealth(entity);
                
            case "Angle":
                float[] rotations = RotationUtils.getRotationsToEntity(entity);
                if (rotations != null) {
                    return Math.abs(RotationUtils.getAngleDifference(
                        PlayerUtils.getPlayerYaw(), rotations[0]));
                }
                return Double.MAX_VALUE;
                
            default:
                return EntityUtils.getDistanceToEntity(entity);
        }
    }
    
    private boolean canAttack() {
        if (currentTarget == null) {
            return false;
        }
        
        double attackSpeed = 1000.0 / aps.getValue();
        return attackTimer.hasTimeElapsed((long) attackSpeed);
    }
    
    private void attack() {
        if (isBlocking) {
            stopBlocking();
        }
        
        // Perform attack
        EntityUtils.attackEntity(currentTarget);
        attackTimer.reset();
        
        // Resume blocking
        if (autoBlock.getValue() && PlayerUtils.isHoldingSword()) {
            startBlocking();
        }
    }
    
    private void startBlocking() {
        if (!isBlocking) {
            PlayerUtils.sendUseItem();
            isBlocking = true;
        }
    }
    
    private void stopBlocking() {
        if (isBlocking) {
            PlayerUtils.sendStopUsingItem();
            isBlocking = false;
        }
    }
    
    public Object getCurrentTarget() {
        return currentTarget;
    }
    
    public boolean hasTarget() {
        return currentTarget != null;
    }
}
