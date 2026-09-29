package io.bbui.device;
import android.os.ParcelFileDescriptor;
import android.os.IBinder;

interface IDeviceService {
    String start(in ParcelFileDescriptor server, IBinder lease) = 0;
    ParcelFileDescriptor video() = 1;
    ParcelFileDescriptor control() = 2;
    String inspect() = 3;
    String launch(String component) = 4;
    void stop() = 5;
    String packages() = 6;
    String system(String request) = 7;
    oneway void setSystemStopped(long epoch) = 8;
    void initializeSupport(in ParcelFileDescriptor server) = 9;
    void beginSystemActions(long epoch) = 10;
    void destroy() = 16777114;
}
