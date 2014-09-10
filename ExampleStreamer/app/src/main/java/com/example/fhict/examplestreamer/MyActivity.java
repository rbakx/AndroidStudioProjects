package com.example.fhict.examplestreamer;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Toast;
import android.os.Looper;
import android.graphics.YuvImage;
import android.graphics.Rect;


public class MyActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback, Runnable {

    private  boolean previewing = false;
    private  boolean streaming = false;
    private  DataOutputStream stream;
    private  Socket socket = null;

    Camera camera;
    SurfaceView surfaceView;
    SurfaceHolder surfaceHolder;

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int imageFormat;
    Camera.Size previewSize;
    Rect previewArea;

    private SocketTask mSocketTask = null;

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.e("ReneBlog", "OnCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        try {
            surfaceView = (SurfaceView) findViewById(R.id.camerapreview);
            surfaceHolder = surfaceView.getHolder();
            surfaceHolder.addCallback(this);
            surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        } catch (Exception e) {
            Log.e("exception onCreate: ", e.getMessage());
            Toast.makeText(getApplicationContext(), "exception: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.e("ReneBlog", "surfaceCreated");
        try {
            camera = Camera.open();
            mSocketTask = new SocketTask();
            mSocketTask.execute();
        } catch (Exception e) {
            Log.e("exception surfaceCreated: ", e.getMessage());
            Toast.makeText(getApplicationContext(), "exception: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }

    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width,
                               int height) {
        Log.e("ReneBlog", "surfaceChanged");
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
            Log.e("exception surfaceChanged: ", e.getMessage());
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.e("ReneBlog", "surfaceDestroyed");
        try {
            streaming = false;

            if (camera != null) {
                camera.stopPreview();
                camera.setPreviewCallback(null);
                camera.release();
                camera = null;
                previewing = false;
            }
            if (socket != null) {
                socket.close();
            }
        } catch (Exception e) {
            Log.e("exception surfaceDestroyed: ", e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        Log.e("ReneBlog", "onDestroy");
        super.onDestroy();
        try {
            streaming = false;

            if (camera != null) {
                camera.stopPreview();
                camera.setPreviewCallback(null);
                camera.release();
                camera = null;
                previewing = false;
            }
            if (socket != null) {
                socket.close();
            }
        } catch (Exception e) {
            Log.e("exception onDestroy: ", e.getMessage());
        }
    }

    private class SocketTask extends AsyncTask {
        private static final String TAG = "StreamTASK";

        @Override
        protected Object doInBackground(Object... params) {
            Log.e("ReneBlog", "SocketTask");
            try {
                boolean doAgain = true;

                ServerSocket server = new ServerSocket(44445);

                while (doAgain == true) {
                    // Set timeout to be able to check on activity stopped.
                    server.setSoTimeout(1000);
                    try {
                        // server.accept is blocking, it returns when either a connection request
                        // is received, or after the timeout set.
                        // In case a connection request is received, we break out of the while loop.
                        // In case of a timeout, we remain looping, but check if the activity
                        // is still running. If not, we stop this backgound thread.
                        socket = server.accept();
                        break;
                    } catch (SocketTimeoutException e) {
                        if (previewing == false) { // activity has stopped, return
                            server.close();
                            return null;
                        }
                    }
                }
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
                Log.e("exception doInBackground: ", e.getMessage());
                final String eFinal = e.getMessage();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(getApplicationContext(), "exception: " + eFinal, Toast.LENGTH_SHORT).show();
                    }
                });
            }
            // Call this background task again to wait for the next socket connection request.
            mSocketTask = new SocketTask();
            mSocketTask.execute();
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
            Log.e("exception onPreviewFrame: ", e.getMessage());
            Toast.makeText(getApplicationContext(), "exception6: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    String boundary = boundary = "---------------------------7da24f2e50046";

    @Override
    public void run() {
        // TODO: cache not filling?
        try {
            // Check if activity is still active.
            if (previewing == false) {
                return;
            }
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


            buffer.writeTo(stream);
            stream.write(("\r\n--" + boundary + "\r\n").getBytes());
            stream.flush();
        } catch (Exception e) {
            Log.e("exception run: ", e.getMessage());
            streaming = false;
            Toast.makeText(getApplicationContext(), "exception: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}