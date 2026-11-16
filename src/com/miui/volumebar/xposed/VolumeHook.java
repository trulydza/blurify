package com.miui.volumebar.xposed;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VolumeHook implements IXposedHookLoadPackage {
    private static final String TAG = "BlurifyVolume";
    private static Object overlayController = null;
    private static Method showOverlayMethod = null;
    private static Method dismissMethod = null;
    private static Method stepVolumeMethod = null;
    private static Handler mainHandler = null;
    private static Context systemUiContext = null;
    private static final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private static volatile boolean isCapturing = false;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.android.systemui".equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log(TAG + ": Loaded in com.android.systemui");
        mainHandler = new Handler(Looper.getMainLooper());

        // 1. Hook SamsungVolumeDialogImpl constructor to capture SystemUI Context
        try {
            Class<?> volumeDialogClass = XposedHelpers.findClass(
                    "com.android.systemui.volume.SamsungVolumeDialogImpl",
                    lpparam.classLoader
            );

            XposedBridge.hookAllConstructors(volumeDialogClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    systemUiContext = (Context) param.args[0];
                    XposedBridge.log(TAG + ": SamsungVolumeDialogImpl created with context: " + systemUiContext);
                    initBlurify(systemUiContext);
                }
            });
            XposedBridge.log(TAG + ": Hooked SamsungVolumeDialogImpl constructor");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook SamsungVolumeDialogImpl constructor: " + t);
        }

        // 2. Hook SamsungVolumeDialogImpl$callbacks$1 (VolumeDialogController.Callbacks)
        try {
            Class<?> callbacksClass = XposedHelpers.findClass(
                    "com.android.systemui.volume.SamsungVolumeDialogImpl$callbacks$1",
                    lpparam.classLoader
            );

            // onShowRequested(int reason, boolean keyguard, int flags)
            XposedHelpers.findAndHookMethod(
                    callbacksClass,
                    "onShowRequested",
                    int.class, boolean.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(null); // Stop stock dispatch
                            showOverlay();
                        }
                    }
            );

            // onDismissRequested(int reason)
            XposedHelpers.findAndHookMethod(
                    callbacksClass,
                    "onDismissRequested",
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(null);
                            dismissOverlay();
                        }
                    }
            );

            // onKeyEvent(boolean isKeyDown, boolean isVibrating)
            XposedHelpers.findAndHookMethod(
                    callbacksClass,
                    "onKeyEvent",
                    boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            boolean isKeyDown = (Boolean) param.args[0];
                            if (isKeyDown) {
                                showOverlay();
                            }
                        }
                    }
            );

            XposedBridge.log(TAG + ": Hooked callbacks (onShowRequested, onDismissRequested, onKeyEvent)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook callbacks: " + t);
        }

        // 3. Fallback: Hook VolumePanelImpl.dispatch to intercept ACTION_PANEL_SHOW
        try {
            Class<?> volumePanelClass = XposedHelpers.findClass(
                    "com.android.systemui.volume.VolumePanelImpl",
                    lpparam.classLoader
            );

            Class<?> actionClass = XposedHelpers.findClass(
                    "com.samsung.systemui.splugins.volume.VolumePanelAction",
                    lpparam.classLoader
            );

            XposedHelpers.findAndHookMethod(
                    volumePanelClass,
                    "dispatch",
                    actionClass,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Object action = param.args[0];
                            if (action != null) {
                                Object actionType = XposedHelpers.callMethod(action, "getActionType");
                                if (actionType != null && "ACTION_PANEL_SHOW".equals(actionType.toString())) {
                                    param.setResult(null); // suppress stock panel show!
                                    showOverlay();
                                }
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + ": Hooked VolumePanelImpl.dispatch");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook VolumePanelImpl.dispatch: " + t);
        }

        // 4. Guarantee: Prevent VolumePanelWindow from ever showing
        try {
            Class<?> windowClass = XposedHelpers.findClass(
                    "com.android.systemui.volume.view.standard.VolumePanelWindow",
                    lpparam.classLoader
            );

            XposedHelpers.findAndHookMethod(
                    windowClass,
                    "show",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            return null;
                        }
                    }
            );
            XposedBridge.log(TAG + ": Hooked VolumePanelWindow.show -> disabled");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Could not hook VolumePanelWindow.show: " + t);
        }
    }

    private static void initBlurify(final Context context) {
        if (overlayController != null) {
            return;
        }

        registerLookReceiver(context);

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                    ClassLoader cl = VolumeHook.class.getClassLoader();
                    Class<?> controllerClass = Class.forName("com.miui.volumebar.ui.VolumeOverlayController", true, cl);

                    Constructor<?> ctor = controllerClass.getConstructor(Context.class, AudioManager.class);
                    overlayController = ctor.newInstance(context, am);

                    boolean isLiquid = context.getSharedPreferences("look", 0).getBoolean("liquid", false);
                    Field f = controllerClass.getDeclaredField("liquidLook");
                    f.setAccessible(true);
                    f.setBoolean(overlayController, isLiquid);

                    showOverlayMethod = controllerClass.getMethod("showOverlayImmediate");
                    dismissMethod = controllerClass.getMethod("dismiss");
                    stepVolumeMethod = controllerClass.getMethod("stepVolume", boolean.class);

                    XposedBridge.log(TAG + ": VolumeOverlayController successfully created! Initial liquid=" + isLiquid);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": Error initializing VolumeOverlayController: " + t);
                }
            }
        });
    }

    private static void registerLookReceiver(final Context context) {
        try {
            android.content.IntentFilter filter = new android.content.IntentFilter("com.miui.volumebar.SET_LOOK");
            android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, android.content.Intent intent) {
                    if ("com.miui.volumebar.SET_LOOK".equals(intent.getAction())) {
                        boolean liquid = intent.getBooleanExtra("liquid", false);
                        XposedBridge.log(TAG + ": Received SET_LOOK broadcast: liquid=" + liquid);
                        ctx.getSharedPreferences("look", 0).edit().putBoolean("liquid", liquid).apply();
                        updateLiquidLook(liquid);
                    }
                }
            };

            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, 2); // Context.RECEIVER_EXPORTED = 2
            } else {
                context.registerReceiver(receiver, filter);
            }
            XposedBridge.log(TAG + ": Registered SET_LOOK broadcast receiver in SystemUI");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to register SET_LOOK receiver: " + t);
        }
    }

    private static void updateLiquidLook(final boolean liquid) {
        if (mainHandler == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (overlayController != null) {
                    try {
                        Field f = overlayController.getClass().getDeclaredField("liquidLook");
                        f.setAccessible(true);
                        f.setBoolean(overlayController, liquid);
                        XposedBridge.log(TAG + ": Updated overlayController.liquidLook = " + liquid);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": Error setting liquidLook on overlayController: " + t);
                    }
                }
            }
        });
    }

    private static void showOverlay() {
        if (mainHandler == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (overlayController != null && showOverlayMethod != null) {
                    try {
                        showOverlayMethod.invoke(overlayController);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": Error invoking showOverlayImmediate: " + t);
                    }
                }
            }
        });

        // Trigger backdrop blur capture in background
        captureBackdrop();
    }

    private static void dismissOverlay() {
        if (mainHandler == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (overlayController != null && dismissMethod != null) {
                    try {
                        dismissMethod.invoke(overlayController);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": Error invoking dismiss: " + t);
                    }
                }
            }
        });
    }

    private static void captureBackdrop() {
        if (isCapturing || systemUiContext == null || overlayController == null) {
            return;
        }

        isCapturing = true;
        bgExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Bitmap screenshot = takeSemScreenshot(systemUiContext);
                    if (screenshot != null) {
                        ClassLoader moduleCl = VolumeHook.class.getClassLoader();
                        Class<?> blurHelperClass = Class.forName("com.miui.volumebar.util.BlurHelper", true, moduleCl);
                        Object blurHelper = blurHelperClass.getField("INSTANCE").get(null);
                        Method fastBlurMethod = blurHelperClass.getMethod("fastBlur", Bitmap.class, float.class, int.class);

                        boolean isLiquid = systemUiContext.getSharedPreferences("look", 0).getBoolean("liquid", false);
                        float scale = isLiquid ? 0.75f : 0.25f;
                        int radius = isLiquid ? 3 : 12;

                        final Bitmap blurred = (Bitmap) fastBlurMethod.invoke(blurHelper, screenshot, scale, radius);
                        if (blurred != null) {
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    applyBackdropBitmap(blurred);
                                }
                            });
                        }
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": Error capturing backdrop: " + t);
                } finally {
                    isCapturing = false;
                }
            }
        });
    }

    private static Bitmap takeSemScreenshot(Context context) {
        try {
            ClassLoader cl = context.getClassLoader();
            Class<?> swmClass = Class.forName("com.samsung.android.view.SemWindowManager", true, cl);
            Object swm = swmClass.getMethod("getInstance").invoke(null);

            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            boolean isLiquid = context.getSharedPreferences("look", 0).getBoolean("liquid", false);
            int div = isLiquid ? 2 : 4;
            int w = dm.widthPixels / div;
            int h = dm.heightPixels / div;

            for (Method m : swmClass.getMethods()) {
                if ("screenshot".equals(m.getName())) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 9) {
                        return (Bitmap) m.invoke(swm, 0, 2000, false, new Rect(), w, h, false, 0, true);
                    } else if (params.length == 8) {
                        return (Bitmap) m.invoke(swm, 0, 2000, false, new Rect(), w, h, false, 0);
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": takeSemScreenshot failed: " + t);
        }
        return null;
    }

    private static long lastPressTime = 0;
    private static void incrementRapidPresses() {
        long now = System.currentTimeMillis();
        if (now - lastPressTime < 100) {
            return;
        }
        lastPressTime = now;
        if (overlayController == null || mainHandler == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Field f = overlayController.getClass().getDeclaredField("rapidPresses");
                    f.setAccessible(true);
                    Object state = f.get(overlayController);
                    if (state != null) {
                        Method getInt = state.getClass().getMethod("getIntValue");
                        Method setInt = state.getClass().getMethod("setIntValue", int.class);
                        int current = (Integer) getInt.invoke(state);
                        setInt.invoke(state, current + 1);
                    }
                } catch (Throwable t) {
                    // ignore
                }
            }
        });
    }

    private static void applyBackdropBitmap(Bitmap bitmap) {
        if (overlayController == null || bitmap == null) return;
        try {
            Class<?> controllerClass = overlayController.getClass();
            Field backdropField = controllerClass.getDeclaredField("backdropBitmap");
            backdropField.setAccessible(true);
            Object mutableState = backdropField.get(overlayController);
            if (mutableState != null) {
                Method setValueMethod = mutableState.getClass().getMethod("setValue", Object.class);
                setValueMethod.invoke(mutableState, bitmap);
                XposedBridge.log(TAG + ": Applied backdrop blur bitmap (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ") to ComposeView!");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error applying backdropBitmap: " + t);
        }
    }
}
