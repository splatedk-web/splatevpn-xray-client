package com.danieldk.splatevpn.vpn;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Framework-only service: its separate APK process has no target-app Kotlin classloader. */
public final class AppIdentityTestSocketService extends Service {
    public static final String DESCRIPTOR = "com.danieldk.splatevpn.test.socket";

    @Override public IBinder onBind(Intent intent) {
        return new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
                data.enforceInterface(DESCRIPTOR);
                String protocol = data.readString();
                int port = data.readInt();
                String host = data.readString();
                String result = request(protocol, port, host);
                reply.writeNoException();
                reply.writeInt(android.os.Process.myUid());
                reply.writeString(result);
                return true;
            }
        };
    }

    public static String request(String protocol, int port, String host) {
        try {
            if (port < 1 || port > 65535) return "";
            if ("tcp".equals(protocol)) {
                try (Socket socket = new Socket()) {
                    socket.setSoTimeout(1800);
                    socket.connect(new InetSocketAddress("198.18.0.8", port), 1800);
                    socket.getOutputStream().write(("GET /uid HTTP/1.1\r\nHost: " + host +
                        "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    InputStream input = socket.getInputStream();
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        bytes.write(buffer, 0, count);
                        if (bytes.size() > 8192) return "";
                    }
                    String response = new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
                    int split = response.indexOf("\r\n\r\n");
                    return split < 0 ? "" : response.substring(split + 4);
                }
            }
            if ("udp".equals(protocol)) {
                try (DatagramSocket socket = new DatagramSocket()) {
                    socket.setSoTimeout(1800);
                    socket.connect(InetAddress.getByName("198.18.0.8"), port);
                    byte[] bytes = "uid-native-echo".getBytes(StandardCharsets.US_ASCII);
                    socket.send(new DatagramPacket(bytes, bytes.length));
                    DatagramPacket response = new DatagramPacket(new byte[512], 512);
                    socket.receive(response);
                    return new String(response.getData(), response.getOffset(), response.getLength(), StandardCharsets.US_ASCII);
                }
            }
        } catch (Exception ignored) { }
        return "";
    }
}
