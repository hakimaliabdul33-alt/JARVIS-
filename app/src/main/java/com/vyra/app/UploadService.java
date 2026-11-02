package com.vyra.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;

import java.io.InputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

public class UploadService extends Service {

    private static final String CHANNEL_ID = "vyra_uploads";
    private static final int NOTIFICATION_ID = 2001;

    private static final String SUPABASE_URL =
            "https://yawwzzzohotimsdmraxp.supabase.co";

    private static final String SUPABASE_BUCKET = "VYRA";

    private static final String SUPABASE_PUBLISHABLE_KEY =
            "sb_publishable_dZ1SWPE0su5qOmlidi4-Pw_WW-O4Dve";

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(10, TimeUnit.MINUTES)
            .build();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId) {

        startForeground(
                NOTIFICATION_ID,
                buildNotification(
                        "Uploading video to VYRA...",
                        0,
                        false
                )
        );

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String uriString = intent.getStringExtra("videoUri");
        String title = intent.getStringExtra("title");

        if (uriString == null || title == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        new Thread(() ->
                upload(
                        Uri.parse(uriString),
                        title
                )
        ).start();

        return START_NOT_STICKY;
    }

    private void upload(Uri videoUri, String title) {

        try {
            String safeTitle = title.replaceAll(
                    "[^a-zA-Z0-9_-]",
                    "_"
            );

            String path =
                    "videos/background/"
                            + System.currentTimeMillis()
                            + "_"
                            + safeTitle
                            + ".mp4";

            String uploadUrl =
                    SUPABASE_URL
                            + "/storage/v1/object/"
                            + SUPABASE_BUCKET
                            + "/"
                            + path;

            RequestBody body = new RequestBody() {

                @Override
                public okhttp3.MediaType contentType() {
                    return okhttp3.MediaType.parse("video/mp4");
                }

                @Override
                public long contentLength()
                        throws IOException {

                    try (
                            android.database.Cursor cursor =
                                    getContentResolver().query(
                                            videoUri,
                                            null,
                                            null,
                                            null,
                                            null
                                    )
                    ) {

                        if (cursor != null) {

                            int sizeIndex =
                                    cursor.getColumnIndex(
                                            android.provider.OpenableColumns.SIZE
                                    );

                            if (cursor.moveToFirst()
                                    && sizeIndex >= 0
                                    && !cursor.isNull(sizeIndex)) {

                                return cursor.getLong(
                                        sizeIndex
                                );
                            }
                        }

                    } catch (Exception ignored) {
                    }

                    return -1;
                }

                @Override
                public void writeTo(
                        BufferedSink sink
                ) throws IOException {

                    long total = contentLength();
                    long uploaded = 0;

                    try (
                            InputStream input =
                                    getContentResolver()
                                            .openInputStream(videoUri)
                    ) {

                        if (input == null) {
                            throw new IOException(
                                    "Unable to open video."
                            );
                        }

                        byte[] buffer =
                                new byte[64 * 1024];

                        int read;

                        while ((read =
                                input.read(buffer)) != -1) {

                            sink.write(
                                    buffer,
                                    0,
                                    read
                            );

                            uploaded += read;

                            if (total > 0) {

                                int percent =
                                        (int) (
                                                uploaded * 100L
                                                        / total
                                        );

                                long remaining =
                                        Math.max(
                                                0,
                                                total - uploaded
                                        );

                                updateNotification(
                                        percent,
                                        uploaded,
                                        remaining
                                );
                            }
                        }
                    }
                }
            };

            Request request =
                    new Request.Builder()
                            .url(uploadUrl)
                            .addHeader(
                                    "apikey",
                                    SUPABASE_PUBLISHABLE_KEY
                            )
                            .addHeader(
                                    "Authorization",
                                    "Bearer "
                                            + SUPABASE_PUBLISHABLE_KEY
                            )
                            .addHeader(
                                    "Content-Type",
                                    "video/mp4"
                            )
                            .addHeader(
                                    "x-upsert",
                                    "true"
                            )
                            .post(body)
                            .build();

            try (
                    Response response =
                            client.newCall(request).execute()
            ) {

                if (!response.isSuccessful()) {

                    String error =
                            response.body() != null
                                    ? response.body().string()
                                    : "HTTP "
                                            + response.code();

                    showFinishedNotification(
                            "Upload failed",
                            error
                    );

                    stopSelf();
                    return;
                }

                showFinishedNotification(
                        "Upload complete",
                        "100% uploaded • 0% remaining"
                );
            }

        } catch (Exception e) {

            String message =
                    e.getMessage() != null
                            ? e.getMessage()
                            : "Video upload failed.";

            showFinishedNotification(
                    "Upload failed",
                    message
            );
        }

        stopSelf();
    }

    private void updateNotification(
            int percent,
            long uploaded,
            long remaining
    ) {

        NotificationManager manager =
                (NotificationManager)
                        getSystemService(
                                NOTIFICATION_SERVICE
                        );

        String text =
                percent
                        + "% uploaded • "
                        + (100 - percent)
                        + "% remaining";

        Notification notification =
                buildNotification(
                        text,
                        percent,
                        true
                );

        manager.notify(
                NOTIFICATION_ID,
                notification
        );
    }

    private Notification buildNotification(
            String text,
            int progress,
            boolean showProgress
    ) {

        android.app.Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= 26) {
            builder =
                    new android.app.Notification.Builder(
                            this,
                            CHANNEL_ID
                    );
        } else {
            builder =
                    new android.app.Notification.Builder(
                            this
                    );
        }

        builder
                .setSmallIcon(
                        android.R.drawable.stat_sys_upload
                )
                .setContentTitle(
                        "VYRA Video Upload"
                )
                .setContentText(text)
                .setOngoing(showProgress)
                .setOnlyAlertOnce(true);

        if (showProgress) {
            builder.setProgress(
                    100,
                    Math.max(0, Math.min(100, progress)),
                    false
            );
        }

        return builder.build();
    }

    private void showFinishedNotification(
            String title,
            String text
    ) {

        NotificationManager manager =
                (NotificationManager)
                        getSystemService(
                                NOTIFICATION_SERVICE
                        );

        Notification notification =
                buildNotification(
                        text,
                        100,
                        false
                );

        manager.notify(
                NOTIFICATION_ID,
                notification
        );
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= 26) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "VYRA Uploads",
                            NotificationManager.IMPORTANCE_LOW
                    );

            channel.setDescription(
                    "VYRA video upload progress"
            );

            NotificationManager manager =
                    getSystemService(
                            NotificationManager.class
                    );

            manager.createNotificationChannel(
                    channel
            );
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
