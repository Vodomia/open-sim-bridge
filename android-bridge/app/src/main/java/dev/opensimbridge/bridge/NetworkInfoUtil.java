package dev.opensimbridge.bridge;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

final class NetworkInfoUtil {
    private NetworkInfoUtil() {}

    static List<String> localUrls(int port) {
        List<String> result = new ArrayList<>();

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface iface : Collections.list(interfaces)) {
                    if (!iface.isUp() || iface.isLoopback()) continue;

                    Enumeration<InetAddress> addresses = iface.getInetAddresses();
                    for (InetAddress address : Collections.list(addresses)) {
                        if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                            result.add("http://" + address.getHostAddress() + ":" + port + "/");
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        if (result.isEmpty()) {
            result.add("http://192.168.43.1:" + port + "/");
        }
        return result;
    }
}
