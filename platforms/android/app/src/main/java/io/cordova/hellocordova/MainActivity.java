/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
 */

package io.cordova.hellocordova;

import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.apache.cordova.*;

import android.Manifest;

public class MainActivity extends CordovaActivity
{
    private static final int PERMISSIONS_REQUEST_CODE = 1;
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        requestPermissions();
        // enable Cordova apps to be started in the background
        Bundle extras = getIntent().getExtras();
        if (extras != null && extras.getBoolean("cdvStartInBackground", false)) {
            moveTaskToBack(true);
        }

        // Set by <content src="index.html" /> in config.xml
        loadUrl(launchUrl);
    }

// Método para solicitar los permisos
private void requestPermissions() {
    // Verificar si el dispositivo usa Android 6.0 (API 23) o superior, ya que los permisos en tiempo de ejecución se introdujeron en API 23
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        // Crear una lista para permisos que aún no se han otorgado
        String[] permissions = {
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_NOTIFICATION_POLICY
        };

        // Lista de permisos que deben ser solicitados
        boolean shouldRequest = false;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                shouldRequest = true;
                break;
            }
        }

        if (shouldRequest) {
            // Solicitar permisos al usuario
            ActivityCompat.requestPermissions(this, permissions, PERMISSIONS_REQUEST_CODE);
        }
    }
}

// Método para manejar la respuesta de la solicitud de permisos
@Override
public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);

    if (requestCode == PERMISSIONS_REQUEST_CODE) {
        for (int i = 0; i < permissions.length; i++) {
            if (grantResults.length > i && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                System.out.println("Permiso concedido: " + permissions[i]);
            } else {
                System.out.println("Permiso denegado: " + permissions[i]);
            }
        }
    }
  }
}