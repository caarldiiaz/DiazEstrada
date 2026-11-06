package com.stradaos.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class Main extends Activity {

    static int RED = 0xFFFE3211;   // color de acento (configurable)
    static int BG = 0xFF15171C;
    static int CARD = 0xFF1E2128;
    static int INNER = 0xFF262A33;
    static int BTN = 0xFF2A2E37;
    static int DOCK = 0xFF0D0E11;
    static int TEXT = 0xFFF2F3F5;
    static int MUTED = 0xFF8B9099;
    static int SOFT = 0xFFC5C8CE;
    static int CIRCLE = 0xFF23262E;
    static int ICON = 0xFFFFFFFF;
    static boolean LIGHT;
    static int RED_DARK = 0xFF2A1416;

    static final String PKG_HUR = "com.andrerinas.headunitrevived";
    static final String PKG_WEB = "org.bromite.bromite";
    static final String ASSET_HUR = "apps/headunit-revived.apk";

    static final Locale ES = new Locale("es", "CO");

    // ranuras configurables: id, nombre, palabras clave en el nombre de la app, palabras clave en el paquete
    static final String[][] SLOTS = {
        {"radio", "Radio", "radio|fm|收音", "radio|fm"},
        {"bt", "Bluetooth", "bluetooth|bt|teléfono|telefono|phone", "bluetooth|.bt|btphone|btmusic"},
        {"cam", "Cámara", "cámara|camara|camera|avin|reversa|dvr", "avin|camera|dvr|backcar|reverse"},
        {"files", "Archivos", "archivo|explorador|gestor|file|files", "filemanager|fileexplorer|documentsui|file"},
        {"settings", "Ajustes", "", ""},
    };
    static final String[] SLOT_ICONS = {"radio", "bluetooth", "photo_camera", "folder", "settings"};

    private SharedPreferences prefs;
    private final Handler h = new Handler();
    private final List<ResolveInfo> apps = new ArrayList<ResolveInfo>();
    private final HashMap<String, Bitmap> iconCache = new HashMap<String, Bitmap>();

    private FrameLayout root;
    private FrameLayout board;
    private FrameLayout drawer;
    private float s = 1f;
    private int builtW, builtH;
    private boolean portraitLayout;

    private TextView weatherTv, weatherCityTv;
    private ImageView weatherIc;
    private LinearLayout weatherBox;
    private double wLat = Double.NaN, wLon = Double.NaN;
    private long weatherAt;
    private Weather.Now weatherNow;
    private FrameLayout settingsView;
    private int settingsCat = -1;
    private TextView timeTv, dateTv, greetTv, speedTv, gpsTv, aaBtnTv, aaSubTv, trackTv, artistTv, carTv, chipTv;
    private NowPlaying nowPlaying;
    private Boolean playingState;
    private TextView sourceTv, timePosTv, mapTagTv, cpSubTv;
    private LiveMapView liveMap;
    private MediaWatcher mediaWatcher;
    private boolean askedPerms;
    static final int SDK = android.os.Build.VERSION.SDK_INT;
    private PlayerArt playerArt;
    // estado de la pista
    private String curTitle, curArtist, curSource;
    private Bitmap curArt;
    private long durMs = -1, posBaseMs = 0, posBaseAt = 0;
    private boolean posKnown;
    private String curOrigin;
    private BtBridge bt;
    private View gpsDot;
    private ImageView playIc;

    private LocationManager lm;
    private long lastFix;
    private boolean installing;

    // ───────────────────────── ciclo de vida ─────────────────────────

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setBackgroundDrawable(new ColorDrawable(BG));
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        migrateOrSetup();
        applyTheme();
        applyAccent();

        root = new FrameLayout(this) {
            @Override
            protected void onSizeChanged(int w, int hh, int ow, int oh) {
                super.onSizeChanged(w, hh, ow, oh);
                if (w > 0 && hh > 0 && (w != builtW || hh != builtH)) {
                    builtW = w;
                    builtH = hh;
                    final int fw = w, fh = hh;
                    post(new Runnable() { public void run() { build(fw, fh); } });
                }
            }
        };
        root.setBackgroundColor(BG);
        setContentView(root);
        try {
            Location ll = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (ll == null) ll = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (ll != null) { wLat = ll.getLatitude(); wLon = ll.getLongitude(); }
        } catch (Throwable ignored) { }
        loadApps();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
        IntentFilter t = new IntentFilter(Intent.ACTION_TIME_TICK);
        t.addAction(Intent.ACTION_TIME_CHANGED);
        t.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, t);
        try {
            registerReceiver(usbReceiver, new IntentFilter("android.hardware.usb.action.USB_DEVICE_ATTACHED"));
        } catch (Throwable ignored) { }
        nowPlaying = new NowPlaying(this, new NowPlaying.Listener() {
            public void onTrack(NowPlaying.Track t) { showTrack(t); }
        });
        try { nowPlaying.start(); } catch (Throwable ignored) { }
        NotifArt.listener = new NotifArt.Listener() {
            public void onAaNotification(final Bitmap art, final java.util.List<String> texts) {
                h.post(new Runnable() { public void run() { applyAaNotif(art, texts); } });
            }
        };
        if (SDK >= 21) {
            try {
                mediaWatcher = new MediaWatcher(this, new NowPlaying.Listener() {
                    public void onTrack(final NowPlaying.Track t) {
                        h.post(new Runnable() { public void run() { showTrack(t); } });
                    }
                });
                mediaWatcher.start();
            } catch (Throwable e) { mediaWatcher = null; }
        }
        bt = new BtBridge(this, new BtBridge.Listener() {
            public void onBtState(NowPlaying.Track t) { showTrack(t); }
        });
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        try { unregisterReceiver(timeReceiver); } catch (Exception ignored) { }
        try { nowPlaying.stop(); } catch (Throwable ignored) { }
        try { unregisterReceiver(usbReceiver); } catch (Throwable ignored) { }
        try { bt.disconnect(); } catch (Throwable ignored) { }
        try { if (mediaWatcher != null) mediaWatcher.stop(); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        if (SDK >= 23 && !askedPerms) {
            askedPerms = true;
            try { Modern.ensurePermissions(this, 77); } catch (Throwable ignored) { }
        }
        if (mediaWatcher != null) mediaWatcher.start();
        guardUsb();
        maybeWeather();
        h.postDelayed(new Runnable() { public void run() { autoChecks(); } }, 4000);
        updateClock();
        refreshStates();
        startGps();
        h.postDelayed(ticker, 1000);
        h.removeCallbacks(progressTick);
        h.post(progressTick);
        applyAaNotif(NotifArt.lastArt, NotifArt.lastTexts);
    }

    /** Android 6+: respuesta a la solicitud de permisos. */
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req != 77) return;
        stopGps();
        startGps();
        seedLastLocation();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopGps();
        h.removeCallbacks(ticker);
        h.removeCallbacks(progressTick);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        closeDrawer();
    }

    @Override
    public void onBackPressed() {
        if (settingsView != null) { closeSettings(); return; }
        closeDrawer(); // es la pantalla de inicio: atrás no la cierra
    }

    private void immersive() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_FULLSCREEN | 0x00001000 /* IMMERSIVE_STICKY, API 19 */);
        } catch (Exception ignored) { }
    }

    /** Al conectar algo por USB: si aún no se eligió cómo conectar el celular y hay varias opciones, se pregunta. */
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            if (prefs.getString("conn_app", null) == null && prefs.getString("aa", null) == null && connCandidates().size() > 1 && hasWindowFocus())
                chooseConnection(null);
            else guardUsb();
        }
    };

    private int btPoll, usbTick, themeTick;
    private boolean usbWarned;

    /** Apps de espejo de fábrica que se adelantan a Open Headunit al conectar el celular por USB. */
    static final String[] USB_HOGS = {"net.easyconn", "com.carbit", "net.easyconn.carman"};
    static final java.util.HashSet<String> USB_HOG_SET = new java.util.HashSet<String>(java.util.Arrays.asList(USB_HOGS));

    private boolean processRunning(String pkg) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            List<android.app.ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
            if (ps == null) return false;
            for (android.app.ActivityManager.RunningAppProcessInfo p : ps)
                if (p.processName != null && (p.processName.equals(pkg) || p.processName.startsWith(pkg + ":"))) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    /** Si EasyConnection quedó corriendo, la cierra; si vuelve, pide al usuario «Forzar detención». */
    private void guardUsb() {
        // Solo se vigila cuando el usuario eligió OTRA app para conectar el celular.
        String chosen = prefs.getString("conn_app", null);
        if (chosen == null && prefs.getString("aa", null) != null) {
            try { chosen = ComponentName.unflattenFromString(prefs.getString("aa", "")).getPackageName(); } catch (Throwable ignored) { }
        }
        if (chosen == null || USB_HOG_SET.contains(chosen) || prefs.getBoolean("allow_easyconn", false)) return;
        final android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        for (final String pkg : USB_HOGS) {
            if (!installed(pkg) || !processRunning(pkg)) continue;
            // Silencioso: con los datos de EasyConnection borrados, Android Auto funciona aunque siga corriendo.
            try { am.killBackgroundProcesses(pkg); } catch (Throwable ignored) { }
        }
    }

    private final Runnable ticker = new Runnable() {
        public void run() {
            if (lastFix > 0 && SystemClock.elapsedRealtime() - lastFix > 6000) setGps(false, 0);
            if (++usbTick % 5 == 0) guardUsb();
            if (bt != null) {
                if (!bt.isConnected()) bt.connect();
                else if (++btPoll % 3 == 0 || curTitle == null) bt.refresh();
            }
            updatePlayIcon();
            if (++themeTick % 30 == 0) {
                boolean was = LIGHT;
                applyTheme();
                if (was != LIGHT) { applyAccent(); rebuild(); }
                maybeWeather();
            }
            h.postDelayed(this, 2000);
        }
    };

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) { updateClock(); }
    };

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            loadApps();
            refreshStates();
        }
    };

    // ───────────────────────── construcción de la UI ─────────────────────────

    private void build(int W, int H) {
        portraitLayout = H > W;
        s = portraitLayout ? Math.min(W / 768f, H / 1024f) : Math.min(W / 1024f, H / 600f);
        root.removeAllViews();
        root.setBackgroundColor(BG);
        getWindow().setBackgroundDrawable(new ColorDrawable(BG));
        board = new FrameLayout(this);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams((int) ((portraitLayout ? 768 : 1024) * s), (int) ((portraitLayout ? 1024 : 600) * s));
        blp.gravity = Gravity.CENTER;
        root.addView(board, blp);

        // En vertical, el dock se convierte en una columna lateral; en horizontal conserva la franja inferior.
        View dockBg = new View(this);
        dockBg.setBackgroundColor(DOCK);
        FrameLayout.LayoutParams dlp;
        if (portraitLayout) {
            dlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) (104 * s)));
            dlp.gravity = Gravity.BOTTOM;
        } else {
            dlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (H - ((H - 600 * s) / 2 + 456 * s)) + 1);
            dlp.gravity = Gravity.BOTTOM;
        }
        root.addView(dockBg, 0, dlp);

        if (portraitLayout) {
            buildTopPortrait();
            buildCarCardPortrait();
            buildAaCardPortrait();
            buildAudioCardPortrait();
            buildWebCardPortrait();
            buildDockPortrait();
        } else {
            buildTop();
            buildCarCard();
            buildAaCard();
            buildAudioCard();
            buildDock();
        }

        drawer = null;
        settingsView = null;
        updateClock();
        refreshStates();
        showWeather();
        if (settingsCat >= 0) showSettings(settingsCat);
    }

    // ── composición específica para pantalla vertical 768x1024 ──

    private void buildTopPortrait() {
        timeTv = tv("", 30, TEXT, true);
        dateTv = tv("", 13, MUTED, false);
        placeWrap(board, timeTv, 20, 14);
        placeWrap(board, dateTv, 22, 52);
        greetTv = tv("", 14, SOFT, false);
        greetTv.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        place(board, greetTv, 420, 20, 270, 28);
        weatherBox = new LinearLayout(this);
        weatherBox.setGravity(Gravity.CENTER_VERTICAL);
        weatherIc = icon("cloud", 18);
        weatherBox.addView(weatherIc, new LinearLayout.LayoutParams((int)(18*s),(int)(18*s)));
        weatherTv = tv("", 13, TEXT, true);
        weatherTv.setPadding((int)(5*s),0,0,0);
        weatherBox.addView(weatherTv);
        weatherCityTv = tv("", 11, MUTED, false);
        weatherCityTv.setPadding((int)(5*s),0,0,0);
        weatherBox.addView(weatherCityTv);
        weatherBox.setVisibility(View.GONE);
        placeWrap(board, weatherBox, 430, 52);
        View gear = circleIcon("settings", 42, 22, CIRCLE);
        place(board, gear, 710, 18, 42, 42);
        gear.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openSettings(); } });
        greetTv.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openSettings(); } });
    }

    private void buildCarCardPortrait() {
        FrameLayout c = card(16, 88, 736, 218, CARD);
        carTv = tv(prefs.getString("car_name", "Mi vehículo"), 20, TEXT, true);
        placeWrap(c, carTv, 18, 14);
        TextView chip = tv(prefs.getString("car_tag", ""), 11, Color.WHITE, true);
        chipTv = chip;
        chip.setPadding((int)(8*s),(int)(3*s),(int)(8*s),(int)(3*s));
        chip.setBackground(round(RED,8));
        chip.setVisibility(prefs.getString("car_tag","").trim().length()==0 ? View.GONE : View.VISIBLE);
        placeWrap(c, chip, 18, 44);
        Bitmap photo = CarPhoto.load(this);
        if (photo != null) {
            ImageView car = new ImageView(this);
            car.setImageBitmap(photo);
            car.setScaleType(ImageView.ScaleType.FIT_CENTER);
            place(c, car, 18, 70, 330, 130);
            car.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { photoMenu(); return true; } });
        } else {
            LinearLayout ph = new LinearLayout(this);
            ph.setOrientation(LinearLayout.VERTICAL);
            ph.setGravity(Gravity.CENTER);
            ph.setBackground(round(INNER,14));
            ImageView ic = icon("directions_car",54);
            ic.setAlpha(0.35f);
            ph.addView(ic,new LinearLayout.LayoutParams((int)(54*s),(int)(54*s)));
            TextView t = tv("Toca para poner la foto de tu vehículo",12,MUTED,false);
            t.setPadding(0,(int)(5*s),0,0);
            ph.addView(t);
            place(c,ph,18,70,330,130);
            ph.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pickPhoto(); } });
        }
        FrameLayout box = new FrameLayout(this);
        box.setBackground(round(INNER,16));
        place(c,box,370,54,346,146);
        LinearLayout lbl = new LinearLayout(this);
        lbl.setGravity(Gravity.CENTER_VERTICAL);
        lbl.addView(icon("speed",16),new LinearLayout.LayoutParams((int)(16*s),(int)(16*s)));
        TextView vl = tv("Velocidad",13,MUTED,false);
        vl.setPadding((int)(6*s),0,0,0);
        lbl.addView(vl);
        placeWrap(box,lbl,16,12);
        LinearLayout gps = new LinearLayout(this);
        gps.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        gpsDot = new View(this);
        gpsDot.setBackground(round(MUTED,4));
        gps.addView(gpsDot,new LinearLayout.LayoutParams((int)(8*s),(int)(8*s)));
        gpsTv = tv("GPS",11,MUTED,false);
        gpsTv.setPadding((int)(6*s),0,0,0);
        gps.addView(gpsTv);
        place(box,gps,180,12,150,18);
        LinearLayout sv = new LinearLayout(this);
        sv.setGravity(Gravity.CENTER_VERTICAL);
        speedTv = tv("--",58,TEXT,true);
        TextView kmh = tv("km/h",15,MUTED,false);
        kmh.setPadding((int)(8*s),0,0,0);
        sv.addView(speedTv); sv.addView(kmh);
        placeWrap(box,sv,16,50);
        setGps(false,0);
    }

    private void buildAaCardPortrait() {
        FrameLayout c = new FrameLayout(this);
        c.setBackground(round(CARD,20));
        place(board,c,16,322,736,348);
        c.addView(new MapView(this,s,20*s),new FrameLayout.LayoutParams(-1,-1));
        liveMap = new LiveMapView(this,s,20*s);
        c.addView(liveMap,new FrameLayout.LayoutParams(-1,-1));
        liveMap.setVisibility(View.INVISIBLE);
        liveMap.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { liveMap.cycleZoom(); } });
        liveMap.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                new AlertDialog.Builder(Main.this,dlg()).setTitle("Diagnóstico del mapa").setMessage(liveMap.diag()).setPositiveButton("OK",null).show();
                return true;
            }
        });
        seedLastLocation();
        LinearLayout tag = new LinearLayout(this);
        tag.setGravity(Gravity.CENTER_VERTICAL);
        tag.setBackground(round((CARD & 0x00FFFFFF) | 0xE0000000,12));
        tag.setPadding((int)(12*s),(int)(7*s),(int)(12*s),(int)(7*s));
        tag.addView(icon("my_location",16),new LinearLayout.LayoutParams((int)(16*s),(int)(16*s)));
        mapTagTv = tv("Buscando GPS…",13,TEXT,true);
        mapTagTv.setPadding((int)(8*s),0,0,0);
        tag.addView(mapTagTv);
        placeWrap(c,tag,16,16);
        FrameLayout panel = new FrameLayout(this);
        panel.setBackground(round((CARD & 0x00FFFFFF) | 0xEB000000,16));
        place(c,panel,12,258,712,78);
        View aa = projButton("navigation","Android Auto",RED,true);
        aaBtnTv = (TextView)aa.getTag();
        place(panel,aa,10,8,340,62);
        View cp = projButton("phone_iphone","CarPlay",BTN,false);
        cpSubTv = (TextView)cp.getTag();
        place(panel,cp,362,8,340,62);
        aaSubTv = aaBtnTv;
        aa.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openAndroidAuto(); } });
        aa.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { chooseConnection(null); return true; } });
        cp.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openCarPlay(); } });
        cp.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { pickFor("carplay","CarPlay"); return true; } });
        updateMapTag();
    }

    private void buildAudioCardPortrait() {
        FrameLayout c = card(16,686,480,220,CARD);
        placeWrap(c,tv("AUDIO",11,MUTED,true),16,12);
        sourceTv = tv("",11,RED,true);
        sourceTv.setGravity(Gravity.RIGHT);
        place(c,sourceTv,300,12,160,16);
        playerArt = new PlayerArt(this,s,asset("ic/music_note.png"));
        place(c,playerArt,18,42,112,112);
        trackTv = tv("Nada sonando",17,TEXT,true);
        place(c,trackTv,148,54,310,26);
        artistTv = tv("Radio, Bluetooth o USB",13,MUTED,false);
        place(c,artistTv,148,84,310,22);
        timePosTv = tv("",12,MUTED,false);
        place(c,timePosTv,148,110,310,18);
        View prev = circleIcon("skip_previous",48,24,BTN);
        View play = circleIcon("play_arrow",56,28,RED);
        playIc = (ImageView)((FrameLayout)play).getChildAt(0);
        View next = circleIcon("skip_next",48,24,BTN);
        place(c,prev,170,154,48,48);
        place(c,play,232,150,56,56);
        place(c,next,302,154,48,48);
        prev.setOnClickListener(mediaClick(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        play.setOnClickListener(mediaClick(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        next.setOnClickListener(mediaClick(KeyEvent.KEYCODE_MEDIA_NEXT));
        c.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { showPlayerDiag(); return true; } });
        renderTrack();
    }

    private void buildWebCardPortrait() {
        FrameLayout c = card(512,686,240,220,CARD);
        FrameLayout ic = new FrameLayout(this);
        ic.setBackground(round(RED_DARK,16));
        ImageView g = icon("public",28);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int)(28*s),(int)(28*s));
        lp.gravity = Gravity.CENTER;
        ic.addView(g,lp);
        place(c,ic,16,20,52,52);
        placeWrap(c,tv("Navegador",17,TEXT,true),16,88);
        place(c,tv("Internet desde la pantalla",11,MUTED,false),16,118,208,36);
        TextView arrow = tv("›",26,RED,false);
        placeWrap(c,arrow,204,166);
        c.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openBrowser(); } });
        c.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { pickFor("web","Navegador"); return true; } });
    }

    private void buildDockPortrait() {
        final String[] icons = {"radio","bluetooth","photo_camera","public","folder","settings","apps"};
        final String[] names = {"Radio","Bluetooth","Cámara","Web","Archivos","Ajustes","Apps"};
        for (int i=0;i<7;i++) {
            final int idx=i;
            LinearLayout b = new LinearLayout(this);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setGravity(Gravity.CENTER_HORIZONTAL);
            b.addView(circleIcon(icons[i],52,24,CIRCLE),new LinearLayout.LayoutParams((int)(52*s),(int)(52*s)));
            TextView l = tv(names[i],10,SOFT,false);
            l.setGravity(Gravity.CENTER_HORIZONTAL);
            l.setPadding(0,(int)(4*s),0,0);
            b.addView(l,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
            place(board,b,10+106*i,930,96,82);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    switch(idx) {
                        case 0: openSlot(0); break;
                        case 1: openSlot(1); break;
                        case 2: openSlot(2); break;
                        case 3: openBrowser(); break;
                        case 4: openSlot(3); break;
                        case 5: openSlot(4); break;
                        default: openDrawer();
                    }
                }
            });
            b.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    int[] map={0,1,2,-1,3,4,-2};
                    int m=map[idx];
                    if(m==-1){pickFor("web","Navegador");return true;}
                    if(m<0)return false;
                    pickFor(SLOTS[m][0],SLOTS[m][1]);
                    return true;
                }
            });
        }
    }

    private void buildTop() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(true);
        timeTv = tv("", 30, TEXT, true);
        dateTv = tv("", 14, MUTED, false);
        dateTv.setPadding((int) (12 * s), 0, 0, 0);
        row.addView(timeTv);
        row.addView(dateTv);
        placeWrap(board, row, 24, 10);

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        weatherBox = new LinearLayout(this);
        weatherBox.setGravity(Gravity.CENTER_VERTICAL);
        weatherIc = icon("cloud", 20);
        weatherBox.addView(weatherIc, new LinearLayout.LayoutParams((int) (20 * s), (int) (20 * s)));
        weatherTv = tv("", 15, TEXT, true);
        weatherTv.setPadding((int) (6 * s), 0, 0, 0);
        weatherBox.addView(weatherTv);
        weatherCityTv = tv("", 13, MUTED, false);
        weatherCityTv.setPadding((int) (6 * s), 0, (int) (14 * s), 0);
        weatherBox.addView(weatherCityTv);
        View sep = new View(this);
        sep.setBackgroundColor(BTN);
        weatherBox.addView(sep, new LinearLayout.LayoutParams(Math.max(1, (int) s), (int) (18 * s)));
        weatherBox.setVisibility(View.GONE);
        right.addView(weatherBox);
        greetTv = tv("", 15, SOFT, false);
        greetTv.setPadding((int) (14 * s), 0, 0, 0);
        right.addView(greetTv);
        place(board, right, 400, 14, 552, 32);
        View gear = circleIcon("settings", 40, 22, CIRCLE);
        place(board, gear, 964, 12, 40, 40);
        gear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openSettings(); }
        });
        greetTv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openSettings(); }
        });
    }

    private void buildCarCard() {
        FrameLayout card = card(16, 64, 300, 380, CARD);
        carTv = tv(prefs.getString("car_name", "Mi vehículo"), 20, TEXT, true);
        place(card, carTv, 20, 16, 260, 26);
        TextView chip = tv(prefs.getString("car_tag", ""), 11, Color.WHITE, true);
        chipTv = chip;
        chip.setPadding((int) (8 * s), (int) (3 * s), (int) (8 * s), (int) (3 * s));
        chip.setBackground(round(RED, 8));
        chip.setVisibility(prefs.getString("car_tag", "").trim().length() == 0 ? View.GONE : View.VISIBLE);
        placeWrap(card, chip, 20, 46);

        Bitmap photo = CarPhoto.load(this);
        if (photo != null) {
            ImageView car = new ImageView(this);
            car.setImageBitmap(photo);
            car.setScaleType(ImageView.ScaleType.FIT_CENTER);
            place(card, car, 18, 78, 264, 160);
            car.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) { photoMenu(); return true; }
            });
        } else {
            LinearLayout ph = new LinearLayout(this);
            ph.setOrientation(LinearLayout.VERTICAL);
            ph.setGravity(Gravity.CENTER);
            ph.setBackground(round(INNER, 16));
            ImageView ic = icon("directions_car", 72);
            ic.setAlpha(0.35f);
            ph.addView(ic, new LinearLayout.LayoutParams((int) (72 * s), (int) (72 * s)));
            TextView t = tv("Toca para poner la foto de tu vehículo", 12, MUTED, false);
            t.setPadding(0, (int) (6 * s), 0, 0);
            ph.addView(t);
            place(card, ph, 18, 82, 264, 152);
            ph.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { pickPhoto(); }
            });
        }

        FrameLayout box = new FrameLayout(this);
        box.setBackground(round(INNER, 16));
        place(card, box, 12, 250, 276, 118);

        LinearLayout lbl = new LinearLayout(this);
        lbl.setGravity(Gravity.CENTER_VERTICAL);
        ImageView sp = icon("speed", 16);
        sp.setAlpha(0.6f);
        lbl.addView(sp, new LinearLayout.LayoutParams((int) (16 * s), (int) (16 * s)));
        TextView vl = tv("Velocidad", 13, MUTED, false);
        vl.setPadding((int) (6 * s), 0, 0, 0);
        lbl.addView(vl);
        placeWrap(box, lbl, 16, 12);

        LinearLayout g = new LinearLayout(this);
        g.setGravity(Gravity.CENTER_VERTICAL);
        gpsDot = new View(this);
        gpsDot.setBackground(round(MUTED, 4));
        g.addView(gpsDot, new LinearLayout.LayoutParams((int) (8 * s), (int) (8 * s)));
        gpsTv = tv("GPS", 11, MUTED, false);
        gpsTv.setPadding((int) (6 * s), 0, 0, 0);
        g.addView(gpsTv);
        g.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        place(box, g, 120, 12, 140, 18);

        LinearLayout sv = new LinearLayout(this);
        sv.setBaselineAligned(true);
        speedTv = tv("0", 56, TEXT, true);
        TextView kmh = tv("km/h", 15, MUTED, false);
        kmh.setPadding((int) (8 * s), 0, 0, 0);
        sv.addView(speedTv);
        sv.addView(kmh);
        placeWrap(box, sv, 16, 34);
        setGps(false, 0);
    }

    private void buildAaCard() {
        FrameLayout card = new FrameLayout(this);
        place(board, card, 328, 64, 420, 380);
        // fondo decorativo mientras no hay GPS; encima, el mapa en vivo
        card.addView(new MapView(this, s, 20 * s), new FrameLayout.LayoutParams(-1, -1));
        liveMap = new LiveMapView(this, s, 20 * s);
        card.addView(liveMap, new FrameLayout.LayoutParams(-1, -1));
        liveMap.setVisibility(View.INVISIBLE);
        liveMap.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { liveMap.cycleZoom(); }
        });
        liveMap.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                new AlertDialog.Builder(Main.this, dlg())
                    .setTitle("Diagnóstico del mapa").setMessage(liveMap.diag())
                    .setPositiveButton("OK", null).show();
                return true;
            }
        });
        seedLastLocation();

        LinearLayout tag = new LinearLayout(this);
        tag.setGravity(Gravity.CENTER_VERTICAL);
        tag.setBackground(round((CARD & 0x00FFFFFF) | 0xE0000000, 12));
        tag.setPadding((int) (12 * s), (int) (7 * s), (int) (12 * s), (int) (7 * s));
        tag.addView(icon("my_location", 16), new LinearLayout.LayoutParams((int) (16 * s), (int) (16 * s)));
        mapTagTv = tv("Buscando GPS…", 13, TEXT, true);
        mapTagTv.setPadding((int) (8 * s), 0, 0, 0);
        tag.addView(mapTagTv);
        placeWrap(card, tag, 16, 16);

        FrameLayout panel = new FrameLayout(this);
        panel.setBackground(round((CARD & 0x00FFFFFF) | 0xEB000000, 16));
        place(card, panel, 12, 284, 396, 84);

        View aa = projButton("navigation", "Android Auto", RED, true);
        aaBtnTv = (TextView) aa.getTag();
        place(panel, aa, 10, 10, 184, 64);
        View cp = projButton("phone_iphone", "CarPlay", BTN, false);
        cpSubTv = (TextView) cp.getTag();
        place(panel, cp, 202, 10, 184, 64);
        aaSubTv = aaBtnTv;

        aa.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openAndroidAuto(); }
        });
        aa.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { chooseConnection(null); return true; }
        });
        cp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openCarPlay(); }
        });
        cp.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { pickFor("carplay", "CarPlay"); return true; }
        });
        updateMapTag();
    }

    /** Botón de proyección: ícono + título + subtítulo (el subtítulo se guarda en getTag()). */
    private View projButton(String ic, String title, int color, boolean filled) {
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(round(color, 14));
        b.setPadding((int) (12 * s), 0, (int) (8 * s), 0);
        ImageView pic = icon(ic, 26);
        if (filled) pic.clearColorFilter();
        b.addView(pic, new LinearLayout.LayoutParams((int) (26 * s), (int) (26 * s)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (10 * s), 0, 0, 0);
        col.addView(tv(title, 15, filled ? Color.WHITE : TEXT, true));
        TextView sub = tv("", 11, filled ? 0xD9FFFFFF : MUTED, false);
        sub.setPadding(0, (int) (3 * s), 0, 0);
        col.addView(sub);
        b.addView(col, new LinearLayout.LayoutParams(-1, -2));
        b.setTag(sub);
        return b;
    }

    // ── mapa en vivo ──

    private void seedLastLocation() {
        Location best = null;
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "passive"}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (Throwable ignored) { }
        }
        if (best != null) feedMap(best);
    }

    private void feedMap(Location l) {
        if (l != null) { wLat = l.getLatitude(); wLon = l.getLongitude(); }
        if (liveMap == null || l == null) return;
        float kmh = l.hasSpeed() ? l.getSpeed() * 3.6f : 0;
        liveMap.setPosition(l.getLatitude(), l.getLongitude(), l.hasBearing() && kmh > 4 ? l.getBearing() : Float.NaN, kmh);
        liveMap.setVisibility(View.VISIBLE);
        updateMapTag();
    }

    private void updateMapTag() {
        if (mapTagTv == null) return;
        boolean live = lastFix > 0;
        if (liveMap == null || !liveMap.hasFix()) mapTagTv.setText("Buscando GPS…");
        else if (liveMap.offline()) mapTagTv.setText(live ? "En vivo · sin internet" : "Última posición · sin internet");
        else mapTagTv.setText(live ? "Mapa en vivo" : "Última posición conocida");
    }

    private void buildAudioCard() {
        FrameLayout card = card(760, 64, 248, 380, CARD);
        placeWrap(card, tv("AUDIO", 11, MUTED, true), 18, 14);
        sourceTv = tv("", 11, RED, true);
        sourceTv.setGravity(Gravity.RIGHT);
        place(card, sourceTv, 80, 14, 150, 16);
        playerArt = new PlayerArt(this, s, asset("ic/music_note.png"));
        place(card, playerArt, 34, 34, 180, 180);
        trackTv = tv("Nada sonando", 17, TEXT, true);
        trackTv.setGravity(Gravity.CENTER_HORIZONTAL);
        place(card, trackTv, 14, 222, 220, 24);
        artistTv = tv("Radio, Bluetooth o USB", 13, MUTED, false);
        artistTv.setGravity(Gravity.CENTER_HORIZONTAL);
        place(card, artistTv, 14, 250, 220, 20);
        timePosTv = tv("", 12, MUTED, false);
        timePosTv.setGravity(Gravity.CENTER_HORIZONTAL);
        place(card, timePosTv, 14, 274, 220, 18);
        View prev = circleIcon("skip_previous", 52, 26, BTN);
        View play = circleIcon("play_arrow", 64, 32, RED);
        playIc = (ImageView) ((FrameLayout) play).getChildAt(0);
        View next = circleIcon("skip_next", 52, 26, BTN);
        place(card, prev, 30, 304, 52, 52);
        place(card, play, 92, 298, 64, 64);
        place(card, next, 166, 304, 52, 52);
        prev.setOnClickListener(mediaClick(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        play.setOnClickListener(mediaClick(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        next.setOnClickListener(mediaClick(KeyEvent.KEYCODE_MEDIA_NEXT));
        card.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { showPlayerDiag(); return true; }
        });
        renderTrack();
    }

    private void buildWebCard() {
        FrameLayout card = card(760, 260, 248, 184, CARD);
        FrameLayout ic = new FrameLayout(this);
        ic.setBackground(round(RED_DARK, 16));
        ImageView g = icon("public", 30);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (30 * s), (int) (30 * s));
        lp.gravity = Gravity.CENTER;
        ic.addView(g, lp);
        place(card, ic, 18, 20, 56, 56);
        placeWrap(card, tv("Navegador", 18, TEXT, true), 18, 94);
        placeWrap(card, tv("Internet desde la pantalla", 12, MUTED, false), 18, 122);
        TextView arrow = tv("›", 26, RED, false);
        placeWrap(card, arrow, 214, 130);
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openBrowser(); }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { pickFor("web", "Navegador"); return true; }
        });
    }

    private void buildDock() {
        final String[] icons = {"radio", "bluetooth", "photo_camera", "public", "folder", "settings", "apps"};
        final String[] names = {"Radio", "Bluetooth", "Cámara", "Navegador", "Archivos", "Ajustes", "Apps"};
        for (int i = 0; i < 7; i++) {
            final int idx = i;
            LinearLayout b = new LinearLayout(this);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setGravity(Gravity.CENTER_HORIZONTAL);
            b.addView(circleIcon(icons[i], 64, 28, CIRCLE), new LinearLayout.LayoutParams((int) (64 * s), (int) (64 * s)));
            TextView l = tv(names[i], 13, SOFT, false);
            l.setGravity(Gravity.CENTER_HORIZONTAL);
            l.setPadding(0, (int) (8 * s), 0, 0);
            b.addView(l, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            place(board, b, 38 + 138 * i, 474, 120, 110);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    switch (idx) {
                        case 0: openSlot(0); break;
                        case 1: openSlot(1); break;
                        case 2: openSlot(2); break;
                        case 3: openBrowser(); break;
                        case 4: openSlot(3); break;
                        case 5: openSlot(4); break;
                        default: openDrawer();
                    }
                }
            });
            b.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    int[] map = {0, 1, 2, -1, 3, 4, -2};
                    int m = map[idx];
                    if (m == -1) { pickFor("web", "Navegador"); return true; }
                    if (m < 0) return false;
                    pickFor(SLOTS[m][0], SLOTS[m][1]);
                    return true;
                }
            });
        }
    }

    // ───────────────────────── helpers de UI ─────────────────────────

    private TextView tv(String t, float size, int color, boolean medium) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * s);
        v.setIncludeFontPadding(false);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return v;
    }

    private GradientDrawable round(int color, float r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(r * s);
        return d;
    }

    private void place(ViewGroup parent, View v, float x, float y, float w, float hh) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (w * s), (int) (hh * s));
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.leftMargin = (int) (x * s);
        lp.topMargin = (int) (y * s);
        parent.addView(v, lp);
    }

    private void placeWrap(ViewGroup parent, View v, float x, float y) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.leftMargin = (int) (x * s);
        lp.topMargin = (int) (y * s);
        parent.addView(v, lp);
    }

    private FrameLayout card(float x, float y, float w, float hh, int color) {
        FrameLayout c = new FrameLayout(this);
        c.setBackground(round(color, 20));
        place(board, c, x, y, w, hh);
        return c;
    }

    private ImageView icon(String name, float size) {
        ImageView v = new ImageView(this);
        v.setImageBitmap(asset("ic/" + name + ".png"));
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (ICON != 0xFFFFFFFF) v.setColorFilter(ICON);
        return v;
    }

    private FrameLayout circleIcon(String name, float d, float ic, int color) {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        f.setBackground(g);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (ic * s), (int) (ic * s));
        lp.gravity = Gravity.CENTER;
        ImageView iv = icon(name, ic);
        if (color == RED) iv.clearColorFilter();
        f.addView(iv, lp);
        return f;
    }

    private Bitmap asset(String path) {
        Bitmap b = iconCache.get(path);
        if (b != null) return b;
        try {
            InputStream in = getAssets().open(path);
            b = BitmapFactory.decodeStream(in);
            in.close();
            iconCache.put(path, b);
        } catch (Exception e) {
            b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
        return b;
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    // ───────────────────────── reloj, GPS, audio ─────────────────────────

    private void updateClock() {
        if (timeTv == null) return;
        Date now = new Date();
        String fmt = prefs.getString("clock", "auto");
        boolean h24 = "24".equals(fmt) || ("auto".equals(fmt) && android.text.format.DateFormat.is24HourFormat(this));
        timeTv.setText(new SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", ES).format(now));
        String d = new SimpleDateFormat("EEEE, d 'de' MMMM", ES).format(now);
        dateTv.setText(d.substring(0, 1).toUpperCase(ES) + d.substring(1));
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String saludo = hr >= 5 && hr < 12 ? "Buenos días" : (hr >= 12 && hr < 19 ? "Buenas tardes" : "Buenas noches");
        String nombre = prefs.getString("user_name", "").trim();
        greetTv.setText(nombre.length() > 0 ? saludo + ", " + nombre : saludo);
    }

    private final LocationListener gpsListener = new LocationListener() {
        public void onLocationChanged(Location l) {
            lastFix = SystemClock.elapsedRealtime();
            setGps(true, l.hasSpeed() ? l.getSpeed() * 3.6f : 0);
            feedMap(l);
        }
        public void onStatusChanged(String p, int st, Bundle e) { }
        public void onProviderEnabled(String p) { }
        public void onProviderDisabled(String p) { setGps(false, 0); }
    };

    /** Ubicación por red (WiFi/datos): solo para ubicar el mapa mientras llega el GPS. */
    private final LocationListener netListener = new LocationListener() {
        public void onLocationChanged(Location l) { if (lastFix == 0) feedMap(l); }
        public void onStatusChanged(String p, int st, Bundle e) { }
        public void onProviderEnabled(String p) { }
        public void onProviderDisabled(String p) { }
    };

    private void startGps() {
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, gpsListener);
            try { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000, 0, netListener); } catch (Throwable ignored) { }
        } catch (Exception e) {
            setGps(false, 0);
        }
    }

    private void stopGps() {
        try { lm.removeUpdates(gpsListener); } catch (Exception ignored) { }
        try { lm.removeUpdates(netListener); } catch (Exception ignored) { }
    }

    private void setGps(boolean ok, float kmh) {
        if (speedTv == null) return;
        if (!ok) lastFix = 0;
        speedTv.setText(ok ? String.valueOf(Math.round(kmh < 2 ? 0 : kmh)) : "--");
        gpsTv.setText(ok ? "GPS" : "Buscando GPS");
        gpsDot.setBackground(round(ok ? 0xFF3DDC84 : MUTED, 4));
        updateMapTag();
    }

    private View.OnClickListener mediaClick(final int code) {
        return new View.OnClickListener() {
            public void onClick(View v) {
                int which = code == KeyEvent.KEYCODE_MEDIA_NEXT ? 1 : code == KeyEvent.KEYCODE_MEDIA_PREVIOUS ? -1 : 0;
                boolean done = false;
                if (mediaWatcher != null && "ms".equals(curOrigin)) {
                    try { done = mediaWatcher.command(which); } catch (Throwable ignored) { }
                }
                if (!done && !sendBtCommand(code)) sendMediaKey(code);
                h.postDelayed(new Runnable() { public void run() { updatePlayIcon(); } }, 600);
            }
        };
    }

    /** Si suena Bluetooth, controla directamente la app Bluetooth de fábrica (com.clw.btmusic). */
    private boolean sendBtCommand(int code) {
        if (!"Bluetooth".equals(curSource)) return false;
        if (bt != null && bt.command(code == KeyEvent.KEYCODE_MEDIA_NEXT ? 1 : code == KeyEvent.KEYCODE_MEDIA_PREVIOUS ? -1 : 0)) {
            h.postDelayed(new Runnable() { public void run() { if (bt != null) bt.refresh(); } }, 700);
            return true;
        }
        String a = code == KeyEvent.KEYCODE_MEDIA_NEXT ? "com.clw.btmusic.next"
                : code == KeyEvent.KEYCODE_MEDIA_PREVIOUS ? "com.clw.btmusic.previous" : "com.clw.btmusic.togglepause";
        try {
            Intent i = new Intent(a);
            i.setClassName("com.clw.btmusic", "com.clw.btmusic.BTPlaybackService");
            return startService(i) != null;
        } catch (Throwable e) {
            return false;
        }
    }

    private void sendMediaKey(int code) {
        long t = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0);
        KeyEvent up = new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0);
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        try {
            java.lang.reflect.Method m = AudioManager.class.getMethod("dispatchMediaKeyEvent", KeyEvent.class);
            m.invoke(am, down);
            m.invoke(am, up);
            return;
        } catch (Throwable ignored) { }
        Intent i1 = new Intent(Intent.ACTION_MEDIA_BUTTON);
        i1.putExtra(Intent.EXTRA_KEY_EVENT, down);
        sendOrderedBroadcast(i1, null);
        Intent i2 = new Intent(Intent.ACTION_MEDIA_BUTTON);
        i2.putExtra(Intent.EXTRA_KEY_EVENT, up);
        sendOrderedBroadcast(i2, null);
    }

    private void updatePlayIcon() {
        if (playIc == null) return;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        boolean on = playingState != null ? playingState : am.isMusicActive();
        playIc.setImageBitmap(asset(on ? "ic/pause.png" : "ic/play_arrow.png"));
    }

    // ───────────────────────── pista en reproducción ─────────────────────────

    private void showTrack(NowPlaying.Track t) {
        long now = SystemClock.elapsedRealtime();
        boolean hasContent = t.title != null || t.artist != null;
        // Un aviso sin pista de otra fuente (p. ej. el sistema cuando entra la cámara de reversa)
        // no debe borrar ni pausar lo que muestra la fuente actual.
        if (curOrigin != null && !curOrigin.equals(t.origin) && !hasContent) return;
        // Con Android Auto conectado el celular suele mandar la misma canción también por Bluetooth:
        // si es la misma pista, se queda Android Auto como fuente (trae carátula) y Bluetooth solo aporta tiempo/estado.
        boolean sameAsAA = ("rcc".equals(curOrigin) || "Android Auto".equals(curSource))
                && "bt".equals(t.origin) && t.title != null && t.title.equals(curTitle);
        if (sameAsAA) {
            t.source = null;
            t.artist = null;
        } else if (hasContent && !t.clear) curOrigin = t.origin;
        if (t.clear) {
            curOrigin = null;
            curTitle = curArtist = curSource = null;
            curArt = null;
            durMs = -1;
            posKnown = false;
            playingState = false;
            renderTrack();
            return;
        }
        boolean newTrack = t.title != null && !t.title.equals(curTitle);
        if (newTrack) {
            posBaseMs = 0;
            posBaseAt = now;
            posKnown = true;
            durMs = -1;
            if (!t.artSet) curArt = null;
            // ¿La notificación de Open Headunit ya trae la carátula de esta canción?
            if (!t.artSet && NotifArt.lastArt != null && textsMatch(NotifArt.lastTexts, t.title)) {
                curArt = NotifArt.lastArt;
                t.source = "Android Auto";
            }
        }
        if (t.title != null) curTitle = t.title;
        if (t.artist != null) curArtist = t.artist;
        if (t.source != null) curSource = t.source;
        if (t.durationMs > 0) durMs = t.durationMs;
        if (t.artSet) curArt = t.art;
        long pos = currentPos();
        if (t.positionMs >= 0) { pos = t.positionMs; posKnown = true; }
        if (t.playing != null) {
            playingState = t.playing;
        }
        posBaseMs = pos;
        posBaseAt = now;
        renderTrack();
    }

    private static boolean textsMatch(java.util.List<String> texts, String title) {
        if (texts == null || title == null) return false;
        String tt = title.trim().toLowerCase(ES);
        for (String s : texts) {
            if (s == null) continue;
            String x = s.trim().toLowerCase(ES);
            if (x.equals(tt) || (tt.length() > 3 && x.contains(tt))) return true;
        }
        return false;
    }

    /** Carátula de Android Auto leída de la notificación de Open Headunit. */
    private void applyAaNotif(Bitmap art, java.util.List<String> texts) {
        if (art == null || curTitle == null) return;
        if (!textsMatch(texts, curTitle)) return;
        curArt = art;
        curSource = "Android Auto";
        renderTrack();
    }

    private void showPlayerDiag() {
        String msg = "Fuente: " + curOrigin + " / " + curSource
                + "\nPista: " + curTitle + " — " + curArtist
                + "\nCarátula: " + (curArt != null ? curArt.getWidth() + "x" + curArt.getHeight() : "no")
                + "\n\nLector de notificaciones (accesibilidad): " + (NotifArt.running ? "ACTIVO" : "apagado")
                + "\nÚltima notificación de Open Headunit: " + (NotifArt.lastAt == 0 ? "ninguna"
                    : ((SystemClock.elapsedRealtime() - NotifArt.lastAt) / 1000) + " s, carátula "
                    + (NotifArt.lastArt != null ? "sí" : "no") + ", textos " + NotifArt.lastTexts)
                + "\nApp Bluetooth conectada: " + (bt != null && bt.isConnected() ? "sí" : "no");
        new AlertDialog.Builder(this, dlg())
            .setTitle("Diagnóstico del reproductor")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .setNeutralButton("Accesibilidad", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { openAccessibility(); }
            })
            .show();
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            toast("Activa «Carátula de Android Auto» (StradaOS)");
        } catch (Throwable e) {
            toast("No se pudo abrir Accesibilidad");
        }
    }

    private long currentPos() {
        long p = posBaseMs;
        if (playingState != null && playingState) p += SystemClock.elapsedRealtime() - posBaseAt;
        if (durMs > 0 && p > durMs) p = durMs;
        return p;
    }

    private static String fmt(long ms) {
        long sec = Math.max(0, ms / 1000);
        return (sec / 60) + ":" + (sec % 60 < 10 ? "0" : "") + (sec % 60);
    }

    private void renderTrack() {
        if (trackTv == null) return;
        if (curTitle == null && curArtist == null) {
            trackTv.setText("Nada sonando");
            artistTv.setText("Radio, Bluetooth o USB");
            sourceTv.setText("");
            timePosTv.setText("");
            playerArt.setArt(null);
            playerArt.setProgress(-1);
        } else {
            boolean aaOff = curSource != null && curSource.toLowerCase(ES).contains("headunit")
                    && ("Conectado".equalsIgnoreCase(curArtist) || "Connected".equalsIgnoreCase(curArtist));
            trackTv.setText(curTitle != null ? curTitle : (curSource != null ? curSource : ""));
            artistTv.setText(aaOff ? "Activa «Pista actual de AA» en Open Headunit" : (curArtist != null ? curArtist : ""));
            sourceTv.setText(curSource != null ? curSource.toUpperCase(ES) : "");
            playerArt.setArt(curArt);
            updateProgress();
        }
        if (playIc != null) {
            boolean on = playingState != null ? playingState
                    : ((AudioManager) getSystemService(AUDIO_SERVICE)).isMusicActive();
            playIc.setImageBitmap(asset(on ? "ic/pause.png" : "ic/play_arrow.png"));
        }
    }

    private void updateProgress() {
        if (playerArt == null || (curTitle == null && curArtist == null)) return;
        if (durMs > 0 && posKnown) {
            long p = currentPos();
            playerArt.setProgress(p / (float) durMs);
            timePosTv.setText(fmt(p) + "  /  " + fmt(durMs));
        } else if (durMs > 0) {
            playerArt.setProgress(-1);
            timePosTv.setText(fmt(durMs));
        } else {
            playerArt.setProgress(-1);
            timePosTv.setText("");
        }
    }

    private final Runnable progressTick = new Runnable() {
        public void run() {
            updateProgress();
            h.postDelayed(this, 1000);
        }
    };

    // ───────────────────────── configuración ─────────────────────────

    private void openSettings() { showSettings(1); }

    private void usbHelp() {
        new AlertDialog.Builder(this, dlg())
            .setTitle("«Ninguna aplic. funciona con este accesorio USB»")
            .setMessage("Ese aviso lo causa una app de espejo de fábrica (EasyConnection/CarbitLink) que toma el USB antes que la app que elegiste.\n\n1. Desconecta el celular.\n2. Ajustes → Aplicaciones → EasyConnection → «Borrar datos».\n3. Apaga y enciende el carro.\n4. En Open Headunit → USB, si tu celular aparece como «Ignorado», tócalo para permitirlo.")
            .setPositiveButton("Abrir EasyConnection", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    for (String p : USB_HOGS) if (installed(p)) {
                        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + p)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Throwable ignored) { }
                        return;
                    }
                    toast("No hay apps de espejo instaladas");
                }
            })
            .setNegativeButton("Cerrar", null).show();
    }

    private void runDiag() {
        toast("Exportando… puede tardar un minuto");
        new Thread(new Runnable() {
            public void run() {
                final String r = Diag.export(Main.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        new AlertDialog.Builder(Main.this, dlg())
                            .setTitle("Diagnóstico listo")
                            .setMessage("Se guardó en:\n" + r + "\n\nCopia la carpeta StradaDiag a una memoria USB y adjúntala en un «issue» del repositorio de StradaOS en GitHub.")
                            .setPositiveButton("OK", null).show();
                    }
                });
            }
        }).start();
    }

    private String clockLabel() {
        String c = prefs.getString("clock", "auto");
        return "12".equals(c) ? "12 horas" : "24".equals(c) ? "24 horas" : "Automático";
    }

    private void chooseClock() {
        final String[] opts = {"Automático", "12 horas (a.m./p.m.)", "24 horas"};
        final String[] vals = {"auto", "12", "24"};
        new AlertDialog.Builder(this, dlg())
            .setTitle("Formato de hora")
            .setItems(opts, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    prefs.edit().putString("clock", vals[w]).apply();
                    updateClock();
                }
            }).show();
    }

    private void editText(final String key, String title, String def) {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setSingleLine(true);
        et.setText(prefs.getString(key, def));
        et.setSelection(et.getText().length());
        FrameLayout wrap = new FrameLayout(this);
        int pad = (int) (20 * s);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(et);
        new AlertDialog.Builder(this, dlg())
            .setTitle(title)
            .setView(wrap)
            .setPositiveButton("Guardar", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    prefs.edit().putString(key, et.getText().toString().trim()).apply();
                    if ("city_manual".equals(key)) refreshWeatherNow();
                    if ("map_url".equals(key)) toast("Se aplicará al reiniciar StradaOS");
                    if ("extra_actions".equals(key)) {
                        try { nowPlaying.stop(); nowPlaying.start(); } catch (Throwable ignored) { }
                    }
                    builtW = 0;
                    build(root.getWidth(), root.getHeight());
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    // ───────────────────────── universal: asistente, color, foto, CarPlay ─────────────────────────

    static final String[] ACCENT_NAMES = {"StradaOS", "Rojo", "Naranja", "Amarillo", "Verde", "Turquesa", "Azul", "Morado", "Rosa", "Plata"};
    static final int[] ACCENT_COLORS = {0xFFFE3211, 0xFFE3262F, 0xFFFF7A1A, 0xFFF5B800, 0xFF2DBE6C, 0xFF14B8B0, 0xFF2F7CF6, 0xFF8E5CF7, 0xFFE8457C, 0xFF9AA3AF};
    static final String[] CARPLAY_PKGS = {"com.zjinnova.zlink", "com.zjinnova.zlink5", "com.autokit", "cn.manstep.phonemirrorBox",
            "cn.manstep.phonemirrorBox.nomap", "com.suding.speedplay", "com.tlink.carplay", "com.syu.carlink", "com.carlinkit.autokit"};

    private void rebuild() {
        builtW = 0;
        if (root.getWidth() > 0) build(root.getWidth(), root.getHeight());
    }

    private void applyAccent() {
        RED = prefs.getInt("accent", 0xFFFE3211);
        int r = (RED >> 16) & 255, g = (RED >> 8) & 255, b = RED & 255;
        int br = (BG >> 16) & 255, bgc = (BG >> 8) & 255, bb = BG & 255, pct = LIGHT ? 16 : 18;
        RED_DARK = 0xFF000000 | ((r * pct / 100 + br * (100 - pct) / 100) << 16) | ((g * pct / 100 + bgc * (100 - pct) / 100) << 8) | (b * pct / 100 + bb * (100 - pct) / 100);
    }

    /** Usuario que actualiza desde una versión anterior: conserva su carro. Usuario nuevo: asistente. */
    private void migrateOrSetup() {
        if (prefs.getBoolean("setup_done", false)) return;
        boolean upgrade = false;
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            upgrade = pi.lastUpdateTime - pi.firstInstallTime > 60000;
        } catch (Throwable ignored) { }
        if (upgrade) {
            prefs.edit().putBoolean("setup_done", true).apply();
        } else {
            h.postDelayed(new Runnable() { public void run() { wizard(); } }, 600);
        }
    }

    private void wizard() {
        new AlertDialog.Builder(this, dlg())
            .setTitle("Bienvenido a StradaOS")
            .setMessage("Vamos a dejar el launcher a tu gusto: tu nombre, tu vehículo, el color y la foto. Toma menos de un minuto y lo puedes cambiar después en el engranaje ⚙.")
            .setPositiveButton("Empezar", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    ask("user_name", "¿Cómo te llamas?", "", new Runnable() { public void run() {
                    ask("car_name", "Nombre del vehículo (ej.: Mazda 2, Spark GT)", "Mi vehículo", new Runnable() { public void run() {
                    ask("car_tag", "Etiqueta corta (versión o placa). Déjalo vacío si no quieres", "", new Runnable() { public void run() {
                    chooseAccent(new Runnable() { public void run() {
                        prefs.edit().putBoolean("setup_done", true).apply();
                        rebuild();
                        pickPhoto();
                    }});
                    }});
                    }});
                    }});
                }
            })
            .setNegativeButton("Después", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { prefs.edit().putBoolean("setup_done", true).apply(); }
            })
            .show();
    }

    private void ask(final String key, String title, String def, final Runnable next) {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setSingleLine(true);
        et.setText(prefs.getString(key, def));
        et.setSelection(et.getText().length());
        FrameLayout wrap = new FrameLayout(this);
        int pad = (int) (20 * s);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(et);
        new AlertDialog.Builder(this, dlg())
            .setTitle(title)
            .setView(wrap)
            .setCancelable(false)
            .setPositiveButton("Siguiente", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    prefs.edit().putString(key, et.getText().toString().trim()).apply();
                    if (next != null) next.run();
                }
            })
            .show();
    }

    private void chooseAccent(final Runnable next) {
        new AlertDialog.Builder(this, dlg())
            .setTitle("Color de acento")
            .setAdapter(new BaseAdapter() {
                public int getCount() { return ACCENT_NAMES.length; }
                public Object getItem(int p) { return ACCENT_NAMES[p]; }
                public long getItemId(int p) { return p; }
                public View getView(int p, View cv, ViewGroup parent) {
                    LinearLayout row = new LinearLayout(Main.this);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    int pad = (int) (14 * s);
                    row.setPadding(pad * 2, pad, pad, pad);
                    View dot = new View(Main.this);
                    GradientDrawable g = new GradientDrawable();
                    g.setShape(GradientDrawable.OVAL);
                    g.setColor(ACCENT_COLORS[p]);
                    dot.setBackground(g);
                    row.addView(dot, new LinearLayout.LayoutParams((int) (28 * s), (int) (28 * s)));
                    TextView t = tv(ACCENT_NAMES[p], 17, TEXT, false);
                    t.setPadding(pad, 0, 0, 0);
                    row.addView(t);
                    return row;
                }
            }, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    prefs.edit().putInt("accent", ACCENT_COLORS[w]).apply();
                    applyAccent();
                    if (next != null) next.run(); else rebuild();
                }
            })
            .setCancelable(next == null)
            .show();
    }

    // ── foto del vehículo ──

    private static final int REQ_PHOTO = 41;

    private void photoMenu() {
        final boolean has = CarPhoto.load(this) != null;
        String[] opts = has ? new String[]{"Elegir otra foto", "Quitar la foto"} : new String[]{"Elegir foto"};
        new AlertDialog.Builder(this, dlg())
            .setTitle("Foto del vehículo")
            .setItems(opts, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    if (w == 0) pickPhoto();
                    else { CarPhoto.delete(Main.this); rebuild(); }
                }
            }).show();
    }

    private void pickPhoto() {
        new AlertDialog.Builder(this, dlg())
            .setTitle("¿De dónde sacamos la foto?")
            .setMessage("Consejo: usa una foto del carro de lado o de frente con fondo liso (blanco, gris o cielo). El fondo se quita solo.")
            .setPositiveButton("Memoria / USB", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { browseImages(); }
            })
            .setNeutralButton("Galería", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    try {
                        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                        i.setType("image/*");
                        startActivityForResult(Intent.createChooser(i, "Elige la foto"), REQ_PHOTO);
                    } catch (Throwable e) { browseImages(); }
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PHOTO || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        processPhoto(new CarPhoto.Opener() {
            public InputStream open() throws Exception { return getContentResolver().openInputStream(uri); }
        });
    }

    private void browseImages() {
        final ProgressDialogLite pd = new ProgressDialogLite("Buscando fotos…");
        new Thread(new Runnable() {
            public void run() {
                final List<File> found = new ArrayList<File>();
                File ext = android.os.Environment.getExternalStorageDirectory();
                String[] roots = {ext.getAbsolutePath(), "/mnt", "/storage", "/udisk", "/mnt/usb_storage"};
                for (String r : roots) scanImages(new File(r), 4, found);
                Collections.sort(found, new Comparator<File>() {
                    public int compare(File a, File b) { long x = b.lastModified() - a.lastModified(); return x > 0 ? 1 : x < 0 ? -1 : 0; }
                });
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (found.isEmpty()) {
                            toast("No encontré fotos (.jpg/.png). Cópialas a la memoria USB o a Download.");
                            return;
                        }
                        final int n = Math.min(found.size(), 80);
                        String[] names = new String[n];
                        for (int i = 0; i < n; i++) names[i] = found.get(i).getName() + "  ·  " + found.get(i).getParentFile().getName();
                        new AlertDialog.Builder(Main.this, dlg())
                            .setTitle("Elige la foto")
                            .setItems(names, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    final File f = found.get(w);
                                    processPhoto(new CarPhoto.Opener() {
                                        public InputStream open() throws Exception { return new java.io.FileInputStream(f); }
                                    });
                                }
                            }).setNegativeButton("Cancelar", null).show();
                    }
                });
            }
        }).start();
    }

    private final java.util.HashSet<String> scanned = new java.util.HashSet<String>();

    private void scanImages(File dir, int depth, List<File> out) {
        if (depth < 0 || dir == null || out.size() > 400) return;
        try {
            String canon = dir.getCanonicalPath();
            if (!scanned.add(canon) && depth < 4) return;
        } catch (Throwable ignored) { }
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName().toLowerCase(ES);
            if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))
                    && f.length() > 15000 && !n.startsWith(".")) out.add(f);
        }
        for (File f : fs) {
            String n = f.getName();
            if (f.isDirectory() && !n.startsWith(".") && !"Android".equals(n) && !"LOST.DIR".equals(n)
                    && !n.equalsIgnoreCase("thumbnails") && !"StradaDiag".equals(n)) scanImages(f, depth - 1, out);
        }
    }

    private void processPhoto(final CarPhoto.Opener opener) {
        final ProgressDialogLite pd = new ProgressDialogLite("Quitando el fondo…");
        new Thread(new Runnable() {
            public void run() {
                Bitmap src = null, cut = null;
                String err = null;
                boolean already = false;
                try {
                    src = CarPhoto.decode(opener);
                    already = CarPhoto.hasTransparency(src);
                    cut = already ? src : CarPhoto.removeBackground(src, 1);
                } catch (Throwable e) {
                    err = e.getMessage();
                }
                final Bitmap fsrc = src, fcut = cut;
                final String ferr = err;
                final boolean falready = already;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (fsrc == null) { toast("No se pudo abrir la imagen" + (ferr != null ? ": " + ferr : "")); return; }
                        previewPhoto(fsrc, fcut, 1, falready);
                    }
                });
            }
        }).start();
    }

    private void previewPhoto(final Bitmap src, final Bitmap cut, final int strength, boolean already) {
        final Bitmap shown = CarPhoto.fitToCard(cut != null ? cut : src);
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(CARD);
        int pad = (int) (16 * s);
        box.setPadding(pad, pad, pad, pad);
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(shown);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(iv, new FrameLayout.LayoutParams((int) (396 * s), (int) (240 * s)));
        String title = already ? "La foto ya trae fondo transparente"
                : cut == null ? "No se detectó un fondo liso (se usará tal cual)"
                : "Vista previa · nivel " + (strength + 1) + " de 4" + (strength >= 2 ? " (también quita el reflejo del piso)" : "");
        AlertDialog.Builder b = new AlertDialog.Builder(this, dlg())
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Usar esta", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    if (CarPhoto.save(Main.this, shown)) { toast("Foto guardada"); rebuild(); }
                    else toast("No se pudo guardar la foto");
                }
            })
            .setNegativeButton("Cancelar", null);
        if (!already && strength < 3) {
            b.setNeutralButton("Quitar más fondo", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    final ProgressDialogLite pd = new ProgressDialogLite("Quitando más fondo…");
                    new Thread(new Runnable() {
                        public void run() {
                            final Bitmap c2 = CarPhoto.removeBackground(src, strength + 1);
                            runOnUiThread(new Runnable() {
                                public void run() { pd.dismiss(); previewPhoto(src, c2 != null ? c2 : cut, strength + 1, false); }
                            });
                        }
                    }).start();
                }
            });
        }
        b.show();
    }

    /** Diálogo de espera simple (compatible con Android 4.1). */
    private class ProgressDialogLite {
        private final AlertDialog d;
        ProgressDialogLite(String msg) {
            LinearLayout l = new LinearLayout(Main.this);
            l.setGravity(Gravity.CENTER_VERTICAL);
            int pad = (int) (22 * s);
            l.setPadding(pad, pad, pad, pad);
            l.addView(new android.widget.ProgressBar(Main.this));
            TextView t = tv(msg, 16, TEXT, false);
            t.setPadding(pad, 0, 0, 0);
            l.addView(t);
            d = new AlertDialog.Builder(Main.this, dlg()).setView(l).setCancelable(false).create();
            d.show();
        }
        void dismiss() { try { d.dismiss(); } catch (Throwable ignored) { } }
    }

    // ── CarPlay (requiere adaptador USB con su app) ──

    /** App de proyección integrada en la pantalla (ZLink, CarLink, TLink, AutoKit…): suele manejar Android Auto y CarPlay. */
    private String nativeProjectionApp() {
        for (String p : CARPLAY_PKGS) if (installed(p)) return p;
        ResolveInfo r = find("carplay|zlink|carlink|autokit|tlink|speedplay", "carplay|zlink|carlink|autokit|tlink");
        // EasyConnection/Carbit es solo espejo de pantalla: no es Android Auto ni CarPlay
        if (r != null && r.activityInfo.packageName.startsWith("net.easyconn")) return null;
        return r != null ? r.activityInfo.packageName : null;
    }

    private String carPlayApp() {
        String saved = prefs.getString("carplay", null);
        if (saved != null) return saved;
        return nativeProjectionApp();
    }

    /** Nombre visible de un paquete o de un componente guardado. */
    private String labelOf(String pkgOrComponent) {
        try {
            String pkg = pkgOrComponent.contains("/") ? ComponentName.unflattenFromString(pkgOrComponent).getPackageName() : pkgOrComponent;
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable e) {
            return "App elegida";
        }
    }

    private void openCarPlay() {
        if (launchSaved("carplay")) return;
        String p = nativeProjectionApp();
        if (p != null && launchPkg(p)) return;
        new AlertDialog.Builder(this, dlg())
            .setTitle("Apple CarPlay")
            .setMessage("CarPlay necesita un chip de autenticación de Apple dentro del equipo, así que no se puede activar solo con una app.\n\n• Si tu pantalla trae CarPlay integrado, deja presionado este botón y elige su app (ZLink, CarLink, TLink…).\n• Si no, puedes usar un adaptador CarPlay USB para pantallas Android (tipo Carlinkit/AutoKit): instala su app y este botón la abrirá.")
            .setPositiveButton("Entendido", null)
            .setNeutralButton("Elegir app", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { pickFor("carplay", "CarPlay"); }
            }).show();
    }

    // ───────────────────────── tema (oscuro / claro / automático) ─────────────────────────

    static int dlg() { return LIGHT ? AlertDialog.THEME_DEVICE_DEFAULT_LIGHT : AlertDialog.THEME_DEVICE_DEFAULT_DARK; }

    private void applyTheme() {
        String mode = prefs.getString("theme", "dark");
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        LIGHT = "light".equals(mode) || ("auto".equals(mode) && hr >= 6 && hr < 18);
        if (LIGHT) {
            BG = 0xFFE9ECF1; CARD = 0xFFFFFFFF; INNER = 0xFFF1F3F7; BTN = 0xFFE2E6EC; DOCK = 0xFFDDE1E8;
            TEXT = 0xFF14161B; MUTED = 0xFF6B7280; SOFT = 0xFF3A404C; CIRCLE = 0xFFFFFFFF; ICON = 0xFF2B303A;
        } else {
            BG = 0xFF15171C; CARD = 0xFF1E2128; INNER = 0xFF262A33; BTN = 0xFF2A2E37; DOCK = 0xFF0D0E11;
            TEXT = 0xFFF2F3F5; MUTED = 0xFF8B9099; SOFT = 0xFFC5C8CE; CIRCLE = 0xFF23262E; ICON = 0xFFFFFFFF;
        }
    }

    // ───────────────────────── clima ─────────────────────────

    private void maybeWeather() {
        if (!prefs.getBoolean("weather_on", true) || Double.isNaN(wLat)) { showWeather(); return; }
        if (SystemClock.elapsedRealtime() - weatherAt < 30 * 60 * 1000L && weatherNow != null) return;
        weatherAt = SystemClock.elapsedRealtime();
        Weather.fetch(this, wLat, wLon, new Weather.Listener() {
            public void onWeather(final Weather.Now n, String err) {
                h.post(new Runnable() { public void run() {
                    if (n != null) weatherNow = n; else weatherAt = SystemClock.elapsedRealtime() - 25 * 60 * 1000L; // reintenta en 5 min
                    showWeather();
                }});
            }
        });
    }

    private void refreshWeatherNow() { weatherAt = 0; weatherNow = null; maybeWeather(); }

    private void showWeather() {
        if (weatherBox == null) return;
        if (!prefs.getBoolean("weather_on", true) || weatherNow == null) { weatherBox.setVisibility(View.GONE); return; }
        weatherBox.setVisibility(View.VISIBLE);
        weatherIc.setImageBitmap(asset("ic/" + Weather.icon(weatherNow.code, weatherNow.day) + ".png"));
        weatherTv.setText(Math.round(weatherNow.temp) + "°" + weatherNow.unit);
        weatherCityTv.setText(weatherNow.city != null ? weatherNow.city : Weather.describe(weatherNow.code));
    }

    // ───────────────────────── panel de ajustes ─────────────────────────

    interface IntCb { void on(int i); }

    static final String[] CAT_NAMES = {"Volver al inicio", "Tu vehículo", "Apariencia", "Conexión", "Accesos y apps", "Mapa y clima", "Música", "Sistema", "Acerca de"};
    static final String[] CAT_ICONS = {"arrow_back", "directions_car", "palette", "usb", "apps", "map", "music_note", "tune", "info"};

    private void closeSettings() {
        settingsCat = -1;
        if (settingsView != null) { root.removeView(settingsView); settingsView = null; }
    }

    private void showSettings(int cat) {
        if (cat == 0) { closeSettings(); return; }
        settingsCat = cat;
        if (settingsView != null) root.removeView(settingsView);
        settingsView = new FrameLayout(this);
        settingsView.setBackgroundColor(BG);
        settingsView.setClickable(true);
        root.addView(settingsView, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout panel = new FrameLayout(this);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams((int) (1024 * s), (int) (600 * s));
        plp.gravity = Gravity.CENTER;
        settingsView.addView(panel, plp);

        // barra lateral
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setBackground(round(CARD, 20));
        side.setPadding((int) (10 * s), (int) (14 * s), (int) (10 * s), (int) (14 * s));
        place(panel, side, 16, 16, 240, 568);
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding((int) (8 * s), 0, 0, (int) (12 * s));
        ImageView logo = new ImageView(this);
        logo.setImageBitmap(asset("logo.png"));
        brand.addView(logo, new LinearLayout.LayoutParams((int) (30 * s), (int) (30 * s)));
        TextView bt = tv("StradaOS", 18, TEXT, true);
        bt.setPadding((int) (10 * s), 0, 0, 0);
        brand.addView(bt);
        side.addView(brand);
        for (int i = 0; i < CAT_NAMES.length; i++) {
            final int idx = i;
            boolean sel = i == cat;
            LinearLayout it = new LinearLayout(this);
            it.setGravity(Gravity.CENTER_VERTICAL);
            it.setPadding((int) (12 * s), 0, (int) (8 * s), 0);
            if (sel) it.setBackground(round(RED_DARK, 12));
            ImageView ic = icon(CAT_ICONS[i], 20);
            if (sel) ic.setColorFilter(RED);
            else if (ICON == 0xFFFFFFFF) ic.setAlpha(0.7f);
            it.addView(ic, new LinearLayout.LayoutParams((int) (20 * s), (int) (20 * s)));
            TextView lt = tv(CAT_NAMES[i], 14, sel ? RED : SOFT, sel);
            lt.setPadding((int) (12 * s), 0, 0, 0);
            it.addView(lt);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, (int) (46 * s));
            ilp.bottomMargin = (int) (4 * s);
            side.addView(it, ilp);
            if (i == 0) {
                View d = new View(this);
                d.setBackgroundColor(BTN);
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, Math.max(1, (int) s));
                dl.topMargin = (int) (2 * s);
                dl.bottomMargin = (int) (8 * s);
                side.addView(d, dl);
            }
            it.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showSettings(idx); }
            });
        }

        // contenido
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        place(panel, sc, 272, 16, 736, 568);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding((int) (8 * s), (int) (6 * s), (int) (8 * s), (int) (24 * s));
        sc.addView(body, new FrameLayout.LayoutParams(-1, -2));
        TextView title = tv(CAT_NAMES[cat], 24, TEXT, true);
        title.setPadding((int) (4 * s), 0, 0, (int) (14 * s));
        body.addView(title);

        switch (cat) {
            case 1: catVehicle(body); break;
            case 2: catLook(body); break;
            case 3: catConnection(body); break;
            case 4: catApps(body); break;
            case 5: catMapWeather(body); break;
            case 6: catMusic(body); break;
            case 7: catSystem(body); break;
            case 8: catAbout(body); break;
        }
    }

    // ── piezas de diseño ──

    private LinearLayout section(LinearLayout body, String name) {
        TextView h2 = tv(name.toUpperCase(ES), 11, MUTED, true);
        h2.setPadding((int) (6 * s), (int) (10 * s), 0, (int) (8 * s));
        body.addView(h2);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(CARD, 16));
        card.setPadding((int) (18 * s), (int) (4 * s), (int) (18 * s), (int) (4 * s));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = (int) (10 * s);
        body.addView(card, lp);
        return card;
    }

    private LinearLayout rowBase(LinearLayout card, String title, String sub) {
        if (card.getChildCount() > 0) {
            View d = new View(this);
            d.setBackgroundColor(BTN);
            card.addView(d, new LinearLayout.LayoutParams(-1, Math.max(1, (int) s)));
        }
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, (int) (12 * s), 0, (int) (12 * s));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = tv(title, 15, TEXT, true);
        t.setSingleLine(false);
        col.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView st = tv(sub, 12, MUTED, false);
            st.setSingleLine(false);
            st.setPadding(0, (int) (3 * s), 0, 0);
            col.addView(st);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    private void row(LinearLayout card, String title, String sub, String value, final Runnable onClick) {
        LinearLayout r = rowBase(card, title, sub);
        if (value != null) {
            TextView v = tv(value, 14, MUTED, false);
            v.setGravity(Gravity.RIGHT);
            v.setPadding((int) (12 * s), 0, (int) (4 * s), 0);
            r.addView(v, new LinearLayout.LayoutParams((int) (220 * s), -2));
        }
        if (onClick != null) {
            ImageView ch = icon("chevron_right", 20);
            ch.setAlpha(0.6f);
            r.addView(ch, new LinearLayout.LayoutParams((int) (20 * s), (int) (20 * s)));
            r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onClick.run(); } });
        }
    }

    private void segmented(LinearLayout card, String title, String sub, String[] opts, int sel, final IntCb cb) {
        LinearLayout r = rowBase(card, title, sub);
        LinearLayout g = new LinearLayout(this);
        g.setBackground(round(BTN, 18));
        int p = (int) (3 * s);
        g.setPadding(p, p, p, p);
        for (int i = 0; i < opts.length; i++) {
            final int idx = i;
            TextView o = tv(opts[i], 13, i == sel ? Color.WHITE : SOFT, i == sel);
            o.setGravity(Gravity.CENTER);
            o.setPadding((int) (14 * s), (int) (7 * s), (int) (14 * s), (int) (7 * s));
            if (i == sel) o.setBackground(round(RED, 15));
            o.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { cb.on(idx); } });
            g.addView(o);
        }
        r.addView(g);
    }

    private void toggle(LinearLayout card, String title, String sub, final boolean on, final IntCb cb) {
        LinearLayout r = rowBase(card, title, sub);
        FrameLayout sw = new FrameLayout(this);
        sw.setBackground(round(on ? RED : BTN, 14));
        View knob = new View(this);
        GradientDrawable k = new GradientDrawable();
        k.setShape(GradientDrawable.OVAL);
        k.setColor(Color.WHITE);
        knob.setBackground(k);
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams((int) (22 * s), (int) (22 * s));
        klp.gravity = (on ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL;
        klp.leftMargin = klp.rightMargin = (int) (3 * s);
        sw.addView(knob, klp);
        r.addView(sw, new LinearLayout.LayoutParams((int) (50 * s), (int) (28 * s)));
        r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { cb.on(on ? 0 : 1); } });
    }

    private String appLabel(String key, String def) {
        String c = prefs.getString(key, null);
        return c == null ? def : labelOf(c);
    }

    private Runnable edit(final String key, final String title, final String def) {
        return new Runnable() { public void run() { editText(key, title, def); } };
    }

    // ── categorías ──

    private void catVehicle(LinearLayout body) {
        LinearLayout c = section(body, "Datos");
        row(c, "Tu nombre", "Aparece en el saludo de la pantalla de inicio", prefs.getString("user_name", ""), edit("user_name", "Tu nombre", ""));
        row(c, "Nombre del vehículo", null, prefs.getString("car_name", "Mi vehículo"), edit("car_name", "Nombre del vehículo", "Mi vehículo"));
        row(c, "Etiqueta", "Versión o placa. Déjala vacía para ocultarla", prefs.getString("car_tag", ""), edit("car_tag", "Etiqueta (vacío para ocultarla)", ""));
        LinearLayout f = section(body, "Foto");
        row(f, "Foto del vehículo", "Se recorta y se le quita el fondo automáticamente", CarPhoto.load(this) != null ? "Configurada" : "Sin foto",
            new Runnable() { public void run() { photoMenu(); } });
    }

    private void catLook(LinearLayout body) {
        LinearLayout c = section(body, "Tema");
        String t = prefs.getString("theme", "dark");
        segmented(c, "Modo", "Automático: claro de 6 a.m. a 6 p.m.", new String[]{"Oscuro", "Claro", "Automático"},
            "light".equals(t) ? 1 : "auto".equals(t) ? 2 : 0, new IntCb() { public void on(int i) {
                prefs.edit().putString("theme", i == 1 ? "light" : i == 2 ? "auto" : "dark").apply();
                applyTheme(); applyAccent(); rebuild();
            }});
        LinearLayout a = section(body, "Color de acento");
        LinearLayout r = rowBase(a, "Color", "Botones, anillo del reproductor, ruta y etiquetas");
        LinearLayout dots = new LinearLayout(this);
        int cur = prefs.getInt("accent", 0xFFFE3211);
        for (int i = 0; i < ACCENT_COLORS.length; i++) {
            final int col = ACCENT_COLORS[i];
            View d = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(col);
            if (col == cur) g.setStroke((int) (3 * s), TEXT);
            d.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (28 * s), (int) (28 * s));
            lp.leftMargin = (int) (8 * s);
            dots.addView(d, lp);
            d.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                prefs.edit().putInt("accent", col).apply(); applyAccent(); rebuild();
            }});
        }
        r.addView(dots);
        LinearLayout h = section(body, "Reloj");
        String ck = prefs.getString("clock", "auto");
        segmented(h, "Formato de hora", null, new String[]{"Auto", "12 h", "24 h"}, "12".equals(ck) ? 1 : "24".equals(ck) ? 2 : 0,
            new IntCb() { public void on(int i) {
                prefs.edit().putString("clock", i == 1 ? "12" : i == 2 ? "24" : "auto").apply(); updateClock(); showSettings(2);
            }});
    }

    private void catConnection(LinearLayout body) {
        LinearLayout c = section(body, "Celular");
        String chosen = prefs.getString("conn_app", null);
        row(c, "Cómo conectar el celular", "La app que se abre al conectar por USB", chosen != null ? labelOf(chosen) : "Preguntar",
            new Runnable() { public void run() { chooseConnection(null); } });
        row(c, "App de Android Auto", "Botón naranja de la tarjeta central", appLabel("aa", "Automática"),
            new Runnable() { public void run() { pickFor("aa", "Android Auto"); } });
        row(c, "App de CarPlay", "Requiere pantalla con CarPlay o un adaptador", appLabel("carplay", carPlayApp() != null ? labelOf(carPlayApp()) : "Ninguna"),
            new Runnable() { public void run() { pickFor("carplay", "CarPlay"); } });
        LinearLayout hl = section(body, "Ayuda");
        row(hl, "El celular dice «Ninguna aplic. funciona…»", "Cómo evitar que EasyConnection tome el USB", null,
            new Runnable() { public void run() { usbHelp(); } });
    }

    private void catApps(LinearLayout body) {
        LinearLayout c = section(body, "Accesos de la barra inferior");
        String[][] items = {{"radio", "Radio"}, {"bt", "Bluetooth"}, {"cam", "Cámara"}, {"web", "Navegador"}, {"files", "Archivos"}};
        for (final String[] it : items) {
            row(c, it[1], null, appLabel(it[0], "Automático"), new Runnable() { public void run() { pickFor(it[0], it[1]); } });
        }
        LinearLayout r = section(body, "Restablecer");
        row(r, "Restablecer accesos", "Vuelve a buscar las apps de fábrica automáticamente", null, new Runnable() { public void run() {
            SharedPreferences.Editor e = prefs.edit();
            for (String k : new String[]{"radio", "bt", "cam", "files", "settings", "aa", "web", "carplay"}) e.remove(k);
            e.apply();
            toast("Accesos restablecidos");
            refreshStates();
            showSettings(4);
        }});
    }

    private void catMapWeather(LinearLayout body) {
        LinearLayout w = section(body, "Clima");
        toggle(w, "Mostrar el clima", "Arriba, junto al saludo (necesita internet)", prefs.getBoolean("weather_on", true), new IntCb() { public void on(int i) {
            prefs.edit().putBoolean("weather_on", i == 1).apply(); refreshWeatherNow(); showSettings(5);
        }});
        segmented(w, "Unidad", null, new String[]{"°C", "°F"}, "F".equals(prefs.getString("temp_unit", "C")) ? 1 : 0, new IntCb() { public void on(int i) {
            prefs.edit().putString("temp_unit", i == 1 ? "F" : "C").apply(); refreshWeatherNow(); showSettings(5);
        }});
        String manual = prefs.getString("city_manual", "");
        row(w, "Ciudad", "Vacío = automática por GPS", manual.length() > 0 ? manual : (weatherNow != null && weatherNow.city != null ? weatherNow.city + " (GPS)" : "Automática"),
            edit("city_manual", "Ciudad (vacío = automática por GPS)", ""));
        LinearLayout m = section(body, "Mapa");
        segmented(m, "Brillo del mapa", "Solo en modo oscuro", new String[]{"Bajo", "Medio", "Alto"}, prefs.getInt("map_bright", 1), new IntCb() { public void on(int i) {
            prefs.edit().putInt("map_bright", i).apply(); rebuild();
        }});
        row(m, "Diagnóstico del mapa", "Fuentes probadas y errores", null, new Runnable() { public void run() {
            new AlertDialog.Builder(Main.this, dlg()).setTitle("Diagnóstico del mapa")
                .setMessage(liveMap != null ? liveMap.diag() : "Mapa no disponible").setPositiveButton("OK", null).show();
        }});
        row(m, "Fuente del mapa (avanzado)", "URL propia con {z}/{x}/{y}", prefs.getString("map_url", "").length() > 0 ? "Personalizada" : "Automática",
            edit("map_url", "URL de teselas {z}/{x}/{y} (vacío = automático)", ""));
    }

    private void catMusic(LinearLayout body) {
        LinearLayout c = section(body, "Pista en reproducción");
        row(c, "Carátula de Android Auto", "Lee la notificación de Open Headunit (accesibilidad)", NotifArt.running ? "Activada" : "Desactivada",
            new Runnable() { public void run() { openAccessibility(); } });
        if (SDK >= 18) row(c, "Música de cualquier app", "Android 5+: acceso a notificaciones", MediaListener.connected ? "Activado" : "Desactivado",
            new Runnable() { public void run() {
                try { startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Throwable e) { toast("No se pudo abrir el ajuste"); }
            }});
        row(c, "Diagnóstico del reproductor", "Fuente activa y lector de notificaciones", null, new Runnable() { public void run() { showPlayerDiag(); } });
        LinearLayout a = section(body, "Avanzado");
        row(a, "Avisos extra de música", "Nombres de avisos (broadcast) de otros equipos", prefs.getString("extra_actions", "").length() > 0 ? "Configurados" : "Ninguno",
            edit("extra_actions", "Acciones de aviso extra (separadas por coma)", ""));
    }

    private void catSystem(LinearLayout body) {
        LinearLayout c = section(body, "Equipo");
        row(c, "Ajustes de Android", null, null, new Runnable() { public void run() {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }});
        row(c, "Asistente de configuración inicial", "Nombre, vehículo, color y foto", null, new Runnable() { public void run() { closeSettings(); wizard(); } });
        LinearLayout d = section(body, "Soporte");
        row(d, "Exportar diagnóstico", "Guarda información de las apps de audio en la memoria interna", null, new Runnable() { public void run() { runDiag(); } });
    }

    private void catAbout(LinearLayout body) {
        LinearLayout c = section(body, "StradaOS");
        LinearLayout r = rowBase(c, "StradaOS · Auto Head Unit UI", "Gratis y de código abierto. Una nueva vida para las pantallas Android del carro.");
        ImageView logo = new ImageView(this);
        logo.setImageBitmap(asset("logo.png"));
        r.addView(logo, 0, new LinearLayout.LayoutParams((int) (56 * s), (int) (56 * s)));
        ((LinearLayout.LayoutParams) r.getChildAt(1).getLayoutParams()).leftMargin = (int) (14 * s);
        row(c, "Versión", null, Updates.currentVersion(this), null);
        row(c, "Android de la pantalla", null, android.os.Build.VERSION.RELEASE + " (API " + SDK + ")", null);

        LinearLayout u = section(body, "Actualizaciones");
        final boolean fd = Updates.fromFdroid(this);
        if (fd) row(u, "Instalado desde F-Droid", "Las actualizaciones llegan por la app de F-Droid", null, null);
        toggle(u, "Comprobar actualizaciones automáticamente", "Al abrir StradaOS con internet, una vez al día",
            prefs.getBoolean("auto_update", !fd), new IntCb() { public void on(int i) {
                prefs.edit().putBoolean("auto_update", i == 1).apply(); showSettings(8);
            }});
        row(u, "Canal de actualizaciones", null, "GitHub Releases", null);
        long last = prefs.getLong("upd_last", 0);
        row(u, "Buscar actualizaciones", last == 0 ? "Aún no se ha comprobado"
                : "Última comprobación: " + new SimpleDateFormat("yyyy-MM-dd HH:mm", ES).format(new Date(last)), null,
            new Runnable() { public void run() { checkUpdates(true); } });

        LinearLayout k = section(body, "Comunidad");
        row(k, "Dar una estrella en GitHub", "Ayuda a que más personas encuentren StradaOS", "★",
            new Runnable() { public void run() { linkDialog("Dar una estrella ⭐", "Abre el repositorio y toca «Star». ¡Gracias!", Updates.REPO_URL); } });
        row(k, "Donar", "Si StradaOS revivió tu pantalla, invítame un café", "☕",
            new Runnable() { public void run() { linkDialog("Apoya el proyecto ☕", "StradaOS es gratis. Las donaciones ayudan a mantenerlo y a probarlo en más pantallas.", Updates.donateUrl(Main.this)); } });
        row(k, "Reportar un problema o sugerir algo", "Crea un «issue» en GitHub (adjunta el diagnóstico)", null,
            new Runnable() { public void run() { linkDialog("Reportar un problema", "Cuenta tu modelo de pantalla y su versión de Android. Puedes adjuntar la carpeta StradaDiag (Sistema → Exportar diagnóstico).", Updates.ISSUES_URL); } });
        row(k, "Código fuente", Updates.REPO_URL.replace("https://", ""), null,
            new Runnable() { public void run() { linkDialog("Código fuente", "Licencia GPL-3.0", Updates.REPO_URL); } });

        LinearLayout l = section(body, "Créditos y licencias");
        row(l, "Licencia", "GNU General Public License v3.0", null, null);
        row(l, "Mapas", "© colaboradores de OpenStreetMap, OpenTopoMap, Esri", null, null);
        row(l, "Clima", "Open-Meteo.com (CC BY 4.0)", null, null);
        row(l, "Android Auto", "Open Headunit (GPL-3.0) · Android Auto es marca de Google", null, null);
        row(l, "Códigos QR", "QR Code generator library · Project Nayuki (MIT)", null, null);
        row(l, "Íconos", "Material Symbols (Apache 2.0)", null, null);
        row(l, "Certificados raíz", "Mozilla CA (certifi, MPL-2.0)", null, null);
    }

    // ───────────────────────── actualizaciones, estrella y donaciones ─────────────────────────

    private boolean updBusy, promptShown;

    /** Al volver al inicio: busca versión nueva (1 vez al día) o, si toca, el recuadro «¿Te gusta StradaOS?». */
    private void autoChecks() {
        if (!hasWindowFocus() || settingsView != null || drawer != null) return;
        long now = System.currentTimeMillis();
        boolean auto = prefs.getBoolean("auto_update", !Updates.fromFdroid(this));
        if (auto && now - prefs.getLong("upd_last", 0) > 20L * 3600 * 1000) { checkUpdates(false); return; }
        // recuadro tipo SimpMusic: tras 5 días de uso y 15 aperturas, y luego cada 45 días si eligió «Más tarde»
        int opens = prefs.getInt("opens", 0) + 1;
        long first = prefs.getLong("first_use", 0);
        SharedPreferences.Editor e = prefs.edit().putInt("opens", opens);
        if (first == 0) e.putLong("first_use", first = now);
        e.apply();
        long next = prefs.getLong("enjoy_next", first + 5L * 24 * 3600 * 1000);
        if (!promptShown && opens >= 15 && now >= next && !prefs.getBoolean("enjoy_done", false)) {
            promptShown = true;
            enjoyDialog();
        }
    }

    private void checkUpdates(final boolean manual) {
        if (updBusy) return;
        updBusy = true;
        if (manual) toast("Buscando actualizaciones…");
        Updates.check(this, new Updates.Listener() {
            public void onResult(final Updates.Release r, final String err) {
                h.post(new Runnable() { public void run() {
                    updBusy = false;
                    if (settingsView != null && settingsCat == 8) showSettings(8);
                    if (r != null) {
                        if (manual || !r.version.equals(prefs.getString("upd_skip", ""))) updateDialog(r);
                    } else if (manual) {
                        if (err != null) linkDialog("No se pudo comprobar", "Esta pantalla no logró conectarse con GitHub (" + shortNetErr(err) + "). Puedes revisar las versiones desde tu celular:", Updates.RELEASES_URL);
                        else toast("Tienes la última versión (" + Updates.currentVersion(Main.this) + ")");
                    }
                }});
            }
        });
    }

    private static String shortNetErr(String e) {
        if (e.contains("SSL") || e.contains("handshake")) return "cifrado no compatible";
        if (e.contains("UnknownHost")) return "sin internet";
        if (e.contains("Timeout")) return "tiempo agotado";
        return "error de red";
    }

    private void updateDialog(final Updates.Release r) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * s);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView v = tv("Tienes la " + Updates.currentVersion(this) + " · nueva: " + r.version, 13, MUTED, false);
        box.addView(v);
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        TextView notes = tv(Updates.plain(r.notes).length() > 0 ? Updates.plain(r.notes) : "Mejoras y correcciones.", 14, TEXT, false);
        notes.setSingleLine(false);
        notes.setPadding(0, (int) (10 * s), 0, (int) (10 * s));
        sc.addView(notes);
        box.addView(sc, new LinearLayout.LayoutParams(-1, (int) (200 * s)));
        AlertDialog.Builder b = new AlertDialog.Builder(this, dlg())
            .setTitle("Nueva versión de StradaOS")
            .setView(box)
            .setPositiveButton(r.apkUrl != null ? "Descargar e instalar" : "Ver en GitHub", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    if (r.apkUrl != null) downloadUpdate(r); else linkDialog("StradaOS " + r.version, "Descarga la nueva versión desde:", r.pageUrl);
                }
            })
            .setNeutralButton("Omitir esta versión", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { prefs.edit().putString("upd_skip", r.version).apply(); }
            })
            .setNegativeButton("Más tarde", null);
        b.show();
    }

    /** Descarga el APK y abre el instalador; si esta pantalla no puede conectarse, muestra el código QR. */
    private void downloadUpdate(final Updates.Release r) {
        if (!unknownSourcesOk()) return;
        final ProgressDialogLite pd = new ProgressDialogLite("Descargando StradaOS " + r.version + "…");
        new Thread(new Runnable() { public void run() {
            File out = null;
            try {
                byte[] data = Net.get(r.apkUrl, 60000);
                File dir = getExternalFilesDir(null);
                if (dir == null) dir = getFilesDir();
                dir.mkdirs();
                out = new File(dir, "StradaOS-" + r.version + ".apk");
                java.io.FileOutputStream os = new java.io.FileOutputStream(out);
                os.write(data);
                os.close();
                out.setReadable(true, false);
            } catch (Throwable e) { out = null; }
            final File f = out;
            runOnUiThread(new Runnable() { public void run() {
                pd.dismiss();
                if (f != null) installFile(f, "StradaOS " + r.version);
                else linkDialog("Descárgala desde tu celular", "Esta pantalla no pudo descargar el archivo. Escanea el código, descarga el APK y pásalo por USB.", r.pageUrl);
            }});
        }}).start();
    }

    private void enjoyDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (22 * s);
        box.setPadding(pad, pad, pad, (int) (6 * s));
        ImageView logo = new ImageView(this);
        logo.setImageBitmap(asset("logo.png"));
        box.addView(logo, new LinearLayout.LayoutParams((int) (64 * s), (int) (64 * s)));
        TextView t = tv("¿Disfrutando StradaOS?", 20, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, (int) (14 * s), 0, (int) (8 * s));
        box.addView(t);
        TextView m = tv("Si te gusta usar StradaOS, dale una estrella en GitHub. Y si revivió tu pantalla, considera invitarme un café ☕", 14, SOFT, false);
        m.setSingleLine(false);
        m.setGravity(Gravity.CENTER);
        box.addView(m);
        new AlertDialog.Builder(this, dlg())
            .setView(box)
            .setPositiveButton("Dar una estrella", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    prefs.edit().putBoolean("enjoy_done", true).apply();
                    linkDialog("Dar una estrella ⭐", "Abre el repositorio y toca «Star». ¡Gracias!", Updates.REPO_URL);
                }
            })
            .setNeutralButton("Donar", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    prefs.edit().putBoolean("enjoy_done", true).apply();
                    linkDialog("Apoya el proyecto ☕", "StradaOS es gratis. Las donaciones ayudan a mantenerlo.", Updates.donateUrl(Main.this));
                }
            })
            .setNegativeButton("Más tarde", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    prefs.edit().putLong("enjoy_next", System.currentTimeMillis() + 45L * 24 * 3600 * 1000).apply();
                }
            })
            .show();
    }

    /** Enlace con código QR: en pantallas viejas el navegador no abre GitHub, pero el celular sí. */
    private void linkDialog(String title, String msg, final String url) {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (20 * s);
        box.setPadding(pad, pad, pad, (int) (6 * s));
        ImageView qr = new ImageView(this);
        qr.setImageBitmap(qrBitmap(url, (int) (200 * s)));
        box.addView(qr, new LinearLayout.LayoutParams((int) (200 * s), (int) (200 * s)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (20 * s), 0, 0, 0);
        TextView m = tv(msg, 14, SOFT, false);
        m.setSingleLine(false);
        col.addView(m);
        TextView hint = tv("Escanéalo con la cámara de tu celular o entra a:", 12, MUTED, false);
        hint.setSingleLine(false);
        hint.setPadding(0, (int) (12 * s), 0, (int) (4 * s));
        col.addView(hint);
        TextView link = tv(url.replace("https://", ""), 13, RED, true);
        link.setSingleLine(false);
        col.addView(link);
        box.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        new AlertDialog.Builder(this, dlg())
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Abrir aquí", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                    catch (Throwable e) { toast("No hay navegador disponible"); }
                }
            })
            .setNegativeButton("Cerrar", null)
            .show();
    }

    private static Bitmap qrBitmap(String text, int size) {
        io.nayuki.qrcodegen.QrCode q = io.nayuki.qrcodegen.QrCode.encodeText(text, io.nayuki.qrcodegen.QrCode.Ecc.MEDIUM);
        int border = 2, n = q.size + border * 2;
        int scale = Math.max(1, size / n);
        Bitmap b = Bitmap.createBitmap(n * scale, n * scale, Bitmap.Config.RGB_565);
        b.eraseColor(Color.WHITE);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint();
        p.setColor(Color.BLACK);
        for (int y = 0; y < q.size; y++)
            for (int x = 0; x < q.size; x++)
                if (q.getModule(x, y)) c.drawRect((x + border) * scale, (y + border) * scale, (x + border + 1) * scale, (y + border + 1) * scale, p);
        return b;
    }

    // ───────────────────────── apps ─────────────────────────

    private void loadApps() {
        apps.clear();
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        final PackageManager pm = getPackageManager();
        for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
            if (!getPackageName().equals(r.activityInfo.packageName)) apps.add(r);
        }
        final HashMap<ResolveInfo, String> labels = new HashMap<ResolveInfo, String>();
        for (ResolveInfo r : apps) labels.put(r, String.valueOf(r.loadLabel(pm)));
        Collections.sort(apps, new Comparator<ResolveInfo>() {
            public int compare(ResolveInfo a, ResolveInfo b) {
                return labels.get(a).compareToIgnoreCase(labels.get(b));
            }
        });
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshStates() {
        if (aaBtnTv == null) return;
        String nat = nativeProjectionApp();
        String aaSaved = prefs.getString("aa", null);
        String chosen = prefs.getString("conn_app", null);
        if (aaSaved != null) aaBtnTv.setText(labelOf(aaSaved));
        else if (chosen != null && installed(chosen)) aaBtnTv.setText(labelOf(chosen));
        else if (connCandidates().size() > 1) aaBtnTv.setText("Toca para elegir");
        else if (nat != null) aaBtnTv.setText(labelOf(nat));
        else if (installed(PKG_HUR)) aaBtnTv.setText("Open Headunit · USB");
        else aaBtnTv.setText("Toca para instalar");
        String cpApp = carPlayApp();
        cpSubTv.setText(cpApp != null ? labelOf(cpApp) : "Pantalla o adaptador");
        updatePlayIcon();
    }

    private ResolveInfo find(String labelKeys, String pkgKeys) {
        PackageManager pm = getPackageManager();
        if (labelKeys.length() > 0) {
            for (String k : labelKeys.split("\\|")) {
                for (ResolveInfo r : apps) {
                    String l = String.valueOf(r.loadLabel(pm)).toLowerCase(ES);
                    if (k.length() <= 2 ? l.equals(k) || l.startsWith(k + " ") : l.contains(k)) return r;
                }
            }
        }
        if (pkgKeys.length() > 0) {
            for (String k : pkgKeys.split("\\|")) {
                for (ResolveInfo r : apps) {
                    if (r.activityInfo.packageName.toLowerCase(ES).contains(k)) return r;
                }
            }
        }
        return null;
    }

    private boolean launchSaved(String key) {
        String c = prefs.getString(key, null);
        if (c == null) return false;
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(ComponentName.unflattenFromString(c));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            return true;
        } catch (Exception e) {
            prefs.edit().remove(key).apply();
            return false;
        }
    }

    private void launch(ResolveInfo r) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(new ComponentName(r.activityInfo.packageName, r.activityInfo.name));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("No se pudo abrir la app");
        }
    }

    private boolean launchPkg(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void openSlot(int idx) {
        String key = SLOTS[idx][0];
        if (launchSaved(key)) return;
        if ("settings".equals(key)) {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        ResolveInfo r = find(SLOTS[idx][2], SLOTS[idx][3]);
        if (r != null) {
            launch(r);
        } else {
            toast("No encontré la app de " + SLOTS[idx][1] + ". Elígela de la lista.");
            pickFor(key, SLOTS[idx][1]);
        }
    }

    private void pickFor(final String key, String name) {
        final PackageManager pm = getPackageManager();
        final List<String> labels = new ArrayList<String>();
        labels.add("↺  Automático (por defecto)");
        for (ResolveInfo r : apps) labels.add(String.valueOf(r.loadLabel(pm)));
        new AlertDialog.Builder(this, dlg())
            .setTitle("¿Qué app abre «" + name + "»?")
            .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    if (which == 0) {
                        prefs.edit().remove(key).apply();
                        toast("Restablecido");
                    } else {
                        ResolveInfo r = apps.get(which - 1);
                        prefs.edit().putString(key,
                            new ComponentName(r.activityInfo.packageName, r.activityInfo.name).flattenToString()).apply();
                        toast("Guardado: " + labels.get(which));
                    }
                    refreshStates();
                    if (settingsView != null) showSettings(settingsCat);
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    // ───────────────────────── Android Auto y navegador ─────────────────────────

    private void openAndroidAuto() {
        if (launchSaved("aa")) return;
        String chosen = prefs.getString("conn_app", null);
        if (chosen != null && launchPkg(chosen)) return;
        List<String> c = connCandidates();
        if (chosen == null && c.size() > 1) {
            chooseConnection(new Runnable() { public void run() { openAndroidAuto(); } });
            return;
        }
        if (c.size() == 1 && launchPkg(c.get(0))) return;
        File apk = findApkOnUsb("revived");
        if (apk != null && compatible(apk)) {
            installFile(apk, "Open Headunit");
            return;
        }
        new AlertDialog.Builder(this, dlg())
            .setTitle("Android Auto")
            .setMessage("Esta pantalla no trae Android Auto integrado.\n\nInstala la app gratuita «Open Headunit» (github.com/andreknieriem/headunit-revived): copia su APK en la memoria USB o en Download y vuelve a tocar el botón. Luego conecta el celular por cable USB.")
            .setPositiveButton("Entendido", null)
            .setNeutralButton("Elegir app", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { pickFor("aa", "Android Auto"); }
            }).show();
    }

    // ── método de conexión del celular (lo elige el usuario) ──

    /** Apps de esta pantalla que pueden conectar el celular, en orden sugerido. */
    private List<String> connCandidates() {
        List<String> out = new ArrayList<String>();
        if (installed(PKG_HUR)) out.add(PKG_HUR);
        String nat = nativeProjectionApp();
        if (nat != null && !out.contains(nat)) out.add(nat);
        for (String p : CARPLAY_PKGS) if (installed(p) && !out.contains(p)) out.add(p);
        for (String p : USB_HOGS) if (installed(p) && !out.contains(p)) out.add(p);
        return out;
    }

    private String connDescription(String pkg) {
        if (PKG_HUR.equals(pkg)) return "Android Auto por cable USB (necesita la app Android Auto en el celular)";
        if (pkg.startsWith("net.easyconn") || pkg.startsWith("com.carbit")) return "Espejo de pantalla (necesita la app CarbitLink en el celular)";
        return "Android Auto / CarPlay integrados en la pantalla";
    }

    private void chooseConnection(final Runnable after) {
        final List<String> c = connCandidates();
        final List<String> items = new ArrayList<String>();
        for (String p : c) items.add(labelOf(p) + "\n" + connDescription(p));
        items.add("Otra app…");
        new AlertDialog.Builder(this, dlg())
            .setTitle("¿Cómo quieres conectar tu celular?")
            .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    if (w == c.size()) { pickFor("aa", "Android Auto"); return; }
                    prefs.edit().putString("conn_app", c.get(w)).remove("aa").apply();
                    usbWarned = false;
                    toast("Conexión: " + labelOf(c.get(w)) + ". Cámbiala dejando presionado el botón.");
                    refreshStates();
                    if (settingsView != null) showSettings(settingsCat);
                    if (!USB_HOG_SET.contains(c.get(w))) guardUsb();
                    if (after != null) after.run();
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }


    private void openBrowser() {
        if (launchSaved("web")) return;
        for (String p : new String[]{"org.mozilla.firefox", "org.mozilla.fennec_fdroid", "com.opera.mini.native",
                "com.opera.browser", PKG_WEB}) {
            if (launchPkg(p)) return;
        }
        File apk = findApkOnUsb("firefox", "navegador", "browser", "opera");
        if (apk != null && compatible(apk)) {
            installFile(apk, "el navegador");
            return;
        }
        if (apk != null) toast("«" + apk.getName() + "» necesita un Android más nuevo que el de esta pantalla. Abro el navegador de fábrica.");
        if (launchPkg("com.android.browser")) return;
        ResolveInfo r = find("navegador|browser|internet", "browser");
        if (r != null) { launch(r); return; }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.google.com"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast("No encontré un navegador. Déjalo presionado para elegir uno.");
        }
    }

    /** true si el APK se puede instalar en esta versión de Android. */
    private boolean compatible(File apk) {
        try {
            return getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0) != null;
        } catch (Throwable e) {
            return true;
        }
    }

    private boolean unknownSourcesOk() {
        if (SDK >= 26) {
            try {
                if (Modern.canInstall(this)) return true;
                toast("Permite «Instalar apps desconocidas» para StradaOS y vuelve a tocar.");
                return false;
            } catch (Throwable e) { return true; }
        }
        try {
            if (Settings.Secure.getInt(getContentResolver(), Settings.Secure.INSTALL_NON_MARKET_APPS) == 1) return true;
        } catch (Exception e) {
            return true; // si no se puede leer, deja que el instalador decida
        }
        toast("Activa «Orígenes desconocidos» y vuelve a tocar.");
        try {
            startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
        return false;
    }

    private void installFromAsset(final String asset, final String name) {
        if (installing || !unknownSourcesOk()) return;
        installing = true;
        toast("Preparando " + name + "…");
        new Thread(new Runnable() {
            public void run() {
                File out = null;
                try {
                    File dir = getExternalFilesDir(null);
                    if (dir == null) dir = getFilesDir();
                    dir.mkdirs();
                    out = new File(dir, new File(asset).getName());
                    InputStream in = getAssets().open(asset);
                    OutputStream os = new FileOutputStream(out);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    os.close();
                    in.close();
                    out.setReadable(true, false);
                    dir.setExecutable(true, false);
                } catch (Exception e) {
                    out = null;
                }
                final File f = out;
                runOnUiThread(new Runnable() {
                    public void run() {
                        installing = false;
                        if (f == null) toast("No se pudo preparar " + name);
                        else installFile(f, name);
                    }
                });
            }
        }).start();
    }

    private void installFile(File f, String name) {
        if (!unknownSourcesOk()) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            if (SDK >= 24) {
                i.setDataAndType(ApkProvider.share(f), "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                i.setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive");
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("No se pudo abrir el instalador para " + name);
        }
    }

    private File findApkOnUsb(String... keys) {
        String[] roots = {"/storage", "/mnt", "/mnt/media_rw", "/mnt/usb_storage", "/udisk", "/sdcard"};
        File dl = new File(android.os.Environment.getExternalStorageDirectory(), "Download");
        File hit0 = scanDir(dl, keys, 0);
        if (hit0 != null) return hit0;
        for (String r : roots) {
            File[] vols = new File(r).listFiles();
            if (vols == null) continue;
            for (File v : vols) {
                File hit = scanDir(v, keys, 2);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private File scanDir(File dir, String[] keys, int depth) {
        if (depth < 0 || dir == null || !dir.isDirectory()) return null;
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            String n = f.getName().toLowerCase(ES);
            if (f.isFile() && n.endsWith(".apk")) {
                for (String k : keys) if (n.contains(k)) return f;
            }
        }
        for (File f : fs) {
            if (f.isDirectory() && !f.getName().startsWith(".") && !"Android".equals(f.getName())) {
                File hit = scanDir(f, keys, depth - 1);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    // ───────────────────────── cajón de apps ─────────────────────────

    private void openDrawer() {
        if (drawer != null) return;
        loadApps();
        drawer = new FrameLayout(this);
        drawer.setBackgroundColor((BG & 0x00FFFFFF) | 0xF5000000);
        drawer.setClickable(true);

        TextView title = tv("Aplicaciones  ·  Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")", 18, TEXT, true);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2);
        tlp.leftMargin = (int) (28 * s);
        tlp.topMargin = (int) (20 * s);
        drawer.addView(title, tlp);

        TextView close = tv("Cerrar  ✕", 15, SOFT, false);
        close.setGravity(Gravity.CENTER);
        close.setBackground(round(BTN, 18));
        close.setPadding((int) (16 * s), (int) (8 * s), (int) (16 * s), (int) (8 * s));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2);
        clp.gravity = Gravity.TOP | Gravity.RIGHT;
        clp.rightMargin = (int) (24 * s);
        clp.topMargin = (int) (16 * s);
        drawer.addView(close, clp);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeDrawer(); }
        });

        final PackageManager pm = getPackageManager();
        GridView grid = new GridView(this);
        grid.setNumColumns(6);
        grid.setVerticalSpacing((int) (18 * s));
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setAdapter(new BaseAdapter() {
            public int getCount() { return apps.size(); }
            public Object getItem(int p) { return apps.get(p); }
            public long getItemId(int p) { return p; }
            public View getView(int p, View cv, ViewGroup parent) {
                LinearLayout cell;
                ImageView im;
                TextView lb;
                if (cv == null) {
                    cell = new LinearLayout(Main.this);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(Gravity.CENTER_HORIZONTAL);
                    cell.setPadding(0, (int) (6 * s), 0, (int) (6 * s));
                    im = new ImageView(Main.this);
                    cell.addView(im, new LinearLayout.LayoutParams((int) (56 * s), (int) (56 * s)));
                    lb = tv("", 13, SOFT, false);
                    lb.setGravity(Gravity.CENTER);
                    lb.setPadding((int) (4 * s), (int) (8 * s), (int) (4 * s), 0);
                    cell.addView(lb, new LinearLayout.LayoutParams(-1, -2));
                } else {
                    cell = (LinearLayout) cv;
                    im = (ImageView) cell.getChildAt(0);
                    lb = (TextView) cell.getChildAt(1);
                }
                ResolveInfo r = apps.get(p);
                Drawable d = r.loadIcon(pm);
                im.setImageDrawable(d);
                lb.setText(r.loadLabel(pm));
                return cell;
            }
        });
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int p, long id) {
                launch(apps.get(p));
            }
        });
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(-1, -1);
        glp.topMargin = (int) (76 * s);
        glp.leftMargin = (int) (16 * s);
        glp.rightMargin = (int) (16 * s);
        drawer.addView(grid, glp);

        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
    }

    private void closeDrawer() {
        if (drawer != null) {
            root.removeView(drawer);
            drawer = null;
        }
    }
}
