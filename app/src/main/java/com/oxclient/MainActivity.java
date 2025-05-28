package com.oxclient;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Switch;
import android.widget.Toast;
import android.widget.TextView;
import android.view.View;
import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.oxclient.config.ConfigManager;
import com.oxclient.gui.ModuleAdapter;
import com.oxclient.manager.ModuleManager;
import com.oxclient.utils.Logger;

public class MainActivity extends AppCompatActivity {
    
    private static final int OVERLAY_PERMISSION_REQUEST_CODE = 1001;
    private static final String TAG = "OxClient";
    
    private Button startServiceBtn;
    private Button stopServiceBtn;
    private Switch autoStartSwitch;
    private TextView statusText;
    private RecyclerView modulesList;
    private ModuleAdapter moduleAdapter;
    
    private SharedPreferences prefs;
    private ConfigManager configManager;
    private ModuleManager moduleManager;
    
    static {
        System.loadLibrary("oxclient");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initializeComponents();
        setupUI();
        checkPermissions();
        
        Logger.info(TAG, "OxClient initialized successfully");
    }
    
    private void initializeComponents() {
        prefs = getSharedPreferences("oxclient_prefs", MODE_PRIVATE);
        configManager = new ConfigManager(this);
        moduleManager = ModuleManager.getInstance();
        moduleManager.initialize();
        
        // Initialize native library
        initializeNative();
    }
    
    private void setupUI() {
        startServiceBtn = findViewById(R.id.btn_start_service);
        stopServiceBtn = findViewById(R.id.btn_stop_service);
        autoStartSwitch = findViewById(R.id.switch_auto_start);
        statusText = findViewById(R.id.text_status);
        modulesList = findViewById(R.id.recycler_modules);
        
        // Setup RecyclerView
        moduleAdapter = new ModuleAdapter(moduleManager.getModules());
        modulesList.setLayoutManager(new LinearLayoutManager(this));
        modulesList.setAdapter(moduleAdapter);
        
        // Set click listeners
        startServiceBtn.setOnClickListener(v -> startOverlayService());
        stopServiceBtn.setOnClickListener(v -> stopOverlayService());
        
        autoStartSwitch.setChecked(prefs.getBoolean("auto_start", false));
        autoStartSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("auto_start", isChecked).apply();
        });
        
        updateStatus();
    }
    
    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE);
            }
        }
    }
    
    private void startOverlayService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_LONG).show();
            checkPermissions();
            return;
        }
        
        Intent serviceIntent = new Intent(this, OverlayService.class);
        startForegroundService(serviceIntent);
        
        updateStatus();
        Toast.makeText(this, "OxClient service started", Toast.LENGTH_SHORT).show();
        Logger.info(TAG, "Overlay service started");
    }
    
    private void stopOverlayService() {
        Intent serviceIntent = new Intent(this, OverlayService.class);
        stopService(serviceIntent);
        
        updateStatus();
        Toast.makeText(this, "OxClient service stopped", Toast.LENGTH_SHORT).show();
        Logger.info(TAG, "Overlay service stopped");
    }
    
    private void updateStatus() {
        boolean isServiceRunning = OverlayService.isRunning();
        statusText.setText(isServiceRunning ? "Status: Running" : "Status: Stopped");
        statusText.setTextColor(isServiceRunning ? 
            getResources().getColor(android.R.color.holo_green_light) : 
            getResources().getColor(android.R.color.holo_red_light));
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Overlay permission granted", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_LONG).show();
                }
            }
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        if (moduleAdapter != null) {
            moduleAdapter.notifyDataSetChanged();
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        configManager.saveConfig();
        cleanupNative();
    }
    
    // Native methods
    public native void initializeNative();
    public native void cleanupNative();
    public native boolean isMinecraftRunning();
    public native void injectIntoMinecraft();
}
