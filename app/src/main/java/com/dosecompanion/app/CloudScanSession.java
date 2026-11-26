package com.dosecompanion.app;

import android.app.Application;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import com.dosecompanion.core.LabelDraft;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Memory-only session survives configuration changes. Never retains an Activity, view or saved-state handle. */
public final class CloudScanSession extends AndroidViewModel {
    enum Page { CAPTURE, PHOTOS }
    Page page = Page.CAPTURE;
    int retakeIndex = -1;
    final ArrayList<Bitmap> photos = new ArrayList<>();
    final Object photosLock = new Object();
    final MutableLiveData<Integer> changes = new MutableLiveData<>(0);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile CloudReader reader;
    private volatile int epoch;
    private int revision;
    boolean busy, checking, ready;
    LabelDraft draft;
    long elapsed;
    String message = "Capture the name/strength and full instructions in two to four views.";
    String retention = "Check the cloud connection to view its retention policy.";
    public CloudScanSession(Application application) { super(application); }
    private void changed() { changes.setValue(++revision); }
    void captureMore(int replace) {
        if (busy || draft != null || replace < -1 || replace >= photos.size() || (replace == -1 && photos.size() >= 4)) return;
        retakeIndex = replace; page = Page.CAPTURE; changed();
    }
    void acceptPhoto(Bitmap photo) {
        if (busy || draft != null) { photo.recycle(); return; }
        if (retakeIndex >= 0 && retakeIndex < photos.size()) photos.set(retakeIndex, photo).recycle();
        else if (photos.size() < 4) photos.add(photo);
        else { photo.recycle(); return; }
        retakeIndex = -1;
        page = photos.size() >= 2 ? Page.PHOTOS : Page.CAPTURE;
        changed();
    }
    void reviewPhotos() {
        if (busy || draft != null || photos.size() < 2) return;
        retakeIndex = -1; page = Page.PHOTOS; changed();
    }
    void removeExtra(int index) {
        if (busy || draft != null || index < 2 || index >= photos.size()) return;
        photos.remove(index).recycle(); changed();
    }
    void checkConnection() {
        if (busy || checking || draft != null) return;
        checking = true; ready = false; message = "Preparing the label reader… A sleeping service can take about a minute to start. You can capture photos while waiting."; final int job = epoch; changed();
        worker.execute(() -> {
            boolean valid = false; String policy = retention, outcome;
            try (CloudReader connection = new CloudReader(getApplication())) {
                reader = connection;
                if (job != epoch) { connection.cancel(); return; }
                var config = connection.settings(); policy = config.get("retention_notice").getAsString();
                if (policy.length() > 2000) throw new IOException("The backend sent invalid configuration metadata.");
                valid = config.get("ready").getAsBoolean() && "standard".equals(config.get("retention_policy").getAsString());
                outcome = valid ? "Cloud reader configured. Photos are sent only after consent and pressing Send."
                        : "API setup is needed on the server. Ask the pilot administrator to check its configuration.";
            } catch (IOException failure) { outcome = failure.getMessage(); }
            catch (RuntimeException failure) { outcome = "The backend returned invalid connection settings. Check its configuration."; }
            finally { reader = null; }
            final boolean active = valid; final String text = outcome, notice = policy;
            main.post(() -> { if (epoch == job) { checking = false; ready = active; message = text; retention = notice; changed(); } });
        });
    }
    void upload() {
        if (busy || checking || !ready || page != Page.PHOTOS || photos.size() < 2 || draft != null) return;
        busy = true; final int job = ++epoch; ArrayList<Bitmap> chosen = new ArrayList<>(photos);
        message = "Reading all selected photos together… You can rotate the phone; this request will continue."; changed();
        worker.execute(() -> {
            ArrayList<byte[]> selected = new ArrayList<>(); LabelDraft result = null; String error = null; long start = SystemClock.elapsedRealtime();
            try (CloudReader connection = new CloudReader(getApplication())) {
                reader = connection;
                for (Bitmap photo : chosen) synchronized (photosLock) {
                    if (epoch != job) { connection.cancel(); return; }
                    byte[] jpeg = CloudReader.photo(photo);
                    if (jpeg.length > 3*1024*1024) { Arrays.fill(jpeg,(byte)0); throw new IOException("A photo is too large. Replace it with a closer view of the relevant label section."); }
                    selected.add(jpeg);
                }
                if (epoch != job) { connection.cancel(); return; }
                result = connection.read(selected);
            } catch (IOException failure) { error = failure.getMessage(); }
            catch (RuntimeException failure) { error = "The cloud reader returned an unusable response. Your photos are kept; enter details manually or explicitly retry."; }
            finally { reader = null; for (byte[] bytes : selected) Arrays.fill(bytes,(byte)0); }
            final LabelDraft answer = result; final String failure = error; final long duration = SystemClock.elapsedRealtime()-start;
            main.post(() -> { if (epoch == job) { busy = false; draft = answer; elapsed = duration; message = failure == null ? "Check every returned field against the source photos." : failure; changed(); } });
        });
    }
    void clear() {
        epoch++; CloudReader active = reader; if (active != null) active.cancel();
        busy = false; checking = false; ready = false; draft = null; page = Page.CAPTURE; retakeIndex = -1;
        synchronized (photosLock) { for (Bitmap photo : photos) if (!photo.isRecycled()) photo.recycle(); photos.clear(); }
        message = "Photos and unconfirmed details were cleared when you left the screen. Capture new views to continue.";
        changed();
    }
    @Override protected void onCleared() { clear(); worker.shutdown(); super.onCleared(); }
}
