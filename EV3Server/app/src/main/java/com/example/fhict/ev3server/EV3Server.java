/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.fhict.ev3server;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.hardware.Camera;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.View.OnClickListener;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.IntentFilter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.io.InputStream;
import java.io.FileInputStream;

/**
 * This is the main Activity that displays the current chat session
 */
public class EV3Server extends Activity {
    // Debugging
    private static final String TAG = "EV3Server";
    private static final boolean D = true;
    private WebServer server;
    private String bluetoothStatus = "-"; // to report back to client
    private String rawMessageFromEv3 = "";  // to report back to client

    // Message types sent from the BluetoothChatService Handler
    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;

    // Key names received from the BluetoothChatService Handler
    public static final String DEVICE_NAME = "device_name";
    public static final String TOAST = "toast";

    // Intent request codes
    private static final int REQUEST_CONNECT_DEVICE = 1;
    private static final int REQUEST_ENABLE_BT = 2;

    // Layout Views
    private TextView mTitle;
    private ListView mConversationView;
    private EditText mOutEditText;
    private Button mSendButton;
    private Button mButtonForward;
    private Button mButtonBackward;
    private Button mButtonStop;

    // Name of the connected device
    private String mConnectedDeviceName = null;
    // Array adapter for the conversation thread
    private ArrayAdapter<String> mConversationArrayAdapter;
    // String buffer for outgoing messages
    private StringBuffer mOutStringBuffer;
    // Local Bluetooth adapter
    private BluetoothAdapter mBluetoothAdapter = null;
    // Member object for the chat services
    private BluetoothChatService mChatService = null;
    private Camera camObject = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (D) Log.e(TAG, "+++ ON CREATE +++");

        // Set up the window layout
        requestWindowFeature(Window.FEATURE_CUSTOM_TITLE);
        setContentView(R.layout.main);
        getWindow().setFeatureInt(Window.FEATURE_CUSTOM_TITLE, R.layout.custom_title);

        // Set up the custom title
        mTitle = (TextView) findViewById(R.id.title_left_text);
        mTitle.setText(R.string.app_name);
        mTitle = (TextView) findViewById(R.id.title_right_text);

        // Get local Bluetooth adapter
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // If the adapter is null, then Bluetooth is not supported
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Start web server
        server = new WebServer();
        try {
            Toast.makeText(getApplicationContext(), "starting server", Toast.LENGTH_SHORT).show();
            server.start();
        } catch (IOException ioe) {
            Log.w("Httpd", "The server could not start.");
        }
        Log.w("Httpd", "Web server initialized.");
    }

    @Override
    public void onStart() {
        super.onStart();
        if (D) Log.e(TAG, "++ ON START ++");

        // If BT is not on, request that it be enabled.
        // setupChat() will then be called during onActivityResult
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
            // Otherwise, setup the chat session
        } else {
            if (mChatService == null) setupChat();
        }
    }

    @Override
    public synchronized void onResume() {
        super.onResume();
        if (D) Log.e(TAG, "+ ON RESUME +");

        // Performing this check in onResume() covers the case in which BT was
        // not enabled during onStart(), so we were paused to enable it...
        // onResume() will be called when ACTION_REQUEST_ENABLE activity returns.
        if (mChatService != null) {
            // Only if the state is STATE_NONE, do we know that we haven't started already
            if (mChatService.getState() == BluetoothChatService.STATE_NONE) {
                // Start the Bluetooth chat services
                mChatService.start();
            }
        }
    }

    @Override
    public synchronized void onPause() {
        super.onPause();
        if (D) Log.e(TAG, "- ON PAUSE -");
    }

    @Override
    public void onStop() {
        super.onStop();
        if (D) Log.e(TAG, "-- ON STOP --");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Stop the Bluetooth chat services
        if (mChatService != null) mChatService.stop();
        if (D) Log.e(TAG, "--- ON DESTROY ---");
        if (server != null)
            server.stop();

        // Release the camera object
        if (camObject != null) {
            camObject.release();
        }
    }

    private void setupChat() {
        Log.d(TAG, "setupChat()");

        // Initialize the array adapter for the conversation thread
        mConversationArrayAdapter = new ArrayAdapter<String>(this, R.layout.message);
        mConversationView = (ListView) findViewById(R.id.in);
        mConversationView.setAdapter(mConversationArrayAdapter);

        // Initialize the compose field with a listener for the return key
        mOutEditText = (EditText) findViewById(R.id.edit_text_out);
        mOutEditText.setOnEditorActionListener(mWriteListener);

        // Initialize the send button with a listener that for click events
        mSendButton = (Button) findViewById(R.id.button_send);
        mSendButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                // Send a message using content of the edit text widget
                TextView view = (TextView) findViewById(R.id.edit_text_out);
                String message = view.getText().toString();
                sendEV3Message(message);
            }
        });
        //ReneB: add cmd button listeners
        mButtonForward = (Button) findViewById(R.id.button_forward);
        mButtonForward.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                // Send a message using content of the edit text widget
                sendEV3Message("cmd:forward");
            }
        });
        mButtonBackward = (Button) findViewById(R.id.button_backward);
        mButtonBackward.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                // Send a message using content of the edit text widget
                sendEV3Message("cmd:backward");
            }
        });
        mButtonStop = (Button) findViewById(R.id.button_stop);
        mButtonStop.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                // Send a message using content of the edit text widget
                sendEV3Message("cmd:stop");
            }
        });

        // Initialize the BluetoothChatService to perform bluetooth connections
        mChatService = new BluetoothChatService(this, mHandler);

        // Initialize the buffer for outgoing messages
        mOutStringBuffer = new StringBuffer("");
    }

    private void ensureDiscoverable() {
        if (D) Log.d(TAG, "ensure discoverable");
        if (mBluetoothAdapter.getScanMode() !=
                BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivity(discoverableIntent);
        }
    }

    /**
     * Sends a EV3 message.
     *
     * @param message A string of text to send.
     */
    private void sendEV3Message(String message) {
        // Check that we're actually connected before trying anything
        if (mChatService.getState() != BluetoothChatService.STATE_CONNECTED) {
            Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show();
            return;
        }

        // Check that there's actually something to send
        if (message.length() > 0 && message.contains(":")) {
            // Added by ReneB for communication to  MindStorms EV3
            // Below comments are taken from Joeri's EVM3essenger program, thanks Joeri!
            // Byte:  0  0x12  PayloadSize : Byte 0 and 1 contain the size (bytes) of the payload.
            //        1  0x00                The size counting starts at byte 2. Here: 00 12 (hex) = 18 (dec)
            //        -------
            //        2  0x01  SecretHeader: Byte 2..5 contain a header (just a guess)
            //        3  0x00                The contents are unknown, but every message has these values here.
            //        4  0x81
            //        5  0x9E
            //        -------
            //        6  0x05  TitleSize   : Byte 6 contains the size (bytes) of the Title field, which follows.
            //        -------
            //        7  0x70  Title       : The title text string (ascii). Here: "ping"
            //        8  0x69                The last character is always 0x00.
            //        9  0x6E
            //       10  0x67
            //       11  0x00
            //       --------
            //       12  0x06  ValueSize   : Byte (6 + TitleSize + 1) contains the size (bytes) of the Value field which follows.
            //       13  0x00                Here: 00 06 (hex) = 6 (dec)
            //       --------
            //       14  0x68  Value       : The value. Here the text: "hello"
            //       15  0x65                The value field can contain:
            //       16  0x6C                - Text (length: variable, ends with 0x00) ---> string
            //       17  0x6C                - Number (length: 4 bytes)                ---> float (Single)
            //       18  0x6F                - Logic (length: 1 byte)                  ---> bool
            //       19  0x00
            //
            byte[] payloadSizeFieldLsbyte = new byte[]{0x00};
            byte[] payloadSizeFieldMsbyte = new byte[]{0x00};
            byte[] secretHeader = new byte[]{0x01, 0x00, (byte) 0x81, (byte) 0x9E};
            byte[] titleSizeField = new byte[]{0x00};
            byte[] valueSizeFieldLsbyte = new byte[]{0x00};
            byte[] valueSizeFieldMsbyte = new byte[]{0x00};
            byte[] padByte = new byte[]{0x00};

            String[] messageParts = message.split(":");
            String titleField = messageParts[0];
            String valueField = messageParts[1];
            byte[] titleFieldBytes = titleField.getBytes();
            byte[] valueFieldBytes = valueField.getBytes();

            payloadSizeFieldLsbyte[0] = (byte) (9 + titleFieldBytes.length + valueFieldBytes.length);
            titleSizeField[0] = (byte) (titleFieldBytes.length + 1); // include padding byte
            valueSizeFieldLsbyte[0] = (byte) (valueFieldBytes.length + 1); // include padding byte

            ByteBuffer byteBuffer = ByteBuffer.allocate(11 + titleFieldBytes.length + valueFieldBytes.length);
            byteBuffer.put(payloadSizeFieldLsbyte);
            byteBuffer.put(payloadSizeFieldMsbyte);
            byteBuffer.put(secretHeader);
            byteBuffer.put(titleSizeField);
            byteBuffer.put(titleFieldBytes);
            byteBuffer.put(padByte);
            byteBuffer.put(valueSizeFieldLsbyte);
            byteBuffer.put(valueSizeFieldMsbyte);
            byteBuffer.put(valueFieldBytes);
            byteBuffer.put(padByte);

            //byte[] bytesToSend = new byte[]{0x12,0x00,0x01,0x00,(byte) 0x81,(byte) 0x9e,0x05,0x70,0x69,0x6e,0x67,0x00,0x06,0x00,0x68,0x65,0x6c,0x6c,0x6f,0x00};

            byteBuffer.clear();
            byte[] bytesToSend = new byte[byteBuffer.capacity()];
            byteBuffer.get(bytesToSend, 0, bytesToSend.length);

            // Get the message bytes and tell the BluetoothChatService to write
            mChatService.write(bytesToSend);

            // Reset out string buffer to zero and clear the edit text field
            mOutStringBuffer.setLength(0);
            mOutEditText.setText(mOutStringBuffer);
        }
    }

    // The action listener for the EditText widget, to listen for the return key
    private TextView.OnEditorActionListener mWriteListener =
            new TextView.OnEditorActionListener() {
                public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
                    // If the action is a key-up event on the return key, send the message
                    if (actionId == EditorInfo.IME_NULL && event.getAction() == KeyEvent.ACTION_UP) {
                        String message = view.getText().toString();
                        sendEV3Message(message);
                    }
                    if (D) Log.i(TAG, "END onEditorAction");
                    return true;
                }
            };

    // The Handler that gets information back from the BluetoothChatService
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_STATE_CHANGE:
                    if (D) Log.i(TAG, "MESSAGE_STATE_CHANGE: " + msg.arg1);
                    switch (msg.arg1) {
                        case BluetoothChatService.STATE_CONNECTED:
                            mTitle.setText(R.string.title_connected_to);
                            mTitle.append(mConnectedDeviceName);
                            mConversationArrayAdapter.clear();
                            bluetoothStatus = "connected";
                            break;
                        case BluetoothChatService.STATE_CONNECTING:
                            mTitle.setText(R.string.title_connecting);
                            bluetoothStatus = "connecting";
                            break;
                        case BluetoothChatService.STATE_LISTEN:
                        case BluetoothChatService.STATE_NONE:
                            mTitle.setText(R.string.title_not_connected);
                            bluetoothStatus = "not connected";
                            break;
                    }
                    break;
                case MESSAGE_WRITE:
                    byte[] writeBuf = (byte[]) msg.obj;
                    // construct a string from the buffer
                    String writeMessage = new String(writeBuf);
                    mConversationArrayAdapter.add("Me:  " + writeMessage);
                    break;
                case MESSAGE_READ:
                    byte[] readBuf = (byte[]) msg.obj;
                    // construct a string from the valid bytes in the buffer
                    String readMessage = new String(readBuf, 0, msg.arg1);
                    mConversationArrayAdapter.add(mConnectedDeviceName + ":  " + readMessage);
                    rawMessageFromEv3 = readMessage;
                    break;
                case MESSAGE_DEVICE_NAME:
                    // save the connected device's name
                    mConnectedDeviceName = msg.getData().getString(DEVICE_NAME);
                    Toast.makeText(getApplicationContext(), "Connected to "
                            + mConnectedDeviceName, Toast.LENGTH_SHORT).show();
                    break;
                case MESSAGE_TOAST:
                    Toast.makeText(getApplicationContext(), msg.getData().getString(TOAST),
                            Toast.LENGTH_SHORT).show();
                    break;
            }
        }
    };

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (D) Log.d(TAG, "onActivityResult " + resultCode);
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    // Get the device MAC address
                    String address = data.getExtras()
                            .getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
                    // Get the BLuetoothDevice object
                    BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
                    // Attempt to connect to the device
                    mChatService.connect(device);
                }
                break;
            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                if (resultCode == Activity.RESULT_OK) {
                    // Bluetooth is now enabled, so set up a chat session
                    setupChat();
                } else {
                    // User did not enable Bluetooth or an error occured
                    Log.d(TAG, "BT not enabled");
                    Toast.makeText(this, R.string.bt_not_enabled_leaving, Toast.LENGTH_SHORT).show();
                    finish();
                }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.option_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.scan:
                // Launch the DeviceListActivity to see devices and do scan
                Intent serverIntent = new Intent(this, DeviceListActivity.class);
                startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
                return true;
            case R.id.discoverable:
                // Ensure this device is discoverable by others
                ensureDiscoverable();
                return true;
        }
        return false;
    }

    private static String[] getBatteryStatus(Context context) {
        String[] returnStatus = new String[]{"-", "-"};
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = context.registerReceiver(null, ifilter);
            // Are we charging / charged?
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL;
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            float batteryPct = level / (float) scale;

            returnStatus[0] = String.format("%.0f", batteryPct * 100);
            if (isCharging == true) {
                returnStatus[1] = "yes";
            } else {
                returnStatus[1] = "no";
            }
            return returnStatus;
        } catch (Exception e) {
            return returnStatus;
        }
    }

    private static String getWifiStrength(Context context) {
        try {
            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            int rssi = wifiManager.getConnectionInfo().getRssi();
            int percentage = wifiManager.calculateSignalLevel(rssi, 100);
            return Integer.toString(percentage);
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    void lightOn() {
        try {
            camObject = Camera.open();
            Camera.Parameters p = camObject.getParameters();
            p.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
            camObject.setParameters(p);
            camObject.startPreview();
        } catch (Exception e) {
            final Exception eFinal = e;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), "exception: " + eFinal.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    void lightOff() {
        try {
            camObject.stopPreview();
            camObject.release();
        } catch (Exception e) {
            final Exception eFinal = e;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), "exception: " + eFinal.getMessage(), Toast.LENGTH_LONG).show();
                }
            });

        }
    }

    public class WebServer extends NanoHTTPD {
        public WebServer() {
            super(44444);
        }

        private String feedbackString = "-"; // To send feedback to the client

        @Override
        public Response serve(IHTTPSession session) {
            Method method = session.getMethod();
            String uri = session.getUri();
            System.out.println(method + " '" + uri + "' ");

            String answer = "";

            // Remove comments below for debugging purposes, this will show the uri
            /*
            final String uriFinal = uri;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), "uri = " + uriFinal, Toast.LENGTH_SHORT).show();
                }
            });
            */

            // Handle style sheets
            if (uri.contains(".css")) {
                try {

                    File root = Environment.getExternalStorageDirectory();
                    InputStream mbuffer = new FileInputStream(root.getAbsolutePath() + "/www" + uri);
                    return new NanoHTTPD.Response(Response.Status.OK, MIME_CSS, mbuffer);

                } catch (IOException ioe) {
                    final IOException ioeFinal = ioe;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getApplicationContext(), "exception: " + ioeFinal.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                    Log.w("Httpd", ioe.toString());
                    return new NanoHTTPD.Response(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal error");
                }
            }

            // Handle jpg images
            if (uri.contains(".jpg")) {
                try {

                    File root = Environment.getExternalStorageDirectory();
                    InputStream mbuffer = new FileInputStream(root.getAbsolutePath() + "/www" + uri);
                    return new NanoHTTPD.Response(Response.Status.OK, MIME_JPG, mbuffer);
                } catch (IOException ioe) {
                    final IOException ioeFinal = ioe;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getApplicationContext(), "exception: " + ioeFinal.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                    Log.w("Httpd", ioe.toString());
                    return new NanoHTTPD.Response(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal error");
                }
            }

            // Handle png images
            if (uri.contains(".png")) {
                try {

                    File root = Environment.getExternalStorageDirectory();
                    InputStream mbuffer = new FileInputStream(root.getAbsolutePath() + "/www" + uri);
                    return new NanoHTTPD.Response(Response.Status.OK, MIME_PNG, mbuffer);
                } catch (IOException ioe) {
                    final IOException ioeFinal = ioe;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getApplicationContext(), "exception: " + ioeFinal.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                    Log.w("Httpd", ioe.toString());
                    return new NanoHTTPD.Response(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal error");
                }
            }

            // Handle javascript
            if (uri.contains(".js")) {
                try {

                    File root = Environment.getExternalStorageDirectory();
                    InputStream mbuffer = new FileInputStream(root.getAbsolutePath() + "/www" + uri);
                    return new NanoHTTPD.Response(Response.Status.OK, MIME_JS, mbuffer);
                } catch (IOException ioe) {
                    final IOException ioeFinal = ioe;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getApplicationContext(), "exception: " + ioeFinal.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                    Log.w("Httpd", ioe.toString());
                    return new NanoHTTPD.Response(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal error");
                }
            }

            // Handle .html
            // uri will be "/" for the main page, here index.html.
            // For other pages (like used in a frameset) the uri will be something like "/index1.html".
            if (uri.contains(".html") || uri.equals("/")) {
                Map<String, String> parms = session.getParms();
                try {
                    // Open file from SD Card
                    File root = Environment.getExternalStorageDirectory();
                    String fileToOpen = "/index.html"; // start page
                    if (uri.contains(".html")) {
                        fileToOpen = uri;
                    }
                    FileReader index = new FileReader(root.getAbsolutePath() +
                            "/www" + fileToOpen);

                    BufferedReader reader = new BufferedReader(index);
                    String line = "";
                    while ((line = reader.readLine()) != null) {
                        answer += line + "\n";
                    }
                    reader.close();
                } catch (IOException ioe) {
                    final IOException ioeFinal = ioe;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getApplicationContext(), "exception: " + ioeFinal.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                    Log.w("Httpd", ioe.toString());
                }

                // Construct a feedback string to sent back to the client.
                //
                // Construct the feedback containing battery level and charging status
                String[] batteryStatus = new String[2];
                batteryStatus = getBatteryStatus(getApplicationContext());

                // Construct the feedback containing wifi strength
                String wifiStrengthPercentage = getWifiStrength(getApplicationContext());

                // Construct the feedback containing the message from the EV3.
                // Because the raw message contains unknown characters at the start these are removed.
                // This is done by looking for the '<' character because it is assumed that
                // the real message part starts with a html <br> character.
                // This is to be taken care of in the EV3 program!
                // This way also the name of the EV3 message box is removed.
                String ev3Cmd = parms.get("ev3cmd");
                String messageFromEv3Readable = "";
                if (rawMessageFromEv3 != "") {
                    int idx = rawMessageFromEv3.indexOf("<");
                    if (idx == -1) {  // display raw message if there is no '<'
                        idx = 0;
                    }
                    messageFromEv3Readable = rawMessageFromEv3.substring(idx);

                }
                // rawMessageFromEv3 keeps its value, which is ok as we want to keep showing
                // the last message of the EV3 until a new message arrives.
                // However, we reset it to "" when the connection to the EV3 is lost.
                if (!bluetoothStatus.equals("connected")) {
                    rawMessageFromEv3 = "";
                }

                // If no ev3 cmd, fill in "-" in the feedback string
                String tmpEv3Cmd;
                if (ev3Cmd == null) {
                    tmpEv3Cmd = "-";
                } else {
                    tmpEv3Cmd = ev3Cmd;
                }

                // Now compose the complete feedback string
                feedbackString =
                        "<br>battery level = " + batteryStatus[0] +
                                "<br>charging = " + batteryStatus[1] +
                                "<br>wifi strength = " + wifiStrengthPercentage +
                                "<br>cmd sent to EV3 = " + tmpEv3Cmd +
                                "<br><b>EV3 status:</b>" +
                                "<br>bluetooth status = " + bluetoothStatus +
                                messageFromEv3Readable; // messageFromEv3Readable starts with <br>

                String answerWithFeedback = answer.replace("feedbackstring", feedbackString);

                // Handle html button actions with name 'ev3cmd' and send feedback
                if (ev3Cmd != null) {
                    // Send the EV3 cmd received from the client to the EV3
                    // Run on UI thread to avoid "Only the original thread that created a view hierarchy can touch its views" error
                    // or "Can't create handler inside thread that has not called Looper.prepare()" error
                    try {
                        final String ev3CmdFinal = ev3Cmd;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                sendEV3Message(ev3CmdFinal);
                            }
                        });
                    } catch (Exception e) {
                        final String eFinal = e.getMessage();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(getApplicationContext(), "ecxeption" + eFinal, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }

                // Handle html button actions with name 'servercmd' and send feedback
                String serverCmd = parms.get("servercmd");
                if (serverCmd != null) {
                    if (serverCmd.contains("cmd:connect-ev3")) {
                        // Get BlueTooth address from the command which are the last 17 characters
                        String btAddr = serverCmd.substring(serverCmd.length() - 17);
                        try {
                            BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(btAddr);
                            //Attempt to connect to the device
                            mChatService.connect(device);
                        } catch (Exception e) {
                            final String eFinal = e.getMessage();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(getApplicationContext(), "ecxeption" + eFinal, Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    }
                    if (serverCmd.equals("cmd:light-on")) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                lightOn();
                            }
                        });
                    } else if (serverCmd.equals("cmd:light-off")) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                lightOff();
                            }
                        });
                    }
                }
                return new NanoHTTPD.Response(Response.Status.OK, MIME_HTML, answerWithFeedback);
            }
            // Should not come here
            return new NanoHTTPD.Response(Response.Status.OK, MIME_PLAINTEXT, "unsupported MIME type");
        }
    }
}