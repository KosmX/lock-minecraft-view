package com.kosmx.lockMinecartView;

import com.google.common.collect.Lists;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.FabricKeyBinding;
import net.fabricmc.fabric.api.client.keybinding.KeyBindingRegistry;
import net.fabricmc.fabric.api.event.client.ClientTickCallback;
import net.fabricmc.loader.util.sat4j.core.Vec;
import net.minecraft.entity.vehicle.MinecartEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import javax.annotation.Nullable;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import me.sargunvohra.mcmods.autoconfig1u.AutoConfig;
import me.sargunvohra.mcmods.autoconfig1u.serializer.GsonConfigSerializer;

import java.util.List;

//This class contains all code...
//Originally for Fabric loader

public class LockViewClient implements ClientModInitializer {

    //-------------system variables--------------------
    private static boolean isHeld = false;
    private static FabricKeyBinding keyBinding;
    public static LockViewConfig config;
    public static boolean enabled;
    public static Logger LOGGER = LogManager.getLogger();
    public static final String MOD_ID = "lock_minecart_view";
    public static final String MOD_NAME = "Better Minecart rotation"; //To be correct
    //-------------calculating vars--------------------
    public static float yaw = 0f;
    //public static float pitch = 0f;
    private static boolean doCorrection;
    @Nullable
    private static Vec3d gotVelocity = null;
    @Nullable
    private static Vec3d posVelocity = null;

    @Nullable
    private static Vec3d lastCoord = null;
    private static float lastYaw = 0f;
    private static Vec3d lastVelocity;
    private static float rawLastYaw;
    private static float rawYaw;
    public static int tickAfterLastFollow = 0;
    private static float difference;
    private static int lastSlowdown = 0;

    //-------------methods-----------------------------

    public static List<String> getDebug(){
        List<String> list = Lists.newArrayList();
        list.add(MOD_NAME + " debug info - can be turned off");
        boolean bl1 = false;
        boolean bl2 = false;
        if(gotVelocity != null) {
            bl1 = true;
            list.add("\"fake\" velocity: " + Double.toString(gotVelocity.length()));
        }
        if(posVelocity != null){
            list.add("real velocity: " + Double.toString(posVelocity.length()));
            if(posVelocity.length() > 0.00000001) bl2 = true;
        }
        if(bl1 && bl2){
            list.add("quotient(fake/real): " + Double.toString(gotVelocity.length()/posVelocity.length()));
        }
        else list.add("quotient(fake/real): ∞");
        list.add("Last slowdown (collision): " + Integer.toString(lastSlowdown));
        return list;
    }

    public static float sphericalFromVec3d(Vec3d vec3d){
        //float f = MathHelper.sqrt(Entity.squaredHorizontalLength(vec3d));
        //pitch = (float)(MathHelper.atan2(vec3d.y, (double)f) * 57.2957763671875D);
        return (float)(MathHelper.atan2(-vec3d.x, vec3d.z) * 57.2957763671875D);
    }

    public static boolean onStartRiding(){
        lastCoord = null;
        tickAfterLastFollow = 100;
        lastVelocity = Vec3d.ZERO;
        lastSlowdown = 100;
        return !enabled;
    }

    public static void setMinecartDirection(float yawF){
        if (config.smoothMode){
            if(!config.rollerCoasterMode && tickAfterLastFollow > config.threshold){
                LockViewClient.yaw = yawF;
            }
            else if(doCorrection){
                LockViewClient.yaw = normalize(LockViewClient.yaw + 180f);
            }
            if (Math.abs(yawF - LockViewClient.yaw) < 180f){
                LockViewClient.yaw = LockViewClient.yaw/2 + yawF/2;
            }
            else{
                float tmp = LockViewClient.yaw/2 + yawF/2;
                //log(Level.INFO, Float.toString(LockViewClient.yaw));
                LockViewClient.yaw = (tmp >= 0) ? tmp - 180f : tmp + 180f;
            }
            //log(Level.INFO, Float.toString(LockViewClient.yaw));
        }
        else{
            LockViewClient.yaw = yawF;
        }
    }

    public static boolean setMinecartDirection(MinecartEntity minecart){
        boolean update = false;
        float yawF = rawYaw;
        if (minecart.getVelocity().lengthSquared()>0.000002f) {
            yawF = sphericalFromVec3d(minecart.getVelocity());
            update = true;
        }
        else if(minecart.getVelocity().lengthSquared() == 0 && updatePos(minecart)){
            yawF = sphericalFromVec3d(posVelocity);
            update = true;
        }

        LockViewClient.rawLastYaw = LockViewClient.rawYaw;
        rawYaw = yawF;
        checkSmartCorrection(minecart);
        setMinecartDirection(rawYaw);
        return update;
    }

    public static void setMinecartDirection(Vec3d vec3d){
        setMinecartDirection(sphericalFromVec3d(vec3d));
    }

    public static void smartCalc(MinecartEntity minecart){
        LockViewClient.lastYaw = LockViewClient.yaw;
        //if (minecart.getVelocity().lengthSquared()>0.000002f)
        //update = true;
        boolean update = setMinecartDirection(minecart);
            //log(Level.INFO, Float.toString(LockViewClient.yaw - LockViewClient.lastYaw));
        if (LockViewClient.tickAfterLastFollow++ >= config.threshold){
            LockViewClient.lastYaw = LockViewClient.yaw;
            //log(Level.INFO, "clear rotation" + Integer.toString(tickAfterLastFollow) + " : " + Boolean.toString(update));
        }
        else if(doCorrection){
            LockViewClient.lastYaw = normalize(LockViewClient.lastYaw + 180f);
            //log(Level.INFO, "do smart correction");
        }
        doCorrection = false;
        if(update) LockViewClient.tickAfterLastFollow = 0;
        LockViewClient.difference = LockViewClient.yaw - LockViewClient.lastYaw;
    }

    private static void checkSmartCorrection(MinecartEntity minecart){
        boolean correction = false;
        if(config.smartMode){
            float ang = 60;
            if (Math.abs(LockViewClient.rawLastYaw - LockViewClient.rawYaw) > 180f-ang && Math.abs(LockViewClient.rawLastYaw - LockViewClient.rawYaw)<180+ang){
                correction = true;
            }
            /*-------------------Explain, what does the following complicated code------------------------
             *The Smart correction's aim is to make difference between a U-turn and a collision, what is'n an easy task
             * The speed vector always rotate in 180* so I need data from somewhere else:Position->real speed(with a little delay)
             * I observed 2 things:
             * 1:On collision, the speed decreases (gotVelocity)
             * 2:On taking U-turn, the real velocity vector and the minecart.getVelocity() are ~perpendicular
             *
             * fix to piston rail-
             * while travelling on piston-rail
             * gotVelocity = zero
             * posVelocity always real (while euclidean space isn't broken)
             *
             *              :D          Don't give up!!! (message to everyone, who read my code)
             */

            //posVelocity is from position's change -- real, big delay
            //gotVelocity is from Minecart.getVelocity() -- represents rail's direction, immediate

            if(lastCoord != null){
                updatePos(minecart);
                if( gotVelocity.length() != 0 && lastVelocity.length()/gotVelocity.length() > 2.4d) lastSlowdown = 0;
                ++lastSlowdown;
                boolean bl1 = correction && posVelocity.lengthSquared() > 0.000008f && Math.abs(posVelocity.normalize().dotProduct(gotVelocity.normalize())) < 0.8f;//vectors dot product ~0, if vectors are ~perpendicular to each other
                boolean bl2 = (!bl1) || lastSlowdown < config.threshold && Math.abs(posVelocity.normalize().dotProduct(gotVelocity.normalize())) < 0.866f && gotVelocity.lengthSquared() < 0.32;
                if(bl1 && !bl2) {
                    correction = false;
                }
            }
        }
        LockViewClient.doCorrection = correction;
    }
    private static boolean updatePos(MinecartEntity minecart){
        boolean success = lastCoord != null;
        Vec3d pos = minecart.getPos();
        if(success) {
            posVelocity = new Vec3d(pos.x - lastCoord.x, 0, pos.z - lastCoord.z);
            lastVelocity = (gotVelocity == null) ? new Vec3d(0, 0, 0) : gotVelocity;
            gotVelocity = new Vec3d(minecart.getVelocity().getX(), 0, minecart.getVelocity().getZ());
        }
        lastCoord = pos;
        return success;
    }

    public static float calcYaw(float entityYaw){
        //log(Level.INFO, Float.toString(LockViewClient.difference));
        return entityYaw + LockViewClient.difference;
    }


    private static float normalize(Float f){
        return (Math.abs(f) > 180) ? (f < 0) ? f + 360f : f - 360f : f;
    }


    //-------------control key variables---------------
    

    @Override
    public void onInitializeClient() {
        log(Level.INFO, "Initializing");

        //setup Config
        AutoConfig.register(LockViewConfig.class, GsonConfigSerializer::new);
        config = AutoConfig.getConfigHolder(LockViewConfig.class).getConfig();
        enabled = config.enabled;


        //setup key
        keyBinding = FabricKeyBinding.Builder.create(
            new Identifier(MOD_ID, "toggle"), 
            net.minecraft.client.util.InputUtil.Type.KEYSYM, 
            GLFW.GLFW_KEY_F7, 
            MOD_NAME
        ).build();          //pre-create key
        KeyBindingRegistry.INSTANCE.addCategory(MOD_NAME);
        KeyBindingRegistry.INSTANCE.register(keyBinding);   //register key
        ClientTickCallback.EVENT.register(e ->
        {
            if (keyBinding.isPressed()){
                if(isHeld)return;
                isHeld = true;
                enabled = onStartRiding();
            }
            else if (isHeld){
                isHeld = false;
            }
        });
    }
    //net.minecraft.client.render.item.HeldItemRenderer
    public static void log(Level level, String message){
        LOGGER.log(level, "["+MOD_NAME+"] " + message);
    }

}