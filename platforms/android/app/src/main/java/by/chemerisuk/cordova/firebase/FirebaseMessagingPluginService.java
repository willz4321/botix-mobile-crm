package by.chemerisuk.cordova.firebase;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import static android.content.ContentResolver.SCHEME_ANDROID_RESOURCE;
import io.cordova.hellocordova.R;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URL;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;


public class FirebaseMessagingPluginService extends FirebaseMessagingService {
    private static final String TAG = "FCMPluginService";

    public static final String ACTION_FCM_MESSAGE = "by.chemerisuk.cordova.firebase.ACTION_FCM_MESSAGE";
    public static final String EXTRA_FCM_MESSAGE = "by.chemerisuk.cordova.firebase.EXTRA_FCM_MESSAGE";
    public static final String ACTION_FCM_TOKEN = "by.chemerisuk.cordova.firebase.ACTION_FCM_TOKEN";
    public static final String EXTRA_FCM_TOKEN = "by.chemerisuk.cordova.firebase.EXTRA_FCM_TOKEN";
    public final static String NOTIFICATION_ICON_KEY = "com.google.firebase.messaging.default_notification_icon";
    public final static String NOTIFICATION_COLOR_KEY = "com.google.firebase.messaging.default_notification_color";
    public final static String NOTIFICATION_CHANNEL_KEY = "com.google.firebase.messaging.default_notification_channel_id";

    private LocalBroadcastManager broadcastManager;
    private NotificationManager notificationManager;
    private int defaultNotificationIcon;
    private int defaultNotificationColor;
    private String defaultNotificationChannel;

    private Socket mSocket;
    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate() llamado/////////////////////////////");
        broadcastManager = LocalBroadcastManager.getInstance(this);
        notificationManager = ContextCompat.getSystemService(this, NotificationManager.class);
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(getApplicationContext().getPackageName(), PackageManager.GET_META_DATA);
            defaultNotificationIcon = ai.metaData.getInt(NOTIFICATION_ICON_KEY, ai.icon);
            defaultNotificationChannel = ai.metaData.getString(NOTIFICATION_CHANNEL_KEY, "default");
        } catch (PackageManager.NameNotFoundException e) {
            Log.d(TAG, "Failed to load meta-data", e);
        } catch(Resources.NotFoundException e) {
            Log.d(TAG, "Failed to load notification color", e);
        }
        // On Android O or greater we need to create a new notification channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel defaultChannel = notificationManager.getNotificationChannel(defaultNotificationChannel);
            if (defaultChannel == null) {
                notificationManager.createNotificationChannel(
                        new NotificationChannel(defaultNotificationChannel, "Firebase", NotificationManager.IMPORTANCE_HIGH));
            }
        }
        Log.d(TAG, "FireBaseMesagginf Cargado de manera correcta::::::::::::::///////////////////////////////: " );
        try {
            mSocket = IO.socket("https://botix.axiomarobotics.com:10000"); // Reemplaza con la URL de tu servidor
            mSocket.connect();

            // Escucha el evento 'internalMessage'
            mSocket.on("internalMessage", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    JSONObject data = (JSONObject) args[0];
                    handleInternalMessage(data);
                }
            });

            mSocket.on("newMessage", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    JSONObject data = (JSONObject) args[0];
                    handleInternalMessage(data);
                }
            });


        } catch (Exception e) {
            Log.e(TAG, "Error configuring socket connection", e);
        }
    }

    private void handleInternalMessage(JSONObject data) {
        try {
            // Extrae los datos del mensaje
            String messageText = data.getString("text");
            String senderId = data.getString("senderId");

            // Muestra la notificación con el mensaje recibido
            showNotification(messageText, senderId);

        } catch (Exception e) {
            Log.e(TAG, "Error processing internal message", e);
        }
    }

    private void showNotification(String messageText, String senderId) {
        // Crea el contenido de la notificación
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, defaultNotificationChannel)
                .setSmallIcon(defaultNotificationIcon) // Icono de la notificación
                .setContentTitle("Message from " + (senderId != null ? senderId : "Facundo")) // Título: nombre del remitente o 'Facundo' si es nulo
                .setContentText(messageText) // Texto del mensaje
                .setPriority(NotificationCompat.PRIORITY_HIGH) // Alta prioridad para mostrar inmediatamente
                .setAutoCancel(true); // La notificación desaparece al tocarla

        // Muestra la notificación
        notificationManager.notify(1, builder.build()); // El ID de la notificación es '1'
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mSocket != null) {
            mSocket.disconnect();
            mSocket.off("internalMessage");
            mSocket.off("newMessage");
        }
    }

    @Override
    public void onNewToken(@NonNull String token) {
        FirebaseMessagingPlugin.sendToken(token);

        Intent intent = new Intent(ACTION_FCM_TOKEN);
        intent.putExtra(EXTRA_FCM_TOKEN, token);
        broadcastManager.sendBroadcast(intent);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        FirebaseMessagingPlugin.sendNotification(remoteMessage);

        Intent intent = new Intent(ACTION_FCM_MESSAGE);
        intent.putExtra(EXTRA_FCM_MESSAGE, remoteMessage);
        broadcastManager.sendBroadcast(intent);

        if (FirebaseMessagingPlugin.isForceShow()) {
            RemoteMessage.Notification notification = remoteMessage.getNotification();
            if (notification != null) {
                showAlert(notification);
            }
        }
    }

    private void showAlert(RemoteMessage.Notification notification) {
        // Obtener el contexto y los datos de la notificación
        String imageUrl = notification.getImageUrl() != null ? notification.getImageUrl().toString() : null;
        Bitmap largeIcon = null;

        // Descargar la imagen desde la URL si existe
        if (imageUrl != null) {
            try {
                URL url = new URL(imageUrl);
                largeIcon = BitmapFactory.decodeStream(url.openConnection().getInputStream());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // Crear el constructor de la notificación
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, getNotificationChannel(notification))
                .setSound(getNotificationSound(notification.getSound()))
                .setContentTitle(notification.getTitle())
                .setContentText(notification.getBody())
                .setGroup(notification.getTag())
                .setSmallIcon(R.drawable.ic_cdv_splashscreen) // Icono pequeño para la barra de estado
                .setPriority(NotificationCompat.PRIORITY_HIGH); // Asegura que la notificación se muestre de inmediato

        // Si la imagen fue descargada correctamente, establecerla como icono grande
        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon); // Muestra la imagen como ícono grande (similar a una foto de perfil)
        }

        // Mostrar la notificación
        notificationManager.notify(0, builder.build());

    }


    private String getNotificationChannel(RemoteMessage.Notification notification) {
        String channel = notification.getChannelId();
        if (channel == null) {
            return defaultNotificationChannel;
        } else {
            return channel;
        }
    }

    private Uri getNotificationSound(String soundName) {
        if (soundName == null || soundName.isEmpty()) {
            return null;
        } else if (soundName.equals("default")) {
            return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        } else {
            return Uri.parse(SCHEME_ANDROID_RESOURCE + "://" + getApplicationContext().getPackageName() + "/raw/" + soundName);
        }
    }
}
