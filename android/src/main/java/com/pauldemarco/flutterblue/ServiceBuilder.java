package com.pauldemarco.flutterblue;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;

import com.pauldemarco.flutter_blue.Protos;

import java.util.UUID;

import io.flutter.plugin.common.MethodChannel;

public class ServiceBuilder {
    private ServiceBuilder() {
        // NOTE: sealed class for static access only
    }

    private static BluetoothGattDescriptor descriptorFromProtoMessage(Protos.BluetoothDescriptor desc) {
        final BluetoothGattDescriptor out = new BluetoothGattDescriptor(UUID.fromString(desc.getUuid()),
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);

        // set value if possible
        if (desc.getValue() != null) {
            if (!desc.getValue().isEmpty()) {
                out.setValue(desc.getValue().toByteArray());
            }
        }

        return out;
    }

    private static BluetoothGattCharacteristic characteristicFromProtoMessage(Protos.BluetoothCharacteristic chs, MethodChannel.Result result) {
        final Protos.CharacteristicProperties props = chs.getProperties();

        int properties = 0;
        if (props.getBroadcast()) {
            properties |= BluetoothGattCharacteristic.PROPERTY_BROADCAST;
        }
        if (props.getNotify()) {
            properties |= BluetoothGattCharacteristic.PROPERTY_NOTIFY;
        }
        if (props.getIndicate()) {
            properties |= BluetoothGattCharacteristic.PROPERTY_INDICATE;
        }
        if (props.getExtendedProperties()) {
            properties |= BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS;
        }
        if (props.getWrite()) {
            properties |= BluetoothGattCharacteristic.PROPERTY_WRITE;
        }
        if (props.getRead()) {
            properties |= BluetoothGattCharacteristic.PROPERTY_READ;
        }

        // NOT IMPLEMENTED
        if (props.getIndicateEncryptionRequired()) {
            result.error("bluetooth_server_error",
                    "characteristic property indicate encryption required not implemented!",
                    null);
            return null;
        }

        if (props.getNotifyEncryptionRequired()) {
            result.error("bluetooth_server_error",
                    "characteristic property notify encryption required not implemented!",
                    null);
            return null;
        }

        if (props.getAuthenticatedSignedWrites()) {
            result.error("bluetooth_server_error",
                    "characteristic property authenticated signed writes not implemented!",
                    null);
            return null;
        }
        // NOT IMPLEMENTED

        final BluetoothGattCharacteristic out = new BluetoothGattCharacteristic(
                UUID.fromString(chs.getUuid()),
                properties, 0);

        // set value if possible
        if (chs.getValue() != null) {
            if (!chs.getValue().isEmpty()) {
                out.setValue(chs.getValue().toByteArray());
            }
        }

        for (final Protos.BluetoothDescriptor desc : chs.getDescriptorsList()) {
            if (!out.addDescriptor(descriptorFromProtoMessage(desc))) {
                result.error("bluetooth_server_error",
                        "failed to add descriptor to characteristic!",
                        null);
                return null;
            }
        }

        return out;
    }

    public static BluetoothGattService serviceFromProtoMessage(Protos.BluetoothService ps, MethodChannel.Result result) {
        final BluetoothGattService service =
                new BluetoothGattService(UUID.fromString(ps.getUuid()),
                        ps.getIsPrimary() ? BluetoothGattService.SERVICE_TYPE_PRIMARY : BluetoothGattService.SERVICE_TYPE_SECONDARY);

        for (final Protos.BluetoothCharacteristic chrs : ps.getCharacteristicsList()) {
            final BluetoothGattCharacteristic bleChr = characteristicFromProtoMessage(chrs, result);
            if (bleChr == null) {
                // NOTE: call above assumed to set result's error!
                return null;
            }
            if (!service.addCharacteristic(bleChr)) {
                result.error("bluetooth_server_error",
                        "failed to add characteristic into service!",
                        null);
                return null;
            }
        }

        for (final Protos.BluetoothService srvc : ps.getIncludedServicesList()) {
            final BluetoothGattService bleSrvc = serviceFromProtoMessage(srvc, result);
            if (bleSrvc == null) {
                // NOTE: call above assumed to set result's error!
                return null;
            }
            if (!service.addService(bleSrvc)) {
                result.error("bluetooth_server_error",
                        "failed to add included service into service!",
                        null);
                return null;
            }
        }

        return service;
    }
}
