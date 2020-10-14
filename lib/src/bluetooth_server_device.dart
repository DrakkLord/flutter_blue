
import 'package:flutter_blue/flutter_blue.dart';

class BluetoothServerDevice {
  final BluetoothDevice device;
  final bool connected;

  const BluetoothServerDevice(this.device, this.connected);
}