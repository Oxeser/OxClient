package com.oxclient;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.app.NotificationCompat;

import com.oxclient.events.EventManager;
import com.oxclient.events.impl.GameTickEvent;
import com.oxclient.gui.ClickGUI;
import com.oxclient.manager.ModuleManager;
import com.oxclient.utils.Logger;
import com.oxclient.utils.MinecraftUtils;

import java.util.Timer;
import java.util.TimerTask;

public class OverlayService extends Service {
    
    private static final String TAG = "OverlayService";
    private static final String CHANNEL_ID = "oxclient_service";
    private static final int NOTIFICATION_ID = 1;
    
    private WindowManager windowManager;
    private View overlayView;
    private View menuButton;
    private ClickGUI clickGUI;
    
    private static boolean isRunning = false;
    private Timer gameTickTimer;
    private ModuleManager moduleManager;
    private EventManager eventManager;
    
    private boolean isMenuVisible = false;
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;

    @Override
    public void onCreate() {
        super.onCreate();
        
        moduleManager = ModuleManager.getInstance();
        eventManager = EventManager.getInstance();
        clickGUI = new ClickGUI(this);
        
        createNotificationChannel();
        setupOverlay();
        startGameTick();
        
        isRunning = true;
        Logger.info(TAG, "OverlayService created");
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "OxClient Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("OxClient overlay service");
            channel.enableLights(true);
            channel.setLightColor(Color.BLUE);
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    private void setupOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        // Create menu button
        createMenuButton();
        
        // Create main overlay
        createMainOverlay();
    }
    
    private void createMenuButton() {
        menuButton = LayoutInflater.from(this).inflate(R.layout.menu_button, null);
        
        WindowManager.LayoutParams buttonParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        );
        
        buttonParams.gravity = Gravity.TOP | Gravity.START;
        buttonParams.x = 100;
        buttonParams.y = 100;
        
        // Make button draggable
        menuButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = buttonParams.x;
                        initialY = buttonParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                        
                    case MotionEvent.ACTION_UP:
                        int xDiff = (int) (event.getRawX() - initialTouchX);
                        int yDiff = (int) (event.getRawY() - initialTouchY);
                        
                        if (Math.abs(xDiff) < 10 && Math.abs(yDiff) < 10) {
                            toggleMenu();
                        }
                        return true;
                        
                    case MotionEvent.ACTION_MOVE:
                        buttonParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                        buttonParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(menuButton, buttonParams);
                        return true;
                }
                return false;
            }
        });
        
        windowManager.addView(menuButton, buttonParams);
    }
    
    private void createMainOverlay() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null);
        
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        
        params.gravity = Gravity.TOP | Gravity.START;
        overlayView.setVisibility(View.GONE);
        
        windowManager.addView(overlayView, params);
    }
    
    private void toggleMenu() {
        if (isMenuVisible) {
            hideMenu();
        } else {
            showMenu();
        }
    }
    
    private void showMenu() {
        if (MinecraftUtils.isMinecraftForeground(this)) {
            clickGUI.show();
            overlayView.setVisibility(View.VISIBLE);
            isMenuVisible = true;
        }
    }
    
    private void hideMenu() {
        clickGUI.hide();
        overlayView.setVisibility(View.GONE);
        isMenuVisible = false;
    }
    
    private void startGameTick() {
        gameTickTimer = new Timer();
        gameTickTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (MinecraftUtils.isMinecraftForeground(OverlayService.this)) {
                    // Post game tick event
                    eventManager.post(new GameTickEvent());
                    
                    // Update modules
                    moduleManager.onTick();
                }
            }
        }, 0, 50); // 20 TPS
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, createNotification());
        return START_STICKY;
    }
    
    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, 
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? 
                PendingIntent.FLAG_IMMUTABLE : 0
        );
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OxClient")
            .setContentText("Client is running")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        if (gameTickTimer != null) {
            gameTickTimer.cancel();
        }
        
        if (overlayView != null) {
            windowManager.removeView(overlayView);
        }
        
        if (menuButton != null) {
            windowManager.removeView(menuButton);
        }
        
        isRunning = false;
        Logger.info(TAG, "OverlayService destroyed");
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    public static boolean isRunning() {
        return isRunning;
    }
    
    public WindowManager getWindowManager() {
        return windowManager;
    }
}
