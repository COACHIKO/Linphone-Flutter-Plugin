package com.egytelecoms.hatif;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.nfc.FormatException;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;

public class Utils {
    private final String TAG = Utils.class.getSimpleName();
   public boolean checkPermissions(String[] permissions, Activity activity) {
      try {
         int result;
         List<String> listPermissionsNeeded = new ArrayList<>();
         for (String p : permissions) {
            result = ContextCompat.checkSelfPermission(activity, p);
            if (result != PackageManager.PERMISSION_GRANTED) {
               listPermissionsNeeded.add(p);
            }
         }
         if (!listPermissionsNeeded.isEmpty()) {
            // Request permissions - this will show the system dialog
            ActivityCompat.requestPermissions(activity, listPermissionsNeeded.toArray(new String[listPermissionsNeeded.size()]), 1111);
            // Return true to indicate we're handling the request
            // The actual permission grant/deny will be handled by Android's callback
            return true;
         }
      }catch (Exception e){
         e.printStackTrace();
      }

      return true;
   }
}
