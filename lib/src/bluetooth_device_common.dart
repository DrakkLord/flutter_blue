part of flutter_blue;

abstract class BluetoothDeviceCommon {
  final DeviceIdentifier id;
  final String name;
  final BluetoothDeviceType type;

  BluetoothDeviceCommon(this.id, this.name, this.type);
}