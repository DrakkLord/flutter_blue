package com.pauldemarco.flutterblue;

import android.bluetooth.BluetoothGatt;

public abstract class GattHelpers {
    public static String connectionStateToString(int state) {
        String str = state + " [";
        switch (state) {
            case BluetoothGatt.STATE_CONNECTED:
                str += "CONNECTED";
                break;
            case BluetoothGatt.STATE_CONNECTING:
                str += "CONNECTING";
                break;
            case BluetoothGatt.STATE_DISCONNECTED:
                str += "DISCONNECTED";
                break;
            case BluetoothGatt.STATE_DISCONNECTING:
                str += "DISCONNECTING";
                break;
            default:
                str += "UNKNOWN";
                break;
        }

        str += "]";
        return str;
    }

     public static String gattStatusToString(int status) {
        String str = status + " [";
        switch (status) {
            case BluetoothGatt.GATT_CONNECTION_CONGESTED:
                str += "CONNECTION_CONGESTED";
                break;
            case BluetoothGatt.GATT_FAILURE:
                str += "FAILURE";
                break;
            case BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION:
                str += "INSUFFICIENT_AUTHENTICATION";
                break;
            case BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION:
                str += "INSUFFICIENT_ENCRYPTION";
                break;
            case BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH:
                str += "INVALID_ATTRIBUTE_LENGTH";
                break;
            case BluetoothGatt.GATT_INVALID_OFFSET:
                str += "INVALID_OFFSET";
                break;
            case BluetoothGatt.GATT_READ_NOT_PERMITTED:
                str += "READ_NOT_PERMITTED";
                break;
            case BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED:
                str += "REQUEST_NOT_SUPPORTED";
                break;
            case BluetoothGatt.GATT_SUCCESS:
                str += "SUCCESS";
                break;
            case BluetoothGatt.GATT_WRITE_NOT_PERMITTED:
                str += "WRITE_NOT_PERMITTED";
                break;
            default:
                str += "UNKNOWN";
                break;
        }

        str += "]";
        return str;
    }
}
