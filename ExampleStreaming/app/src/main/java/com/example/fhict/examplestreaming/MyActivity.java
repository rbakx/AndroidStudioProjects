package com.example.fhict.examplestreaming;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Random;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.ShutterCallback;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore.Images.Media;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;
import android.graphics.YuvImage;
import android.graphics.Rect;

import java.util.Iterator;


public class MyActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback, Runnable {

    Camera camera;
    SurfaceView surfaceView;
    SurfaceHolder surfaceHolder;
    boolean previewing = false;

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int imageFormat;
    Camera.Size previewSize;
    Rect previewArea;

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        try {
            surfaceView = (SurfaceView) findViewById(R.id.camerapreview);
            surfaceHolder = surfaceView.getHolder();
            surfaceHolder.addCallback(this);
            surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

            new SocketTask().execute();
        } catch (Exception e) {
            Toast.makeText(getApplicationContext(), "exception1: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width,
                               int height) {
        // TODO Auto-generated method stub
        try {
            if (previewing) {
                camera.stopPreview();
                previewing = false;
            }
            if (camera != null) {
                camera.setPreviewDisplay(surfaceHolder);
                camera.setPreviewCallback(MyActivity.this);
                camera.startPreview();
                imageFormat = camera.getParameters().getPreviewFormat();
                previewSize = camera.getParameters().getPreviewSize();
                previewArea = new Rect(0, 0, previewSize.width, previewSize.height);
                List<int[]> fpsList;
                Toast.makeText(getApplicationContext(), "format = " + imageFormat + ", " + previewSize.width + "x" + previewSize.height, Toast.LENGTH_SHORT).show();

                previewing = true;

            }
        } catch (Exception e) {
            Log.e("surfaceChanged", e.getMessage());
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // TODO Auto-generated method stub
        camera = Camera.open();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // TODO Auto-generated method stub
        try {
            if (camera != null) {
                camera.stopPreview();
                camera.release();
                camera = null;
                previewing = false;
            }
        } catch (Exception e) {
            Log.e("surfaceDestroyed", e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            streaming = false;
            if (camera != null) {
                camera.stopPreview();
                camera.release();
                camera = null;
                previewing = false;
            }
            if (socket != null) {
                socket.close();
            }
        } catch (Exception e) {
            Log.e("onDestroy", e.getMessage());
        }
    }

    private static DataOutputStream stream;
    private boolean prepared;
    private Socket socket = null;
    private static boolean streaming = false;
    private int count = 0;

    private class SocketTask extends AsyncTask {

        private static final String TAG = "StreamTASK";

        @Override
        protected Object doInBackground(Object... params) {
            try {
                if (socket != null) {
                    socket.close();
                }
                ServerSocket server = new ServerSocket(44445);

                socket = server.accept();

                server.close();

                Log.i(TAG, "New connection to :" + socket.getInetAddress());
                final String mFinal1 = "New connection to :" + socket.getInetAddress();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(getApplicationContext(), mFinal1, Toast.LENGTH_SHORT).show();
                    }
                });

                stream = new DataOutputStream(socket.getOutputStream());
                prepared = true;

                if (stream != null) {
                    // send the header
                    stream.write(("HTTP/1.0 200 OK\r\n" +
                            "Server: iRecon\r\n" +
                            "Connection: close\r\n" +
                            "Max-Age: 0\r\n" +
                            "Expires: 0\r\n" +
                            "Cache-Control: no-store, no-cache, must-revalidate, pre-check=0, post-check=0, max-age=0\r\n" +
                            "Pragma: no-cache\r\n" +
                            "Content-Type: multipart/x-mixed-replace; " +
                            "boundary=" + boundary + "\r\n" +
                            "\r\n" +
                            "--" + boundary + "\r\n").getBytes());

                    stream.flush();
                    streaming = true;
                }
            } catch (Exception e) {
                Log.e(TAG, e.getMessage());
                final String eFinal = e.getMessage();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(getApplicationContext(), "exception: " + eFinal, Toast.LENGTH_LONG).show();
                    }
                });
            }
            new SocketTask().execute();
            return null;
        }

    }

    byte[] frame = null;
    private Handler mHandler = new Handler(Looper.getMainLooper());


    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        frame = data;

        try {
            if (streaming) {
                mHandler.post(this);
            }
        } catch (Exception e) {
            Toast.makeText(getApplicationContext(), "exception: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    String boundary = boundary = "---------------------------7da24f2e50046";

    @Override
    public void run() {
        // TODO: cache not filling?
        try {
            buffer.reset();

            switch (imageFormat) {
                case ImageFormat.JPEG:
                    // nothing to do, leave it that way
                    buffer.write(frame);
                    break;
                case ImageFormat.NV16:
                case ImageFormat.NV21:
                case ImageFormat.YUY2:
                case ImageFormat.YV12:
                    new YuvImage(frame, imageFormat, previewSize.width, previewSize.height, null).compressToJpeg(previewArea, 50, buffer);
                    break;

                default:
                    throw new Exception("Error while encoding: unsupported image format");
            }

            buffer.flush();

            // write the content header
            stream.write(("Content-type: image/jpeg\r\n" +
                    "Content-Length: " + buffer.size() + "\r\n" +
                    "X-Timestamp:" + System.currentTimeMillis() + "\r\n" +
                    "\r\n").getBytes());

            if (count % 100 == 0) {
                Toast.makeText(getApplicationContext(), "streaming!!!", Toast.LENGTH_SHORT).show();
                count = 0;
            }
            count = count + 1;

            buffer.writeTo(stream);
            stream.write(("\r\n--" + boundary + "\r\n").getBytes());
            stream.flush();
            Log.e("WriteStream", "Writing to stream");
        } catch (Exception e) {
//	        stop();
//	        notifyOnEncoderError(this, e.getMessage());
            streaming = false;
            Toast.makeText(getApplicationContext(), "exception: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}