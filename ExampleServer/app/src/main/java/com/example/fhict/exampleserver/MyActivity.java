package com.example.fhict.exampleserver;

import android.app.Activity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.os.Environment;
import android.util.Log;

import java.io.*;
import java.util.*;

import android.widget.Toast;


public class MyActivity extends Activity {
    private WebServer server;

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my);
        server = new WebServer();
        try {
            Toast.makeText(getApplicationContext(), "going to start server", Toast.LENGTH_LONG).show();
            server.start();
        } catch (IOException ioe) {
            Log.w("Httpd", "The server could not start.");
        }
        Log.w("Httpd", "Web server initialized.");
    }


    // DON'T FORGET to stop the server
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (server != null)
            server.stop();
    }

    public class WebServer extends NanoHTTPD {
        public WebServer() {
            super(8080);
        }

        @Override
        public Response serve(IHTTPSession session) {
            Method method = session.getMethod();
            String uri = session.getUri();
            System.out.println(method + " '" + uri + "' ");

            String answer = "";

            Map<String, String> parms = session.getParms();


            try {
                // Open file from SD Card
                File root = Environment.getExternalStorageDirectory();
                FileReader index = new FileReader(root.getAbsolutePath() +
                        "/www/index.html");
                BufferedReader reader = new BufferedReader(index);
                String line = "";
                while ((line = reader.readLine()) != null) {
                    answer += line;
                }
                reader.close();

            } catch (IOException ioe) {
                Log.w("Httpd", ioe.toString());
            }

            if (parms.get("cmd") != null) {
                String answerWithFeedback = answer.replace("feedback", parms.get("cmd"));
                return new NanoHTTPD.Response(answerWithFeedback);
            } else {
                return new NanoHTTPD.Response(answer);
            }
        }

    }
}