part of flutter_blue;

abstract class BluetoothDeviceCommon {
  final DeviceIdentifier id;
  final String name;
  final BluetoothDeviceType type;

  BluetoothDeviceCommon(this.id, this.name, this.type);

  protos.BluetoothDevice toProto() {
    return protos.BluetoothDevice.create()
      ..remoteId = id.id
      ..name = name
      ..type = BluetoothDevice_Type.valueOf(type.index);
  }
}