// Copyright 2017, Paul DeMarco.
// All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.pauldemarco.flutterblue;

import android.app.Activity;
import android.Manifest;
import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.pauldemarco.flutter_blue.Protos;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.EventChannel.StreamHandler;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener;


/**
 * FlutterBluePlugin
 */
public class FlutterBluePlugin implements MethodCallHandler, RequestPermissionsResultListener  {
    private static final String TAG = "FlutterBluePlugin";
    private static final String NAMESPACE = "plugins.pauldemarco.com/flutter_blue";
    private static final int REQUEST_COARSE_LOCATION_PERMISSIONS = 1452;
    static final private UUID CCCD_ID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Registrar registrar;
    private final Activity activity;
    private final MethodChannel channel;
    private final EventChannel stateChannel;
    private final BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private final Map<String, BluetoothGatt> mGattServers = new HashMap<>();
    private LogLevel logLevel = LogLevel.EMERGENCY;

    // Pending call and result for startScan, in the case where permissions are needed
    private MethodCall pendingCall;
    private Result pendingResult;

    // advertisement
    private BluetoothLeAdvertiser mBluetoothLeAdvertiser;
    private boolean mServiceAdvertised;

    // server
    private BluetoothGattServer mBluetoothGattServer;
    private boolean mServerActive;
    private HashMap<String, BluetoothDevice> gattClients = new HashMap<>();

    /**
     * Plugin registration.
     */
    public static void registerWith(Registrar registrar) {
        final FlutterBluePlugin instance = new FlutterBluePlugin(registrar);
        registrar.addRequestPermissionsResultListener(instance);
    }

    FlutterBluePlugin(Registrar r){
        this.registrar = r;
        this.activity = r.activity();
        this.channel = new MethodChannel(registrar.messenger(), NAMESPACE+"/methods");
        this.stateChannel = new EventChannel(registrar.messenger(), NAMESPACE+"/state");
        this.mBluetoothManager = (BluetoothManager) r.activity().getSystemService(Context.BLUETOOTH_SERVICE);
        this.mBluetoothAdapter = mBluetoothManager.getAdapter();
        channel.setMethodCallHandler(this);
        stateChannel.setStreamHandler(stateHandler);
    }

    @Override
    public void onMethodCall(MethodCall call, Result result) {
        if(mBluetoothAdapter == null && !"isAvailable".equals(call.method)) {
            result.error("bluetooth_unavailable", "the device does not have bluetooth", null);
            return;
        }

        switch (call.method) {
            case "setLogLevel":
            {
                int logLevelIndex = (int)call.arguments;
                logLevel = LogLevel.values()[logLevelIndex];
                result.success(null);
                break;
            }

            case "state":
            {
                Protos.BluetoothState.Builder p = Protos.BluetoothState.newBuilder();
                try {
                    switch(mBluetoothAdapter.getState()) {
                        case BluetoothAdapter.STATE_OFF:
                            p.setState(Protos.BluetoothState.State.OFF);
                            break;
                        case BluetoothAdapter.STATE_ON:
                            p.setState(Protos.BluetoothState.State.ON);
                            break;
                        case BluetoothAdapter.STATE_TURNING_OFF:
                            p.setState(Protos.BluetoothState.State.TURNING_OFF);
                            break;
                        case BluetoothAdapter.STATE_TURNING_ON:
                            p.setState(Protos.BluetoothState.State.TURNING_ON);
                            break;
                        default:
                            p.setState(Protos.BluetoothState.State.UNKNOWN);
                            break;
                    }
                } catch (SecurityException e) {
                    p.setState(Protos.BluetoothState.State.UNAUTHORIZED);
                }
                result.success(p.build().toByteArray());
                break;
            }

            case "isAvailable":
            {
                result.success(mBluetoothAdapter != null);
                break;
            }

            case "isServerAvailable":
            {
                final BluetoothLeAdvertiser advertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
                result.success(mBluetoothAdapter != null && advertiser != null);
                break;
            }

            case "isOn":
            {
                result.success(mBluetoothAdapter.isEnabled());
                break;
            }

            case "startScan":
            {
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                            activity,
                            new String[] {
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                            },
                            REQUEST_COARSE_LOCATION_PERMISSIONS);
                    pendingCall = call;
                    pendingResult = result;
                    break;
                }
                startScan(call, result);
                break;
            }

            case "stopScan":
            {
                stopScan();
                result.success(null);
                break;
            }

            case "startAdvertisement":
            {
                startAdvertisement(call, result);
                break;
            }

            case "stopAdvertisement":
            {
                stopAdvertisement();
                result.success(null);
                break;
            }

            case "startServer":
            {
                startServer(call, result);
                break;
            }

            case "stopServer":
            {
                stopServer();
                result.success(null);
                break;
            }

            case "getConnectedDevices":
            {
                List<BluetoothDevice> devices = mBluetoothManager.getConnectedDevices(BluetoothProfile.GATT);
                Protos.ConnectedDevicesResponse.Builder p = Protos.ConnectedDevicesResponse.newBuilder();
                for(BluetoothDevice d : devices) {
                    p.addDevices(ProtoMaker.from(d));
                }
                result.success(p.build().toByteArray());
                log(LogLevel.EMERGENCY, "mGattServers size: " + mGattServers.size());
                break;
            }

            case "connect":
            {
                byte[] data = call.arguments();
                Protos.ConnectRequest options;
                try {
                    options = Protos.ConnectRequest.newBuilder().mergeFrom(data).build();
                } catch (InvalidProtocolBufferException e) {
                    result.error("RuntimeException", e.getMessage(), e);
                    break;
                }
                String deviceId = options.getRemoteId();
                BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(deviceId);
                boolean isConnected = mBluetoothManager.getConnectedDevices(BluetoothProfile.GATT).contains(device);

                // If device is already connected, return error
                if(mGattServers.containsKey(deviceId) && isConnected) {
                    result.error("already_connected", "connection with device already exists", null);
                    return;
                }

                // If device was connected to previously but is now disconnected, attempt a reconnect
                // If reconnect fails cleanup the GATT server instance and carry on creating a new one
                if(mGattServers.containsKey(deviceId) && !isConnected) {
                    if (!mGattServers.get(deviceId).connect()) {
                        mGattServers.get(deviceId).close();
                        mGattServers.remove(deviceId);
                    } else {
                        result.success(null);
                        break;
                    }
                }

                // New request, connect and add gattServer to Map
                BluetoothGatt gattServer;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    gattServer = device.connectGatt(activity, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
                } else {
                    gattServer = device.connectGatt(activity, false, mGattCallback);
                }

                if (options.getAndroidAutoConnect()) {
                    gattServer.connect();
                }
                mGattServers.put(deviceId, gattServer);
                result.success(null);
                break;
            }

            case "disconnect":
            {
                String deviceId = (String)call.arguments;

                final BluetoothDevice gattClient = gattClients.remove(deviceId);
                if (gattClient != null) {
                    mBluetoothGattServer.cancelConnection(gattClient);
                }

                BluetoothGatt gattServer = mGattServers.remove(deviceId);
                if (gattServer != null) {
					boolean skipCall = false;
					final BluetoothAdapter localBluetoothAdapter = mBluetoothAdapter;
					if (localBluetoothAdapter != null) {
                        skipCall = !localBluetoothAdapter.isEnabled();
                    }

					if (!skipCall) {
						gattServer.disconnect();
					}
                }
                result.success(null);
                break;
            }

            case "deviceState":
            {
                String deviceId = (String)call.arguments;
                BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(deviceId);
                int state = mBluetoothManager.getConnectionState(device, BluetoothProfile.GATT);
                try {
                    result.success(ProtoMaker.from(device, state).toByteArray());
                } catch(Exception e) {
                    result.error("device_state_error", e.getMessage(), null);
                }
                break;
            }

            case "discoverServices":
            {
                String deviceId = (String)call.arguments;
                BluetoothGatt gattServer = mGattServers.get(deviceId);
                if(gattServer == null) {
                    result.error("discover_services_error", "no instance of BluetoothGatt, have you connected first?", null);
                    return;
                }
                if(gattServer.discoverServices()) {
                    result.success(null);
                } else {
                    result.error("discover_services_error", "unknown reason", null);
                }
                break;
            }

            case "requestMTU":
            {
                byte[] data = call.arguments();
                Protos.RequestMTURequest options;
                try {
                    options = Protos.RequestMTURequest.newBuilder().mergeFrom(data).build();
                } catch (InvalidProtocolBufferException e) {
                    result.error("RuntimeException", e.getMessage(), e);
                    break;
                }
                String deviceId = options.getRemoteId();
                int localMTUSize = options.getLocalMTUSize();

                BluetoothGatt gattServer = mGattServers.get(deviceId);
                if(gattServer == null) {
                    result.error("request_mtu_error", "no instance of BluetoothGatt, have you connected first?", null);
                    return;
                }
                if (localMTUSize <= 0) {
                    result.error("request_mtu_error", "invalid mtu size requested", null);
                    return;
                }

                if(gattServer.requestMtu(localMTUSize)) {
                    result.success(null);
                } else {
                    result.error("request_mtu_error", "requestMtu call failed", null);
                }
                break;
            }

            case "services":
            {
                String deviceId = (String)call.arguments;
                BluetoothGatt gattServer = mGattServers.get(deviceId);
                if(gattServer == null) {
                    result.error("get_services_error", "no instance of BluetoothGatt, have you connected first?", null);
                    return;
                }
                Protos.DiscoverServicesResult.Builder p = Protos.DiscoverServicesResult.newBuilder();
                p.setRemoteId(deviceId);
                for(BluetoothGattService s : gattServer.getServices()){
                    p.addServices(ProtoMaker.from(gattServer.getDevice(), s, gattServer.getServices()));
                }
                result.success(p.build().toByteArray());
                break;
            }

            case "servicesServer":
            {
                final String deviceId = (String)call.arguments;
                BluetoothDevice targetDevice = null;
                for (final BluetoothDevice device : mBluetoothManager.getConnectedDevices(BluetoothProfile.GATT)) {
                    if (device.getAddress().equals(deviceId)) {
                        targetDevice = device;
                        break;
                    }
                }
                if (targetDevice == null) {
                    result.error("services_server_error", "no instance of BluetoothDevice, is it connected?", null);
                    return;
                }

                Protos.DiscoverServicesResult.Builder p = Protos.DiscoverServicesResult.newBuilder();
                p.setRemoteId(deviceId);
                for (BluetoothGattService s : mBluetoothGattServer.getServices()) {
                    p.addServices(ProtoMaker.from(targetDevice, s, mBluetoothGattServer.getServices()));
                }
                result.success(p.build().toByteArray());
                break;
            }

            case "readCharacteristic":
            {
                byte[] data = call.arguments();
                Protos.ReadCharacteristicRequest request;
                try {
                    request = Protos.ReadCharacteristicRequest.newBuilder().mergeFrom(data).build();
                } catch (InvalidProtocolBufferException e) {
                    result.error("RuntimeException", e.getMessage(), e);
                    break;
                }

                BluetoothGatt gattServer;
                BluetoothGattCharacteristic characteristic;
                try {
                    gattServer = locateGatt(request.getRemoteId());
                    characteristic = locateCharacteristic(gattServer, request.getServiceUuid(), request.getSecondaryServiceUuid(), request.getCharacteristicUuid());
                } catch(Exception e) {
                    result.error("read_characteristic_error", e.getMessage(), null);
                    return;
                }

                if(gattServer.readCharacteristic(characteristic)) {
                    result.success(null);
                } else {
                    result.error("read_characteristic_error", "unknown reason, may occur if readCharacteristic was called before last read finished.", null);
                }
                break;
            }

            case "readDescriptor":
            {
                byte[] data = call.arguments();
                Protos.ReadDescriptorRequest request;
                try {
                    request = Protos.ReadDescriptorRequest.newBuilder().mergeFrom(data).build();
                } catch (InvalidProtocolBufferException e) {
                    result.error("RuntimeException", e.getMessage(), e);
                    break;
                }

                BluetoothGatt gattServer;
                BluetoothGattCharacteristic characteristic;
                BluetoothGattDescriptor descriptor;
                try {
                    gattServer = locateGatt(request.getRemoteId());
                    characteristic = locateCharacteristic(gattServer, request.getServiceUuid(), request.getSecondaryServiceUuid(), request.getCharacteristicUuid());
                    descriptor = locateDescriptor(characteristic, request.getDescriptorUuid());
                } catch(Exception e) {
                    result.error("read_descriptor_error", e.getMessage(), null);
                    return;
                }

                if(gattServer.readDescriptor(descriptor)) {
                    result.success(null);
                } else {
                    result.error("read_descriptor_error", "unknown reason, may occur if readDescriptor was called before last read finished.", null);
                }
                break;
            }

            case "writeCharacteristic":
            {
                byte[] data = call.arguments();
                Protos.WriteCharacteristicRequest request;
                try {
                    request = Protos.WriteCharacteristicRequest.newBuilder().mergeFrom(data).build();
                } catch (InvalidProtocolBufferException e) {
                    result.error("RuntimeException", e.getMessage(), e);
                    break;
                }

                BluetoothGatt gattServer;
                BluetoothGattCharacteristic characteristic;
                try {
                    gattServer = locateGatt(request.getRemoteId());
                    characteristic = locateCharacteristic(gattServer, request.getServiceUuid(), request.getSecondaryServiceUuid(), request.getCharacteristicUuid());
                } catch(Exception e) {
                    result.error("write_characteristic_error", e.getMessage(), null);
                    return;
                }

                // Set characteristic to new value
                if(!characteristic.setValue(request.getValue().toByteArray())){
                    result.error("write_characteristic_error", "could not set the local value of characteristic", null);
                }

                // Apply the correct write type
                if(request.getWriteType() == Protos.WriteCharacteristicRequest.WriteType.WITHOUT_RESPONSE) {
                    characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                } else {
                    characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                }

                if(!gattServer.writeCharacteristic(characteristic)){
                    result.error("write_characteristic_error", "writeCharacteristic failed", null);
                    return;
                }

                result.success(null);
                break;
            }

            case "writeDescriptor":
            {
                byte[] data = call.arguments();
                Protos.WriteDescriptorRequest request;
                try {
                    request = Protos.WriteDescriptorRequest.newBuilder().mergeFrom(data).build();
                } catch (InvalidProtocolBufferException e) {
                    result.error("RuntimeException", e.getMessage(), e);
                    break;
                }

                BluetoothGatt gattServer;
                BluetoothGattCharacteristic characteristic;
                BluetoothGattDescriptor descriptor;
                try {
                    gattServer = locateGatt(request.getRemoteId());
                    characteristic = locateCharacteristic(gattServer, request.getServiceUuid(), request.getSecondaryServiceUuid(), request.getCharacteristicUuid());
                    descriptor = locateDescriptor(characteristic, request.getDescriptorUuid());
                } catch(Exception e) {
                    result.error("write_descriptor_error", e.getMessage(), null);
                    return;
                }

                // Set descriptor to new value
                if(!descriptor.setValue(request.getValue().toByteArray())){
                    result.error("write_descriptor_error", "could not set the local value for descriptor", null);
                }

                if(!gattServer.writeDescriptor(descriptor)){
                    result.error("write_descriptor_error", "writeCharacteristic failed", null);
                    return;
                }

                result.success(null);
                break;
            }

            case "setNotification":
            {
                byte[] data = call.arguments();
                Protos.SetNotificationRequest request;
                try {
                    request = Protos.SetNotificationRequest.newBuilder().mergeFrom(data).build();
                } catch (InvalidProtocolBufferException e) {
                    result.error("RuntimeException", e.getMessage(), e);
                    break;
                }

                BluetoothGatt gattServer;
                BluetoothGattCharacteristic characteristic;
                BluetoothGattDescriptor cccDescriptor;
                try {
                    gattServer = locateGatt(request.getRemoteId());
                    characteristic = locateCharacteristic(gattServer, request.getServiceUuid(), request.getSecondaryServiceUuid(), request.getCharacteristicUuid());
                    cccDescriptor = characteristic.getDescriptor(CCCD_ID);
                    if(cccDescriptor == null) {
                        throw new Exception("could not locate CCCD descriptor for characteristic: " +characteristic.getUuid().toString());
                    }
                } catch(Exception e) {
                    result.error("set_notification_error", e.getMessage(), null);
                    return;
                }

                byte[] value = null;

                if(request.getEnable()) {
                    boolean canNotify = (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0;
                    boolean canIndicate = (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) > 0;
                    if(!canIndicate && !canNotify) {
                        result.error("set_notification_error", "the characteristic cannot notify or indicate", null);
                        return;
                    }
                    if(canIndicate) {
                        value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                    }
                    if(canNotify) {
                        value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                    }
                } else {
                    value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                }

                if(!gattServer.setCharacteristicNotification(characteristic, request.getEnable())){
                    result.error("set_notification_error", "could not set characteristic notifications to :" + request.getEnable(), null);
                    return;
                }

                if(!cccDescriptor.setValue(value)) {
                    result.error("set_notification_error", "error when setting the descriptor value to: " + value, null);
                    return;
                }

                if(!gattServer.writeDescriptor(cccDescriptor)) {
                    result.error("set_notification_error", "error when writing the descriptor", null);
                    return;
                }

                result.success(null);
                break;
            }

            default:
            {
                result.notImplemented();
                break;
            }
        }
    }

    @Override
    public boolean onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_COARSE_LOCATION_PERMISSIONS) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScan(pendingCall, pendingResult);
            } else {
                pendingResult.error(
                        "no_permissions", "flutter_blue plugin requires location permissions for scanning", null);
                pendingResult = null;
                pendingCall = null;
            }
            return true;
        }
        return false;
    }

    private BluetoothGatt locateGatt(String remoteId) throws Exception {
        BluetoothGatt gattServer = mGattServers.get(remoteId);
        if(gattServer == null) {
            throw new Exception("no instance of BluetoothGatt, have you connected first?");
        }
        return gattServer;
    }

    private BluetoothGattCharacteristic locateCharacteristic(BluetoothGatt gattServer, String serviceId, String secondaryServiceId, String characteristicId) throws Exception {
        BluetoothGattService primaryService = gattServer.getService(UUID.fromString(serviceId));
        if(primaryService == null) {
            throw new Exception("service (" + serviceId + ") could not be located on the device");
        }
        BluetoothGattService secondaryService = null;
        if(secondaryServiceId.length() > 0) {
            for(BluetoothGattService s : primaryService.getIncludedServices()){
                if(s.getUuid().equals(UUID.fromString(secondaryServiceId))){
                    secondaryService = s;
                }
            }
            if(secondaryService == null) {
                throw new Exception("secondary service (" + secondaryServiceId + ") could not be located on the device");
            }
        }
        BluetoothGattService service = (secondaryService != null) ? secondaryService : primaryService;
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(characteristicId));
        if(characteristic == null) {
            throw new Exception("characteristic (" + characteristicId + ") could not be located in the service ("+service.getUuid().toString()+")");
        }
        return characteristic;
    }

    private BluetoothGattDescriptor locateDescriptor(BluetoothGattCharacteristic characteristic, String descriptorId) throws Exception {
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUID.fromString(descriptorId));
        if(descriptor == null) {
            throw new Exception("descriptor (" + descriptorId + ") could not be located in the characteristic ("+characteristic.getUuid().toString()+")");
        }
        return descriptor;
    }

    private final StreamHandler stateHandler = new StreamHandler() {
        private EventSink sink;

        private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();

                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                    final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR);
                    switch (state) {
                        case BluetoothAdapter.STATE_OFF:
                            sink.success(Protos.BluetoothState.newBuilder().setState(Protos.BluetoothState.State.OFF).build().toByteArray());
                            break;
                        case BluetoothAdapter.STATE_TURNING_OFF:
                            sink.success(Protos.BluetoothState.newBuilder().setState(Protos.BluetoothState.State.TURNING_OFF).build().toByteArray());
                            break;
                        case BluetoothAdapter.STATE_ON:
                            sink.success(Protos.BluetoothState.newBuilder().setState(Protos.BluetoothState.State.ON).build().toByteArray());
                            break;
                        case BluetoothAdapter.STATE_TURNING_ON:
                            sink.success(Protos.BluetoothState.newBuilder().setState(Protos.BluetoothState.State.TURNING_ON).build().toByteArray());
                            break;
                    }
                }
            }
        };

        @Override
        public void onListen(Object o, EventChannel.EventSink eventSink) {
            sink = eventSink;
            IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            activity.registerReceiver(mReceiver, filter);
        }

        @Override
        public void onCancel(Object o) {
            sink = null;
            activity.unregisterReceiver(mReceiver);
        }
    };

    private void startScan(MethodCall call, Result result) {
        byte[] data = call.arguments();
        Protos.ScanSettings settings;
        try {
            settings = Protos.ScanSettings.newBuilder().mergeFrom(data).build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                startScan21(settings);
            } else {
                startScan18(settings);
            }
            result.success(null);
        } catch (Exception e) {
            result.error("startScan", e.getMessage(), e);
        }
    }

    private void stopScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            stopScan21();
        } else {
            stopScan18();
        }
    }

    private ScanCallback scanCallback21;

    @TargetApi(21)
    private ScanCallback getScanCallback21() {
        if(scanCallback21 == null){
            scanCallback21 = new ScanCallback() {

                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    super.onScanResult(callbackType, result);
                    Protos.ScanResult scanResult = ProtoMaker.from(result.getDevice(), result);
                    invokeMethodUIThread("ScanResult", scanResult.toByteArray());
                }

                @Override
                public void onBatchScanResults(List<ScanResult> results) {
                    super.onBatchScanResults(results);

                }

                @Override
                public void onScanFailed(int errorCode) {
                    super.onScanFailed(errorCode);
                }
            };
        }
        return scanCallback21;
    }

    @TargetApi(21)
    private void startScan21(Protos.ScanSettings proto) throws IllegalStateException {
        BluetoothLeScanner scanner = mBluetoothAdapter.getBluetoothLeScanner();
        if(scanner == null) throw new IllegalStateException("getBluetoothLeScanner() is null. Is the Adapter on?");
        int scanMode = proto.getAndroidScanMode();
        int count = proto.getServiceUuidsCount();
        List<ScanFilter> filters = new ArrayList<>(count);
        for(int i = 0; i < count; i++) {
            String uuid = proto.getServiceUuids(i);
            ScanFilter f = new ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(uuid)).build();
            filters.add(f);
        }
        ScanSettings settings = new ScanSettings.Builder().setScanMode(scanMode).build();
        scanner.startScan(filters, settings, getScanCallback21());
    }

    @TargetApi(21)
    private void stopScan21() {
        BluetoothLeScanner scanner = mBluetoothAdapter.getBluetoothLeScanner();
        if(scanner != null) scanner.stopScan(getScanCallback21());
    }

    private BluetoothAdapter.LeScanCallback scanCallback18;

    private BluetoothAdapter.LeScanCallback getScanCallback18() {
        if(scanCallback18 == null) {
            scanCallback18 = new BluetoothAdapter.LeScanCallback() {
                @Override
                public void onLeScan(final BluetoothDevice bluetoothDevice, int rssi,
                                     byte[] scanRecord) {
                    Protos.ScanResult scanResult = ProtoMaker.from(bluetoothDevice, scanRecord, rssi);
                    invokeMethodUIThread("ScanResult", scanResult.toByteArray());
                }
            };
        }
        return scanCallback18;
    }

    private void startScan18(Protos.ScanSettings proto) throws IllegalStateException {
        List<String> serviceUuids = proto.getServiceUuidsList();
        UUID[] uuids = new UUID[serviceUuids.size()];
        for(int i = 0; i < serviceUuids.size(); i++) {
            uuids[i] = UUID.fromString(serviceUuids.get(i));
        }
        boolean success = mBluetoothAdapter.startLeScan(uuids, getScanCallback18());
        if(!success) throw new IllegalStateException("getBluetoothLeScanner() is null. Is the Adapter on?");
    }

    private void stopScan18() {
        mBluetoothAdapter.stopLeScan(getScanCallback18());
    }

    private void startAdvertisement(MethodCall call, Result result) {
        if (mServiceAdvertised) {
            result.error("bluetooth_advertisement_error",
                    "advertisement already started",
                    null);
            return;
        }

        byte[] data = call.arguments();
        Protos.ServerAdvertisePayload payload;
        try {
            payload = Protos.ServerAdvertisePayload.newBuilder().mergeFrom(data).build();
        } catch (InvalidProtocolBufferException e) {
            result.error("RuntimeException", e.getMessage(), e);
            return;
        }

        if (payload.getServiceUuid() == null) {
            result.error("bluetooth_advertisement_error",
                    "start advertisement must have service UUID set!",
                    null);
            return;
        }
        if (payload.getServiceUuid().isEmpty()) {
            result.error("bluetooth_advertisement_error",
                    "start advertisement must have service UUID set!",
                    null);
            return;
        }

        mServiceAdvertised = false;
        mBluetoothLeAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        if (mBluetoothLeAdvertiser == null) {
            result.error("bluetooth_advertisement_error",
                    "unable to start advertisement, failed to get bluetooth le advertiser!",
                    null);
            return;
        }

        final AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build();

        final ParcelUuid serviceUUID = ParcelUuid.fromString(payload.getServiceUuid());
        final AdvertiseData advrData = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(serviceUUID)
                .build();

        final AdvertiseData.Builder scanResponseBuilder = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false);

        if (!payload.getManufacturerData().isEmpty()) {
            scanResponseBuilder.addManufacturerData(payload.getManufacturerID(), payload.getManufacturerData().toByteArray());
        }
        final AdvertiseData scanResponse = scanResponseBuilder.build();

        mBluetoothLeAdvertiser.startAdvertising(settings, advrData, scanResponse, mAdvertiseCallback);
        result.success(null);
    }

    private void stopAdvertisement() {
        if (mBluetoothLeAdvertiser != null) {
            if (mServiceAdvertised) {
                mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
            }
            mBluetoothLeAdvertiser = null;
        }
        mServiceAdvertised = false;
    }

    private void startServer(MethodCall call, Result result) {
        if (mServerActive) {
            result.error("bluetooth_server_error",
                    "server already started",
                    null);
            return;
        }

        byte[] data = call.arguments();
        Protos.BluetoothService payload;
        try {
            payload = Protos.BluetoothService.newBuilder().mergeFrom(data).build();
        } catch (InvalidProtocolBufferException e) {
            result.error("RuntimeException", e.getMessage(), e);
            return;
        }

        if (payload.getUuid() == null) {
            result.error("bluetooth_server_error",
                    "start server must have root service UUID set!",
                    null);
            return;
        }
        if (payload.getUuid().isEmpty()) {
            result.error("bluetooth_server_error",
                    "start server must have root service UUID set!",
                    null);
            return;
        }

        final BluetoothGattService service = ServiceBuilder.serviceFromProtoMessage(payload, result);
        if (service == null) {
            // NOTE: result error state already set!
            return;
        }

        mBluetoothGattServer = mBluetoothManager.openGattServer(activity.getApplicationContext(), mGattServerCallback);
        if (mBluetoothGattServer == null) {
            result.error("bluetooth_server_error",
                    "start server failed to open gatt server!",
                    null);
            return;
        }

        if (!mBluetoothGattServer.addService(service)) {
            mBluetoothGattServer.close();
            mBluetoothGattServer = null;

            result.error("bluetooth_server_error",
                    "start server failed to add gatt service to server!",
                    null);
            return;
        }

        result.success(null);
    }

    private void stopServer() {
        if (mBluetoothGattServer != null) {
            mBluetoothGattServer.close();
        }
        mServerActive = false;
    }

    private AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            mServiceAdvertised = true;

            Protos.ServerAdvertiseResult.Builder advertiseResult = Protos.ServerAdvertiseResult.newBuilder();
            advertiseResult.setSuccess(true);
            advertiseResult.setErrorCode(0);
            invokeMethodUIThread("ServerAdvertiseResult", advertiseResult.build().toByteArray());
        }

        @Override
        public void onStartFailure(int errorCode) {
            mServiceAdvertised = false;

            Protos.ServerAdvertiseResult.Builder advertiseResult = Protos.ServerAdvertiseResult.newBuilder();
            advertiseResult.setSuccess(false);
            advertiseResult.setErrorCode(errorCode);
            invokeMethodUIThread("ServerAdvertiseResult", advertiseResult.build().toByteArray());
        }
    };

    private boolean isServerClientDevice(BluetoothDevice device) {
        return gattClients.containsKey(device.getAddress());
    }

    private BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {
        // TODO

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            super.onConnectionStateChange(device, status, newState);

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gattClients.put(device.getAddress(), device);
            } else {
                gattClients.remove(device.getAddress());
            }

            invokeMethodUIThread("ServerDeviceState",
                                  ProtoMaker.from(device, newState == BluetoothProfile.STATE_CONNECTED).toByteArray());
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            if (!isServerClientDevice(device)) {
                mBluetoothGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
                return;
            }

            if (descriptor.getUuid().equals(CCCD_ID)) {
                boolean notifyEnabled = false;
                boolean indicationEnabled = false;
                if (value != null) {
                    if (value.length == 2) {
                        if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                            notifyEnabled = true;
                        } else if (Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                            notifyEnabled = false;
                        } else if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                            indicationEnabled = true;
                        }
                    }
                }

                if (indicationEnabled) {
                    Log.e(TAG, "server indication cannot be enabled, not implemented!");
                    if (responseNeeded) {
                        mBluetoothGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
                    }
                    return;
                }

                Log.e(TAG, "enable notify: " + (notifyEnabled ? "TRUE" : "FALSE"));
            }

            if (responseNeeded) {
                mBluetoothGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
            if (!isServerClientDevice(device)) {
                mBluetoothGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                return;
            }

            Log.e(TAG, "read characteristic: "  + characteristic.getUuid().toString());
            mBluetoothGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
        }
    };

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            log(LogLevel.DEBUG, "[onConnectionStateChange] status: " + status + " newState: " + newState);
            invokeMethodUIThread("DeviceState", ProtoMaker.from(gatt.getDevice(), newState).toByteArray());
            if(newState == BluetoothProfile.STATE_DISCONNECTED) {
                if(!mGattServers.containsKey(gatt.getDevice().getAddress())) {
                    gatt.close();
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            log(LogLevel.DEBUG, "[onServicesDiscovered] count: " + gatt.getServices().size() + " status: " + status);
            Protos.DiscoverServicesResult.Builder p = Protos.DiscoverServicesResult.newBuilder();
            p.setRemoteId(gatt.getDevice().getAddress());
            for(BluetoothGattService s : gatt.getServices()) {
                p.addServices(ProtoMaker.from(gatt.getDevice(), s, gatt.getServices()));
            }
            invokeMethodUIThread("DiscoverServicesResult", p.build().toByteArray());
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            log(LogLevel.DEBUG, "[onCharacteristicRead] uuid: " + characteristic.getUuid().toString() + " status: " + status);
            Protos.ReadCharacteristicResponse.Builder p = Protos.ReadCharacteristicResponse.newBuilder();
            p.setRemoteId(gatt.getDevice().getAddress());
            p.setCharacteristic(ProtoMaker.from(gatt.getDevice(), characteristic, gatt.getServices()));
            invokeMethodUIThread("ReadCharacteristicResponse", p.build().toByteArray());
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            log(LogLevel.DEBUG, "[onCharacteristicWrite] uuid: " + characteristic.getUuid().toString() + " status: " + status);
            Protos.WriteCharacteristicRequest.Builder request = Protos.WriteCharacteristicRequest.newBuilder();
            request.setRemoteId(gatt.getDevice().getAddress());
            request.setCharacteristicUuid(characteristic.getUuid().toString());
            request.setServiceUuid(characteristic.getService().getUuid().toString());
            Protos.WriteCharacteristicResponse.Builder p = Protos.WriteCharacteristicResponse.newBuilder();
            p.setRequest(request);
            p.setSuccess(status == BluetoothGatt.GATT_SUCCESS);
            invokeMethodUIThread("WriteCharacteristicResponse", p.build().toByteArray());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            log(LogLevel.DEBUG, "[onCharacteristicChanged] uuid: " + characteristic.getUuid().toString());
            Protos.OnCharacteristicChanged.Builder p = Protos.OnCharacteristicChanged.newBuilder();
            p.setRemoteId(gatt.getDevice().getAddress());
            p.setCharacteristic(ProtoMaker.from(gatt.getDevice(), characteristic, gatt.getServices()));
            invokeMethodUIThread("OnCharacteristicChanged", p.build().toByteArray());
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            log(LogLevel.DEBUG, "[onDescriptorRead] uuid: " + descriptor.getUuid().toString() + " status: " + status);
            // Rebuild the ReadAttributeRequest and send back along with response
            Protos.ReadDescriptorRequest.Builder q = Protos.ReadDescriptorRequest.newBuilder();
            q.setRemoteId(gatt.getDevice().getAddress());
            q.setCharacteristicUuid(descriptor.getCharacteristic().getUuid().toString());
            q.setDescriptorUuid(descriptor.getUuid().toString());
            if(descriptor.getCharacteristic().getService().getType() == BluetoothGattService.SERVICE_TYPE_PRIMARY) {
                q.setServiceUuid(descriptor.getCharacteristic().getService().getUuid().toString());
            } else {
                // Reverse search to find service
                for(BluetoothGattService s : gatt.getServices()) {
                    for(BluetoothGattService ss : s.getIncludedServices()) {
                        if(ss.getUuid().equals(descriptor.getCharacteristic().getService().getUuid())){
                            q.setServiceUuid(s.getUuid().toString());
                            q.setSecondaryServiceUuid(ss.getUuid().toString());
                            break;
                        }
                    }
                }
            }
            Protos.ReadDescriptorResponse.Builder p = Protos.ReadDescriptorResponse.newBuilder();
            p.setRequest(q);

            // in case of the remote is disconnected or there is an issue the getValue may return null!
            byte[] valueData = descriptor.getValue();
            if (valueData == null) {
                valueData = new byte[0];
            }

            p.setValue(ByteString.copyFrom(valueData));
            invokeMethodUIThread("ReadDescriptorResponse", p.build().toByteArray());
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            log(LogLevel.DEBUG, "[onDescriptorWrite] uuid: " + descriptor.getUuid().toString() + " status: " + status);
            Protos.WriteDescriptorRequest.Builder request = Protos.WriteDescriptorRequest.newBuilder();
            request.setRemoteId(gatt.getDevice().getAddress());
            request.setDescriptorUuid(descriptor.getUuid().toString());
            request.setCharacteristicUuid(descriptor.getCharacteristic().getUuid().toString());
            request.setServiceUuid(descriptor.getCharacteristic().getService().getUuid().toString());
            Protos.WriteDescriptorResponse.Builder p = Protos.WriteDescriptorResponse.newBuilder();
            p.setRequest(request);
            p.setSuccess(status == BluetoothGatt.GATT_SUCCESS);
            invokeMethodUIThread("WriteDescriptorResponse", p.build().toByteArray());

            if(descriptor.getUuid().compareTo(CCCD_ID) == 0) {
                // SetNotificationResponse
                Protos.SetNotificationResponse.Builder q = Protos.SetNotificationResponse.newBuilder();
                q.setRemoteId(gatt.getDevice().getAddress());
                q.setCharacteristic(ProtoMaker.from(gatt.getDevice(), descriptor.getCharacteristic(), gatt.getServices()));
                invokeMethodUIThread("SetNotificationResponse", q.build().toByteArray());
            }
        }

        @Override
        public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
            log(LogLevel.DEBUG, "[onReliableWriteCompleted] status: " + status);
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            log(LogLevel.DEBUG, "[onReadRemoteRssi] rssi: " + rssi + " status: " + status);
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            log(LogLevel.DEBUG, "[onMtuChanged] mtu: " + mtu + " status: " + status);

            Protos.RequestMTUResult.Builder p = Protos.RequestMTUResult.newBuilder();
            p.setRemoteId(gatt.getDevice().getAddress());
            p.setRemoteMTUSize(mtu);
            p.setSuccess(status == BluetoothGatt.GATT_SUCCESS);

            invokeMethodUIThread("RequestMTUResult", p.build().toByteArray());
        }
    };

    enum LogLevel
    {
        EMERGENCY, ALERT, CRITICAL, ERROR, WARNING, NOTICE, INFO, DEBUG;
    }

    private void log(LogLevel level, String message) {
        if(level.ordinal() <= logLevel.ordinal()) {
            Log.d(TAG, message);
        }
    }

    private void invokeMethodUIThread(final String name, final byte[] byteArray)
    {
        activity.runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        channel.invokeMethod(name, byteArray);
                    }
                });
    }

    private byte[] uuidToBytes(UUID uid) {
        final ByteBuffer buffer = ByteBuffer.allocate((Long.SIZE / Byte.SIZE) * 2);
        buffer.putLong(uid.getMostSignificantBits());
        buffer.putLong(uid.getLeastSignificantBits());
        return buffer.array();
    }
}