package ru.vorontsov.splitter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.widget.*;
import java.io.*;
import java.util.*;

public class MainActivity extends Activity {
    static final int PICK = 1;
    static final String DIR = "Разделитель";
    EditText size; TextView info; ProgressBar bar; Button bFile, bApp;
    volatile boolean busy;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(p, p, p, p);
        TextView t = new TextView(this); t.setText("Размер части, МБ:"); l.addView(t);
        size = new EditText(this); size.setInputType(InputType.TYPE_CLASS_NUMBER); size.setText("20"); l.addView(size);
        bFile = new Button(this); bFile.setText("Выбрать файл"); l.addView(bFile);
        bApp = new Button(this); bApp.setText("Выбрать приложение"); l.addView(bApp);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); bar.setMax(1000); l.addView(bar);
        info = new TextView(this); info.setPadding(0, p, 0, 0); info.setTextIsSelectable(true);
        info.setText("Части сохраняются в «Загрузки/" + DIR + "» с именами имя.001, имя.002 …"); l.addView(info);
        ScrollView sv = new ScrollView(this); sv.addView(l); setContentView(sv);
        bFile.setOnClickListener(v -> {
            if (busy) return;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
            startActivityForResult(i, PICK);
        });
        bApp.setOnClickListener(v -> { if (!busy) pickApp(); });
    }

    long partBytes() {
        long mb; try { mb = Long.parseLong(size.getText().toString().trim()); } catch (Exception e) { mb = 20; }
        if (mb < 1) mb = 1;
        return mb * 1024 * 1024;
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        String name = "file"; long len = -1;
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), s = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0) name = c.getString(n);
                if (s >= 0 && !c.isNull(s)) len = c.getLong(s);
            }
        } catch (Exception ignored) {}
        final String fn = name; final long fl = len;
        run(Collections.singletonList(new Src(fn, fl, () -> getContentResolver().openInputStream(u))));
    }

    void pickApp() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        List<String[]> rows = new ArrayList<>();
        for (ApplicationInfo a : apps) rows.add(new String[]{ pm.getApplicationLabel(a) + "\n" + a.packageName, a.packageName });
        rows.sort((x, y) -> x[0].compareToIgnoreCase(y[0]));
        String[] labels = new String[rows.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = rows.get(i)[0];
        new AlertDialog.Builder(this).setTitle("Приложение (" + labels.length + ")").setItems(labels, (d, w) -> {
            try {
                ApplicationInfo a = pm.getApplicationInfo(rows.get(w)[1], 0);
                List<Src> list = new ArrayList<>();
                list.add(fileSrc(a.packageName + ".apk", a.sourceDir));
                if (a.splitSourceDirs != null) for (String s : a.splitSourceDirs)
                    list.add(fileSrc(a.packageName + "__" + new File(s).getName(), s));
                run(list);
            } catch (Exception e) { info.setText("Ошибка: " + e); }
        }).show();
    }

    Src fileSrc(String name, String path) { File f = new File(path); return new Src(name, f.length(), () -> new FileInputStream(f)); }

    interface Opener { InputStream open() throws IOException; }
    static class Src { String name; long len; Opener o; Src(String n, long l, Opener o) { name = n; len = l; this.o = o; } }

    void run(List<Src> list) {
        busy = true; bFile.setEnabled(false); bApp.setEnabled(false); bar.setProgress(0);
        long part = partBytes();
        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            try {
                for (Src s : list) out.append(split(s, part)).append('\n');
                out.append("\nПапка: Загрузки/").append(DIR).append("\nСобрать на ПК: copy /b имя.001+имя.002+… имя  (или cat имя.* > имя)");
                String r = "Готово!\n" + out;
                runOnUiThread(() -> { info.setText(r); bar.setProgress(1000); });
            } catch (Exception e) {
                runOnUiThread(() -> info.setText("Ошибка: " + e.getMessage()));
            } finally {
                runOnUiThread(() -> { busy = false; bFile.setEnabled(true); bApp.setEnabled(true); });
            }
        }).start();
    }

    String split(Src s, long part) throws IOException {
        byte[] buf = new byte[1 << 20];
        long done = 0; int idx = 0;
        try (InputStream in = new BufferedInputStream(s.o.open(), 1 << 20)) {
            int n = in.read(buf);
            while (n > 0) {
                idx++;
                String pn = s.name + String.format(Locale.US, ".%03d", idx);
                try (OutputStream os = openOut(pn)) {
                    long w = 0;
                    while (n > 0) {
                        int take = (int) Math.min(n, part - w);
                        os.write(buf, 0, take); w += take; done += take;
                        if (take < n) { System.arraycopy(buf, take, buf, 0, n - take); n -= take; break; }
                        n = in.read(buf);
                        final long d = done; final int i2 = idx;
                        runOnUiThread(() -> {
                            info.setText(s.name + ": часть " + i2 + ", " + (d >> 20) + " МБ" + (s.len > 0 ? " из " + (s.len >> 20) + " МБ" : ""));
                            if (s.len > 0) bar.setProgress((int) (d * 1000 / s.len));
                        });
                        if (w >= part) break;
                    }
                }
            }
        }
        return s.name + " → " + idx + " ч. (" + (done >> 20) + " МБ)";
    }

    OutputStream openOut(String name) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIR);
            Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new IOException("не удалось создать " + name);
            return getContentResolver().openOutputStream(u);
        }
        File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR); d.mkdirs();
        return new FileOutputStream(new File(d, name));
    }
}
