part of flutter_blue;

class BluetoothServerDevice {
  final BluetoothDevice device;
  final bool connected;

  const BluetoothServerDevice(this.device, this.connected);
}
