package com.detector.esp.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Pengelola bahasa — Bahasa Indonesia / English
 */
public class Lang {

    private static boolean isEnglish = false;
    private static final String PREFS = "esp_settings";

    public static void load(Context ctx) {
        isEnglish = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("lang_en", false);
    }

    public static void setEnglish(Context ctx, boolean en) {
        isEnglish = en;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("lang_en", en).apply();
    }

    public static boolean isEnglish() { return isEnglish; }

    // ====== Label Deteksi ======
    private static final String[] LABELS_ID = {
        "Orang", "Sepeda", "Mobil", "Motor", "Pesawat", "Bus", "Kereta", "Truk",
        "Perahu", "Lampu Lalu Lintas", "Hidran", "Rambu Stop", "Parkometer", "Bangku", "Burung", "Kucing",
        "Anjing", "Kuda", "Domba", "Sapi", "Gajah", "Beruang", "Zebra", "Jerapah",
        "Tas Ransel", "Payung", "Tas Tangan", "Dasi", "Koper", "Frisbee", "Ski", "Snowboard",
        "Bola", "Layang-layang", "Tongkat Baseball", "Sarung Tangan", "Skateboard", "Papan Selancar", "Raket Tenis", "Botol",
        "Gelas Anggur", "Cangkir", "Garpu", "Pisau", "Sendok", "Mangkuk", "Pisang", "Apel",
        "Sandwich", "Jeruk", "Brokoli", "Wortel", "Hot Dog", "Pizza", "Donat", "Kue",
        "Kursi", "Sofa", "Tanaman Pot", "Kasur", "Meja Makan", "Toilet", "TV", "Laptop",
        "Mouse", "Remote", "Keyboard", "HP", "Microwave", "Oven", "Pemanggang", "Wastafel",
        "Kulkas", "Buku", "Jam", "Vas", "Gunting", "Boneka Beruang", "Pengering Rambut", "Sikat Gigi"
    };

    private static final String[] LABELS_EN = {
        "Person", "Bicycle", "Car", "Motorcycle", "Airplane", "Bus", "Train", "Truck",
        "Boat", "Traffic Light", "Fire Hydrant", "Stop Sign", "Parking Meter", "Bench", "Bird", "Cat",
        "Dog", "Horse", "Sheep", "Cow", "Elephant", "Bear", "Zebra", "Giraffe",
        "Backpack", "Umbrella", "Handbag", "Tie", "Suitcase", "Frisbee", "Skis", "Snowboard",
        "Ball", "Kite", "Baseball Bat", "Baseball Glove", "Skateboard", "Surfboard", "Tennis Racket", "Bottle",
        "Wine Glass", "Cup", "Fork", "Knife", "Spoon", "Bowl", "Banana", "Apple",
        "Sandwich", "Orange", "Broccoli", "Carrot", "Hot Dog", "Pizza", "Donut", "Cake",
        "Chair", "Couch", "Potted Plant", "Bed", "Dining Table", "Toilet", "TV", "Laptop",
        "Mouse", "Remote", "Keyboard", "Cell Phone", "Microwave", "Oven", "Toaster", "Sink",
        "Refrigerator", "Book", "Clock", "Vase", "Scissors", "Teddy Bear", "Hair Dryer", "Toothbrush"
    };

    public static String[] getLabels() { return isEnglish ? LABELS_EN : LABELS_ID; }

    // ====== Teks UI ======
    public static String settings()     { return isEnglish ? "ESP Detection Settings" : "Pengaturan ESP"; }
    public static String person()       { return isEnglish ? "Person" : "Manusia"; }
    public static String vehicle()      { return isEnglish ? "Vehicles (Car/Motorcycle/Bus/Truck/Bicycle/Boat)" : "Kendaraan (Mobil/Motor/Bus/Truk/Sepeda/Perahu)"; }
    public static String animal()       { return isEnglish ? "Animals (Cat/Dog/Bird/Horse/Cow...)" : "Hewan (Kucing/Anjing/Burung/Kuda/Sapi...)"; }
    public static String objects()      { return isEnglish ? "Objects (Phone/Backpack/Bottle/Chair...)" : "Objek (HP/Tas/Botol/Kursi...)"; }
    public static String enableAll()    { return isEnglish ? "Enable All" : "Aktifkan Semua"; }
    public static String disableAll()   { return isEnglish ? "Disable All" : "Nonaktifkan Semua"; }
    public static String satellite()    { return isEnglish ? "Satellite Monitor" : "Monitor Satelit"; }
    public static String language()     { return isEnglish ? "Bahasa: English \u2192 Ganti ke Indonesia" : "Bahasa: Indonesia \u2192 Switch to English"; }
    public static String ok()           { return isEnglish ? "OK" : "Simpan"; }
    public static String cancel()       { return isEnglish ? "Cancel" : "Batal"; }
    public static String locked()       { return isEnglish ? "LOCKED" : "TERKUNCI"; }
    public static String targets()      { return isEnglish ? "Targets" : "Target"; }

    // ====== Teks tambahan ======
    public static String render()       { return "Render"; }
    public static String detect()       { return isEnglish ? "Detect" : "Deteksi"; }
    public static String target()       { return isEnglish ? "Target" : "Target"; }
    public static String espSystem()    { return "[ESP V2]"; }
    public static String waitingGps()   { return isEnglish ? "Waiting for GPS..." : "Menunggu GPS..."; }
    public static String waitingSat()   { return isEnglish ? "Waiting for satellite data..." : "Menunggu data satelit..."; }
    public static String noPermission() { return isEnglish ? "Location permission not granted" : "Izin lokasi belum diberikan"; }
    public static String satDetail()    { return isEnglish ? "\u2501\u2501\u2501 Satellite Details \u2501\u2501\u2501" : "\u2501\u2501\u2501 Detail Satelit \u2501\u2501\u2501"; }
    public static String satTotal(int total, int used) {
        return isEnglish
            ? String.format("Total: %d visible, %d used for fix", total, used)
            : String.format("Total: %d terlihat, %d digunakan untuk fix", total, used);
    }
    public static String satMonitor()   { return isEnglish ? "\uD83D\uDEF0 Satellite Monitor" : "\uD83D\uDEF0 Monitor Satelit"; }
    public static String bootAllOk()    { return isEnglish ? ">>> ALL SYSTEMS OPERATIONAL <<<" : ">>> SEMUA SISTEM BEROPERASI <<<"; }
    public static String appTitle()     { return isEnglish ? "ESP Detection System v2.0" : "ESP Sistem Deteksi v2.0"; }
    public static String camRear()      { return isEnglish ? "Rear camera" : "Kamera belakang"; }
    public static String camZoom()      { return isEnglish ? "Digital zoom" : "Zoom digital"; }
    public static String gpuInit()      { return isEnglish ? "GPU Delegate initialized" : "Delegasi GPU diinisialisasi"; }
    public static String gpsReady()     { return isEnglish ? "Location provider ready" : "Penyedia lokasi siap"; }
    public static String memFree()      { return isEnglish ? "free" : "tersedia"; }
    public static String cores()        { return isEnglish ? "cores" : "inti"; }
}
