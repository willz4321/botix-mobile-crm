
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
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.RemoteMessage;
import com.silkimen.http.HttpRequest;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import by.chemerisuk.cordova.support.CordovaMethod;
import by.chemerisuk.cordova.support.ReflectiveCordovaPlugin;
import io.cordova.hellocordova.R;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import me.leolin.shortcutbadger.ShortcutBadger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

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
    private WebView webViewUser;
    private int defaultNotificationIcon;
    private Socket mSocket;
    private AtomicReference<String> id_usuario_ref = new AtomicReference<>("");
    private ScheduledExecutorService scheduler;

    public final static String NOTIFICATION_CHANNEL_KEY = "com.google.firebase.messaging.default_notification_channel_id";
    @Override
    protected void pluginInitialize() {
        FirebaseMessagingPlugin.instance = this;

        firebaseMessaging = FirebaseMessaging.getInstance();
        notificationManager = getSystemService(cordova.getActivity(), NotificationManager.class);
        lastBundle = getNotificationData(cordova.getActivity().getIntent());

        try {
            this.webViewUser = (WebView) webView.getEngine().getView();

            if (webViewUser != null) {
                Log.d(TAG, "webViewUser inicializado correctamente.");
            } else {
                Log.e(TAG, "webViewUser no se inicializó correctamente.");
            }
            if (webViewUser != null) {
                cordova.getActivity().runOnUiThread(() -> {
                    webViewUser.evaluateJavascript(
                            "document.addEventListener('DOMContentLoaded', function() {" +
                                    "   var items = {};" +
                                    "   for (var i = 0; i < localStorage.length; i++) {" +
                                    "       var key = localStorage.key(i);" +
                                    "       items[key] = localStorage.getItem(key);" +
                                    "   }" +
                                    "   items;" + // Retorna el objeto JSON con los datos de localStorage
                                    "}, false);", value -> {
                                Log.d(TAG, "Contenido de localStorage: " + value);

                                // Aquí puedes verificar si `user_id` está presente en el JSON de `localStorage`
                                if (value != null && !value.equals("null")) {
                                    String id_usuario = value.replace("\"", "");
                                    id_usuario_ref.set(id_usuario);
                                    Log.d(TAG, "Datos de usuario: " + id_usuario);
                                } else {
                                    Log.d(TAG, "No se encontró 'user_id' en localStorage.");
                                }
                            });
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "Error configurando la conexión de la webview", e);
        }

        // Iniciar el scheduler para comprobar cambios en id_usuario_ref
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (!id_usuario_ref.get().isEmpty()) {
                    try {
                        // Ejecutar el bloque de código cuando id_usuario_ref no esté vacío
                        getToken(new CordovaArgs(new JSONArray()), new CallbackContext("tokenCallback", webView) {
                            @Override
                            public void success(String token) {
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
                    // Detener el scheduler después de ejecutar la lógica
                    scheduler.shutdown();
                }
            }
        }, 0, 1, TimeUnit.SECONDS); // Revisa cada segundo

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

            IO.Options options = new IO.Options();
            options.query = "id_usuario=" + id_usuario_ref.get() ;
            options.transports = new String[]{"websocket"};

            // Inicializar y conectar el socket
            mSocket = IO.socket("https://botix.axiomarobotics.com:10000", options);
            mSocket.connect();

            // Escuchar evento de conexión
            mSocket.on(Socket.EVENT_CONNECT, args -> {
                Log.v(TAG,"Conectado al servidor socket con ID: " + mSocket.id());
            });


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

            mSocket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    Log.v(TAG,"Socket.IO conectado!");
                }
            });

            mSocket.on("disconnect", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    Log.e(TAG,"Socket.IO desconectado");
                }
            });

            mSocket.on("connect_error", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    System.err.println("Error al conectar: " + args[0]);
                }
            });


        } catch (Exception e) {
            Log.e(TAG, "Error configuring socket connection", e);
        }

    }
    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("receiveDataFromReact")) {
            String userId = args.getString(0);
            this.receiveDataFromReact(userId);
            return true;
        }
        return false;
    }

    public void receiveDataFromReact(String userId) {
        Log.d(TAG, "ID de usuario recibido desde React: " + userId);
        id_usuario_ref.set(userId);
    }
    private void handleInternalMessage(JSONObject data) {
        try {
            Log.d(TAG, "Contenido de data: " + data.toString());
            // Extrae los datos del mensaje
            String messageText = data.has("text") ? data.getString("text") : "";
            String senderId = data.has("senderId") ? data.getString("senderId") : "";
            String nombre = data.has("destino_nombre") ? data.getString("destino_nombre") : "";
            String apellido = data.has("destino_apellido") ? data.getString("destino_apellido") : "";
            String foto = data.has("destino_foto") ? data.getString("destino_foto") : "";
            String integracion = data.has("integracion") ? data.getString("integracion") : "";
            String tipo = data.has("type") ? data.getString("type") : "";
            String tipo_mensaje = data.has("message_type") ? data.getString("message_type") : "";
            String duracion = data.has("duration") ? data.getString("duration") : "";
            String image_url = data.has("url") ? data.getString("url") : "";
            String fileName = data.has("file_name") ? data.getString("file_name") : "";


            // Verifica que webViewUser esté inicializado antes de llamar a evaluateJavascript
            if (webViewUser != null) {
                // Ejecuta JavaScript para obtener el id_usuario desde localStorage
                cordova.getActivity().runOnUiThread(() -> {
                    webViewUser.evaluateJavascript("localStorage.getItem('user_id');", value -> {
                        String id_usuario = value != null && !value.equals("null") ? value.replace("\"", "") : "";
                        System.out.println("Datos de usuario:" + id_usuario);

                        // Usar un switch para manejar la lógica de integración
                        switch (integracion) {
                            case "Interno":
                                if (!id_usuario.equals(senderId)) {
                                    switch (tipo_mensaje){
                                        case "text":
                                            showNotification(messageText, nombre, apellido, foto);
                                            break;
                                        case "audio":
                                            showNotificationMedia(duracion, nombre, apellido, foto, tipo_mensaje);
                                            break;
                                        case "video":
                                            showNotificationMedia(duracion, nombre, apellido, foto, tipo_mensaje);
                                            break;
                                        case "image":
                                            showNotificationImage(image_url, messageText, nombre, apellido, foto);
                                            break;
                                        case "document":
                                            showNotificationDocument(fileName, nombre, apellido, foto);
                                            break;
                                    }
                                }
                                break;

                            default:
                                if (!"reply".equals(tipo)) {
                                    switch (tipo_mensaje){
                                        case "text":
                                            showNotification(messageText, nombre, apellido, foto);
                                            break;
                                        case "audio":
                                            showNotificationMedia(duracion, nombre, apellido, foto, tipo_mensaje);
                                            break;
                                        case "video":
                                            showNotificationMedia(duracion, nombre, apellido, foto, tipo_mensaje);
                                            break;
                                        case "image":
                                            showNotificationImage(image_url, messageText, nombre, apellido, foto);
                                            break;
                                        case "document":
                                            showNotificationDocument(fileName, nombre, apellido, foto);
                                            break;
                                    }
                                }
                                break;
                        }
                    });
                });
            } else {
                Log.e(TAG, "webViewUser no está inicializado.");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing internal message", e);
        }
    }
    private void showNotification(String messageText, String nombre, String apellido, String foto) {
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
    private void showNotificationMedia(String messageText,String nombre, String apellido, String foto, String tipo){
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
        String mensaje = "";
        switch (tipo){
            case "audio":
                mensaje = "\uD83C\uDF99\uFE0F Mensaje de audio" + formatVideoDuration(messageText);
                break;
            case "video":
                mensaje = "\uD83C\uDFA5 Video" + formatVideoDuration(messageText);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, defaultNotificationChannel)
                .setSmallIcon(R.drawable.ic_cdv_splashscreen) // Icono de la notificación
                .setContentTitle(nombre + " " + apellido)
                .setContentText(mensaje) // Texto del mensaje
                .setPriority(NotificationCompat.PRIORITY_HIGH) // Alta prioridad para mostrar inmediatamente
                .setAutoCancel(true); // La notificación desaparece al tocarla

        // Si la imagen fue descargada correctamente, usar BigPictureStyle
        if (imageBitmap != null) {
            builder.setLargeIcon(imageBitmap); // Establece la imagen como el ícono grande (foto de perfil)
        }

        // Muestra la notificación
        notificationManager.notify(1, builder.build()); // El ID de la notificación es '1'
    }
    private void showNotificationImage(String messageText, String  urlIMage, String nombre, String apellido, String foto) {
        Context context = cordova.getActivity();

        Bitmap imageBitmap = null;
        Bitmap imageMessage = null;

        try {
            URL url = new URL(foto);
            imageBitmap = BitmapFactory.decodeStream(url.openConnection().getInputStream());
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
          URL url = new URL(urlIMage);
          imageMessage = BitmapFactory.decodeStream(url.openConnection().getInputStream());
        }catch (IOException e){
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

        if (imageMessage != null) {
            builder.setStyle(new NotificationCompat.BigPictureStyle()
                    .bigPicture(imageBitmap));
        }

        notificationManager.notify(1, builder.build()); // El ID de la notificación es '1'
    }
    private void showNotificationDocument(String messageText, String nombre, String apellido, String foto) {
        Context context = cordova.getActivity();

        Bitmap imageBitmap = null;

        try {
            URL url = new URL(foto);
            imageBitmap = BitmapFactory.decodeStream(url.openConnection().getInputStream());
        } catch (IOException e) {
            e.printStackTrace();
        }

        String mensaje = "\uD83D\uDCC4 Documento: " + messageText;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, defaultNotificationChannel)
                .setSmallIcon(R.drawable.ic_cdv_splashscreen) // Icono de la notificación
                .setContentTitle(nombre + " " + apellido)
                .setContentText(mensaje) // Texto del mensaje
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        // Si la imagen fue descargada correctamente, usar BigPictureStyle
        if (imageBitmap != null) {
            builder.setLargeIcon(imageBitmap); // Establece la imagen como el ícono grande (foto de perfil)
        }
        notificationManager.notify(1, builder.build());
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
        String idUsuario = id_usuario_ref.get(); // Asumimos que el id_usuario es el segundo argumento
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... voids) {
                try {
                    Log.d(TAG, "El id de usuario a enviar es : " + idUsuario); // Log para ver el token
                    String serverToken = getTokenFromServer(idUsuario);
                    String firebaseToken = await(firebaseMessaging.getToken());
                    Log.d(TAG, "Token del servidor: " + serverToken); // Log para ver el token
                    Log.d(TAG, "Token del dispositivo: " + firebaseToken); // Log para ver el token
                    // Verifica si el token del servidor es nulo, 0, indefinido o diferente del token de Firebase
                    if ( !serverToken.equals(firebaseToken) || serverToken == null || serverToken.equals("0") || serverToken.equals("undefined")) {
                        setTokenToServer(firebaseToken, idUsuario);
                    }
                    return firebaseToken;
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
    private String getTokenFromServer(String idUsuario) throws Exception {
        System.out.println("esta es la informacion del id:"  +  idUsuario);
        URL url = new URL("https://botix.axiomarobotics.com:10000/api/auth/get_token_firebase?id_usuario=" + idUsuario);
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        urlConnection.setRequestMethod("GET");
        try (BufferedReader in = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()))) {
            String inputLine;
            StringBuilder content = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine);
            }
            return content.toString();
        } finally {
            urlConnection.disconnect();
        }
    }
    private void setTokenToServer(String token, String idUsuario) {
        OkHttpClient client = new OkHttpClient();

        // Crear el JSON con el token y el id de usuario
        String json = "{ \"token\": \"" + token + "\", \"id_usuario\": \"" + idUsuario + "\" }";
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), json);

        // Configurar la solicitud HTTP POST
        Request request = new Request.Builder()
                .url("https://botix.axiomarobotics.com:10000/api/auth/set_token_firebase")
                .post(body)
                .build();

        // Ejecutar la solicitud en un hilo en segundo plano
        new Thread(() -> {
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    Log.d("TAG", "Response from server: " + response.body().string());
                } else {
                    Log.e("TAG", "Request failed with code: " + response.code());
                }
            } catch (Exception e) {
                Log.e("TAG", "Error sending data: " + e.getMessage());
            }
        }).start();
    }
    @CordovaMethod
    private void onTokenRefresh(CallbackContext callbackContext) {
        instance.tokenRefreshCallback = callbackContext;
    }

    public static String formatVideoDuration(String durationString) {
        try {
            // Convertir el string a un double
            double duration = Double.parseDouble(durationString);

            // Extraer la parte entera como minutos
            int minutes = (int) duration;

            // Convertir la parte decimal a segundos
            int seconds = (int) Math.round((duration - minutes) * 60);

            // Asegurarnos de que los segundos estén en dos dígitos
            return String.format("%d:%02d", minutes, seconds);
        } catch (NumberFormatException e) {
            // En caso de error de formato, devolver una cadena vacía
            return "";
        }
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
