part of flutter_blue;

class BluetoothDeviceServer extends BluetoothDeviceCommon {
  BluetoothDeviceServer.fromProto(protos.BluetoothDevice p) :
        super(new DeviceIdentifier(p.remoteId), p.name, BluetoothDeviceType.values[p.type.value]);

  /// Returns a list of Bluetooth GATT services offered by the remote device
  /// This function requires that discoverServices has been completed for this device
  Stream<List<BluetoothServiceServer>> get services async* {
    yield await FlutterBlue.instance._channel
        .invokeMethod('servicesServer', id.toString())
        .then((buffer) =>
    new protos.DiscoverServicesResult.fromBuffer(buffer).services)
        .then((i) => i.map((s) => new BluetoothServiceServer.fromProto(s)).toList());
  }

  /// The current connection state of the device
  Stream<BluetoothDeviceState> get state async* {
    yield await FlutterBlue.instance._channel
        .invokeMethod('deviceState', id.toString())
        .then((buffer) => new protos.DeviceStateResponse.fromBuffer(buffer))
        .then((p) => BluetoothDeviceState.values[p.state.value]);

    yield* FlutterBlue.instance._methodStream
        .where((m) => m.method == "ServerDeviceState")
        .map((m) => m.arguments)
        .map((buffer) => new protos.BluetoothServerDevice.fromBuffer(buffer))
        .where((p) => p.device.remoteId == id.toString())
        .map((p) => p.connected ? BluetoothDeviceState.connected : BluetoothDeviceState.disconnected);
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
          other is BluetoothDevice &&
              runtimeType == other.runtimeType &&
              id == other.id;

  @override
  int get hashCode => id.hashCode;
}