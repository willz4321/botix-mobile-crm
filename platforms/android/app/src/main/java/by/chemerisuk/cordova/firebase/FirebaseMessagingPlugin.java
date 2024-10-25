
package by.chemerisuk.cordova.firebase;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.RemoteMessage;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URL;
import java.util.Set;

import by.chemerisuk.cordova.support.CordovaMethod;
import by.chemerisuk.cordova.support.ReflectiveCordovaPlugin;
import io.cordova.hellocordova.R;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import me.leolin.shortcutbadger.ShortcutBadger;

import static androidx.core.content.ContextCompat.getSystemService;
import static com.google.android.gms.tasks.Tasks.await;
import static by.chemerisuk.cordova.support.ExecutionThread.WORKER;

public class FirebaseMessagingPlugin extends ReflectiveCordovaPlugin {
    private static final String TAG = "FCMPlugin";
    private String defaultNotificationChannel = "43431240"; // Reemplaza con tu ID de canal
    private JSONObject lastBundle;
    private boolean isBackground = false;
    private boolean forceShow = false;
    private CallbackContext tokenRefreshCallback;
    private CallbackContext foregroundCallback;
    private CallbackContext backgroundCallback;
    private static FirebaseMessagingPlugin instance;
    private NotificationManager notificationManager;
    private FirebaseMessaging firebaseMessaging;
    private CallbackContext requestPermissionCallback;

    private int defaultNotificationIcon;
    private Socket mSocket;
    public final static String NOTIFICATION_CHANNEL_KEY = "com.google.firebase.messaging.default_notification_channel_id";
    @Override
    protected void pluginInitialize() {
        FirebaseMessagingPlugin.instance = this;

        firebaseMessaging = FirebaseMessaging.getInstance();
        notificationManager = getSystemService(cordova.getActivity(), NotificationManager.class);
        lastBundle = getNotificationData(cordova.getActivity().getIntent());

        try {
            // Llama al método getToken sin pasar un argumento que necesite un índice
            getToken(new CordovaArgs(new JSONArray()), new CallbackContext("tokenCallback", webView) {
                @Override
                public void success(String token) {
                    // Aquí mostramos el token en los logs de la consola
                    Log.d(TAG, "Token FCM obtenido: " + token);
                }

                @Override
                public void error(String errorMessage) {
                    Log.e(TAG, "Error al obtener el token de FCM: " + errorMessage);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error en pluginInitialize al obtener el token", e);
        }

        Log.d(TAG, "FireBaseMesagginf Cargado de manera correcta::::::::::::::///////////////////////////////: " );
        try {
            // Definir el ID y el nombre del canal

            String channelName = "Canal de socket"; // Nombre del canal
            String channelDescription = "socket"; // Descripción del canal

            // Crear el canal de notificación si estamos en Android O o superior
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel notificationChannel = new NotificationChannel(
                        defaultNotificationChannel,
                        channelName,
                        NotificationManager.IMPORTANCE_HIGH
                );
                notificationChannel.setDescription(channelDescription);

                // Crear el NotificationManager
                notificationManager = (NotificationManager) cordova.getActivity().getSystemService(Context.NOTIFICATION_SERVICE);

                notificationManager.createNotificationChannel(notificationChannel);
            }

            // Establecer la conexión del socket
            mSocket = IO.socket("https://botix.axiomarobotics.com:10000"); // Reemplaza con la URL de tu servidor
            mSocket.connect();

            // Escuchar el evento 'internalMessage'
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
            String nombre = data.getString("nombre");
            String apellido = data.getString("apellido");
            String foto = data.getString("foto");

            // Muestra la notificación con el mensaje recibido
            showNotification(messageText, senderId, nombre, apellido, foto);

        } catch (Exception e) {
            Log.e(TAG, "Error processing internal message", e);
        }
    }

    private void showNotification(String messageText, String senderId, String nombre, String apellido, String foto) {
        Context context = cordova.getActivity();

        // Descarga la imagen desde la URL de la foto
        Bitmap imageBitmap = null;
        try {
            // Descarga la imagen desde la URL
            URL url = new URL(foto);
            imageBitmap = BitmapFactory.decodeStream(url.openConnection().getInputStream());
        } catch (IOException e) {
            e.printStackTrace();
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, defaultNotificationChannel)
                .setSmallIcon(R.drawable.ic_cdv_splashscreen) // Icono de la notificación
                .setContentTitle(nombre + " " + apellido)
                .setContentText(messageText) // Texto del mensaje
                .setPriority(NotificationCompat.PRIORITY_HIGH) // Alta prioridad para mostrar inmediatamente
                .setAutoCancel(true); // La notificación desaparece al tocarla

        // Si la imagen fue descargada correctamente, usar BigPictureStyle
        if (imageBitmap != null) {
            builder.setLargeIcon(imageBitmap); // Establece la imagen como el ícono grande (foto de perfil)
        }

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
    @CordovaMethod(WORKER)
    private void subscribe(CordovaArgs args, final CallbackContext callbackContext) throws Exception {
        String topic = args.getString(0);
        await(firebaseMessaging.subscribeToTopic(topic));
        callbackContext.success();
    }

    @CordovaMethod(WORKER)
    private void unsubscribe(CordovaArgs args, CallbackContext callbackContext) throws Exception {
        String topic = args.getString(0);
        await(firebaseMessaging.unsubscribeFromTopic(topic));
        callbackContext.success();
    }

    @CordovaMethod
    private void clearNotifications(CallbackContext callbackContext) {
        notificationManager.cancelAll();
        callbackContext.success();
    }

    @CordovaMethod(WORKER)
    private void deleteToken(CallbackContext callbackContext) throws Exception {
        await(firebaseMessaging.deleteToken());
        callbackContext.success();
    }

    @CordovaMethod(WORKER)
    private void getToken(CordovaArgs args, CallbackContext callbackContext) throws Exception {
        String type = args.optString(0); // Usa optString para obtener el primer argumento sin lanzar excepción

        if (type != null && !type.isEmpty()) {
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, (String) null));
        } else {
            new AsyncTask<Void, Void, String>() {
                @Override
                protected String doInBackground(Void... voids) {
                    try {
                        return await(firebaseMessaging.getToken());
                    } catch (Exception e) {
                        Log.e(TAG, "Error al obtener el token: " + e.getMessage());
                        return null;
                    }
                }

                @Override
                protected void onPostExecute(String fcmToken) {
                    if (fcmToken != null) {
                        Log.d(TAG, "Token FCM obtenido: " + fcmToken); // Log para ver el token
                        callbackContext.success(fcmToken);
                    } else {
                        callbackContext.error("Error al obtener el token");
                    }
                }
            }.execute();
        }
    }

    @CordovaMethod
    private void onTokenRefresh(CallbackContext callbackContext) {
        instance.tokenRefreshCallback = callbackContext;
    }

    @CordovaMethod
    private void onMessage(CallbackContext callbackContext) {
        instance.foregroundCallback = callbackContext;
    }

    @CordovaMethod
    private void onBackgroundMessage(CallbackContext callbackContext) {
        instance.backgroundCallback = callbackContext;

        if (lastBundle != null) {
            sendNotification(lastBundle, callbackContext);
            lastBundle = null;
        }
    }

    @CordovaMethod
    private void setBadge(CordovaArgs args, CallbackContext callbackContext) throws JSONException {
        int value = args.getInt(0);
        if (value >= 0) {
            Context context = cordova.getActivity().getApplicationContext();
            ShortcutBadger.applyCount(context, value);
            callbackContext.success();
        } else {
            callbackContext.error("Badge value can't be negative");
        }
    }

    @CordovaMethod
    private void getBadge(CallbackContext callbackContext) {
        Context context = cordova.getActivity();
        SharedPreferences settings = context.getSharedPreferences("badge", Context.MODE_PRIVATE);
        callbackContext.success(settings.getInt("badge", 0));
    }

    @CordovaMethod
    private void requestPermission(CordovaArgs args, CallbackContext callbackContext) throws JSONException {
        JSONObject options = args.getJSONObject(0);
        Context context = cordova.getActivity().getApplicationContext();
        forceShow = options.optBoolean("forceShow");
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            callbackContext.success();
        } else if (Build.VERSION.SDK_INT >= 33) {
            requestPermissionCallback = callbackContext;
            PermissionHelper.requestPermission(this, 0, Manifest.permission.POST_NOTIFICATIONS);
        } else {
            callbackContext.error("Notifications permission is not granted");
        }
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        for (int result : grantResults) {
            if (result == PackageManager.PERMISSION_DENIED) {
                requestPermissionCallback.error("Notifications permission is not granted");
                return;
            }
        }
        requestPermissionCallback.success();
    }

    @Override
    public void onNewIntent(Intent intent) {
        JSONObject notificationData = getNotificationData(intent);
        if (instance != null && notificationData != null) {
            sendNotification(notificationData, instance.backgroundCallback);
        }
    }

    @Override
    public void onPause(boolean multitasking) {
        this.isBackground = true;
    }

    @Override
    public void onResume(boolean multitasking) {
        this.isBackground = false;
    }

    static void sendNotification(RemoteMessage remoteMessage) {
        JSONObject notificationData = new JSONObject(remoteMessage.getData());
        RemoteMessage.Notification notification = remoteMessage.getNotification();
        try {
            if (notification != null) {
                notificationData.put("gcm", toJSON(notification));
            }
            notificationData.put("google.message_id", remoteMessage.getMessageId());
            notificationData.put("google.sent_time", remoteMessage.getSentTime());

            if (instance != null) {
                CallbackContext callbackContext = instance.isBackground ? instance.backgroundCallback
                        : instance.foregroundCallback;
                instance.sendNotification(notificationData, callbackContext);
            }
        } catch (JSONException e) {
            Log.e(TAG, "sendNotification", e);
        }
    }

    static void sendToken(String instanceId) {
        if (instance != null) {
            if (instance.tokenRefreshCallback != null && instanceId != null) {
                PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, instanceId);
                pluginResult.setKeepCallback(true);
                instance.tokenRefreshCallback.sendPluginResult(pluginResult);
            }
        }
    }

    static boolean isForceShow() {
        return instance != null && instance.forceShow;
    }

    private void sendNotification(JSONObject notificationData, CallbackContext callbackContext) {
        if (callbackContext != null) {
            PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, notificationData);
            pluginResult.setKeepCallback(true);
            callbackContext.sendPluginResult(pluginResult);
        }
    }

    private JSONObject getNotificationData(Intent intent) {
        Bundle bundle = intent.getExtras();

        if (bundle == null) {
            return null;
        }

        if (!bundle.containsKey("google.message_id") && !bundle.containsKey("google.sent_time")) {
            return null;
        }

        try {
            JSONObject notificationData = new JSONObject();
            Set<String> keys = bundle.keySet();
            for (String key : keys) {
                notificationData.put(key, bundle.get(key));
            }
            return notificationData;
        } catch (JSONException e) {
            Log.e(TAG, "getNotificationData", e);
            return null;
        }
    }

    private static JSONObject toJSON(RemoteMessage.Notification notification) throws JSONException {
        JSONObject result = new JSONObject()
                .put("body", notification.getBody())
                .put("title", notification.getTitle())
                .put("sound", notification.getSound())
                .put("icon", notification.getIcon())
                .put("tag", notification.getTag())
                .put("color", notification.getColor())
                .put("clickAction", notification.getClickAction());

        Uri imageUri = notification.getImageUrl();
        if (imageUri != null) {
            result.put("imageUrl", imageUri.toString());
        }

        return result;
    }
}
