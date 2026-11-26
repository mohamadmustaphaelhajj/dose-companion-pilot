package com.dosecompanion.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Base64;
import com.dosecompanion.core.LabelDraft;
import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The phone talks only to the configured bridge. No provider API key is stored on the phone. */
final class CloudReader implements AutoCloseable {
    private final String endpoint, token;
    private volatile HttpURLConnection active;
    private volatile boolean cancelled;
    CloudReader(Context context) throws IOException {
        var settings = context.getSharedPreferences("cloud_bridge", Context.MODE_PRIVATE);
        endpoint = settings.getString("endpoint", "http://127.0.0.1:8787"); token = settings.getString("token", "");
        validateEndpoint(endpoint);
        if (token.length() < 32) throw new IOException("Cloud connection needs setup. Open Cloud connection first.");
    }
    static void validateEndpoint(String value) throws IOException {
        try {
            URI uri = new URI(value);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath() == null || uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IOException("Use the server’s base address.");
            boolean local = uri.getHost().equals("127.0.0.1") || uri.getHost().equals("localhost");
            if (!uri.getScheme().equals("https") && !(local && uri.getScheme().equals("http")))
                throw new IOException("Use HTTPS, or the localhost USB development bridge.");
        } catch (URISyntaxException | RuntimeException invalid) { throw new IOException("Enter a valid server address."); }
    }
    JsonObject settings() throws IOException {
        try { return JsonParser.parseString(request("/v1/config", null)).getAsJsonObject(); }
        catch (RuntimeException invalid) { throw new IOException("The reader did not return valid settings. If the hosted service is starting, wait briefly and check the connection again. No photos were sent."); }
    }
    LabelDraft read(List<byte[]> images) throws IOException {
        if (images.size() < 2 || images.size() > 4) throw new IOException("Choose two to four photos.");
        JsonObject body = new JsonObject(); body.addProperty("consent", true); JsonArray encoded = new JsonArray();
        for (byte[] image : images) encoded.add(Base64.encodeToString(image, Base64.NO_WRAP));
        body.add("images", encoded);
        return ExtractJson.parse(request("/v1/extract", body.toString()));
    }
    static byte[] photo(Bitmap source) {
        int edge = Math.max(source.getWidth(), source.getHeight()); Bitmap image = source;
        if (edge > 2000) image = Bitmap.createScaledBitmap(source, source.getWidth()*2000/edge, source.getHeight()*2000/edge, true);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); image.compress(Bitmap.CompressFormat.JPEG, 94, output); return output.toByteArray();
        } finally { if (image != source) image.recycle(); }
    }
    private String request(String path, String body) throws IOException {
        if (!BuildConfig.CLOUD_READER_ENABLED) throw new IOException("Cloud reading is not enabled in this build.");
        if (cancelled) throw new IOException("Cancelled.");
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint.replaceAll("/+$", "") + path).openConnection();
        active = connection;
        try {
            if (cancelled) throw new IOException("Cancelled.");
            if (body != null) connection.setRequestProperty("X-Scan-Request-Id", UUID.randomUUID().toString().replace("-", ""));
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setConnectTimeout(12000); connection.setReadTimeout(body == null ? 90000 : 170000);
            connection.setRequestProperty("Authorization", "Bearer " + token); connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(data); }
                finally { Arrays.fill(data, (byte)0); }
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                String code = "";
                try (InputStream error = connection.getErrorStream()) {
                    if (error != null) {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int n;
                        while ((n = error.read(buffer)) != -1) { if (bytes.size()+n > 16384) break; bytes.write(buffer,0,n); }
                        code = JsonParser.parseString(bytes.toString("UTF-8")).getAsJsonObject().get("error").getAsString();
                    }
                } catch (IOException | RuntimeException ignored) { }
                throw new IOException(errorMessage(status, code));
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = input.read(buffer)) != -1) { if (output.size()+n > 16384) throw new IOException("Server reply exceeded the expected limit."); output.write(buffer,0,n); }
            }
            if (cancelled) throw new IOException("Cancelled."); return output.toString(StandardCharsets.UTF_8.name());
        } catch (SocketTimeoutException timeout) {
            throw new IOException(body == null ? "Connection check timed out. The hosted reader may still be starting. Check internet access and the server address, then check again. For USB development, check the Mac and cable."
                : "Reading timed out. Your photos are kept. The provider may have processed this request; no retry was sent. Check the connection before explicitly trying again.");
        } catch (javax.net.ssl.SSLException tls) {
            throw new IOException("Secure connection failed. Check the backend HTTPS certificate; do not switch a public server to HTTP.");
        } catch (ConnectException | UnknownHostException unavailable) {
            throw new IOException("Cannot reach the reader. Check internet access and the hosted server address. For USB development, keep the Mac awake, reconnect USB and run server/pair_android.py. Then check the connection again.");
        } catch (SocketException interrupted) {
            throw new IOException(body == null ? "Connection check was interrupted. Check internet access and server availability; for USB development, reconnect the cable."
                : "The connection was interrupted. Your photos are kept. The request may have reached the provider; no automatic retry was sent. Check internet access and server availability before explicitly trying again.");
        } finally { connection.disconnect(); if (active == connection) active = null; }
    }
    static String errorMessage(int status, String code) {
        switch (code) {
            case "provider_quota_exhausted": return "OpenAI API credits are exhausted. Add credits to the API organization, allow the balance to update, then explicitly retry. Your photos are kept.";
            case "provider_auth_failed": return "The server’s OpenAI API key was rejected. Ask the pilot administrator to update its server configuration. Your photos are kept.";
            case "provider_access_denied": case "provider_model_unavailable": return "The API project cannot access the configured model. Check model access and OPENAI_MODEL on the server. Your photos are kept.";
            case "provider_rate_limited": return "OpenAI is temporarily rate limiting requests. Wait before explicitly retrying. No automatic retry was sent.";
            case "incomplete_model_result": return "The cloud reading stopped before completing. No fields were accepted. Your photos are kept; you can explicitly retry or enter details manually.";
            case "model_could_not_read_label": return "The model could not read the label. Inspect and replace blurry or missing views, then send again or enter details manually.";
            case "invalid_model_result": return "The model returned an unusable result. No fields were accepted. Your photos are kept; retry explicitly or enter details manually.";
            case "duplicate_submission": return "This submission was already received. It was not sent to OpenAI again. A new explicit attempt may incur another charge.";
            case "provider_timeout_or_connection_failed": return "The backend could not finish contacting OpenAI. Your photos are kept. The request may have been processed; no automatic retry was sent.";
            case "provider_unavailable": return "OpenAI is temporarily unavailable. Your photos are kept. Wait before explicitly trying again.";
            case "provider_request_rejected": return "OpenAI rejected the server request. Check the backend model configuration before retrying. Your photos are kept.";
            case "photo_too_large": case "request_too_large": return "The selected photos are too large. Replace them with closer views of the relevant label sections.";
        }
        if (status == 401) return "Phone pairing was rejected. Check the pairing token in Cloud connection settings or ask the pilot administrator for a new token.";
        if (status == 503) return "The reader is starting or its API setup is incomplete. Wait briefly and check the connection again; contact the pilot administrator if it persists. Your photos are kept.";
        if (status == 429) return "The reader is busy. Wait briefly; no automatic retry was sent.";
        return "The backend could not complete this reading. Your photos are kept; check the connection before trying again.";
    }
    void cancel() { cancelled = true; HttpURLConnection connection = active; if (connection != null) connection.disconnect(); }
    @Override public void close() { cancel(); }
}
